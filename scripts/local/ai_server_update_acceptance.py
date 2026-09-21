"""验证服务端 PUT 修改仍经原业务入口、确认与操作日志。"""

import json
import threading
import uuid

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import steps, sql


def run():
    token = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    code = "api_edit_" + uuid.uuid4().hex[:8]
    model = next(value for value in h.api(token, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")
    h.api(token, "/ai/config/provider", "POST", {"name": "修改接口验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    capability = next(value for value in h.api(token, "/ai/admin/apis")["data"]
                      if value["method"] == "PUT" and value["path"] == "/system/post")
    h.api(token, "/ai/admin/apis/" + capability["id"], "PUT", {"fingerprint": capability["fingerprint"], "enabled": True})
    h.api(token, "/system/post", "POST", {"postCode": code, "postName": code, "postSort": 1, "status": "0"})
    row = h.api(token, "/system/post/list?postCode=" + code)["rows"][0]
    evidence = {"passed": False, "postId": row["postId"]}
    try:
        body = {"postId": row["postId"], "postCode": code, "postName": "修改已验收" + code[-4:], "postSort": 9, "status": "1"}
        h.SCENARIOS[code] = steps([("server_api_describe", {"id": capability["id"]}), (capability["id"], {"body": body})])
        request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}
        pending = h.api(token, "/ai/chat/turn", "POST", {**request, "userMessage": code})["data"]
        assert pending["type"] == "TOOL_CALL" and pending["toolCall"]["executionSide"] == "SERVER"
        assert h.api(token, "/system/post/list?postCode=" + code)["rows"][0]["postName"] == code
        call_id = pending["toolCall"]["callId"]
        confirmed = h.api(token, f"/ai/chat/conversations/{pending['conversationId']}/server-tools/{call_id}/confirm", "POST", {"approved": True})
        assert confirmed["data"]["status"] == "SUCCEEDED", confirmed
        finished = h.api(token, "/ai/chat/turn", "POST", {**request, "conversationId": pending["conversationId"], "toolResult": {"callId": call_id}})["data"]
        assert json.loads(finished["message"])["success"]
        actual = h.api(token, "/system/post/list?postCode=" + code)["rows"][0]
        assert all(actual[key] == value for key, value in body.items())
        assert sql("select count(*) as n from sys_oper_log where method like '%%SysPostController.edit%%' and oper_param like %s", ("%" + code + "%",))[0]["n"] >= 1
        evidence.update({"passed": True, "conversationId": pending["conversationId"], "fields": list(body), "trace": str(h.TRACE)})
    finally:
        h.api(token, "/system/post/" + str(row["postId"]), "DELETE")
        evidence["fixtureCleanup"] = True
        (h.EVIDENCE / f"api-update-{h.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
