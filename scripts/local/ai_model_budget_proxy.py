"""真实模型验收专用代理：持久累计预算，进程重启不清零，不持久化上游凭据。"""

from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
import time
from urllib.parse import urlsplit
import winreg

import requests
from ai_evolution_vm import EVIDENCE
from ai_evolution_stack import credentials

LEDGER_PATH = EVIDENCE / "model-budget-stage1.json"
TRACE_PATH = EVIDENCE / "model-inputs-stage1.jsonl"
OUTPUT_PATH = EVIDENCE / "model-outputs-stage1.jsonl"
LOCK = threading.Lock()
MODEL = "gpt-5.6-luna"
LIMITS = {"requests": 48, "inputTokens": 300000, "outputTokens": 40000,
          "toolRounds": 40, "retries": 2, "upstreamSeconds": 1800, "wallSeconds": 3600}


def setting(name):
    value = os.environ.get(name)
    if not value:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            value = winreg.QueryValueEx(key, name)[0]
    if not value:
        raise RuntimeError("缺少已授权的模型环境变量：" + name)
    return value


class Budget:
    def __init__(self, path=LEDGER_PATH):
        self.path = path
        self.state = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {
            "stage": "API-1", "limits": LIMITS, "startedAt": None, "entries": []}
        if self.state["limits"] != LIMITS:
            raise RuntimeError("预算文件上限与已审核阶段预算不一致，禁止自动重置")

    def save(self):
        temporary = self.path.with_suffix(".pending")
        temporary.write_text(json.dumps(self.state, ensure_ascii=False, indent=2), encoding="utf-8")
        for attempt in range(5):
            try:
                os.replace(temporary, self.path)
                return
            except PermissionError:
                if attempt == 4:
                    raise
                time.sleep(0.05 * (2 ** attempt))

    def begin_validation(self):
        prior = self.state.get("validationAttempts", 1 if self.state["entries"] else 0)
        retries = prior
        if retries + sum(entry["retry"] for entry in self.state["entries"]) > LIMITS["retries"]:
            raise RuntimeError("本阶段真实验收重试预算已用尽，禁止重新开批次")
        self.state["validationAttempts"] = prior + 1
        self.state["validationRetries"] = retries
        self.save()

    def reserve(self, method, path, body):
        entries = self.state["entries"]
        now = time.time()
        started = self.state["startedAt"]
        if started is not None and now - started >= LIMITS["wallSeconds"]:
            raise RuntimeError("本阶段真实模型验收墙钟预算已用尽")
        canonical = {key: value for key, value in body.items() if key != "prompt_cache_key"}
        encoded = json.dumps(canonical, ensure_ascii=False, sort_keys=True).encode("utf-8")
        fingerprint = hashlib.sha256(method.encode() + path.encode() + encoded).hexdigest()
        retry = any(entry["fingerprint"] == fingerprint for entry in entries)
        # UTF-8 字节数加协议余量作为保守预扣；有可信 usage 后据实结算。
        input_reserve = len(encoded) + 2048 if method == "POST" else 0
        output_reserve = int(body.get("max_completion_tokens", body.get("max_tokens", 4096))) if method == "POST" else 0
        if method == "POST" and not 1 <= output_reserve <= 4096:
            raise RuntimeError("单次输出与推理预算必须在 1～4096 之间")
        task = body.get("prompt_cache_key") or "configuration-check"
        checks = {"requests": len(entries) + 1,
                  "inputTokens": sum(entry["inputTokens"] for entry in entries) + input_reserve,
                  "outputTokens": sum(entry["outputTokens"] for entry in entries) + output_reserve,
                  "toolRounds": sum(entry["toolRounds"] for entry in entries),
                  "retries": sum(entry["retry"] for entry in entries) + int(retry)
                             + self.state.get("validationRetries", 0),
                  "upstreamSeconds": sum(entry["upstreamSeconds"] for entry in entries) + 120}
        for name, value in checks.items():
            if value > LIMITS[name] or (name == "toolRounds" and value >= LIMITS[name]):
                raise RuntimeError("本阶段累计预算不足：" + name)
        if sum(entry["toolRounds"] for entry in entries if entry["task"] == task) >= 12:
            raise RuntimeError("单任务工具轮次已达 12")
        entry = {"id": len(entries) + 1, "task": task, "fingerprint": fingerprint, "retry": int(retry),
                 "startedAt": datetime.now(timezone.utc).isoformat(), "method": method, "path": path,
                 "inputTokens": input_reserve, "outputTokens": output_reserve,
                 "toolRounds": 1 if method == "POST" else 0, "upstreamSeconds": 120,
                 "status": "RESERVED", "usageVerified": False}
        self.state["startedAt"] = started or now
        entries.append(entry)
        self.save()
        return entry

    def settle(self, entry, status, seconds, response):
        entry["status"] = status
        entry["upstreamSeconds"] = round(seconds, 3)
        usage = response.get("usage") if isinstance(response, dict) else None
        if usage and isinstance(usage.get("prompt_tokens"), int) and isinstance(usage.get("completion_tokens"), int):
            entry["inputTokens"] = max(0, usage["prompt_tokens"])
            entry["outputTokens"] = max(0, usage["completion_tokens"])
            entry["usageVerified"] = True
            entry["usage"] = usage
        if status == 200:
            entry["toolRounds"] = int(any(choice.get("message", {}).get("tool_calls") for choice in response.get("choices", [])))
            if entry["method"] == "GET":
                entry["catalog"] = [{key: value[key] for key in
                    ("id", "context_window", "context_length", "max_input_tokens", "max_output_tokens") if key in value}
                    for value in response.get("data", [])]
        self.save()


class Proxy(BaseHTTPRequestHandler):
    budget = None
    upstream = None
    secret = None
    local_secret = None

    def log_message(self, *_):
        pass

    def send(self, status, body):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self.forward("GET")

    def do_POST(self):
        self.forward("POST")

    def forward(self, method):
        if self.headers.get("Authorization") != "Bearer " + self.local_secret:
            self.send(401, {"error": {"message": "缺少任务代理凭证"}})
            return
        path = self.path.removeprefix("/v1")
        if (method, path) not in (("GET", "/models"), ("POST", "/chat/completions")):
            self.send(404, {"error": {"message": "验收代理未开放该路径"}})
            return
        size = int(self.headers.get("Content-Length", "0"))
        if size > 262144:
            self.send(413, {"error": {"message": "单次输入体积超过验收上限"}})
            return
        body = json.loads(self.rfile.read(size)) if size else {}
        if method == "POST" and (body.get("model") != MODEL or body.get("stream")):
            self.send(400, {"error": {"message": "只允许已授权模型的非流式验收请求"}})
            return
        with LOCK:
            try:
                entry = self.budget.reserve(method, path, body)
            except RuntimeError as error:
                self.send(429, {"error": {"message": str(error)}})
                return
            with TRACE_PATH.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps({"requestId": entry["id"], "body": body}, ensure_ascii=False) + "\n")
        started = time.monotonic()
        status, response = 502, {}
        try:
            upstream = requests.request(method, self.upstream + path,
                headers={"Authorization": "Bearer " + self.secret, "Content-Type": "application/json"},
                json=body if method == "POST" else None, timeout=(10, 110), allow_redirects=False)
            status = upstream.status_code
            response = upstream.json()
            if method == "GET" and status == 200:
                response = {**response, "data": [value for value in response.get("data", []) if value.get("id") == MODEL]}
            # 错误只披露固定分类，避免上游错误中回显地址或凭据。
            output = response if status == 200 else {"error": {"message": "上游模型拒绝请求，状态 " + str(status)}}
        except Exception:
            status = 502
            output = {"error": {"message": "上游连接或响应失败；本次已计入预算，不自动重试"}}
        finally:
            with LOCK:
                self.budget.settle(entry, status, time.monotonic() - started, response)
                if status == 200:
                    with OUTPUT_PATH.open("a", encoding="utf-8") as stream:
                        stream.write(json.dumps({"requestId": entry["id"], "response": response}, ensure_ascii=False) + "\n")
        self.send(status, output)


def serve():
    Proxy.budget = Budget()
    Proxy.upstream = setting("OPENAI_CUSTOM_BASE_URL").rstrip("/")
    parsed = urlsplit(Proxy.upstream)
    if parsed.scheme not in ("https", "http") or parsed.username or parsed.password:
        raise RuntimeError("上游地址格式不受支持")
    Proxy.secret = setting("OPENAI_CUSTOM_API_KEY")
    Proxy.local_secret = credentials()["modelProxy"]
    server = ThreadingHTTPServer(("127.0.0.1", 18094), Proxy)
    print("模型预算代理就绪：127.0.0.1:18094；未进行上游调用", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
