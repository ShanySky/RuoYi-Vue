"""真实核对归属变更、触发器完整性和已知写入后的会话停止。"""

import json
import threading
import uuid
import os
import subprocess
from pathlib import Path

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import steps, sql
from ai_evolution_stack import migrate


def run():
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "scope_" + uuid.uuid4().hex[:8]
    users = []
    role_id = None
    checks = []
    request = {"route": "/index", "pageContext": {}, "frontendTools": []}
    h.api(root, "/ai/config/provider", "POST", {"name": "归属保护验收", "baseUrl": "http://127.0.0.1:18093/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    request["modelId"] = next(value for value in h.api(root, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")["modelId"]
    catalog = h.api(root, "/ai/admin/apis")["data"]
    read_cap = next(value for value in catalog if value["method"] == "GET" and value["path"] == "/system/user/list")
    write_cap = next(value for value in catalog if value["method"] == "PUT" and value["path"] == "/system/user")
    for capability in (read_cap, write_cap):
        if not capability["enabled"]:
            h.api(root, "/ai/admin/apis/" + capability["id"], "PUT", {"fingerprint": capability["fingerprint"], "enabled": True})

    def turn(token, label, sequence):
        h.SCENARIOS[label] = steps(sequence)
        return h.api(token, "/ai/chat/turn", "POST", {**request, "userMessage": label})["data"]

    try:
        menus = sql("select menu_id from sys_menu where perms in ('system:user:list','system:user:edit')")
        h.api(root, "/system/role", "POST", {"roleName": tag, "roleKey": tag, "roleSort": 90,
            "dataScope": "3", "status": "0", "menuIds": [value["menu_id"] for value in menus]})
        role_id = sql("select role_id from sys_role where role_key=%s", (tag,))[0]["role_id"]
        for suffix in ("reader", "subject"):
            h.api(root, "/system/user", "POST", {"userName": tag + suffix, "nickName": tag + suffix,
                "password": "TestOnly_123!", "deptId": 103, "roleIds": [role_id], "postIds": [], "status": "0"})
            users.append(sql("select user_id from sys_user where user_name=%s", (tag + suffix,))[0]["user_id"])
        token = h.api(None, "/login", "POST", {"username": tag + "reader", "password": "TestOnly_123!"})["token"]
        read = turn(token, tag + "read", [("server_api_describe", {"id": read_cap["id"]}),
            (read_cap["id"], {"query": {"userName": tag + "subject"}}),
            ("server_api_result", lambda results: {"resultId": json.loads(results[1]["content"])["result"]["resultId"], "jsonPointer": "/rows"})])
        assert json.loads(read["message"])["result"]["items"][0]["userId"] == users[1]
        subject = {"userId": users[1], "userName": tag + "subject", "nickName": tag + "subject", "deptId": 104,
                   "roleIds": [role_id], "postIds": [], "status": "0"}
        h.api(root, "/system/user", "PUT", subject)
        assert h.api(token, "/system/user/list?userName=" + tag + "subject")["total"] == 0
        history = h.api(token, f"/ai/chat/conversations/{read['conversationId']}", expect=None)
        assert history["code"] != 200 and tag + "subject" not in json.dumps(history), history
        before = len(h.REQUESTS)
        denied = h.api(token, "/ai/chat/turn", "POST", {**request, "conversationId": read["conversationId"], "userMessage": "继续读取上一份结果"}, expect=None)
        assert denied["code"] != 200 and len(h.REQUESTS) == before
        metadata = h.api(token, f"/ai/chat/conversations/{read['conversationId']}/run-state")["data"]
        assert set(metadata) == {"activeRun"}
        assert h.api(root, f"/ai/chat/conversations/{read['conversationId']}/run-state", expect=None)["code"] != 200
        checks.append("记录移出部门后旧历史与模型续跑拒绝；状态入口仅提供本人运行事实")
        repair_source = turn(token, tag + "beforeRepair", [("server_api_describe", {"id": read_cap["id"]}),
            (read_cap["id"], {"query": {"userName": tag + "reader"}})])
        for corruption in ("missing", "changed"):
            before_revision = sql("select revision from ai_scope_revision where guard_id=1")[0]["revision"]
            sql("drop trigger ai_scope_user_update")
            if corruption == "changed":
                sql("create trigger ai_scope_user_update after update on sys_user for each row update ai_scope_revision set revision=revision where guard_id=1")
            try:
                denied = turn(token, tag + corruption, [("server_api_describe", {"id": read_cap["id"]})])
                assert json.loads(denied["message"])["success"] is False, denied
            finally:
                migrate()
            assert sql("select revision from ai_scope_revision where guard_id=1")[0]["revision"] > before_revision
            checks.append("触发器" + ("缺失" if corruption == "missing" else "定义篡改") + "时禁止披露")
        assert h.api(token, f"/ai/chat/conversations/{repair_source['conversationId']}", expect=None)["code"] != 200
        checks.append("保护修复会提升归属版本，修复前旧结果不会重新变得可读")
        sql("update sys_role set data_scope='1' where role_id=%s", (role_id,))
        subject["deptId"] = 103
        pending = turn(token, tag + "write", [("server_api_describe", {"id": write_cap["id"]}),
                                               (write_cap["id"], {"body": subject})])
        assert pending["type"] == "TOOL_CALL" and pending["toolCall"]["executionSide"] == "SERVER", pending
        call_id = pending["toolCall"]["callId"]
        before = len(h.REQUESTS)
        outcome = h.api(token, f"/ai/chat/conversations/{pending['conversationId']}/server-tools/{call_id}/confirm", "POST", {"approved": True})["data"]
        assert outcome["status"] == "SUCCEEDED" and not outcome["continuationAllowed"], outcome
        assert h.api(root, "/system/user/" + str(users[1]))["data"]["deptId"] == 103
        assert h.api(token, "/ai/chat/runs/" + str(pending["runId"]))["data"]["status"] == "FAILED"
        assert len(h.REQUESTS) == before
        checks.append("已知写入保存成功事实且停止后续运行，不误标未知、不继续调用模型")
        browser_scenario = tag + "browser"
        subject["nickName"] = "归属界面验收" + tag[-4:]
        h.SCENARIOS[browser_scenario] = steps([("server_api_describe", {"id": write_cap["id"]}),
                                              (write_cap["id"], {"body": subject})])
        environment = {name: os.environ[name] for name in
            ("SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PATH") if name in os.environ}
        environment.update({"SCOPE_TEST_ACCOUNT": tag + "reader", "SCOPE_TEST_SCENARIO": browser_scenario,
            "PLAYWRIGHT_EXECUTABLE_PATH": "C:/Program Files/Google/Chrome/Application/chrome.exe",
            "EVIDENCE_DIR": str(h.EVIDENCE / "scope-browser")})
        frontend = Path(__file__).resolve().parents[3] / "RuoYi-Vue3"
        subprocess.run(["C:/Users/Shane/AppData/Roaming/fnm/node-versions/v20.20.2/installation/node.exe",
                        "tests/ai-scope-change-e2e.mjs"], cwd=frontend, env=environment, check=True, timeout=120)
        assert h.api(root, "/system/user/" + str(users[1]))["data"]["nickName"] == subject["nickName"]
        checks.append("浏览器展示已知写入事实和停止说明，不再提交模型续跑")
        evidence = {"passed": True, "checks": checks, "readConversation": read["conversationId"],
                    "writeConversation": pending["conversationId"], "trace": str(h.TRACE)}
        (h.EVIDENCE / f"api-scope-revision-{h.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(evidence, ensure_ascii=False), flush=True)
    finally:
        for user_id in users:
            h.api(root, "/system/user/" + str(user_id), "DELETE")
        if role_id:
            h.api(root, "/system/role/" + str(role_id), "DELETE")


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
