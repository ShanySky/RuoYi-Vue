"""真实写入成功但结果空间满时，保留执行事实并禁止自动续跑。"""

import json
import threading
import uuid

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import database, sql, steps


def run():
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "known_" + uuid.uuid4().hex[:8]
    h.api(root, "/ai/config/provider", "POST", {"name": "已知写入结果验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    model = next(value for value in h.api(root, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")
    capability = next(value for value in h.api(root, "/ai/admin/apis")["data"]
        if value["method"] == "POST" and value["path"] == "/system/post")
    if not capability["enabled"]:
        h.api(root, "/ai/admin/apis/" + capability["id"], "PUT", {"fingerprint": capability["fingerprint"], "enabled": True})
    h.SCENARIOS[tag] = steps([("server_api_describe", {"id": capability["id"]}),
        (capability["id"], {"body": {"postCode": tag, "postName": tag, "postSort": 20, "status": "0"}})])
    pending = h.api(root, "/ai/chat/turn", "POST", {"modelId": model["modelId"], "route": "/index",
        "pageContext": {}, "frontendTools": [], "userMessage": tag})["data"]
    assert pending["type"] == "TOOL_CALL"
    call_id = pending["toolCall"]["callId"]
    try:
        count = sql("select count(*) as n from ai_server_call where user_id=1 and result_json is not null and expire_time>sysdate()")[0]["n"]
        assert count < 64
        with database() as connection, connection.cursor() as cursor:
            cursor.executemany("insert into ai_server_call(call_id,conversation_id,run_id,user_id,tool_name,"
                "risk_level,status,result_id,result_json,create_time,expire_time) values(%s,%s,%s,1,%s,'READ',"
                "'SUCCEEDED',%s,'{}',sysdate(),date_add(sysdate(),interval 30 minute))",
                [(uuid.uuid4().hex, pending["conversationId"], pending["runId"], tag, uuid.uuid4().hex) for _ in range(64 - count)])
        before = len(h.REQUESTS)
        assert h.api(root, "/system/post/list?postCode=" + tag)["total"] == 0
        endpoint = f"/ai/chat/conversations/{pending['conversationId']}/server-tools/{call_id}/confirm"
        result = h.api(root, endpoint, "POST", {"approved": True})["data"]
        assert result["status"] == "SUCCEEDED" and result["continuationAllowed"] is False, result
        # 停止后原待确认项已取消；重复确认须拒绝，下面另查真实副作用和持久事实。
        assert h.api(root, endpoint, "POST", {"approved": True}, expect=None)["code"] != 200
        assert len(h.REQUESTS) == before
        rows = h.api(root, "/system/post/list?postCode=" + tag)["rows"]
        assert len(rows) == 1 and rows[0]["postCode"] == tag
        call = sql("select status,error_code,result_json from ai_server_call where call_id=%s", (call_id,))[0]
        assert call == {"status": "SUCCEEDED", "error_code": "RESULT_NOT_DISCLOSED_AFTER_EXECUTION", "result_json": None}, call
        assert h.api(root, f"/ai/chat/runs/{pending['runId']}")["data"]["status"] == "FAILED"
        evidence = {"passed": True, "check": "结果空间满仍保留已知写入成功；停止续跑、重复确认无第二次副作用",
            "conversationId": pending["conversationId"], "trace": str(h.TRACE)}
        (h.EVIDENCE / f"api-known-write-limit-{h.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(evidence, ensure_ascii=False), flush=True)
    finally:
        sql("delete from ai_server_call where tool_name=%s and conversation_id=%s", (tag, pending["conversationId"]))
        for row in h.api(root, "/system/post/list?postCode=" + tag)["rows"]:
            h.api(root, "/system/post/" + str(row["postId"]), "DELETE")


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
