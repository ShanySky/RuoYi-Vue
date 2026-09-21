"""真实容器反例验收：隔离、实际写入限额、进程和停止，不调用收费模型。"""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import json
import threading
import time
import requests
from ai_evolution_stack import credentials
from ai_evolution_vm import EVIDENCE

URL = "http://127.0.0.1:28097/v1/"
AUTH = {"Authorization": "Bearer " + credentials()["workspace"]}
active = {}
artifacts = []
findings = []
halt = threading.Event()
base = int(time.time())


def call(action, data=None, expected=200, binary=False):
    response = requests.post(URL + action, headers=AUTH, json=data or {}, timeout=60)
    assert response.status_code == expected, (action, response.status_code, response.text[:300])
    return response.content if binary else response.json()


def task(index=1, user=900001):
    key = (user, base + index)
    active.setdefault(key, {"userId": user, "runId": base + index, "runCreatedAt": time.time()})
    return active[key]


def execute(command, index=1, user=900001, expected=200, timeout=30):
    return call("execute", {**task(index, user), "command": command, "timeoutSeconds": timeout}, expected)


def stop(value):
    result = call("stop", value)
    assert result["stopped"]
    active.pop((value["userId"], value["runId"]), None)


def record(name, **facts):
    findings.append({"name": name, **facts})
    print(json.dumps(findings[-1], ensure_ascii=False), flush=True)


def heartbeat():
    while not halt.wait(1):
        try:
            call("lease", {"tasks": list(active.values())})
        except Exception:
            pass


def run():
    status = call("status")
    assert status["ready"] and status["tasks"] == 0
    threading.Thread(target=heartbeat, daemon=True).start()
    try:
        result = execute("python - <<'PY'\nimport os,socket,json\nfrom pathlib import Path\ns=socket.socket();s.settimeout(1)\ntry:\n s.connect(('10.0.2.2',13392));network=True\nexcept OSError: network=False\nprint(json.dumps({'uid':os.getuid(),'network':network,'socket':Path('/var/run/docker.sock').exists(),'hostSecret':Path('/home/ruoyitest/ai-evolution/workspace/service.token').exists(),'credentials':any('KEY' in k or 'TOKEN' in k or 'PASSWORD' in k for k in os.environ),'memory':Path('/sys/fs/cgroup/memory.max').read_text().strip(),'pids':Path('/sys/fs/cgroup/pids.max').read_text().strip(),'cpu':Path('/sys/fs/cgroup/cpu.max').read_text().strip()}))\nPY")
        isolation = json.loads(result["output"])
        assert isolation == {"uid": 1000, "network": False, "socket": False, "hostSecret": False, "credentials": False,
            "memory": "268435456", "pids": "32", "cpu": "50000 100000"}, isolation
        assert execute("echo forbidden > /outside-file")["exitCode"] != 0
        record("实际任务身份、网络、宿主文件和内核资源边界", **isolation)

        execute("printf task-one > private.txt")
        assert "False" in execute("python -c \"from pathlib import Path;print(Path('/work/private.txt').exists())\"", 2)["output"]
        execute("true", 3, expected=409)
        active.pop((900001, base + 3))
        execute("true", 4, 900002)
        execute("true", 5, 900002)
        execute("true", 6, 900003, expected=409)
        active.pop((900003, base + 6))
        assert call("status")["tasks"] == 4
        for value in list(active.values()):
            if value["runId"] != base + 1:
                stop(value)
        record("跨任务文件不可见，每用户两任务和全局四任务上限实际拒绝")

        overflow = execute("python - <<'PY'\nfrom pathlib import Path\ntry:\n Path('oversize.bin').write_bytes(b'x'*(8*1024*1024+1))\n print('UNBOUNDED')\nexcept OSError as e: print('FILE_LIMIT',e.errno)\nPY")
        assert "FILE_LIMIT" in overflow["output"] and "UNBOUNDED" not in overflow["output"]
        execute("rm -f /work/oversize.bin")
        full = execute("python - <<'PY'\nfrom pathlib import Path\ntry:\n for n in range(10): Path('fill-'+str(n)).write_bytes(b'x'*(8*1024*1024))\n print('UNBOUNDED')\nexcept OSError as e: print('SPACE_LIMIT',e.errno)\nPY")
        assert "SPACE_LIMIT 28" in full["output"] and "UNBOUNDED" not in full["output"]
        execute("rm -f /work/fill-*")
        nodes = execute("python - <<'PY'\nfrom pathlib import Path\ntry:\n for n in range(600): Path('node-'+str(n)).touch()\n print('UNBOUNDED')\nexcept OSError as e: print('NODE_LIMIT',e.errno,n)\nPY")
        assert "NODE_LIMIT 28" in nodes["output"] and "UNBOUNDED" not in nodes["output"]
        execute("rm -f /work/node-*")
        record("实际写入触发单文件、任务字节和文件节点上限", file=overflow["output"].strip(), space=full["output"].strip(), nodes=nodes["output"].strip())

        processes = execute("python - <<'PY'\nimport subprocess\ntry:\n for n in range(60): subprocess.Popen(['sleep','20'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n print('UNBOUNDED')\nexcept OSError as e: print('PROCESS_LIMIT',e.errno,n)\nPY")
        assert "PROCESS_LIMIT 11" in processes["output"] and "UNBOUNDED" not in processes["output"]
        assert execute("python -c \"print('AFTER_REAP')\"")["output"].strip() == "AFTER_REAP"
        memory = execute("python -c \"data=bytearray(400*1024*1024);print('UNBOUNDED')\"")
        assert memory["exitCode"] != 0 and "UNBOUNDED" not in memory["output"]
        memory_events = execute("cat /sys/fs/cgroup/memory.events")["output"]
        assert any(line.startswith("oom_kill ") and int(line.split()[1]) > 0 for line in memory_events.splitlines())
        record("进程和内存超额实际失败，残留进程被回收", processes=processes["output"].strip(), memoryExit=memory["exitCode"], memoryEvents=memory_events)

        imported = call("import", {**task(), "path": "data.json", "content": '{"scope":"本人范围","count":2}'})
        assert imported["bytes"] > 0
        published = call("publish", {**task(), "path": "data.json", "name": "受控数据.json"})
        artifacts.append({**task(), "id": published["id"]})
        data = call("read", artifacts[-1], binary=True)
        assert json.loads(data)["count"] == 2 and hashlib.sha256(data).hexdigest() == published["sha256"]
        call("read", {**artifacts[-1], "userId": 900002}, expected=409)
        execute("ln -s /etc/passwd bad-link; ln data.json hard-link")
        call("publish", {**task(), "path": "bad-link", "name": "bad.txt"}, expected=409)
        call("publish", {**task(), "path": "hard-link", "name": "bad.txt"}, expected=409)
        call("publish", {**task(), "path": "../etc/passwd", "name": "bad.txt"}, expected=409)
        execute("rm -f /work/bad-link /work/hard-link")
        record("真实成果校验、跨用户读取、上跳和符号或硬链接拒绝", artifactBytes=published["bytes"])

        with ThreadPoolExecutor(max_workers=1) as pool:
            slow = pool.submit(execute, "sleep 25")
            time.sleep(1)
            stop(task())
            try:
                slow.result(timeout=10)
                raise AssertionError("停止后命令未失败")
            except AssertionError as error:
                assert "409" in str(error), str(error)
        assert call("status")["tasks"] == 0
        execute("sleep 5", index=7, expected=409, timeout=1)
        active.pop((900001, base + 7), None)
        assert call("status")["tasks"] == 0
        record("外部停止和命令超时都删除整任务容器，活动任务归零")

        execute("true", 8)
        active.pop((900001, base + 8))
        deadline = time.monotonic() + 22
        while call("status")["tasks"] and time.monotonic() < deadline:
            time.sleep(1)
        assert call("status")["tasks"] == 0
        record("续租丢失后自动回收，模拟后端异常退出")
    finally:
        for value in list(active.values()):
            stop(value)
        for artifact in artifacts:
            call("delete", artifact)
        halt.set()
        final = call("status")
        path = EVIDENCE / ("workspace-isolation-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
        path.write_text(json.dumps({"findings": findings, "image": status["image"], "final": final}, ensure_ascii=False, indent=2), encoding="utf-8")
    assert final["tasks"] == 0 and final["artifactCount"] == 0
    print("AI_WORKSPACE_ISOLATION_OK " + str(path), flush=True)


if __name__ == "__main__":
    run()
