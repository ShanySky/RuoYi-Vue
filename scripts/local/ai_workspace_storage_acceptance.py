"""上传同版本真实存储边界验收并取回证据。"""
from datetime import datetime
from ai_evolution_vm import connect, EVIDENCE
from ai_workspace_environment import ROOT, REMOTE, execute

with connect() as client:
    with client.open_sftp() as transfer:
        transfer.put(str(ROOT / "runtime/workspace/storage_test.py"), REMOTE + "/storage_test.py")
    try:
        execute(client, "sudo python3 -u " + REMOTE + "/storage_test.py", timeout=900)
    finally:
        source = "/var/lib/ruoyi-ai-workspace-storage-test/evidence.json"
        execute(client, "sudo install -m 0644 " + source + " " + REMOTE + "/storage-evidence.json")
        with client.open_sftp() as transfer:
            target = EVIDENCE / ("workspace-storage-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
            transfer.get(REMOTE + "/storage-evidence.json", str(target))
        print("存储证据：" + str(target), flush=True)
