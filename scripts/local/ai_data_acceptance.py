"""按原业务行集和真实数据库统计验证受控数据能力，不使用收费模型。"""

from collections import Counter
import json
import threading
import uuid

import ai_server_api_acceptance as h
from ai_server_governance_acceptance import sql, steps


def run():
    root = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "data_" + uuid.uuid4().hex[:8]
    users, roles, departments = [], [], []
    checks = []
    passed = False
    h.api(root, "/ai/config/provider", "POST", {"name": "数据确定性验收", "baseUrl": f"http://127.0.0.1:{h.MODEL_PORT}/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    model = next(value for value in h.api(root, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")
    request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}

    def turn(token, suffix, sequence, hook=None, **extra):
        label = tag + suffix
        h.SCENARIOS[label] = steps(sequence, hook)
        return h.api(token, "/ai/chat/turn", "POST", {**request, "userMessage": label, **extra}, expect=None)

    def result(response):
        assert response["code"] == 200, response
        return json.loads(response["data"]["message"])

    def denied(response):
        assert response.get("code") != 200 or result(response).get("success") is False, response

    def record(name, **facts):
        checks.append({"name": name, **facts})
        print(json.dumps(checks[-1], ensure_ascii=False), flush=True)

    def policy(row, enabled=True, fields=None, operations=None):
        body = {"fingerprint": row["fingerprint"], "enabled": enabled,
            "fields": [c["name"] for c in row.get("columns", []) if c["supported"]] if fields is None else fields,
            "operations": ["QUERY", "AGGREGATE"] if operations is None and row["key"] != "database" else operations or []}
        return h.api(root, "/ai/admin/data/" + row["key"], "PUT", body, expect=None)

    def identity(suffix, dept, permissions, scope="3"):
        name = tag + suffix
        menus = sql("select menu_id from sys_menu where perms in (" + ",".join(["%s"] * len(permissions)) + ")", permissions)
        h.api(root, "/system/role", "POST", {"roleName": name, "roleKey": name, "roleSort": 90, "status": "0",
            "dataScope": scope, "menuIds": [row["menu_id"] for row in menus]})
        role = sql("select role_id from sys_role where role_key=%s", (name,))[0]["role_id"]
        roles.append(role)
        h.api(root, "/system/user", "POST", {"userName": name, "nickName": name, "password": "TestOnly_123!", "deptId": dept,
            "roleIds": [role], "postIds": [], "status": "0"})
        user = sql("select user_id from sys_user where user_name=%s", (name,))[0]["user_id"]
        users.append(user)
        token = h.api(None, "/login", "POST", {"username": name, "password": "TestOnly_123!"})["token"]
        return token, role, user, name

    def read(id, query):
        return [("server_data_describe", {"id": id}), (id, query),
            ("server_api_result", lambda results: {"resultId": json.loads(results[1]["content"])["result"]["resultId"], "jsonPointer": "/rows", "limit": 50})]

    try:
        governance = h.api(root, "/ai/admin/data")["data"]
        # 可重跑时显式关闭恢复起点；仍验证未配置开放并非通过默认全开获得。
        assert policy(governance, False)["code"] == 200
        assert result(turn(root, "closed", [("server_data_search", {})]))["result"]["total"] == 0
        unsupported = next(row for row in governance["tables"] if row["key"] == "sys_config")
        assert policy(unsupported, fields=["config_value"])["code"] != 200
        tables = {row["key"]: row for row in governance["tables"] if row["supported"]}
        assert set(tables) == {"sys_user", "sys_dept", "sys_post"}, set(tables)
        assert not next(column for column in tables["sys_user"]["columns"] if column["name"] == "password")["supported"]
        assert policy(tables["sys_user"], fields=["password"])["code"] != 200
        for row in tables.values():
            assert policy(row)["code"] == 200
        assert policy(governance)["code"] == 200
        record("真实库表目录默认关闭；未知业务表和密码字段无法开放", supportedTables=sorted(tables))
        h.api(root, "/system/dept", "POST", {"parentId": 103, "deptName": tag + "child", "orderNum": 30, "status": "0"})
        child = sql("select dept_id from sys_dept where dept_name=%s", (tag + "child",))[0]["dept_id"]
        departments.append(child)
        permissions = ("system:user:list", "system:dept:list", "system:post:list")
        a, role, uid, name = identity("reader", 103, permissions)
        b, _, _, _ = identity("other", 104, permissions)
        identity("child", child, permissions)
        manager, _, _, _ = identity("manager", 103, ("ai:data:view", "ai:data:edit"))
        assert h.api(manager, "/ai/admin/data")["code"] == 200
        assert result(turn(manager, "manager", [("server_data_search", {})]))["result"]["total"] == 0
        denied(turn(manager, "forged", [("server_data_describe", {"id": "data_users"})]))
        record("只有治理权限的管理员仍不能发现或加载本人无业务权限的数据")
        last_read = None
        for scope in ("1", "2", "3", "4", "5"):
            h.api(root, "/system/role/dataScope", "PUT", {"roleId": role, "dataScope": scope, "deptIds": [104] if scope == "2" else []})
            native = h.api(None, "/login", "POST", {"username": name, "password": "TestOnly_123!"})["token"]
            expected = h.api(native, "/system/user/list?pageSize=100")["rows"]
            begin = len(h.REQUESTS)
            response = turn(a, "rows" + scope, read("data_users", {"operation": "QUERY", "columns": ["user_id", "user_name", "dept_id", "dept_name"], "limit": 100}))
            rows = result(response)["result"]["items"]
            assert {row["user_id"] for row in rows} == {row["userId"] for row in expected}, (scope, rows, expected)
            aggregate = turn(a, "aggregate" + scope, read("data_users", {"operation": "AGGREGATE", "columns": [], "groupBy": ["dept_id"], "metrics": [{"function": "COUNT"}]}))
            counts = {row["dept_id"]: row["metric_1"] for row in result(aggregate)["result"]["items"]}
            assert counts == dict(Counter(row["deptId"] for row in expected)), counts
            first = {item["function"]["name"] for item in h.REQUESTS[begin]["tools"]}
            assert "data_users" not in first and "server_data_search" in first
            later = next(item["function"] for body in h.REQUESTS[begin+1:] for item in body["tools"] if item["function"]["name"] == "data_users")
            assert "password" not in json.dumps(later)
            if scope == "5":
                assert len(rows) == 1 and rows[0]["user_id"] == uid
            last_read = response["data"]
            record("数据范围与原业务明细、独立分组计数一致", scope=scope, rows=len(rows), departments=len(counts))
        source = sql("select result_id from ai_server_call where conversation_id=%s and capability_id='data_users' and result_id is not null", (last_read["conversationId"],))[0]["result_id"]
        for token, suffix in ((a, "crossRun"), (b, "crossUser")):
            denied(turn(token, suffix, [("server_api_result", {"resultId": source, "jsonPointer": "/rows"})]))
        record("数据结果句柄不能跨用户或跨运行读取")
        fields = [column["name"] for column in tables["sys_user"]["columns"] if column["supported"] and column["name"] != "user_name"]
        assert policy(tables["sys_user"], fields=fields)["code"] == 200
        assert h.api(a, f"/ai/chat/conversations/{last_read['conversationId']}", expect=None)["code"] != 200
        before = len(h.REQUESTS)
        assert h.api(a, "/ai/chat/turn", "POST", {**request, "conversationId": last_read["conversationId"], "userMessage": "继续分析旧结果"}, expect=None)["code"] != 200
        assert len(h.REQUESTS) == before
        for index, query in enumerate((
            {"operation": "QUERY", "columns": ["user_name"]},
            {"operation": "QUERY", "columns": ["user_id"], "filters": [{"field": "user_name", "operator": "eq", "value": name}]},
            {"operation": "AGGREGATE", "groupBy": ["user_name"], "metrics": [{"function": "COUNT"}]},
            {"operation": "QUERY", "columns": ["user_id"], "orderBy": [{"field": "user_name", "direction": "ASC"}]})):
            denied(turn(a, "hidden" + str(index), [("server_data_describe", {"id": "data_users"}), ("data_users", query)]))
        assert policy(tables["sys_user"])["code"] == 200
        assert policy(tables["sys_dept"], False)["code"] == 200
        denied(turn(a, "closedDependency", [("server_data_describe", {"id": "data_users"})]))
        assert policy(tables["sys_dept"])["code"] == 200
        assert policy(tables["sys_user"], operations=["QUERY"])["code"] == 200
        denied(turn(a, "closedAggregate", [("server_data_describe", {"id": "data_users"}), ("data_users", {"operation": "AGGREGATE", "metrics": [{"function": "COUNT"}]})]))
        assert policy(tables["sys_user"])["code"] == 200
        record("字段收紧阻断旧历史、模型续跑及字段各种用途；关联表关闭和统计操作关闭均有效")
        subject_token, subject_role, subject_id, subject_name = identity("subject", 103, permissions)
        h.api(root, "/system/role/dataScope", "PUT", {"roleId": role, "dataScope": "3", "deptIds": []})
        owned_query = {"operation": "QUERY", "columns": ["user_id", "user_name"],
            "filters": [{"field": "user_name", "operator": "eq", "value": subject_name}]}
        old = turn(a, "beforeTransfer", read("data_users", owned_query))
        assert result(old)["result"]["items"][0]["user_id"] == subject_id
        h.api(root, "/system/user", "PUT", {"userId": subject_id, "userName": subject_name, "nickName": subject_name,
            "deptId": 104, "roleIds": [subject_role], "postIds": [], "status": "0"})
        assert h.api(a, f"/ai/chat/conversations/{old['data']['conversationId']}", expect=None)["code"] != 200
        assert result(turn(a, "afterTransfer", read("data_users", owned_query)))["result"]["items"] == []
        record("记录转出原授权部门后，数据视图旧历史拒绝且重新查询为零行")
        sql("update sys_role set data_scope='9' where role_id=%s", (role,))
        try:
            denied(turn(a, "unknownScope", [("server_data_describe", {"id": "data_users"})]))
        finally:
            sql("update sys_role set data_scope='3' where role_id=%s", (role,))
        record("无法理解的原业务数据范围值保守关闭，不猜测为全表权限")
        menus = sql("select menu_id from sys_menu where perms='system:user:list'")
        for menu in menus:
            sql("delete from sys_role_menu where role_id=%s and menu_id=%s", (role, menu["menu_id"]))
        denied(turn(a, "revoked", [("server_data_describe", {"id": "data_users"})]))
        record("原业务权限撤销后旧登录令牌无法再次加载数据能力")
        passed = True
    finally:
        for user in users:
            h.api(root, "/system/user/" + str(user), "DELETE")
        for role in roles:
            h.api(root, "/system/role/" + str(role), "DELETE")
        for dept in reversed(departments):
            h.api(root, "/system/dept/" + str(dept), "DELETE")
        evidence = {"passed": passed, "checks": checks, "trace": str(h.TRACE), "requests": len(h.REQUESTS), "paidRequests": 0}
        (h.EVIDENCE / f"data-acceptance-{h.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_CONTROLLED_DATA_ACCEPTANCE_OK", flush=True)


if __name__ == "__main__":
    server = h.ThreadingHTTPServer(("127.0.0.1", h.MODEL_PORT), h.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
