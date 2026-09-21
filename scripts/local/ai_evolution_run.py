"""以任务专属端口和环境启动验收服务，不继承模型凭据或改变宿主默认版本。"""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess
import shutil
import hashlib
import re
import time
import requests
from zipfile import ZipFile

from ai_evolution_vm import EVIDENCE
from ai_evolution_stack import credentials

BACKEND = Path(__file__).resolve().parents[2]
FRONTEND = BACKEND.parent / "RuoYi-Vue3"
JAVA = Path("D:/app/Java/jdk-17.0.11/bin/java.exe")
NODE = Path("C:/Users/Shane/AppData/Roaming/fnm/node-versions/v20.20.2/installation/node.exe")
PYTHON = EVIDENCE / "venv/Scripts/python.exe"


def start(kind, baseline, regression=False, regression_db=None):
    if regression_db and (not regression or kind != "backend"
            or not re.fullmatch(r"ruoyi_ai_regression_[0-9]{8}_[0-9]{6}", regression_db)):
        raise RuntimeError("只允许为回归后端选择本任务命名的独立数据库")
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    label = "baseline" if baseline else "regression" if regression else "evolution"
    folder = EVIDENCE / f"runtime-{label}"
    folder.mkdir(exist_ok=True)
    environment = {name: os.environ[name] for name in
                   ("SystemRoot", "WINDIR", "SystemDrive", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA")
                   if name in os.environ}
    environment["PATH"] = str(JAVA.parent) + os.pathsep + str(NODE.parent) + os.pathsep + "D:/app/Git/cmd;C:/Windows/System32"
    environment["PYTHONUTF8"] = "1"
    backend_port = 28091 if baseline else 28093 if regression else 28092
    if kind == "backend":
        value = credentials()
        database = "ruoyi_ai_baseline" if baseline else "ruoyi_ai_upgrade" if regression else "ruoyi_ai_evolution"
        if regression_db:
            database = regression_db
        environment.update({
            "SPRING_DATASOURCE_DRUID_MASTER_URL": f"jdbc:mysql://127.0.0.1:13392/{database}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true",
            "SPRING_DATASOURCE_DRUID_MASTER_USERNAME": "root",
            "SPRING_DATASOURCE_DRUID_MASTER_PASSWORD": value["mysql"],
            "SPRING_DATA_REDIS_HOST": "127.0.0.1",
            "SPRING_DATA_REDIS_PORT": "16392",
            "SPRING_DATA_REDIS_DATABASE": "0" if baseline else "2" if regression else "1",
            "AI_MASTER_KEY": value["encryption"],
            "TOKEN_SECRET": value["encryption"],
            "RUOYI_PROFILE": str(folder / "uploads"),
            "SERVER_ADDRESS": "127.0.0.1",
            "SERVER_PORT": str(backend_port),
            "RUOYI_AI_HARNESS_NODE": "C:/Users/Shane/AppData/Roaming/fnm/node-versions/v22.22.2/installation/node.exe",
            "RUOYI_AI_HARNESS_HOME": str(BACKEND / "runtime/pi"),
            "RUOYI_AI_WORKSPACE_TOKEN_FILE": str(EVIDENCE / "workspace-broker.token"),
        })
        logging_path = folder / "logback.xml"
        logging_source = (BACKEND / "ruoyi-admin/src/main/resources/logback.xml").read_text(encoding="utf-8")
        logging_path.write_text(logging_source.replace('value="/home/ruoyi/logs"',
                                                       f'value="{folder.as_posix()}/logs"'), encoding="utf-8")
        jar = EVIDENCE / "stage1-baseline/ruoyi-admin-8c820632.jar" if baseline else folder / f"ruoyi-admin-{stamp}.jar"
        if not baseline:
            shutil.copy2(BACKEND / "ruoyi-admin/target/ruoyi-admin.jar", jar)
        with ZipFile(jar) as package:
            manifest = package.read("META-INF/MANIFEST.MF").decode("utf-8")
            if "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in manifest:
                raise RuntimeError("构建包尚未完成 Spring 重打包，不启动半成品；请先等待构建结束")
        command = [str(JAVA), "-Dfile.encoding=UTF-8", "-Xmx1536m", "-jar", str(jar),
                   "--spring.profiles.active=ci", f"--logging.config={logging_path.as_uri()}"]
        directory = BACKEND
    elif kind == "frontend":
        environment.update({"VITE_APP_BACKEND_URL": f"http://127.0.0.1:{backend_port}",
                            "VITE_APP_PORT": "5183" if baseline else "5185" if regression else "5184",
                            "VITE_APP_OPEN": "false", "VITE_APP_BASE_API": "/dev-api"})
        command = [str(NODE), str(FRONTEND / "node_modules/vite/bin/vite.js"), "--mode", "ci", "--host", "127.0.0.1"]
        directory = FRONTEND
    elif kind == "api-mock":
        command = [str(PYTHON), "-u", str(BACKEND / "scripts/local/ai_server_api_acceptance.py"), "--serve-only"]
        directory = BACKEND
    else:
        command = [str(PYTHON), "-u", str(BACKEND / "scripts/local/ai_baseline_mock.py"), "--port", "18092" if baseline else "18095"]
        if not baseline:
            command.append("--current")
        directory = BACKEND
    stdout_path = folder / f"{kind}-{stamp}.out.log"
    stderr_path = folder / f"{kind}-{stamp}.err.log"
    with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
        process = subprocess.Popen(command, cwd=directory, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=stdout, stderr=stderr, creationflags=subprocess.CREATE_NO_WINDOW)
    record = {"kind": kind, "baseline": baseline, "pid": process.pid,
              "started": stamp, "stdout": str(stdout_path), "stderr": str(stderr_path)}
    if kind == "backend":
        record.update({"jar": str(jar), "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "database": database})
    (folder / f"{kind}-{stamp}.json").write_text(json.dumps(record), encoding="utf-8")
    print(json.dumps(record))
    if kind == "backend":
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("后端启动期间退出，请核查刚记录的双流日志")
            try:
                if requests.get(f"http://127.0.0.1:{backend_port}/captchaImage", timeout=2).status_code == 200:
                    print("任务后端已就绪：" + str(backend_port), flush=True)
                    return
            except requests.RequestException:
                pass
            time.sleep(1)
        raise RuntimeError("后端在 60 秒内未就绪，请核查启动记录，不继续验收")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("backend", "frontend", "mock", "api-mock"))
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--baseline", action="store_true")
    selection.add_argument("--regression", action="store_true")
    parser.add_argument("--regression-db")
    args = parser.parse_args()
    start(args.kind, args.baseline, args.regression, args.regression_db)
