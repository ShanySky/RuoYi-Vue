"""在专用全栈环境验证权限、数据范围、结果归属、停止与写入事实。"""

from concurrent.futures import ThreadPoolExecutor
import json
import threading
import time
import uuid

import pymysql
import ai_server_api_acceptance as harness
from ai_evolution_stack import credentials


def database(autocommit=True):
    return pymysql.connect(host="127.0.0.1", port=13392, user="root", password=credentials()["mysql"],
                           database="ruoyi_ai_evolution", charset="utf8mb4", autocommit=autocommit,
                           cursorclass=pymysql.cursors.DictCursor)


def sql(statement, args=()):
    with database() as connection, connection.cursor() as cursor:
        cursor.execute(statement, args)
        return cursor.fetchall() if cursor.description else cursor.rowcount


def steps(sequence, hook=None):
    def respond(body, messages, latest, latest_name):
        start = max(index for index, value in enumerate(messages) if value.get("role") == "user")
        results = [value for value in messages[start + 1:] if value.get("role") == "tool"]
        index = len(results)
        if hook:
            hook(index)
        if index >= len(sequence):
            return {"role": "assistant", "content": json.dumps(json.loads(latest["content"]), ensure_ascii=False)}
        name, arguments = sequence[index]
        return harness.tool(name, arguments(results) if callable(arguments) else arguments)
    return respond


def run():
    api = harness.api
    root = api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    tag = "gov_" + uuid.uuid4().hex[:8]
    evidence = {"tag": tag, "checks": [], "realModelRequests": 0, "trace": str(harness.TRACE)}
    users, roles, posts = [], [], []
    catalog = api(root, "/ai/admin/apis")["data"]
    capabilities = {}
    for method, path in (("GET", "/system/user/list"), ("GET", "/system/post/list"), ("POST", "/system/post")):
        row = next(value for value in catalog if value["method"] == method and value["path"] == path)
        assert row["supported"], row
        if not row["enabled"]:
            api(root, "/ai/admin/apis/" + row["id"], "PUT", {"fingerprint": row["fingerprint"], "enabled": True})
        capabilities[(method, path)] = row
    user_list = capabilities[("GET", "/system/user/list")]["id"]
    post_add = capabilities[("POST", "/system/post")]["id"]
    api(root, "/ai/config/provider", "POST", {"name": "治理确定性验收", "baseUrl": f"http://127.0.0.1:{harness.MODEL_PORT}/v1",
        "token": "isolated-mock-only", "enabled": True, "timeoutSeconds": 60})
    model = next(value for value in api(root, "/ai/config/models")["data"] if value["modelCode"] == "mock-api-model")
    request = {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": []}

    def turn(token, name, sequence, *, hook=None, conversation=None, expect=200):
        harness.SCENARIOS[name] = steps(sequence, hook)
        payload = {**request, "userMessage": name}
        if conversation:
            payload["conversationId"] = conversation
        response = api(token, "/ai/chat/turn", "POST", payload, expect=expect)
        return response.get("data", response)

    def note(name, **facts):
        evidence["checks"].append({"name": name, **facts})
        print(json.dumps(evidence["checks"][-1], ensure_ascii=False), flush=True)

    def identity(suffix, permissions, dept):
        menus = sql("select menu_id from sys_menu where perms in (" + ",".join(["%s"] * len(permissions)) + ")", permissions)
        name = tag + suffix
        api(root, "/system/role", "POST", {"roleName": name, "roleKey": name, "roleSort": 90,
            "status": "0", "dataScope": "3", "menuIds": [value["menu_id"] for value in menus]})
        role_id = sql("select role_id from sys_role where role_key=%s", (name,))[0]["role_id"]
        roles.append(role_id)
        api(root, "/system/user", "POST", {"userName": name, "nickName": "治理验收" + suffix, "password": "TestOnly_123!",
            "deptId": dept, "roleIds": [role_id], "postIds": [], "status": "0"})
        user_id = sql("select user_id from sys_user where user_name=%s", (name,))[0]["user_id"]
        users.append(user_id)
        token = api(None, "/login", "POST", {"username": name, "password": "TestOnly_123!"})["token"]
        return token, role_id, user_id, name

    def grant(role_id, permission, enabled):
        menu_id = sql("select menu_id from sys_menu where perms=%s", (permission,))[0]["menu_id"]
        if enabled:
            sql("insert ignore into sys_role_menu(role_id,menu_id) values(%s,%s)", (role_id, menu_id))
        else:
            sql("delete from sys_role_menu where role_id=%s and menu_id=%s", (role_id, menu_id))

    def write(token, label, code):
        return turn(token, label, [("server_api_describe", {"id": post_add}),
            (post_add, {"body": {"postCode": code, "postName": code, "postSort": 6, "status": "0"}})])

    def confirmation(pending):
        assert pending["type"] == "TOOL_CALL" and pending["toolCall"]["executionSide"] == "SERVER", pending
        return f"/ai/chat/conversations/{pending['conversationId']}/server-tools/{pending['toolCall']['callId']}/confirm"

    def count(code):
        return sql("select count(*) as n from sys_post where post_code=%s", (code,))[0]["n"]

    def read_sequence(capability):
        return [("server_api_describe", {"id": capability}), (capability, {"query": {"pageSize": 50}}),
                ("server_api_result", lambda results: {"resultId": json.loads(results[-1]["content"])["result"]["resultId"],
                                                       "jsonPointer": "/rows", "limit": 50})]

    try:
        reader, reader_role, reader_id, _ = identity("r", ("system:user:list", "system:post:list"), 103)
        writer, writer_role, _, writer_name = identity("w", ("system:post:list", "system:post:add"), 104)
        result = turn(reader, tag + "scope", read_sequence(user_list))
        returned = json.loads(result["message"])["result"]["items"]
        assert returned and all(row["deptId"] == 103 for row in returned), returned
        assert reader_id in [row["userId"] for row in returned]
        assert users[1] not in [row["userId"] for row in returned]
        assert any(row["userId"] == users[1] for row in api(root, "/system/user/list?pageSize=50")["rows"])
        note("原业务部门数据范围有效", conversation=result["conversationId"], returnedUserIds=[row["userId"] for row in returned])
        cid = result["conversationId"]
        assert api(writer, f"/ai/chat/conversations/{cid}", expect=None)["code"] != 200
        audit = api(root, f"/ai/admin/audit/{cid}")["data"]
        assert audit["protectedResults"] and not audit["messages"] and not audit["checkpoints"], audit
        note("会话归属与审计不扩大业务读取权")
        result_id = sql("select result_id from ai_server_call where conversation_id=%s and result_id is not null", (cid,))[0]["result_id"]
        for actor, suffix in ((reader, "same"), (writer, "other")):
            denied = turn(actor, tag + suffix, [("server_api_result", {"resultId": result_id, "jsonPointer": "/rows"})])
            assert json.loads(denied["message"])["success"] is False, denied
        note("结果句柄跨运行及跨用户均拒绝")
        found = turn(reader, tag + "search", [("server_api_search", {"query": "岗位"})])
        assert all(item["id"] != post_add for item in json.loads(found["message"])["result"]["items"])
        denied = turn(reader, tag + "describe", [("server_api_describe", {"id": post_add})])
        assert json.loads(denied["message"])["success"] is False
        denied = turn(writer, tag + "unloaded", [(post_add, {"body": {}})], expect=None)
        assert denied["code"] != 200
        note("发现和详情检查本人权限且拒绝未披露工具")
        grant(reader_role, "system:user:list", False)
        assert api(reader, f"/ai/chat/conversations/{cid}", expect=None)["code"] != 200
        before = len(harness.REQUESTS)
        denied = turn(reader, tag + "history", [], conversation=cid, expect=None)
        assert denied["code"] != 200 and len(harness.REQUESTS) == before
        grant(reader_role, "system:user:list", True)
        note("旧登录令牌撤权后历史和模型续跑立即拒绝")
        # 数据范围改变同样使旧结果失效，重新查询只返回新的本人范围。
        sql("update sys_role set data_scope='5' where role_id=%s", (reader_role,))
        assert api(reader, f"/ai/chat/conversations/{cid}", expect=None)["code"] != 200
        scoped = turn(reader, tag + "self", read_sequence(user_list))
        rows = json.loads(scoped["message"])["result"]["items"]
        assert [row["userId"] for row in rows] == [reader_id], rows
        note("部门范围收窄为本人后新查询生效且旧结果失效")
        sql("update sys_role set data_scope='3' where role_id=%s", (reader_role,))
        code = tag + "revoked"
        pending = write(writer, tag + "write-revoked", code)
        path = confirmation(pending)
        grant(writer_role, "system:post:add", False)
        assert api(writer, path, "POST", {"approved": True})["data"]["status"] == "FAILED"
        assert count(code) == 0
        grant(writer_role, "system:post:add", True)
        note("写入确认前撤权阻止业务副作用")
        code = tag + "stopped"
        pending = write(writer, tag + "write-stopped", code)
        path = confirmation(pending)
        api(writer, f"/ai/chat/runs/{pending['runId']}/cancel", "POST", {"reason": "USER_STOP"})
        assert api(writer, path, "POST", {"approved": True}, expect=None)["code"] != 200
        assert count(code) == 0
        note("停止后确认不能新增业务记录")
        code = tag + "concurrent"
        posts.append(code)
        pending = write(writer, tag + "write-concurrent", code)
        path = confirmation(pending)
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(api, writer, path, "POST", {"approved": True}, None) for _ in range(2)]
            outcomes = [future.result() for future in futures]
        assert count(code) == 1, outcomes
        assert any(value.get("data", {}).get("status") == "SUCCEEDED" for value in outcomes), outcomes
        assert sql("select count(*) as n from sys_oper_log where oper_name=%s and method like '%%SysPostController.add%%'", (writer_name,))[0]["n"] >= 1
        note("并发重复确认单次写入且复用原业务操作日志")
        duplicate = write(writer, tag + "duplicate", code)
        assert api(writer, confirmation(duplicate), "POST", {"approved": True})["data"]["status"] == "FAILED"
        assert count(code) == 1
        note("原业务唯一性校验仍生效")
        # 阻塞原 MySQL 事务，真实触发回环请求超时；解除后核对可能已发生的写入。
        code = tag + "unknown"
        posts.append(code)
        pending = write(writer, tag + "write-unknown", code)
        path = confirmation(pending)
        with database(False) as locked:
            with locked.cursor() as cursor:
                cursor.execute("select post_id from sys_post for update")
            outcome = api(writer, path, "POST", {"approved": True})
            assert outcome["data"]["status"] == "UNKNOWN", outcome
            locked.rollback()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and count(code) == 0:
            time.sleep(0.2)
        assert count(code) == 1, "超时后的真实业务副作用未观察到，不能证明未知结果用例"
        duplicate = turn(writer, tag + "retry-unknown", [("server_api_describe", {"id": post_add}),
            (post_add, {"body": {"postCode": code + "again", "postName": code + "again", "postSort": 6, "status": "0"}})],
            conversation=pending["conversationId"], expect=None)
        assert duplicate["code"] != 200 and count(code + "again") == 0
        note("真实超时结果标记未知并禁止盲目重试", committedRows=count(code))
        api(reader, "/logout", "POST")
        assert api(reader, "/ai/chat/conversations", expect=None)["code"] != 200
        note("退出登录后令牌不能继续访问")
        evidence["passed"] = True
    finally:
        for code in posts:
            for row in sql("select post_id from sys_post where post_code=%s", (code,)):
                api(root, "/system/post/" + str(row["post_id"]), "DELETE")
        for user_id in users:
            api(root, "/system/user/" + str(user_id), "DELETE")
        for role_id in roles:
            api(root, "/system/role/" + str(role_id), "DELETE")
        evidence["fixtureCleanup"] = True
        evidence["modelRequests"] = len(harness.REQUESTS)
        (harness.EVIDENCE / f"api-governance-{harness.STAMP}.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_SERVER_GOVERNANCE_ACCEPTANCE_OK", flush=True)


if __name__ == "__main__":
    server = harness.ThreadingHTTPServer(("127.0.0.1", harness.MODEL_PORT), harness.Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        run()
    finally:
        server.shutdown()
        server.server_close()
