"""用真实业务结果验证分页披露和数据库配额锁的拒绝效果。"""

import json
import threading
import uuid

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import database, sql, steps


def run():
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "limit_" + uuid.uuid4().hex[:8]
    h.api(root, "/ai/config/provider", "POST", {"name": "结果限制验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    model = next(value for value in h.api(root, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")
    capability = next(value for value in h.api(root, "/ai/admin/apis")["data"]
                      if value["method"] == "GET" and value["path"] == "/system/post/list")
    request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}
    reports = []

    def turn(label, sequence):
        h.SCENARIOS[label] = steps(sequence)
        return h.api(root, "/ai/chat/turn", "POST", {**request, "userMessage": label})["data"]

    query = [("server_api_describe", {"id": capability["id"]}),
             (capability["id"], {"query": {"postCode": tag, "pageSize": 50}})]
    try:
        for index in range(12):
            h.api(root, "/system/post", "POST", {"postCode": tag + str(index), "postName": tag + str(index),
                "postSort": 20, "status": "0", "remark": "受控结果验收" * 70})
        def result_args(results, limit):
            return {"resultId": json.loads(results[1]["content"])["result"]["resultId"], "jsonPointer": "/rows", "limit": limit}
        result = turn(tag + "large", query + [
            ("server_api_result", lambda results: result_args(results, 50)),
            ("server_api_result", lambda results: result_args(results, 1))])
        messages = h.api(root, f"/ai/chat/conversations/{result['conversationId']}")["data"]["messages"]
        disclosures = [json.loads(value["content"]) for value in messages if value["role"] == "TOOL"
                       and value["toolName"] == "server_api_result"]
        assert not disclosures[0]["success"] and "过大" in disclosures[0]["error"], disclosures
        assert disclosures[1]["success"] and len(disclosures[1]["result"]["items"]) == 1
        assert disclosures[1]["result"]["total"] == 12
        reports.append("超出 8 KiB 的一次披露被拒绝，缩为一条后可读取真实结果")
        source = sql("select * from ai_server_call where conversation_id=%s and result_id is not null", (result["conversationId"],))[0]
        for scope, maximum, user_id in (("user", 64, 1), ("global", 1024, 999999)):
            count = sql("select count(*) as n from ai_server_call where result_json is not null and expire_time>sysdate()"
                        + (" and user_id=1" if scope == "user" else ""))[0]["n"]
            assert count < maximum, "现有结果已满，先核对环境恢复点"
            fixture_name = tag + scope
            with database() as connection, connection.cursor() as cursor:
                cursor.executemany("insert into ai_server_call(call_id,conversation_id,run_id,user_id,tool_name,"
                    "risk_level,status,result_id,result_json,create_time,expire_time) values(%s,%s,%s,%s,%s,'READ',"
                    "'SUCCEEDED',%s,'{}',sysdate(),date_add(sysdate(),interval 30 minute))",
                    [(uuid.uuid4().hex, source["conversation_id"], source["run_id"], user_id, fixture_name,
                      uuid.uuid4().hex) for _ in range(maximum - count)])
            try:
                denied = turn(tag + scope, query)
                payload = json.loads(denied["message"])
                assert payload["success"] is False and "空间已达上限" in payload["error"], payload
                saved = sql("select count(*) as n from ai_server_call where conversation_id=%s and result_json is not null",
                            (denied["conversationId"],))[0]["n"]
                assert saved == 0
                reports.append(scope + " 结果配额达到上限后，真实查询的新结果不能保存")
            finally:
                sql("delete from ai_server_call where tool_name=%s and conversation_id=%s", (fixture_name, source["conversation_id"]))
    finally:
        for row in h.api(root, "/system/post/list?postCode=" + tag + "&pageSize=50")["rows"]:
            h.api(root, "/system/post/" + str(row["postId"]), "DELETE")
        (h.EVIDENCE / f"api-result-limits-{h.STAMP}.json").write_text(json.dumps(
            {"passed": len(reports) == 3, "checks": reports, "fixtureCleanup": True, "trace": str(h.TRACE)},
            ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": True, "checks": reports}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
