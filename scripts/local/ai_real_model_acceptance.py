"""累计预算内的真实模型接口闭环，业务断言以原接口与数据库事实为准。"""

import json
import threading
import uuid

import ai_server_api_acceptance as harness
from ai_evolution_stack import credentials
from ai_model_budget_proxy import Budget, Proxy, MODEL, setting, LEDGER_PATH, TRACE_PATH


def run():
    harness.REQUEST_TIMEOUT = 360
    api = harness.api
    root = api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    api(root, "/ai/config/provider", "POST", {"name": "预算内真实模型验收", "baseUrl": "http://127.0.0.1:18094/v1",
        "token": credentials()["modelProxy"], "enabled": True, "timeoutSeconds": 120})
    models = api(root, "/ai/config/models")["data"]
    if not any(value["modelCode"] == MODEL for value in models):
        api(root, "/ai/config/models", "POST", {"modelCodes": [MODEL]})
    model = next(value for value in api(root, "/ai/config/models")["data"] if value["modelCode"] == MODEL)
    request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}
    catalog = api(root, "/ai/admin/apis")["data"]
    for row in catalog:
        if (row["method"], row["path"]) in (("GET", "/system/post/list"), ("POST", "/system/post")) and not row["enabled"]:
            api(root, "/ai/admin/apis/" + row["id"], "PUT", {"fingerprint": row["fingerprint"], "enabled": True})
    before = api(root, "/system/post/list?pageSize=50")
    assert before["rows"], "缺少可核验的真实岗位数据"
    read = api(root, "/ai/chat/turn", "POST", {**request, "userMessage": "请查询后台当前岗位列表，列出前两条的岗位编号、岗位编码和岗位名称，并说明总数。请读取真实业务结果，不要使用页面数据或猜测。"})["data"]
    assert read["type"] == "MESSAGE", read
    detail = api(root, f"/ai/chat/conversations/{read['conversationId']}")["data"]
    tools = [value["toolName"] for value in detail["messages"] if value["role"] == "TOOL"]
    assert all(value in tools for value in ("server_api_search", "server_api_describe", "server_api_result")), tools
    assert sum(row["postName"] in read["message"] for row in before["rows"]) >= 2, read["message"]
    code = "real_api_" + uuid.uuid4().hex[:8]
    name = "真实模型验收" + code[-4:]
    evidence = {"model": MODEL, "readConversation": read["conversationId"], "readAnswer": read["message"],
                "tools": tools, "contextWindow": model.get("contextWindowTokens"), "ledger": str(LEDGER_PATH)}
    try:
        write = api(root, "/ai/chat/turn", "POST", {**request, "userMessage":
            f"请通过后台业务接口新增一个岗位，岗位编码为 {code}，岗位名称为{name}，显示顺序为8，状态为正常。提交前交由系统向我确认，确认后查询结果并报告实际岗位编号。"})["data"]
        evidence.update({"writeConversation": write["conversationId"], "proposedWrite": write,
                         "postCode": code, "postName": name})
        if write["type"] == "MESSAGE" and "确认" in write["message"]:
            assert api(root, "/system/post/list?postCode=" + code)["total"] == 0
            evidence["textConfirmationBeforeCard"] = True
            write = api(root, "/ai/chat/turn", "POST", {**request, "conversationId": write["conversationId"],
                "userMessage": "以上岗位信息正确，请调用新增岗位工具以展示系统正式确认卡。只有系统确认后才执行，不要仅用文字声称完成。"})["data"]
        assert write["type"] == "TOOL_CALL" and write["toolCall"]["executionSide"] == "SERVER", write
        args = json.loads(write["toolCall"]["arguments"])
        assert args["body"]["postCode"] == code and args["body"]["postName"] == name, args
        assert api(root, "/system/post/list?postCode=" + code)["total"] == 0
        call_id = write["toolCall"]["callId"]
        outcome = api(root, f"/ai/chat/conversations/{write['conversationId']}/server-tools/{call_id}/confirm", "POST", {"approved": True})
        assert outcome["data"]["status"] == "SUCCEEDED", outcome
        completed = api(root, "/ai/chat/turn", "POST", {**request, "conversationId": write["conversationId"],
            "toolResult": {"callId": call_id}})["data"]
        actual = api(root, "/system/post/list?postCode=" + code)
        assert actual["total"] == 1 and actual["rows"][0]["postName"] == name
        assert completed["type"] == "MESSAGE" and str(actual["rows"][0]["postId"]) in completed["message"], completed
        evidence.update({"writeConversation": write["conversationId"], "writeAnswer": completed["message"],
                         "postId": actual["rows"][0]["postId"], "passed": True})
    finally:
        for row in api(root, "/system/post/list?postCode=" + code)["rows"]:
            api(root, "/system/post/" + str(row["postId"]), "DELETE")
        evidence["fixtureCleanup"] = True
        evidence["budget"] = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
        (harness.EVIDENCE / f"api-real-model-{harness.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    entries = evidence["budget"]["entries"]
    print(json.dumps({"passed": True, "readConversation": evidence["readConversation"],
        "writeConversation": evidence["writeConversation"], "requests": len(entries),
        "inputTokens": sum(value["inputTokens"] for value in entries),
        "outputTokens": sum(value["outputTokens"] for value in entries),
        "toolRounds": sum(value["toolRounds"] for value in entries)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    Proxy.budget = Budget()
    Proxy.budget.begin_validation()
    Proxy.upstream = setting("OPENAI_CUSTOM_BASE_URL").rstrip("/")
    Proxy.secret = setting("OPENAI_CUSTOM_API_KEY")
    Proxy.local_secret = credentials()["modelProxy"]
    server = harness.ThreadingHTTPServer(("127.0.0.1", 18094), Proxy)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    except Exception as error:
        (harness.EVIDENCE / f"api-real-failure-{harness.STAMP}.json").write_text(json.dumps({
            "passed": False, "failureType": type(error).__name__, "reason": str(error)[:1000],
            "ledger": str(LEDGER_PATH)}, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    finally:
        server.shutdown()
        server.server_close()
