"""跨实际后端进程验证相同数据契约不会因重启失效。"""

import argparse
import json
from pathlib import Path
import ai_server_api_acceptance as h


def snapshot():
    token = h.api(None, "/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    return token, h.api(token, "/ai/admin/data")["data"]


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--capture", action="store_true")
parser.add_argument("--expect")
args = parser.parse_args()
assert args.capture != bool(args.expect)
token, data = snapshot()
if args.capture:
    for row in [data] + [value for value in data["tables"] if value["supported"]]:
        h.api(token, "/ai/admin/data/" + row["key"], "PUT", {"fingerprint": row["fingerprint"], "enabled": True,
            "fields": [column["name"] for column in row.get("columns", []) if column["supported"]],
            "operations": [] if row["key"] == "database" else ["QUERY", "AGGREGATE"]})
    _, data = snapshot()
facts = [{key: row[key] for key in ("key", "fingerprint", "enabled", "contractChanged")}
    for row in [data] + [value for value in data["tables"] if value["supported"]]]
assert all(row["enabled"] and not row["contractChanged"] for row in facts), facts
record = max((h.EVIDENCE / "runtime-evolution").glob("backend-*.json"), key=lambda path: path.name)
runtime = json.loads(record.read_text(encoding="utf-8"))
if args.capture:
    output = h.EVIDENCE / f"data-restart-capture-{h.STAMP}.json"
    output.write_text(json.dumps({"facts": facts, "runtime": runtime}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"captured": str(output), "pid": runtime["pid"]}, ensure_ascii=False))
else:
    expected = Path(args.expect).resolve()
    assert expected.is_relative_to(h.EVIDENCE.resolve()) and expected.name.startswith("data-restart-capture-")
    baseline = json.loads(expected.read_text(encoding="utf-8"))
    assert baseline["facts"] == facts, {"before": baseline["facts"], "after": facts}
    assert baseline["runtime"]["pid"] != runtime["pid"] and baseline["runtime"]["sha256"] == runtime["sha256"]
    evidence = {"passed": True, "capture": str(expected), "beforeRuntime": baseline["runtime"], "afterRuntime": runtime, "facts": facts}
    output = h.EVIDENCE / f"data-restart-verified-{h.STAMP}.json"
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("AI_DATA_RESTART_CONTRACT_OK " + str(output))
