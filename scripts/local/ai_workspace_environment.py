"""在既定任务虚拟机部署固定工作空间镜像和可信服务，不触碰其他虚拟机。"""

import argparse
import json
from pathlib import Path
import secrets
import subprocess
from ai_evolution_vm import connect, EVIDENCE, vbox, VM_NAME
from ai_evolution_stack import credentials

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/home/ruoyitest/ai-evolution/workspace"


def execute(client, command, timeout=1200):
    _, output, error = client.exec_command(command, timeout=timeout)
    stdout, stderr = output.read().decode(), error.read().decode()
    status = output.channel.recv_exit_status()
    if stdout:
        print(stdout, end="", flush=True)
    if stderr:
        print(stderr, end="", flush=True)
    if status:
        raise RuntimeError("任务虚拟机工作空间操作失败：" + str(status))
    return stdout


def prepare():
    with connect() as client:
        execute(client, "mkdir -p " + REMOTE)
        with client.open_sftp() as transfer:
            for name in ("Dockerfile", "requirements.lock", "files.py", "sweep.py", "broker.py"):
                transfer.put(str(ROOT / "runtime/workspace" / name), REMOTE + "/" + name)
        execute(client, "sudo docker build --pull=false -t ruoyi-ai-workspace:20260921 " + REMOTE)


def start():
    values = credentials()
    if "workspace" not in values:
        values["workspace"] = secrets.token_urlsafe(40)
        (EVIDENCE / "local-secrets.json").write_text(json.dumps(values), encoding="utf-8")
    secret_file = EVIDENCE / "workspace-broker.token"
    secret_file.write_text(values["workspace"], encoding="utf-8")
    account = subprocess.check_output(["whoami"], text=True).strip()
    subprocess.run(["icacls", str(secret_file), "/inheritance:r", "/grant:r", f"{account}:(F)"], check=True, capture_output=True)
    info = vbox("showvminfo", VM_NAME, "--machinereadable")
    if 'workspace,tcp,127.0.0.1,28097,,28097' not in info:
        vbox("controlvm", VM_NAME, "natpf1", "workspace,tcp,127.0.0.1,28097,,28097")
    with connect() as client:
        execute(client, "sudo mkdir -p /var/lib/ruoyi-ai-workspace")
        with client.open_sftp() as transfer:
            transfer.put(str(secret_file), REMOTE + "/service.token")
            transfer.chmod(REMOTE + "/service.token", 0o600)
            transfer.put(str(ROOT / "runtime/workspace/broker.py"), REMOTE + "/broker.py")
            unit = """[Unit]
Description=RuoYi AI 受控工作空间
After=docker.service
Requires=docker.service
[Service]
Type=simple
ExecStart=/usr/bin/python3 /home/ruoyitest/ai-evolution/workspace/broker.py --root /var/lib/ruoyi-ai-workspace --image ruoyi-ai-workspace:20260921 --token-file /home/ruoyitest/ai-evolution/workspace/service.token --listen 0.0.0.0
Restart=on-failure
RestartSec=5
TimeoutStopSec=20
MemoryMax=512M
CPUQuota=100%
TasksMax=96
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/var/lib/ruoyi-ai-workspace
StandardOutput=null
[Install]
WantedBy=multi-user.target
"""
            with transfer.file(REMOTE + "/ruoyi-ai-workspace.service", "w") as target:
                target.write(unit)
        execute(client, "sudo install -m 0644 " + REMOTE + "/ruoyi-ai-workspace.service /etc/systemd/system/ruoyi-ai-workspace.service")
        execute(client, "sudo systemctl daemon-reload")
        execute(client, "sudo systemctl enable --now ruoyi-ai-workspace.service")
        execute(client, "sudo systemctl restart ruoyi-ai-workspace.service")
        execute(client, "sudo systemctl status ruoyi-ai-workspace.service --no-pager")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "start"))
    {"prepare": prepare, "start": start}[parser.parse_args().action]()
