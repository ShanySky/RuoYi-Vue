"""可信 Linux 工作空间服务；模型只接触无网络、无宿主挂载的受限容器。"""

import argparse
import base64
from contextlib import contextmanager
from dataclasses import dataclass, field
import fcntl
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import subprocess
import threading
import time
import uuid

MIB = 1024 * 1024
LIMITS = {"taskBytes": 64 * MIB, "taskNodes": 512, "temporaryBytes": 8 * MIB, "temporaryNodes": 64,
    "fileBytes": 8 * MIB, "taskArtifactBytes": 16 * MIB, "taskArtifacts": 8,
    "userArtifactBytes": 64 * MIB, "userArtifacts": 32, "globalArtifactBytes": 256 * MIB, "globalArtifacts": 128,
    "userTasks": 2, "globalTasks": 4, "memoryBytes": 256 * MIB, "processes": 32,
    "cpu": 0.5, "commandSeconds": 30, "taskSeconds": 1800, "leaseSeconds": 15, "outputBytes": 8192}


class Rejected(Exception):
    pass


@dataclass
class Task:
    user: int
    run: int
    name: str
    deadline: float
    lease: float = field(default_factory=time.monotonic)
    lock: threading.Lock = field(default_factory=threading.Lock)
    cancelled: bool = False


class Broker:
    def __init__(self, root, image, artifact_ttl=86400, disk_floor=2 * 1024**3):
        self.root = Path(root).resolve()
        if self.root == Path("/") or self.root.is_symlink():
            raise Rejected("服务数据目录无效")
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.root, 0o700)
        self.exclusive = (self.root / "service.lock").open("a")
        fcntl.flock(self.exclusive, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.files = self.root / "files"
        self.files.mkdir(exist_ok=True, mode=0o700)
        if self.files.is_symlink():
            raise Rejected("成果目录不得是链接")
        self.owner = hashlib.sha256(str(self.root).encode()).hexdigest()[:16]
        self.lock = threading.RLock()
        self.tasks = {}
        self.readers = {}
        self.downloads = threading.BoundedSemaphore(4)
        self.artifact_ttl = max(1, min(86400, artifact_ttl))
        self.disk_floor = max(2 * 1024**3, disk_floor)
        self.stopped = threading.Event()
        self.cleanup_error = False
        self.db = sqlite3.connect(self.root / "inventory.sqlite", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("pragma journal_mode=DELETE")
        self.db.execute("create table if not exists artifact (id text primary key,user_id integer,run_id integer,name text,bytes integer,sha256 text,created real,expires real)")
        self.db.commit()
        info = json.loads(self.docker(["info", "--format", "{{json .}}"], maximum=1024 * 1024))
        if info.get("OSType") != "linux" or info.get("CgroupVersion") != "2" or info.get("CgroupDriver") == "none" \
                or not all(info.get(key) for key in ("MemoryLimit", "SwapLimit", "CpuCfsQuota", "PidsLimit")) \
                or not any("seccomp" in item for item in info.get("SecurityOptions", [])):
            raise Rejected("容器内核隔离或资源限制不可用")
        self.image = json.loads(self.docker(["image", "inspect", image], maximum=128 * 1024))[0]["Id"]
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", self.image):
            raise Rejected("镜像标识无效")
        old = self.docker(["ps", "-aq", "--filter", "label=ruoyi.ai.workspace.owner=" + self.owner]).decode().split()
        for container in old:
            if not re.fullmatch(r"[a-f0-9]{12,64}", container):
                raise Rejected("残留容器标识无效")
            self.docker(["rm", "-f", container])
        known = {row["id"] for row in self.db.execute("select id from artifact")}
        for path in self.files.iterdir():
            if not path.is_file() or path.is_symlink():
                raise Rejected("成果目录存在非法对象")
            if path.name not in known:
                path.unlink()
        self.cleanup()
        threading.Thread(target=self.janitor, daemon=True, name="workspace-cleanup").start()

    @staticmethod
    def docker(arguments, timeout=10, maximum=128 * 1024, data=None, raw_status=False):
        # 不经过 shell；唯一可变命令正文作为 docker exec 的单独参数交给隔离容器。
        process = subprocess.Popen(["docker", *arguments], stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={"PATH": "/usr/bin:/bin:/usr/local/bin", "LANG": "C.UTF-8"})
        output, errors = bytearray(), bytearray()
        oversized = threading.Event()

        def collect(stream, target, limit):
            try:
                while chunk := stream.read(4096):
                    space = max(0, limit - len(target))
                    target.extend(chunk[:space])
                    if len(chunk) > space:
                        oversized.set()
            finally:
                stream.close()

        readers = [threading.Thread(target=collect, args=(process.stdout, output, maximum), daemon=True),
                   threading.Thread(target=collect, args=(process.stderr, errors, 8192), daemon=True)]
        for thread in readers:
            thread.start()
        try:
            if data is not None:
                process.stdin.write(data)
                process.stdin.close()
            status = process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, BrokenPipeError):
            process.kill()
            process.wait(timeout=3)
            raise Rejected("执行超时或管道中断")
        finally:
            for thread in readers:
                thread.join(timeout=2)
        if raw_status:
            return status, bytes(output), bytes(errors), oversized.is_set()
        if status != 0 or oversized.is_set():
            raise Rejected("受控容器操作失败")
        return bytes(output)

    def disk_available(self):
        if shutil.disk_usage(self.root).free < self.disk_floor:
            raise Rejected("磁盘低于保护水位，拒绝新增任务或写入")

    @staticmethod
    def identity(request):
        user, run = request.get("userId"), request.get("runId")
        if type(user) is not int or type(run) is not int or min(user, run) <= 0 or max(user, run) > 2**63 - 1:
            raise Rejected("任务归属无效")
        return user, run

    def task(self, request):
        user, run = self.identity(request)
        self.disk_available()
        with self.lock:
            existing = self.tasks.get((user, run))
            if existing:
                if existing.cancelled:
                    raise Rejected("任务环境正在回收")
                return existing
            created = request.get("runCreatedAt")
            if not isinstance(created, (int, float)) or created > time.time() + 30 or time.time() - created >= LIMITS["taskSeconds"]:
                raise Rejected("任务已超过绝对执行时限")
            if len(self.tasks) >= LIMITS["globalTasks"] or sum(task.user == user for task in self.tasks.values()) >= LIMITS["userTasks"]:
                raise Rejected("任务并发或预留空间已达上限")
            task = Task(user, run, f"ryai-{self.owner}-{uuid.uuid4().hex[:16]}", created + LIMITS["taskSeconds"])
            self.tasks[(user, run)] = task
            try:
                self.docker(["run", "-d", "--name", task.name, "--label", "ruoyi.ai.workspace.owner=" + self.owner,
                    "--label", "ruoyi.ai.user=" + str(user), "--label", "ruoyi.ai.run=" + str(run),
                    "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--init", "--ipc", "none", "--user", "65534:65534", "--cpus", "0.5", "--memory", "256m",
                    "--memory-swap", "256m", "--pids-limit", "32", "--ulimit", "nofile=128:128",
                    "--ulimit", "fsize=8388608:8388608", "--log-driver", "none",
                    "--tmpfs", "/work:rw,nosuid,nodev,noexec,size=64m,nr_inodes=512,uid=1000,gid=1000,mode=0700",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=8m,nr_inodes=64,uid=1000,gid=1000,mode=0700", self.image])
            except Exception:
                self.stop(user, run)
                raise
            return task

    @contextmanager
    def operation(self, request):
        task = self.task(request)
        if not task.lock.acquire(blocking=False):
            raise Rejected("同一任务已有命令或文件操作")
        try:
            if task.cancelled or time.time() >= task.deadline:
                raise Rejected("任务已停止或超时")
            yield task
        finally:
            task.lock.release()

    def execute(self, request):
        command = request.get("command")
        timeout = request.get("timeoutSeconds", 30)
        if not isinstance(command, str) or not command.strip() or len(command.encode()) > 16000 \
                or type(timeout) is not int or not 1 <= timeout <= LIMITS["commandSeconds"]:
            raise Rejected("命令或时限无效")
        with self.operation(request) as task:
            try:
                status, output, errors, truncated = self.docker(["exec", "--user", "1000:1000", "--workdir", "/work",
                    task.name, "/usr/bin/env", "-i", "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8",
                    "HOME=/work", "PYTHONDONTWRITEBYTECODE=1", "/bin/sh", "-lc", command],
                    timeout=timeout, maximum=LIMITS["outputBytes"], raw_status=True)
                self.docker(["exec", "--user", "1000:1000", task.name, "python", "/opt/ruoyi/sweep.py"], timeout=5)
                if task.cancelled:
                    raise Rejected("任务已停止")
                combined = output + (b"\n" + errors if errors else b"")
                return {"exitCode": status, "output": combined[:8192].decode("utf-8", errors="replace"),
                    "truncated": truncated or len(combined) > 8192, "workspace": "/work"}
            except Exception:
                self.stop(task.user, task.run)
                raise Rejected("命令超时、已停止或隔离异常，任务环境已回收")

    def import_result(self, request):
        content, name = request.get("content"), request.get("path")
        if not isinstance(content, str) or len(content.encode()) > 128 * 1024 or not isinstance(name, str):
            raise Rejected("导入内容无效或超限")
        with self.operation(request) as task:
            result = self.docker(["exec", "-i", "--user", "1000:1000", task.name, "python", "/opt/ruoyi/files.py", "write", name], data=content.encode())
            return json.loads(result)

    def publish(self, request):
        name, source = request.get("name"), request.get("path")
        if not isinstance(name, str) or not re.fullmatch(r"[^/\\\x00-\x1f]{1,100}", name) or not isinstance(source, str):
            raise Rejected("成果名称或路径无效")
        with self.operation(request) as task:
            raw = self.docker(["exec", "--user", "1000:1000", task.name, "python", "/opt/ruoyi/files.py", "read", source], maximum=12 * MIB)
            data = base64.b64decode(json.loads(raw)["data"], validate=True)
            if len(data) > LIMITS["fileBytes"]:
                raise Rejected("成果单文件超限")
            with self.lock:
                self.disk_available()
                self.expire_artifacts()
                rows = self.db.execute("select user_id,run_id,bytes from artifact").fetchall()
                for selected, maximum_bytes, maximum_count in (
                    (rows, LIMITS["globalArtifactBytes"], LIMITS["globalArtifacts"]),
                    ([row for row in rows if row["user_id"] == task.user], LIMITS["userArtifactBytes"], LIMITS["userArtifacts"]),
                    ([row for row in rows if row["user_id"] == task.user and row["run_id"] == task.run], LIMITS["taskArtifactBytes"], LIMITS["taskArtifacts"]),
                ):
                    if len(selected) >= maximum_count or sum(row["bytes"] for row in selected) + len(data) > maximum_bytes:
                        raise Rejected("成果容量或文件数量已达任务、用户或全局上限")
                identifier = uuid.uuid4().hex
                path = self.files / identifier
                created = time.time()
                digest = hashlib.sha256(data).hexdigest()
                try:
                    # 先持久预留完整容量；写入或清理失败也不能产生未计费的残留。
                    self.db.execute("insert into artifact values (?,?,?,?,?,?,?,?)", (
                        identifier, task.user, task.run, name, len(data), digest, created, created + self.artifact_ttl))
                    self.db.commit()
                    with path.open("xb") as stream:
                        stream.write(data)
                        stream.flush()
                        os.fsync(stream.fileno())
                except Exception:
                    self.db.rollback()
                    try:
                        path.unlink(missing_ok=True)
                        self.db.execute("delete from artifact where id=?", (identifier,))
                    except OSError:
                        self.cleanup_error = True
                        logging.warning("成果写入及清理失败，保留完整容量预留等待回收")
                        self.db.execute("update artifact set expires=? where id=?", (time.time() - 1, identifier))
                    self.db.commit()
                    raise Rejected("成果保存失败")
                return {"id": identifier, "name": name, "bytes": len(data), "sha256": digest, "expiresAt": created + self.artifact_ttl}

    @contextmanager
    def read(self, request):
        user, run = self.identity(request)
        if not self.downloads.acquire(blocking=False):
            raise Rejected("成果读取并发已达上限")
        identifier = request.get("id")
        entered = False
        try:
            with self.lock:
                row = self.db.execute("select * from artifact where id=? and user_id=? and run_id=? and expires>?",
                    (identifier, user, run, time.time())).fetchone()
                if row is None:
                    raise Rejected("成果不存在、已到期或归属不符")
                self.readers[identifier] = self.readers.get(identifier, 0) + 1
                entered = True
                path = self.files / row["id"]
                if path.is_symlink() or path.stat().st_size != row["bytes"]:
                    raise Rejected("成果存储结构异常")
                data = path.read_bytes()
                if len(data) > LIMITS["fileBytes"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                    raise Rejected("成果校验不符")
            yield data
        finally:
            with self.lock:
                if entered:
                    self.readers[identifier] -= 1
                    if self.readers[identifier] == 0:
                        del self.readers[identifier]
            self.downloads.release()

    def delete(self, request):
        user, run = self.identity(request)
        with self.lock:
            row = self.db.execute("select * from artifact where id=? and user_id=? and run_id=?", (request.get("id"), user, run)).fetchone()
            if row:
                if self.readers.get(row["id"]):
                    raise Rejected("成果正在读取")
                (self.files / row["id"]).unlink(missing_ok=True)
                self.db.execute("delete from artifact where id=?", (row["id"],))
                self.db.commit()
        return {"deleted": True}

    def lease(self, request):
        identifiers = request.get("tasks", [])
        if not isinstance(identifiers, list) or len(identifiers) > 4:
            raise Rejected("活动任务清单无效")
        with self.lock:
            for identifier in identifiers:
                task = self.tasks.get(self.identity(identifier))
                if task and not task.cancelled:
                    task.lease = time.monotonic()
        return {"renewed": True}

    def stop(self, user, run):
        with self.lock:
            task = self.tasks.get((user, run))
            if not task:
                return {"stopped": True}
            task.cancelled = True
        try:
            self.docker(["rm", "-f", task.name])
            with self.lock:
                self.tasks.pop((user, run), None)
            return {"stopped": True}
        except Rejected:
            try:
                remaining = self.docker(["ps", "-aq", "--filter", "label=ruoyi.ai.workspace.owner=" + self.owner,
                    "--filter", "name=^/" + task.name + "$"])
                if not remaining.strip():
                    with self.lock:
                        self.tasks.pop((user, run), None)
                    return {"stopped": True}
            except Rejected:
                pass
            self.cleanup_error = True
            logging.warning("任务容器回收失败，继续保留其资源预留并重试")
            return {"stopped": False}

    def expire_artifacts(self):
        for row in self.db.execute("select id from artifact where expires<=?", (time.time(),)).fetchall():
            if not self.readers.get(row["id"]):
                (self.files / row["id"]).unlink(missing_ok=True)
                self.db.execute("delete from artifact where id=?", (row["id"],))
        self.db.commit()

    def cleanup(self):
        with self.lock:
            tasks = list(self.tasks.values())
            self.expire_artifacts()
        for task in tasks:
            if task.cancelled or time.monotonic() - task.lease >= LIMITS["leaseSeconds"] or time.time() >= task.deadline:
                self.stop(task.user, task.run)
        with self.lock:
            self.cleanup_error = any(task.cancelled for task in self.tasks.values())

    def janitor(self):
        while not self.stopped.wait(2):
            try:
                self.cleanup()
            except Exception:
                self.cleanup_error = True
                logging.warning("工作空间定时清理失败，资源继续计入配额")

    def status(self):
        self.disk_available()
        with self.lock:
            artifacts = self.db.execute("select count(*),coalesce(sum(bytes),0) from artifact").fetchone()
            return {"ready": not self.cleanup_error, "image": self.image, "limits": LIMITS,
                "tasks": len(self.tasks), "artifactCount": artifacts[0], "artifactBytes": artifacts[1], "cleanupError": self.cleanup_error}

    def shutdown(self):
        self.stopped.set()
        for task in list(self.tasks.values()):
            self.stop(task.user, task.run)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    slots = threading.BoundedSemaphore(8)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    broker = None
    token = None

    def log_message(self, *_):
        pass

    def send(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        self.connection.settimeout(60)
        if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.token):
            self.send(401, {"error": "缺少工作空间服务授权"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 <= size <= 256 * 1024:
                raise Rejected("请求体超过限制")
            request = json.loads(self.rfile.read(size) or b"{}")
            if not isinstance(request, dict):
                raise Rejected("请求体无效")
            if self.path == "/v1/read":
                with self.broker.read(request) as data:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(data)
                return
            actions = {"/v1/execute": self.broker.execute, "/v1/import": self.broker.import_result,
                "/v1/publish": self.broker.publish, "/v1/delete": self.broker.delete, "/v1/lease": self.broker.lease,
                "/v1/status": lambda _: self.broker.status(), "/v1/stop": lambda body: self.broker.stop(*self.broker.identity(body))}
            if self.path not in actions:
                raise Rejected("未知管理操作")
            self.send(200, actions[self.path](request))
        except Rejected as error:
            self.send(409, {"error": str(error)})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except Exception:
            self.send(500, {"error": "工作空间服务异常，操作未交付"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=28097)
    parser.add_argument("--artifact-ttl", type=int, default=86400)
    parser.add_argument("--disk-floor", type=int, default=2 * 1024**3)
    args = parser.parse_args()
    Path(args.root).mkdir(parents=True, exist_ok=True, mode=0o700)
    log = RotatingFileHandler(Path(args.root) / "service.log", maxBytes=2 * MIB, backupCount=2, encoding="utf-8")
    logging.basicConfig(level=logging.WARNING, handlers=[log])
    secret_path = Path(args.token_file)
    if secret_path.stat().st_mode & 0o077:
        raise RuntimeError("服务凭据文件必须只允许所有者访问")
    Handler.token = secret_path.read_text().strip()
    if len(Handler.token) < 32:
        raise RuntimeError("服务凭据强度不足")
    broker = Handler.broker = Broker(args.root, args.image, args.artifact_ttl, args.disk_floor)
    server = Server((args.listen, args.port), Handler)
    def terminate(*_):
        broker.shutdown()
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        server.serve_forever()
    finally:
        broker.shutdown()
        server.server_close()
