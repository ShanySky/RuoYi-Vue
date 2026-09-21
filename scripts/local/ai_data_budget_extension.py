"""35 号 R2 的一次性预算修订；保留阶段全部历史和开始时间，不能重复执行。"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

from ai_evolution_vm import EVIDENCE
from ai_model_budget_proxy import STAGES


def amend(proof_path):
    proof_path = Path(proof_path).resolve()
    assert proof_path.is_relative_to(EVIDENCE.resolve()) and proof_path.name.startswith("data-restart-verified-")
    proof = json.loads(proof_path.read_text(encoding="utf-8"))
    assert proof["passed"] and proof["beforeRuntime"]["pid"] != proof["afterRuntime"]["pid"]
    assert proof["beforeRuntime"]["sha256"] == proof["afterRuntime"]["sha256"]
    assert all(row["enabled"] and not row["contractChanged"] for row in proof["facts"])
    path = EVIDENCE / "model-budget-stage2.json"
    original = path.read_bytes()
    state = json.loads(original)
    revised = dict(STAGES["DB-2"][1])
    previous = {**revised, "retries": 2}
    assert revised["retries"] == 3 and state["stage"] == "DB-2" and state["limits"] == previous
    assert not state.get("amendments") and state["validationAttempts"] == 3 and state["validationRetries"] == 2
    entries = state["entries"]
    assert len(entries) == 12 and all(row["status"] == 200 and row["usageVerified"] for row in entries)
    assert sum(row["inputTokens"] for row in entries) == 21140
    assert sum(row["outputTokens"] for row in entries) == 730
    assert sum(row["toolRounds"] for row in entries) == 11
    backup = EVIDENCE / "model-budget-stage2-before-r2.json"
    with backup.open("xb") as stream:
        stream.write(original)
    state["limits"] = revised
    state["amendments"] = [{"time": datetime.now(timezone.utc).isoformat(), "document": "35 R2",
        "reason": "三类已定位失败完成确定性复验；同包跨进程目录稳定后追加一次完整任务，其他上限不变",
        "previousSha256": hashlib.sha256(original).hexdigest(), "previousLimits": previous,
        "newLimits": revised, "restartProof": proof_path.name, "preservedRequests": len(entries)}]
    pending = path.with_suffix(".amendment-pending")
    pending.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(pending, path)
    print("AI_DATA_BUDGET_R2_APPLIED：保留原开始时间、12 次请求及全部累计消耗")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restart-proof", required=True)
    amend(parser.parse_args().restart_proof)
