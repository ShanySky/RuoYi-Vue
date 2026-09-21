"""创建本任务专用验证机；不修改已有虚拟机，不改变宿主默认运行环境。"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import time

import paramiko
import pycdlib
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519


VM_NAME = "RuoYi-AI-Evolution"
VM_ROOT = Path("E:/VMDisk") / VM_NAME
VBOX = Path("D:/Program Files/Oracle/VirtualBox/VBoxManage.exe")
TASK_ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = TASK_ROOT / ".validation"
KEY = EVIDENCE / "vm_ed25519"
SSH_PORT = 22292


def vbox(*args):
    result = subprocess.run([str(VBOX), *map(str, args)], check=True,
                            capture_output=True, encoding="utf-8", errors="strict")
    return result.stdout


def initialize():
    root = VM_ROOT.resolve()
    if root.parent != Path("E:/VMDisk").resolve():
        raise RuntimeError("验证机目录不在指定父目录内")
    if f'"{VM_NAME}"' in vbox("list", "vms"):
        raise RuntimeError("验证机已注册；请使用 inspect 或 connect，不重复创建")
    image = root / "images/noble-server-cloudimg-amd64.vmdk"
    checksums = (root / "images/SHA256SUMS").read_text(encoding="utf-8")
    digest = hashlib.file_digest(image.open("rb"), "sha256").hexdigest()
    if not any(line.startswith(digest) and line.endswith(image.name)
               for line in checksums.splitlines()):
        raise RuntimeError("镜像校验不通过")
    if KEY.exists():
        raise RuntimeError("任务密钥已存在；须核实恢复状态，不能覆盖")
    EVIDENCE.mkdir(exist_ok=True)
    private = ed25519.Ed25519PrivateKey.generate()
    KEY.write_bytes(private.private_bytes(serialization.Encoding.PEM,
                                         serialization.PrivateFormat.OpenSSH,
                                         serialization.NoEncryption()))
    # 私钥仅当前账号可读，不进入 Git、镜像、日志或会话输出。
    account = subprocess.check_output(["whoami"], text=True).strip()
    subprocess.run(["icacls", str(KEY), "/inheritance:r", "/grant:r", f"{account}:(F)"],
                   check=True, capture_output=True)
    public = private.public_key().public_bytes(serialization.Encoding.OpenSSH,
                                             serialization.PublicFormat.OpenSSH).decode()
    user_data = ("#cloud-config\n"
                 "hostname: ruoyi-ai-evolution\n"
                 "manage_etc_hosts: true\n"
                 "ssh_pwauth: false\n"
                 "disable_root: true\n"
                 "users:\n"
                 "  - name: ruoyitest\n"
                 "    groups: [sudo]\n"
                 "    shell: /bin/bash\n"
                 "    sudo: ['ALL=(ALL) NOPASSWD:ALL']\n"
                 "    lock_passwd: true\n"
                 "    ssh_authorized_keys:\n"
                 f"      - {public}\n")
    files = {
        "user-data": user_data,
        "meta-data": f"instance-id: {VM_NAME}-20260921\nlocal-hostname: ruoyi-ai-evolution\n",
        "network-config": "version: 2\nethernets:\n  lan:\n    match:\n      name: 'en*'\n    dhcp4: true\n",
    }
    iso_path = root / "images/seed.iso"
    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, joliet=3, rock_ridge="1.09", vol_ident="cidata")
    for index, (name, content) in enumerate(files.items()):
        data = content.encode("utf-8")
        iso.add_fp(io.BytesIO(data), len(data), iso_path=f"/FILE{index}.;1",
                   rr_name=name, joliet_path=f"/{name}")
    iso.write(str(iso_path))
    iso.close()
    disk = root / "machine/system.vdi"
    vbox("clonemedium", "disk", image, disk, "--format", "VDI")
    vbox("modifymedium", "disk", disk, "--resize", "24576")
    vbox("createvm", "--name", VM_NAME, "--ostype", "Ubuntu_64",
         "--basefolder", root.parent, "--register")
    vbox("modifyvm", VM_NAME, "--memory", "6144", "--cpus", "4",
         "--firmware", "efi", "--graphicscontroller", "vmsvga", "--vram", "16",
         "--audio-enabled", "off", "--clipboard-mode", "disabled", "--drag-and-drop", "disabled",
         "--snapshot-folder", root / "snapshots", "--nic1", "nat",
         "--nat-pf1", f"ssh,tcp,127.0.0.1,{SSH_PORT},,22",
         "--nat-pf1", "mysql,tcp,127.0.0.1,13392,,3306",
         "--nat-pf1", "redis,tcp,127.0.0.1,16392,,6379")
    vbox("storagectl", VM_NAME, "--name", "SATA", "--add", "sata", "--controller", "IntelAhci")
    vbox("storageattach", VM_NAME, "--storagectl", "SATA", "--port", "0",
         "--device", "0", "--type", "hdd", "--medium", disk)
    vbox("storageattach", VM_NAME, "--storagectl", "SATA", "--port", "1",
         "--device", "0", "--type", "dvddrive", "--medium", iso_path)
    inspect()
    vbox("startvm", VM_NAME, "--type", "headless")
    print(json.dumps({"started": VM_NAME, "image_sha256": digest}, ensure_ascii=False))


def inspect():
    info = vbox("showvminfo", VM_NAME, "--machinereadable")
    state = {key.strip('"'): value for key, value in
             (line.split("=", 1) for line in info.splitlines() if "=" in line)}
    for name in ("CfgFile", "SnapFldr", "SATA-0-0", "SATA-1-0"):
        value = state.get(name, "").strip('"')
        if not value or not Path(value).resolve().is_relative_to(VM_ROOT.resolve()):
            raise RuntimeError(f"验证机路径越界或缺失：{name}")
    evidence = EVIDENCE / "vm-info.txt"
    evidence.write_text(info, encoding="utf-8")
    size = sum(path.stat().st_size for path in VM_ROOT.rglob("*") if path.is_file())
    if size > 32 * 1024**3:
        raise RuntimeError("验证机目录超过预算")
    print(json.dumps({"vm": VM_NAME, "bytes": size, "state": state.get("VMState"),
                      "evidence": str(evidence)}, ensure_ascii=False))


def connect():
    # 只首次信任任务机的回环转发，随后固定其主机公钥，禁止静默替换。
    client = paramiko.SSHClient()
    known = EVIDENCE / "vm_known_hosts"
    if known.exists():
        client.load_host_keys(str(known))
    else:
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect("127.0.0.1", port=SSH_PORT, username="ruoyitest",
                   key_filename=str(KEY), look_for_keys=False, allow_agent=False, timeout=10)
    if not known.exists():
        client.save_host_keys(str(known))
    return client


def command(value):
    with connect() as client:
        _, stdout, stderr = client.exec_command(value, timeout=1200)
        while not stdout.channel.exit_status_ready():
            if stdout.channel.recv_ready():
                print(stdout.channel.recv(65536).decode("utf-8"), end="", flush=True)
            if stderr.channel.recv_stderr_ready():
                print(stderr.channel.recv_stderr(65536).decode("utf-8"), end="", flush=True)
            time.sleep(0.2)
        print(stdout.read().decode("utf-8"), end="")
        print(stderr.read().decode("utf-8"), end="")
        raise SystemExit(stdout.channel.recv_exit_status())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("create", "inspect", "command"))
    parser.add_argument("--command")
    args = parser.parse_args()
    if args.action == "create":
        initialize()
    elif args.action == "inspect":
        inspect()
    elif args.command:
        command(args.command)
    else:
        parser.error("command 操作必须提供命令")
