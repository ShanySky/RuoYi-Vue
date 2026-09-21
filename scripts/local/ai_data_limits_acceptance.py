"""通过真实岗位数据证明分页、字节上限、绑定参数和到期拒绝。"""

import json
import threading
import uuid

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import sql, steps


def run():
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "dlimit_" + uuid.uuid4().hex[:7]
    h.api(root, "/ai/config/provider", "POST", {"name": "数据结果限制验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    model = next(row for row in h.api(root, "/ai/config/models")["data"] if row["modelCode"] == "mock-api-model")
    request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}
    checks = []

    def turn(suffix, query, hook=None, page=False):
        label = tag + suffix
        sequence = [("server_data_describe", {"id": "data_posts"}), ("data_posts", query)]
        if page:
            sequence.append(("server_api_result", lambda rows: {"resultId": json.loads(rows[1]["content"])["result"]["resultId"], "jsonPointer": "/rows", "limit": 50}))
        h.SCENARIOS[label] = steps(sequence, hook)
        return h.api(root, "/ai/chat/turn", "POST", {**request, "userMessage": label})["data"]

    base = {"operation": "QUERY", "columns": ["post_id", "post_code"], "limit": 100,
        "filters": [{"field": "post_code", "operator": "contains", "value": tag}]}
    try:
        for index in range(101):
            h.api(root, "/system/post", "POST", {"postCode": tag + str(index), "postName": tag + str(index),
                "postSort": 10, "status": "0", "remark": "数" * 500})
        result = turn("page", base)
        payload = json.loads(result["message"])
        assert payload["success"] is True, payload
        source = sql("select result_json from ai_server_call where result_id=%s", (payload["result"]["resultId"],))[0]
        rows = json.loads(source["result_json"])
        assert len(rows["rows"]) == 100 and rows["hasMore"] is True and rows["nextOffset"] == 100, rows
        last = turn("last", {**base, "offset": 100}, page=True)
        assert len(json.loads(last["message"])["result"]["items"]) == 1
        large = json.loads(turn("large", {**base, "columns": ["post_code", "remark"]})["message"])
        assert large["success"] is False and "128 KiB" in large["error"], large
        small = json.loads(turn("small", {**base, "columns": ["post_code", "remark"], "limit": 1}, page=True)["message"])
        assert small["success"] and len(small["result"]["items"]) == 1
        checks.append("101 条真实岗位按 100/1 分页；超出 128 KiB 结果拒绝，缩小后可读")
        injection = json.loads(turn("injection", {**base, "filters": [{"field": "post_code", "operator": "eq", "value": "x' OR 1=1; DELETE FROM sys_post --"}]}, page=True)["message"])
        assert injection["success"] and injection["result"]["items"] == []
        assert h.api(root, "/system/post/list?postCode=" + tag + "&pageSize=1")["total"] == 101
        checks.append("带 SQL 注入内容的值仅作为参数匹配，返回零行且真实岗位不变")
        def handle(results, limit):
            return {"resultId": json.loads(results[1]["content"])["result"]["resultId"], "jsonPointer": "/rows", "limit": limit}
        h.SCENARIOS[tag + "repair"] = steps([("server_data_describe", {"id": "data_posts"}),
            ("data_posts", {**base, "limit": 1}),
            ("server_api_result", lambda results: handle(results, 100)),
            ("server_api_result", lambda results: handle(results, 1))])
        repaired = h.api(root, "/ai/chat/turn", "POST", {**request, "userMessage": tag + "repair"})["data"]
        messages = h.api(root, f"/ai/chat/conversations/{repaired['conversationId']}")["data"]["messages"]
        attempts = [json.loads(message["content"]) for message in messages if message["role"] == "TOOL" and message["toolName"] == "server_api_result"]
        assert len(attempts) == 2 and attempts[0]["success"] is False and "limit" in attempts[0]["error"], attempts
        assert attempts[1]["success"] is True and len(attempts[1]["result"]["items"]) == 1
        checks.append("读取上限仍为50；模型收到参数拒绝后可缩小参数重查，不把校验拒绝误报为状态保存失败")
        def expire(index):
            if index == 2:
                call = sql("select c.call_id from ai_server_call c join ai_message m on m.conversation_id=c.conversation_id "
                    "where m.role='USER' and m.content=%s and c.result_id is not null", (tag + "expired",))[0]
                sql("update ai_server_call set expire_time=date_sub(sysdate(),interval 1 minute) where call_id=%s", (call["call_id"],))
        expired = json.loads(turn("expired", {**base, "limit": 1}, hook=expire, page=True)["message"])
        assert expired["success"] is False, expired
        checks.append("结果过期后同一运行读取也被拒绝")
        old = next(row for row in h.api(root, "/ai/admin/data")["data"]["tables"] if row["key"] == "sys_post")
        sql("alter table sys_post add column ai_data_acceptance_probe int null")
        try:
            h.SCENARIOS[tag + "changed"] = steps([("server_data_describe", {"id": "data_posts"})])
            changed = h.api(root, "/ai/chat/turn", "POST", {**request, "userMessage": tag + "changed"})["data"]
            assert json.loads(changed["message"])["success"] is False
            assert h.api(root, "/ai/admin/data/sys_post", "PUT", {"fingerprint": old["fingerprint"], "enabled": True,
                "fields": old["fields"], "operations": old["operations"]}, expect=None)["code"] != 200
        finally:
            sql("alter table sys_post drop column ai_data_acceptance_probe")
        checks.append("真实表结构改变后旧开放及旧治理请求均失效，新增列不自动开放")
        evidence = {"passed": True, "checks": checks, "trace": str(h.TRACE)}
        (h.EVIDENCE / f"data-limits-{h.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(evidence, ensure_ascii=False), flush=True)
    finally:
        for row in h.api(root, "/system/post/list?postCode=" + tag + "&pageSize=200")["rows"]:
            h.api(root, "/system/post/" + str(row["postId"]), "DELETE")


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
