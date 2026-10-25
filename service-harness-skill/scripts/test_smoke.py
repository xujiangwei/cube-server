"""Acceptance-verifier tests: a redacted trace is not a source-content export."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from build_packages import ROOT, package
from search_smoke import verify


class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.url = "https://example.com/source"
        data = dict(query="3D", searchedAt="2026-10-25T00:00:00Z", results=[dict(url=self.url)])
        self.raw = json.dumps(dict(status="success", dataJson=json.dumps(data)), separators=(",", ":"))
        self.result = dict(snapshot=dict(runId="run-a", state="succeeded", selection=dict(model=dict(id="actual-model"))),
                           result=dict(result=dict(text="来源 " + self.url)))
        self.trace = dict(calls=[dict(tool="common.search.query", status="confirmed", intent_id="call-a", attempt=1,
                                     result_json_sha256=hashlib.sha256(self.raw.encode()).hexdigest())],
                          inferences=[dict(status="finished", actual_finished=1, error_code=None, cancel_requested=0)])
        self.rows = [dict(run_id="run-a", intent_id="call-a", attempt=1, result_json=self.raw)]

    def test_actual_source_matches_commitment_and_answer(self):
        self.assertEqual(verify(self.result, self.trace, self.rows)["result"], "PASS")

    def test_tampered_or_foreign_export_is_rejected(self):
        changed = copy.deepcopy(self.rows)
        changed[0]["result_json"] += " "
        self.assertFalse(verify(self.result, self.trace, changed)["checks"]["searchEvidence"])
        changed = copy.deepcopy(self.rows)
        changed[0]["run_id"] = "foreign"
        self.assertFalse(verify(self.result, self.trace, changed)["checks"]["searchEvidence"])

    def test_scripted_alias_cannot_pass_real_model_check(self):
        self.result["snapshot"]["selection"]["model"]["id"] = "offline-scripted"
        self.trace["inferences"] = []
        self.assertFalse(verify(self.result, self.trace, self.rows)["checks"]["realModel"])

    def test_failed_or_uncited_results_do_not_pass(self):
        self.result["result"] = dict(result=dict(text="没有来源"))
        self.assertFalse(verify(self.result, self.trace, self.rows)["checks"]["citedSource"])
        self.result["snapshot"]["state"] = "failed"
        self.assertFalse(verify(self.result, self.trace, self.rows)["checks"]["succeeded"])

    def test_packages_are_reproducible(self):
        with tempfile.TemporaryDirectory() as folder:
            a, b = Path(folder) / "a.zip", Path(folder) / "b.zip"
            self.assertEqual(package(ROOT / "harness-tools/search", a), package(ROOT / "harness-tools/search", b))
            self.assertEqual(a.read_bytes(), b.read_bytes())


if __name__ == "__main__":
    unittest.main()
