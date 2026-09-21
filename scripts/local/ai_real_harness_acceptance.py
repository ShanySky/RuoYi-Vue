"""第三阶段累计预算内的接口、受控数据、隔离生成与私有成果真实模型验收。"""

import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import threading
import uuid
from zipfile import ZipFile
import xml.etree.ElementTree as ET
import requests
from pypdf import PdfReader
import ai_server_api_acceptance as h
from ai_server_governance_acceptance import sql
from ai_evolution_stack import credentials
from ai_model_budget_proxy import Budget, Proxy, MODEL, setting


def root_token():
    return h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]


def cleanup(path):
    path = Path(path).resolve()
    if not path.is_relative_to(h.EVIDENCE.resolve()):
        raise RuntimeError("仅清理本任务证据记录中的验收夹具")
    evidence = json.loads(path.read_text(encoding="utf-8"))
    tag = evidence["tag"]
    assert tag.startswith("rh_") and len(tag) == 10
    root = root_token()
    auth = {"Authorization": "Bearer " + credentials()["workspace"]}
    for user in evidence["users"]:
        actual = sql("select user_name from sys_user where user_id=%s", (user,))
        assert len(actual) == 1 and actual[0]["user_name"].startswith(tag)
        for row in sql("select artifact_id,run_id from ai_artifact where user_id=%s", (user,)):
            result = requests.post("http://127.0.0.1:28097/v1/delete", headers=auth,
                json={"userId": user, "runId": row["run_id"], "id": row["artifact_id"]}, timeout=20)
            assert result.status_code == 200
            sql("delete from ai_artifact where artifact_id=%s", (row["artifact_id"],))
        h.api(root, "/system/user/" + str(user), "DELETE")
    if evidence.get("role"):
        actual = sql("select role_key from sys_role where role_id=%s", (evidence["role"],))
        assert len(actual) == 1 and actual[0]["role_key"] == tag
        h.api(root, "/system/role/" + str(evidence["role"]), "DELETE")
    evidence["fixtureCleanup"] = True
    path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_REAL_HARNESS_FIXTURES_CLEANED", flush=True)


def run():
    h.REQUEST_TIMEOUT = 900
    root = root_token()
    tag = "rh_" + uuid.uuid4().hex[:7]
    folder = h.EVIDENCE / ("harness-real-model-" + h.STAMP)
    folder.mkdir()
    evidence = {"passed": False, "tag": tag, "users": [], "role": None, "model": MODEL,
        "ledger": str(Proxy.budget.path), "fixtureCleanup": False}
    path = folder / "evidence.json"
    try:
        menus = sql("select menu_id from sys_menu where perms in ('system:user:list','ai:workspace:use')")
        assert len(menus) == 2
        h.api(root, "/system/role", "POST", {"roleName": tag, "roleKey": tag, "roleSort": 90,
            "dataScope": "3", "status": "0", "menuIds": [row["menu_id"] for row in menus]})
        evidence["role"] = sql("select role_id from sys_role where role_key=%s", (tag,))[0]["role_id"]
        for suffix, dept in (("reader", 103), ("same", 103), ("outside", 104)):
            h.api(root, "/system/user", "POST", {"userName": tag + suffix, "nickName": "组合验收" + suffix,
                "deptId": dept, "password": "TestOnly_123!", "roleIds": [evidence["role"]], "postIds": [], "status": "0"})
            evidence["users"].append(sql("select user_id from sys_user where user_name=%s", (tag + suffix,))[0]["user_id"])
        evidence["username"] = tag + "reader"
        token = h.api(None, "/login", "POST", {"username": evidence["username"], "password": "TestOnly_123!"})["token"]
        native = h.api(token, "/system/user/list?userName=" + tag)["rows"]
        assert len(native) == 2 and evidence["users"][2] not in {row["userId"] for row in native}
        evidence["expectedUsers"] = [{"user_id": row["userId"], "user_name": row["userName"], "dept_name": row["dept"]["deptName"]} for row in native]
        workspace = h.api(root, "/ai/admin/workspace")["data"]
        assert workspace["enabled"] and workspace["environment"]["ready"]
        assert h.api(root, "/ai/admin/data")["data"]["enabled"]
        interface = next(row for row in h.api(root, "/ai/admin/apis")["data"] if row["path"] == "/system/user/list" and row["method"] == "GET")
        if not interface["enabled"]:
            h.api(root, "/ai/admin/apis/" + interface["id"], "PUT", {"fingerprint": interface["fingerprint"], "enabled": True})
        # 确定性前提成立后登记整阶段累计尝试；失败和后续重试仍在同一本账本。
        Proxy.budget.begin_validation()
        h.api(root, "/ai/config/provider", "POST", {"name": "预算内真实组合验收", "baseUrl": "http://127.0.0.1:18094/v1",
            "token": credentials()["modelProxy"], "enabled": True, "timeoutSeconds": 120})
        model = next(row for row in h.api(root, "/ai/config/models")["data"] if row["modelCode"] == MODEL)
        assert model["contextWindowTokens"] == 131072
        evidence["contextWindow"] = model["contextWindowTokens"]
        response = h.api(token, "/ai/chat/turn", "POST", {"modelId": model["modelId"], "route": "/index", "pageContext": {}, "frontendTools": [],
            "userMessage": f"请为用户名包含 {tag} 的当前可见用户制作可下载的表格（xlsx）、文档（docx）和 PDF 各一份。"
            "先发现并调用原用户列表接口核对本人能看到的人数，再用受控数据查询提取用户编号、用户名、部门名称，核对人数一致。"
            "只能处理本人授权范围，数据查询仅取上述三列。将真实查询结果导入隔离工作空间，使用已经安装的 openpyxl、python-docx、reportlab 离线生成三份成果，内容含完整用户名、编号、部门和人数。"
            "PDF 中同时包含可提取的英文 Count: 人数；中文使用内置 STSong-Light 字体。发布三份成果后用中文报告数量和文件名。"
            "任务无需业务写入，无需安装依赖或访问网络，尽量在 16 次工具调用内完成。"})["data"]
        evidence.update({"conversationId": response["conversationId"], "answer": response.get("message")})
        assert response["type"] == "MESSAGE", response
        calls = sql("select tool_name,status,result_json from ai_server_call where conversation_id=%s order by create_time,call_id", (response["conversationId"],))
        tools = [row["tool_name"] for row in calls]
        evidence["tools"] = tools
        assert all(name in tools for name in ("server_api_search", "server_api_describe", interface["id"],
            "server_data_search", "server_data_describe", "data_users", "workspace_import_result", "workspace_execute", "workspace_publish")), tools
        assert all(row["status"] == "SUCCEEDED" for row in calls), [(row["tool_name"], row["status"]) for row in calls]
        for row in calls:
            if row["tool_name"] == "data_users":
                result = json.loads(row["result_json"])
                assert set(result["columns"]) <= {"user_id", "user_name", "dept_name"}
                assert tag + "outside" not in row["result_json"]
        files = h.api(token, f"/ai/chat/conversations/{response['conversationId']}/artifacts")["data"]
        assert len(files) == 3 and {file["name"].rsplit(".", 1)[-1] for file in files} == {"xlsx", "docx", "pdf"}
        evidence["artifacts"] = files
        for file in files:
            download = requests.get(h.BASE + "/ai/artifacts/" + file["id"] + "/download", headers={"Authorization": "Bearer " + token}, timeout=65)
            data = download.content
            assert download.status_code == 200 and download.headers["Cache-Control"] == "no-store"
            assert len(data) == file["bytes"] and hashlib.sha256(data).hexdigest() == file["sha256"]
            extension = file["name"].rsplit(".", 1)[-1]
            (folder / ("result." + extension)).write_bytes(data)
            if extension in ("xlsx", "docx"):
                with ZipFile(BytesIO(data)) as archive:
                    prefix = "xl/" if extension == "xlsx" else "word/"
                    body = "\n".join(" ".join(ET.fromstring(archive.read(name)).itertext()) for name in archive.namelist() if name.startswith(prefix) and name.endswith(".xml"))
            else:
                reader = PdfReader(BytesIO(data))
                assert len(reader.pages) >= 1
                body = "\n".join(page.extract_text() for page in reader.pages)
                assert "Count:" in body and str(len(native)) in body
            assert all(row["userName"] in body for row in native), (extension, "缺少本人可见的完整用户名")
            assert tag + "outside" not in body
            for row in native:
                assert str(row["userId"]) in body and row["dept"]["deptName"] in body
        evidence["passed"] = True
        print("AI_REAL_HARNESS_OK " + str(path), flush=True)
    except Exception as error:
        evidence["failureType"] = type(error).__name__
        evidence["failure"] = str(error)[:2000]
        raise
    finally:
        evidence["budget"] = Proxy.budget.state
        path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print("证据和待清理夹具：" + str(path), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cleanup")
    arguments = parser.parse_args()
    if arguments.cleanup:
        cleanup(arguments.cleanup)
    else:
        Proxy.budget = Budget(stage="HARNESS-3")
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
