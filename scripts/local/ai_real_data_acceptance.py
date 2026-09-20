"""在第二阶段独立累计预算内，让真实模型完成本人范围内的关联统计和明细核对。"""

from collections import Counter
import json
import threading
import uuid

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import sql
from ai_evolution_stack import credentials
from ai_model_budget_proxy import Budget, Proxy, MODEL, setting


def run():
    h.REQUEST_TIMEOUT = 360
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "rdata_" + uuid.uuid4().hex[:7]
    users, role = [], None
    evidence = {"passed": False, "model": MODEL, "ledger": str(Proxy.budget.path)}
    try:
        menus = sql("select menu_id from sys_menu where perms='system:user:list'")
        h.api(root, "/system/role", "POST", {"roleName": tag, "roleKey": tag, "roleSort": 90,
            "dataScope": "3", "status": "0", "menuIds": [item["menu_id"] for item in menus]})
        role = sql("select role_id from sys_role where role_key=%s", (tag,))[0]["role_id"]
        for suffix, dept in (("reader", 103), ("same", 103), ("outside", 104)):
            h.api(root, "/system/user", "POST", {"userName": tag + suffix, "nickName": "分析验收" + suffix,
                "password": "TestOnly_123!", "deptId": dept, "roleIds": [role], "postIds": [], "status": "0"})
            users.append(sql("select user_id from sys_user where user_name=%s", (tag + suffix,))[0]["user_id"])
        token = h.api(None, "/login", "POST", {"username": tag + "reader", "password": "TestOnly_123!"})["token"]
        native = h.api(token, "/system/user/list?userName=" + tag)["rows"]
        assert len(native) == 2 and users[2] not in {item["userId"] for item in native}
        expected_users = sorted([{"user_id": row["userId"], "user_name": row["userName"]} for row in native], key=lambda row: row["user_id"])
        counts = Counter((row["deptId"], row["dept"]["deptName"]) for row in native)
        expected_departments = sorted([{"dept_id": dept, "dept_name": name, "count": count} for (dept, name), count in counts.items()], key=lambda row: row["dept_id"])
        governance = h.api(root, "/ai/admin/data")["data"]
        assert governance["enabled"] and all(row["enabled"] for row in governance["tables"] if row["key"] in ("sys_user", "sys_dept")), "真实模型调用前数据治理前提不成立"
        # 本地夹具和业务基线成立后才登记真实验收批次。
        Proxy.budget.begin_validation()
        h.api(root, "/ai/config/provider", "POST", {"name": "预算内真实数据验收", "baseUrl": "http://127.0.0.1:18094/v1",
            "token": credentials()["modelProxy"], "enabled": True, "timeoutSeconds": 120})
        model = next(value for value in h.api(root, "/ai/config/models")["data"] if value["modelCode"] == MODEL)
        request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}
        response = h.api(token, "/ai/chat/turn", "POST", {**request, "userMessage":
            f"请使用受控数据查询分析用户名包含 {tag} 的当前可见用户，按部门编号和部门名称统计人数，再列出用户编号和用户名。"
            "请读取真实结果，统计仅限本人授权范围，不读取手机号、邮箱等其他字段。"
            '最后输出一个 JSON 对象，包含 departments:[{dept_id,dept_name,count}]、users:[{user_id,user_name}] 和 scope:"本人授权范围"，不要额外解释。'})["data"]
        evidence["conversationId"] = response["conversationId"]
        evidence["answer"] = response.get("message")
        assert response["type"] == "MESSAGE", response
        answer = response["message"]
        parsed = json.loads(answer[answer.index("{"):answer.rindex("}") + 1])
        assert sorted(parsed["users"], key=lambda row: row["user_id"]) == expected_users, parsed
        assert sorted(parsed["departments"], key=lambda row: row["dept_id"]) == expected_departments, parsed
        assert parsed["scope"] == "本人授权范围", parsed
        detail = h.api(token, f"/ai/chat/conversations/{response['conversationId']}")["data"]
        tools = [message["toolName"] for message in detail["messages"] if message["role"] == "TOOL"]
        assert all(name in tools for name in ("server_data_search", "server_data_describe", "data_users", "server_api_result")), tools
        calls = sql("select status,result_json from ai_server_call where conversation_id=%s and capability_id='data_users'", (response["conversationId"],))
        assert len(calls) >= 2 and all(call["status"] == "SUCCEEDED" for call in calls)
        for call in calls:
            actual = json.loads(call["result_json"])
            assert set(actual["columns"]).issubset({"user_id", "user_name", "dept_id", "dept_name", "metric_1"}), actual["columns"]
            assert tag + "outside" not in call["result_json"]
        evidence.update({"passed": True, "tools": tools, "expectedUsers": expected_users, "expectedDepartments": expected_departments})
    finally:
        for user in users:
            h.api(root, "/system/user/" + str(user), "DELETE")
        if role:
            h.api(root, "/system/role/" + str(role), "DELETE")
        evidence["fixtureCleanup"] = True
        evidence["budget"] = Proxy.budget.state
        (h.EVIDENCE / f"data-real-model-{h.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    entries = Proxy.budget.state["entries"]
    print(json.dumps({"passed": True, "conversationId": evidence["conversationId"], "requests": len(entries),
        "inputTokens": sum(row["inputTokens"] for row in entries), "outputTokens": sum(row["outputTokens"] for row in entries),
        "toolRounds": sum(row["toolRounds"] for row in entries)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    Proxy.budget = Budget(stage="DB-2")
    Proxy.upstream = setting("OPENAI_CUSTOM_BASE_URL").rstrip("/")
    Proxy.secret = setting("OPENAI_CUSTOM_API_KEY")
    Proxy.local_secret = credentials()["modelProxy"]
    server = h.ThreadingHTTPServer(("127.0.0.1", 18094), Proxy)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
