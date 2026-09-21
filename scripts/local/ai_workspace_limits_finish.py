"""补充真实处理器节流、第二临时目录限额和慢下载并发/时限反例。"""

import json
import threading
import time
import requests
import ai_server_api_acceptance as h
import ai_workspace_acceptance as b
from ai_server_governance_acceptance import sql, steps


def run():
    evidence = {"passed": False, "checks": [], "paidRequests": 0}
    threading.Thread(target=b.heartbeat, daemon=True).start()
    clients, files, conversation = [], [], None
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    try:
        result = b.execute("python - <<'PY'\nimport time,json\nfrom pathlib import Path\ndef stats():return dict(line.split() for line in Path('/sys/fs/cgroup/cpu.stat').read_text().splitlines())\nbefore=stats();wall=time.monotonic();cpu=time.process_time()\nwhile time.monotonic()-wall<3: sum(range(20000))\nafter=stats();print(json.dumps({'wall':time.monotonic()-wall,'cpu':time.process_time()-cpu,'throttled':int(after['nr_throttled'])-int(before['nr_throttled'])}))\nPY")
        cpu = json.loads(result["output"])
        assert cpu["throttled"] > 0 and cpu["cpu"] < cpu["wall"] * 0.8, cpu
        full = b.execute("python - <<'PY'\nfrom pathlib import Path\ntry:\n Path('/tmp/a').write_bytes(b'x'*(8*1024*1024));Path('/tmp/b').write_bytes(b'x'*4096);print('UNBOUNDED')\nexcept OSError as e:print('TMP_LIMIT',e.errno)\nPY")
        assert "TMP_LIMIT 28" in full["output"] and "UNBOUNDED" not in full["output"]
        b.execute("rm -f /tmp/a /tmp/b")
        nodes = b.execute("python - <<'PY'\nfrom pathlib import Path\ntry:\n for i in range(100):Path('/tmp/n'+str(i)).touch()\n print('UNBOUNDED')\nexcept OSError as e:print('TMP_NODES',e.errno,i)\nPY")
        assert "TMP_NODES 28" in nodes["output"] and "UNBOUNDED" not in nodes["output"]
        b.stop(b.task())
        evidence["checks"].append({"name": "实际处理器节流和 /tmp 8 MiB / 64 节点拒绝", **cpu, "nodes": nodes["output"]})
        print(json.dumps(evidence["checks"][-1], ensure_ascii=False), flush=True)

        h.api(root, "/ai/config/provider", "POST", {"name": "慢下载反例验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1", "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
        model = next(row for row in h.api(root, "/ai/config/models")["data"] if row["modelCode"] == "mock-api-model")
        label = "slow_download_" + h.STAMP
        h.SCENARIOS[label] = steps([("workspace_execute", {"command": "python -c \"open('bounded.bin','wb').write(b'x'*(8*1024*1024))\""}),
            ("workspace_publish", {"path": "bounded.bin", "name": "慢下载验收.bin"})])
        result = h.api(root, "/ai/chat/turn", "POST", {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": [], "userMessage": label})["data"]
        conversation = result["conversationId"]
        files = h.api(root, f"/ai/chat/conversations/{conversation}/artifacts")["data"]
        assert len(files) == 1 and files[0]["bytes"] == 8 * 1024 * 1024
        url = h.BASE + "/ai/artifacts/" + files[0]["id"] + "/download"
        headers = {"Authorization": "Bearer " + root}
        started = time.monotonic()
        for _ in range(4):
            response = requests.get(url, headers=headers, stream=True, timeout=10)
            assert response.headers["Content-Type"] == "application/octet-stream"
            clients.append(response)
        rejected = requests.get(url, headers=headers, timeout=10).json()
        assert rejected["code"] != 200 and "并发" in rejected["msg"], rejected
        print("四个慢读取已占用下载槽，第五个被实际拒绝，等待 60 秒上限回收。", flush=True)
        while time.monotonic() - started < 66:
            time.sleep(1)
        restored = requests.get(url, headers=headers, timeout=15)
        assert len(restored.content) == files[0]["bytes"] and restored.headers["Content-Type"] == "application/octet-stream"
        evidence["checks"].append({"name": "四慢下载拒绝第五个，原客户端未关闭时限到达后容量恢复", "elapsedSeconds": round(time.monotonic() - started, 2)})
        evidence["passed"] = True
    finally:
        for response in clients:
            response.close()
        for value in list(b.active.values()):
            b.stop(value)
        b.halt.set()
        for artifact in files:
            b.call("delete", {"userId": 1, "runId": artifact["runId"], "id": artifact["id"]})
            sql("delete from ai_artifact where artifact_id=%s", (artifact["id"],))
        if conversation:
            h.api(root, "/ai/chat/conversations/" + str(conversation), "DELETE", expect=None)
        (h.EVIDENCE / ("workspace-final-limits-" + h.STAMP + ".json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_WORKSPACE_FINAL_LIMITS_OK", flush=True)


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
