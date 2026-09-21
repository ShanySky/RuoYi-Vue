"""在真实隔离命令执行中，经若依停止或撤权，核对运行、容器和后续模型请求。"""

from concurrent.futures import ThreadPoolExecutor
import json
import threading
import time
import uuid
import requests
import ai_server_api_acceptance as h
from ai_server_governance_acceptance import sql, steps
from ai_evolution_stack import credentials
from ai_harness_lifecycle_acceptance import wait_for


def run():
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "ws_" + uuid.uuid4().hex[:8]
    evidence = {"passed": False, "checks": [], "paidRequests": 0}
    user, role = None, None
    try:
        permission = sql("select menu_id from sys_menu where perms='ai:workspace:use'")[0]["menu_id"]
        h.api(root, "/system/role", "POST", {"roleName": tag, "roleKey": tag, "roleSort": 90, "dataScope": "3", "status": "0", "menuIds": [permission]})
        role = sql("select role_id from sys_role where role_key=%s", (tag,))[0]["role_id"]
        h.api(root, "/system/user", "POST", {"userName": tag, "nickName": tag, "deptId": 103, "password": "TestOnly_123!", "roleIds": [role], "postIds": [], "status": "0"})
        user = sql("select user_id from sys_user where user_name=%s", (tag,))[0]["user_id"]
        token = h.api(None, "/login", "POST", {"username": tag, "password": "TestOnly_123!"})["token"]
        h.api(root, "/ai/config/provider", "POST", {"name": "活动隔离命令停止验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1", "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
        model = next(row for row in h.api(root, "/ai/config/models")["data"] if row["modelCode"] == "mock-api-model")
        def status():
            response = requests.post("http://127.0.0.1:28097/v1/status", headers={"Authorization": "Bearer " + credentials()["workspace"]}, json={}, timeout=5)
            assert response.status_code == 200
            return response.json()
        assert status()["tasks"] == 0
        for mode in ("stop", "revoke"):
            label = tag + mode
            h.SCENARIOS[label] = steps([("workspace_execute", {"command": "sleep 25; echo SHOULD_NOT_FINISH", "timeoutSeconds": 30})])
            before = len(h.REQUESTS)
            with ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(h.api, token, "/ai/chat/turn", "POST", {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": [], "userMessage": label}, None)
                wait_for(lambda: status()["tasks"] == 1)
                call = sql("select call_id,run_id,conversation_id from ai_server_call where user_id=%s and tool_name='workspace_execute' and status='EXECUTING' order by create_time desc limit 1", (user,))[0]
                started = time.monotonic()
                if mode == "stop":
                    h.api(token, f"/ai/chat/runs/{call['run_id']}/cancel", "POST", {"reason": "USER_STOP"})
                else:
                    assert sql("delete from sys_role_menu where role_id=%s and menu_id=%s", (role, permission)) == 1
                wait_for(lambda: status()["tasks"] == 0, 12)
                result = future.result(timeout=45)
            state = sql("select status from ai_run where run_id=%s", (call["run_id"],))[0]["status"]
            actual = sql("select status,result_json from ai_server_call where call_id=%s", (call["call_id"],))[0]
            assert state in ("FAILED", "CANCELLED"), (mode, state, result)
            assert actual["status"] != "SUCCEEDED" and "SHOULD_NOT_FINISH" not in (actual["result_json"] or ""), actual
            assert len(h.REQUESTS) == before + 1, (mode, len(h.REQUESTS), before)
            evidence["checks"].append({"mode": mode, "runId": call["run_id"], "runStatus": state,
                "callStatus": actual["status"], "reclaimedSeconds": round(time.monotonic() - started, 2), "modelRequests": 1})
            print(json.dumps(evidence["checks"][-1], ensure_ascii=False), flush=True)
        evidence["passed"] = True
    finally:
        if user:
            h.api(root, "/system/user/" + str(user), "DELETE")
        if role:
            h.api(root, "/system/role/" + str(role), "DELETE")
        evidence["fixtureCleanup"] = True
        (h.EVIDENCE / ("workspace-cancel-revoke-" + h.STAMP + ".json")).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_WORKSPACE_CANCEL_REVOKE_OK", flush=True)


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
