"""真实杀死任务框架和后端进程，核对确认、租约、孤儿目录及未知写入保护。"""

from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid
import requests
import ai_server_api_acceptance as h
from ai_server_governance_acceptance import sql, steps
from ai_evolution_stack import credentials
from ai_evolution_run import start


def powershell(source):
    return subprocess.check_output(["powershell.exe", "-NoProfile", "-Command", source], encoding="utf-8")


def children(parent):
    result = powershell(f"[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; Get-CimInstance Win32_Process -Filter 'ParentProcessId={int(parent)}' | Where-Object {{ $_.Name -eq 'node.exe' -and $_.CommandLine -like '*runtime*pi*worker.mjs*' }} | Select-Object -ExpandProperty ProcessId")
    return [int(value) for value in result.split()]


def kill(pid, marker):
    assert marker and all(value not in marker for value in "'`$\r\n")
    powershell(f"$taskProcess=Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}'; if($null -eq $taskProcess -or $taskProcess.CommandLine -notlike '*{marker}*'){{throw '进程归属不匹配'}}; Stop-Process -Id {int(pid)} -Force")


def wait_for(check, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.3)
    raise AssertionError("在规定时间内未观察到实际状态")


def run():
    h.REQUEST_TIMEOUT = 360
    stamp = h.STAMP
    tag = "life_" + uuid.uuid4().hex[:8]
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    record_path = max((h.EVIDENCE / "runtime-evolution").glob("backend-*.json"), key=lambda value: value.name)
    before_runtime = json.loads(record_path.read_text())
    pid = before_runtime["pid"]
    evidence = {"passed": False, "beforeRuntime": before_runtime, "checks": [], "paidRequests": 0}
    auth = {"Authorization": "Bearer " + credentials()["workspace"]}
    conversations, post = [], None
    restarted = False

    def status():
        result = requests.post("http://127.0.0.1:28097/v1/status", headers=auth, json={}, timeout=5)
        assert result.status_code == 200
        return result.json()

    def note(name, **facts):
        evidence["checks"].append({"name": name, **facts})
        (h.EVIDENCE / ("harness-lifecycle-" + stamp + ".json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(evidence["checks"][-1], ensure_ascii=False), flush=True)

    try:
        assert status()["tasks"] == 0 and not children(pid)
        h.api(root, "/ai/config/provider", "POST", {"name": "执行框架退出验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1", "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
        model = next(value for value in h.api(root, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")
        request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}
        capability = next(value for value in h.api(root, "/ai/admin/apis")["data"] if value["path"] == "/system/post" and value["method"] == "POST")
        h.api(root, "/ai/admin/apis/" + capability["id"], "PUT", {"fingerprint": capability["fingerprint"], "enabled": True})

        def write(suffix, code):
            label = tag + suffix
            h.SCENARIOS[label] = steps([("server_api_describe", {"id": capability["id"]}),
                (capability["id"], {"body": {"postCode": code, "postName": code, "postSort": 9, "status": "0"}})])
            response = h.api(root, "/ai/chat/turn", "POST", {**request, "userMessage": label})["data"]
            assert response["type"] == "TOOL_CALL", response
            conversations.append(response["conversationId"])
            return response

        first = write("worker", tag + "none")
        worker_pids = children(pid)
        assert len(worker_pids) == 1
        calls_before = len(h.REQUESTS)
        kill(worker_pids[0], "worker.mjs")
        wait_for(lambda: sql("select status from ai_run where run_id=%s", (first["runId"],))[0]["status"] == "FAILED")
        assert sql("select status from ai_pending_tool_call where call_id=%s", (first["toolCall"]["callId"],))[0]["status"] == "CANCELLED"
        rejected = h.api(root, f"/ai/chat/conversations/{first['conversationId']}/server-tools/{first['toolCall']['callId']}/confirm", "POST", {"approved": True}, expect=None)
        assert rejected["code"] != 200
        assert sql("select count(*) n from sys_post where post_code=%s", (tag + "none",))[0]["n"] == 0
        assert len(h.REQUESTS) == calls_before
        note("确认等待中的 Pi 进程异常退出，运行和待办关闭且原业务没有写入", workerPid=worker_pids[0])

        uncertain = write("uncertain", tag + "known")
        confirm_path = f"/ai/chat/conversations/{uncertain['conversationId']}/server-tools/{uncertain['toolCall']['callId']}/confirm"
        h.api(root, confirm_path, "POST", {"approved": True})
        post = sql("select post_id from sys_post where post_code=%s", (tag + "known",))[0]["post_id"]
        assert sql("update ai_server_call set status='EXECUTING',result_id=null,result_json=null,tool_result_json=null,start_time=sysdate(),end_time=null where call_id=%s and status='SUCCEEDED'", (uncertain["toolCall"]["callId"],)) == 1
        note("受控故障模拟：真实业务已提交，但执行回执未持久形成", postId=post, callId=uncertain["toolCall"]["callId"])

        label = tag + "crash"
        h.SCENARIOS[label] = steps([("workspace_execute", {"command": "sleep 25", "timeoutSeconds": 30})])
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(h.api, root, "/ai/chat/turn", "POST", {**request, "userMessage": label})
            wait_for(lambda: status()["tasks"] == 1)
            running = sql("select c.conversation_id,c.run_id from ai_server_call c join ai_run r on r.run_id=c.run_id where c.tool_name='workspace_execute' and c.status='EXECUTING' and r.status='WAITING_TOOL' order by c.create_time desc limit 1")[0]
            conversations.append(running["conversation_id"])
            owned_children = children(pid)
            assert len(owned_children) == 2
            kill(pid, Path(before_runtime["jar"]).name)
            wait_for(lambda: status()["tasks"] == 0, 25)
            wait_for(lambda: not children(pid), 10)
            try:
                future.result(timeout=5)
                raise AssertionError("后端被杀死后原请求仍成功")
            except requests.RequestException:
                pass
        note("后端被强制终止后，两个 Pi 子进程退出且失租回收实际命令容器", parentPid=pid, workerPids=owned_children)

        start("backend", False)
        restarted = True
        after = max((h.EVIDENCE / "runtime-evolution").glob("backend-*.json"), key=lambda value: value.name)
        evidence["afterRuntime"] = json.loads(after.read_text())
        assert sql("select status from ai_server_call where call_id=%s", (uncertain["toolCall"]["callId"],))[0]["status"] == "UNKNOWN"
        for run_id in (uncertain["runId"], running["run_id"]):
            assert sql("select status from ai_run where run_id=%s", (run_id,))[0]["status"] == "FAILED"
            assert sql("select count(*) n from ai_pending_tool_call where run_id=%s and status='PENDING'", (run_id,))[0]["n"] == 0
        assert h.api(root, confirm_path, "POST", {"approved": True}, expect=None)["code"] != 200
        retry_label = tag + "retry"
        h.SCENARIOS[retry_label] = steps([("server_api_describe", {"id": capability["id"]}),
            (capability["id"], {"body": {"postCode": tag + "known", "postName": tag + "known", "postSort": 9, "status": "0"}})])
        retry = h.api(root, "/ai/chat/turn", "POST", {**request, "conversationId": uncertain["conversationId"], "userMessage": retry_label}, expect=None)
        assert retry["code"] != 200
        assert sql("select count(*) n from sys_post where post_code=%s", (tag + "known",))[0]["n"] == 1
        assert sql("select count(*) n from sys_oper_log where business_type=1 and oper_url='/system/post' and oper_param like %s", ("%" + tag + "known%",))[0]["n"] == 1
        directory = h.EVIDENCE / "runtime-evolution/uploads/ai-harness"
        wait_for(lambda: not list(directory.glob("pi_*")), 100)
        note("重启收束旧运行，未知写入拒绝重试且原接口只被调用一次，框架异常目录已回收")
        evidence["passed"] = True
    finally:
        (h.EVIDENCE / ("harness-lifecycle-" + stamp + ".json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        if not restarted:
            try:
                requests.get(h.BASE + "/captchaImage", timeout=2)
            except requests.RequestException:
                start("backend", False)
        if post:
            h.api(root, "/system/post/" + str(post), "DELETE")
        for conversation in conversations:
            h.api(root, "/ai/chat/conversations/" + str(conversation), "DELETE", expect=None)
        (h.EVIDENCE / ("harness-lifecycle-" + stamp + ".json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_HARNESS_LIFECYCLE_OK", flush=True)


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
