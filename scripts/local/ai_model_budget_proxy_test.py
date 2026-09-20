"""预算失效会导致无界收费，先用确定性测试验证持久限制。"""

from pathlib import Path
import shutil
import tempfile
import time
import unittest

from ai_evolution_vm import EVIDENCE
from ai_model_budget_proxy import Budget


class BudgetTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp(prefix="budget-test-", dir=EVIDENCE)).resolve()
        self.path = self.folder / "budget.json"
        self.budget = Budget(self.path)

    def tearDown(self):
        assert self.folder.is_relative_to(EVIDENCE.resolve()) and self.folder.name.startswith("budget-test-")
        shutil.rmtree(self.folder)

    def request(self, index):
        return {"model": "gpt-5.6-luna", "messages": [{"role": "user", "content": str(index)}],
                "max_completion_tokens": 4096}

    def test_restart_preserves_unsettled_reservations_and_wall_budget(self):
        self.budget.reserve("POST", "/chat/completions", self.request(1))
        reloaded = Budget(self.path)
        self.assertEqual(4096, reloaded.state["entries"][0]["outputTokens"])
        reloaded.state["startedAt"] = time.time() - 3601
        reloaded.save()
        with self.assertRaises(RuntimeError):
            Budget(self.path).reserve("POST", "/chat/completions", self.request(2))

    def test_request_count_is_cumulative_even_with_zero_reported_usage(self):
        for index in range(48):
            entry = self.budget.reserve("POST", "/chat/completions", self.request(index))
            self.budget.settle(entry, 200, 0.1, {"usage": {"prompt_tokens": 0, "completion_tokens": 0}})
        with self.assertRaises(RuntimeError):
            Budget(self.path).reserve("POST", "/chat/completions", self.request(49))

    def test_failed_calls_consume_retry_and_conservative_token_budgets(self):
        for _ in range(3):
            entry = self.budget.reserve("POST", "/chat/completions", self.request(1))
            self.budget.settle(entry, 502, 1, {})
        self.assertEqual(12288, sum(entry["outputTokens"] for entry in self.budget.state["entries"]))
        with self.assertRaises(RuntimeError):
            self.budget.reserve("POST", "/chat/completions", self.request(1))

    def test_large_input_or_output_is_rejected_before_reserving(self):
        body = self.request(1)
        body["messages"][0]["content"] = "字" * 100000
        with self.assertRaises(RuntimeError):
            self.budget.reserve("POST", "/chat/completions", body)
        body = self.request(1)
        body["max_completion_tokens"] = 5000
        with self.assertRaises(RuntimeError):
            self.budget.reserve("POST", "/chat/completions", body)
        self.assertFalse(self.budget.state["entries"])

    def test_tool_round_limit_survives_restart(self):
        for index in range(12):
            entry = self.budget.reserve("POST", "/chat/completions", self.request(index))
            self.budget.settle(entry, 200, 0.1, {"usage": {"prompt_tokens": 10, "completion_tokens": 10},
                "choices": [{"message": {"tool_calls": [{"id": str(index)}]}}]})
        with self.assertRaises(RuntimeError):
            Budget(self.path).reserve("POST", "/chat/completions", self.request(13))

    def test_validation_attempts_cannot_reset_stage_retry_limit(self):
        self.budget.begin_validation()
        Budget(self.path).begin_validation()
        Budget(self.path).begin_validation()
        with self.assertRaises(RuntimeError):
            Budget(self.path).begin_validation()

    def test_stage_cannot_be_changed_to_reset_existing_ledger(self):
        self.budget.reserve("POST", "/chat/completions", self.request(1))
        before = self.path.read_bytes()
        with self.assertRaises(RuntimeError):
            Budget(self.path, stage="DB-2")
        self.assertEqual(before, self.path.read_bytes())

    def test_data_stage_has_its_own_lower_persistent_budget(self):
        budget = Budget(self.folder / "data.json", stage="DB-2")
        self.assertEqual(180000, budget.limits["inputTokens"])
        self.assertEqual(32, budget.limits["requests"])
        for index in range(10):
            entry = budget.reserve("POST", "/chat/completions", self.request(index))
            budget.settle(entry, 200, 0.1, {"usage": {"prompt_tokens": 10, "completion_tokens": 10},
                "choices": [{"message": {"tool_calls": [{"id": str(index)}]}}]})
        with self.assertRaises(RuntimeError):
            Budget(budget.path, stage="DB-2").reserve("POST", "/chat/completions", self.request(11))


if __name__ == "__main__":
    unittest.main()
