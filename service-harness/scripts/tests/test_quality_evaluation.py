"""Synthetic evidence tests, never online model quality certificates."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quality_evaluation import commitment, compare, evaluate, hard_assertions, redacted
from replay_trace import digest


class QualityEvaluation(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        rubric = dict(id="synthetic-rubric", version="1", criteria=[dict(id=d, dimension=d, description="Review " + d)
                      for d in ("protocol", "task", "grounding", "limitations")])
        self.fixture = dict(schemaVersion=2, status="FROZEN", rubric=rubric,
                            deployments=[dict(model="TEST-ONLY", profile="synthetic", configDigest="config")], scenarios=[])
        for i in range(50):
            self.fixture["scenarios"].append(dict(id=str(i), input=dict(instruction="Synthetic test " + str(i)),
                expectedTools=[], expectedScope={}, assertions=[dict(kind="allPhysicalFinished")], faults=[],
                budget=dict(maxRounds=8, maxCalls=32), expectedState="succeeded", quality="Synthetic assertions only",
                split="holdout" if i % 5 == 0 else "development", category="synthetic",
                qualityCriteria=[c["id"] for c in rubric["criteria"]]))
        self.rows, self.traces, self.reviews = [], {}, {}
        reference = self.artifact("reference.json", dict(label="synthetic reference"), "reference")
        for i in range(150):
            scene = self.fixture["scenarios"][i // 3]
            snapshot = dict(runId="test-" + str(i), version=1, eventSeq=1, state="succeeded", executionSettlement="confirmed")
            result = dict(state="succeeded", result=dict(text="synthetic " + str(i)))
            result_ref = self.artifact("result-" + str(i) + ".json", result, "result")
            trace = dict(schemaVersion=1, kind="harness-redacted-state-trace", replayComplete=True,
                         runId=snapshot["runId"], snapshot=snapshot, events=[dict(event_seq=1, run_version=1, projection=snapshot)],
                         config=dict(digest="config"), rounds=[dict(intent_id="round-1", attempt=1,
                             payload_json=redacted(dict(request=dict(messages=[dict(role="user", provenance="run.created", content=scene["input"])]))))],
                         calls=[], toolJobs=[], modelJobs=[], files=[],
                         resultCommitment=dict(result_hash=commitment(json.dumps(result).encode())))
            trace["digest"] = digest(trace)
            self.traces[trace["digest"]] = trace
            review = dict(schemaVersion=1, fixtureDigest=digest(self.fixture), scenarioId=scene["id"],
                          traceDigest=trace["digest"], runId=trace["runId"], rubricId=rubric["id"], rubricVersion=rubric["version"],
                          reviewer=dict(id="independent-test-reviewer", kind="human"), recordedAt="synthetic-test-only",
                          evidence=[self.artifact("request-" + str(i) + ".json", scene["input"], "request"), result_ref, reference],
                          criteria=[dict(id=c["id"], verdict="pass", rationale="Synthetic assertion", evidenceIds=["result", "reference"])
                                    for c in rubric["criteria"]])
            self.reviews[digest(review)] = review
            self.rows.append(dict(fixtureDigest=digest(self.fixture), **self.fixture["deployments"][0], scenarioId=scene["id"],
                                  repetition=1 + i % 3, attempt=1, mode="real", producerId="test-collector",
                                  hardPassed=True, languagePassed=True, traceDigest=trace["digest"], reviewDigest=digest(review),
                                  recordedAt="synthetic-test-only", elapsedMillis=100 + i))

    def artifact(self, name, value, kind):
        raw = json.dumps(value).encode()
        (self.root / name).write_bytes(raw)
        return dict(id=kind, kind=kind, path=name, sha256=hashlib.sha256(raw).hexdigest())

    def run_evaluation(self, rows=None, **kwargs):
        return evaluate(self.fixture, self.rows if rows is None else rows, self.traces, self.reviews, self.root, **kwargs)

    def change_review(self, index, mutate):
        review = copy.deepcopy(self.reviews[self.rows[index]["reviewDigest"]])
        mutate(review)
        self.reviews[digest(review)] = review
        self.rows[index]["reviewDigest"] = digest(review)

    def test_complete_evidence_threshold_and_no_input_mutation(self):
        before = copy.deepcopy(self.rows)
        report = self.run_evaluation()
        self.assertEqual(report["result"], "PASS")
        self.assertEqual(report["deployments"][0]["breakdown"]["split"]["holdout"]["passed"], 30)
        self.assertIsNone(report["deployments"][0]["metrics"]["reportedInputTokens"])
        self.assertEqual(self.rows, before)
        self.assertEqual(self.run_evaluation(diagnostic=True)["result"], "NOT_CERTIFIED")
        self.assertTrue(compare(report, report)[0]["nonRegressing"])

    def test_no_review_or_self_review_cannot_be_replaced_by_boolean(self):
        rows = copy.deepcopy(self.rows)
        rows[0].pop("reviewDigest")
        with self.assertRaisesRegex(ValueError, "INDEPENDENT_REVIEW"):
            self.run_evaluation(rows)
        self.change_review(0, lambda r: r["reviewer"].update(id="test-collector"))
        with self.assertRaisesRegex(ValueError, "INDEPENDENT_REVIEW"):
            self.run_evaluation()

    def test_wrong_run_and_missing_criterion_are_rejected(self):
        self.change_review(0, lambda r: r.update(runId="unrelated"))
        with self.assertRaisesRegex(ValueError, "REVIEW_IDENTITY"):
            self.run_evaluation()
        self.change_review(0, lambda r: r.update(runId="test-0", criteria=r["criteria"][:-1]))
        with self.assertRaisesRegex(ValueError, "CRITERIA_INCOMPLETE"):
            self.run_evaluation()

    def test_trace_from_different_input_cannot_support_review(self):
        old = self.rows[0]["traceDigest"]
        trace = copy.deepcopy(self.traces[old])
        trace["rounds"][0]["payload_json"] = redacted(dict(request=dict(messages=[
            dict(role="user", provenance="run.created", content=dict(instruction="unrelated task"))])))
        trace["digest"] = digest({k: v for k, v in trace.items() if k != "digest"})
        self.traces[trace["digest"]] = trace
        self.rows[0]["traceDigest"] = trace["digest"]
        self.change_review(0, lambda r: r.update(traceDigest=trace["digest"]))
        with self.assertRaisesRegex(ValueError, "TRACE_REQUEST_COMMITMENT"):
            self.run_evaluation()

    def test_reexporting_same_run_cannot_inflate_repetitions(self):
        trace = copy.deepcopy(self.traces[self.rows[0]["traceDigest"]])
        trace["feedback"] = [dict(rating="positive")]
        trace["digest"] = digest({k: v for k, v in trace.items() if k != "digest"})
        self.traces[trace["digest"]] = trace
        self.rows[1]["traceDigest"] = trace["digest"]
        self.change_review(1, lambda r: r.update(traceDigest=trace["digest"], runId=trace["runId"]))
        with self.assertRaisesRegex(ValueError, "REAL_RUN_REUSED"):
            self.run_evaluation()

    def test_semantically_identical_input_cannot_be_renamed_as_another_task(self):
        self.fixture["scenarios"][0]["input"] = dict(value=1)
        self.fixture["scenarios"][1]["input"] = dict(value=1.0)
        with self.assertRaisesRegex(ValueError, "SCENARIO_INPUT_REUSED"):
            self.run_evaluation()

    def test_evidence_bytes_and_trace_result_binding_are_checked(self):
        (self.root / "result-0.json").write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "EVIDENCE_DIGEST"):
            self.run_evaluation()
        replacement = self.artifact("result-0.json", dict(result="a different task result"), "result")
        self.change_review(0, lambda r: r["evidence"].__setitem__(1, replacement))
        with self.assertRaisesRegex(ValueError, "RESULT_COMMITMENT"):
            self.run_evaluation()

    def test_reference_path_escape_and_request_mismatch_are_rejected(self):
        self.change_review(0, lambda r: r["evidence"][2].update(path="../reference.json"))
        with self.assertRaisesRegex(ValueError, "EVIDENCE_PATH"):
            self.run_evaluation()
        self.change_review(0, lambda r: r["evidence"][2].update(path="reference.json"))
        replacement = self.artifact("request-0.json", dict(instruction="another task"), "request")
        self.change_review(0, lambda r: r["evidence"].__setitem__(0, replacement))
        with self.assertRaisesRegex(ValueError, "REQUEST_MISMATCH"):
            self.run_evaluation()

    def test_holdout_and_failed_original_survive_successful_retries(self):
        for i in (0, 1):
            self.change_review(i, lambda r: r["criteria"][2].update(verdict="fail"))
        report = self.run_evaluation()
        self.assertEqual(report["deployments"][0]["passed"], 148)
        self.assertEqual(report["result"], "NOT_CERTIFIED")  # 28/30 holdout, despite >95% overall.
        retry = copy.deepcopy(self.rows[2])
        retry.update(scenarioId="0", repetition=1, attempt=2)
        # Reusing a trace is forbidden before an attempt can erase a failure.
        with self.assertRaisesRegex(ValueError, "REAL_TRACE_REUSED"):
            self.run_evaluation(self.rows + [retry])
        retry = copy.deepcopy(self.rows[0])
        retry["attempt"] = 2
        trace = copy.deepcopy(self.traces[retry["traceDigest"]])
        trace["runId"] = trace["snapshot"]["runId"] = trace["events"][0]["projection"]["runId"] = "successful-retry"
        trace["digest"] = digest({k: v for k, v in trace.items() if k != "digest"})
        self.traces[trace["digest"]] = trace
        retry["traceDigest"] = trace["digest"]
        review = copy.deepcopy(self.reviews[retry["reviewDigest"]])
        review.update(runId=trace["runId"], traceDigest=trace["digest"])
        for c in review["criteria"]:
            c["verdict"] = "pass"
        self.reviews[digest(review)] = review
        retry["reviewDigest"] = digest(review)
        result = self.run_evaluation(self.rows + [retry])["deployments"][0]
        self.assertEqual((result["passed"], result["allAttempts"]), (148, 151))

    def test_dimension_regression_is_visible_even_with_equal_task_totals(self):
        baseline = self.run_evaluation()
        candidate = copy.deepcopy(baseline)
        candidate["deployments"][0]["failuresByDimension"]["grounding"] = 1
        comparison = compare(baseline, candidate)[0]
        self.assertEqual(comparison["passedDelta"], 0)
        self.assertEqual(comparison["failureDeltasByDimension"]["grounding"], 1)
        self.assertFalse(comparison["nonRegressing"])

    def test_isolated_cli_writes_diagnostic_report_without_overwriting_evidence(self):
        fixture = self.root / "fixture.json"
        fixture.write_text(json.dumps(self.fixture), encoding="utf-8")
        observations = self.root / "observations.jsonl"
        observations.write_text("\n".join(json.dumps(r) for r in self.rows[:3]), encoding="utf-8")
        for row in self.rows[:3]:
            for column, artifacts in (("traceDigest", self.traces), ("reviewDigest", self.reviews)):
                (self.root / (row[column] + ".json")).write_text(json.dumps(artifacts[row[column]]), encoding="utf-8")
        output = self.root / "new-report.json"
        command = [sys.executable, "-I", "-S", "-B", str(Path(__file__).resolve().parents[1] / "evaluate_traces.py"),
                   str(fixture), str(observations), "--traces-root", str(self.root), "--reviews-root", str(self.root),
                   "--evidence-root", str(self.root), "--diagnostic", "--output", str(output)]
        first = subprocess.run(command, cwd=self.root, capture_output=True, text=True)
        self.assertEqual(first.returncode, 2, first.stderr)
        saved = output.read_bytes()
        self.assertEqual(json.loads(saved)["result"], "NOT_CERTIFIED")
        repeated = subprocess.run(command, cwd=self.root, capture_output=True, text=True)
        self.assertNotIn(repeated.returncode, (0, 2))
        self.assertEqual(saved, output.read_bytes())

    def test_bounded_tools_allow_alternative_paths_but_no_extra_authority(self):
        scene = copy.deepcopy(self.fixture["scenarios"][0])
        scene["toolPolicy"] = dict(mode="bounded", required=["common.data.query"], allowed=["common.data.query", "resource.read"])
        trace = copy.deepcopy(next(iter(self.traces.values())))
        trace["calls"] = [dict(intent_id="call", attempt=1, tool="common.data.query")]
        trace["digest"] = digest({k: v for k, v in trace.items() if k != "digest"})
        self.assertTrue(hard_assertions(scene, self.fixture["deployments"][0], trace))
        trace["calls"].append(dict(intent_id="forbidden", attempt=1, tool="unknown.execute"))
        trace["digest"] = digest({k: v for k, v in trace.items() if k != "digest"})
        self.assertFalse(hard_assertions(scene, self.fixture["deployments"][0], trace))

    def test_unknown_usage_does_not_prove_token_budget(self):
        scene = copy.deepcopy(self.fixture["scenarios"][0])
        scene["budget"]["maxInputTokens"] = 10
        self.assertFalse(hard_assertions(scene, self.fixture["deployments"][0], next(iter(self.traces.values()))))

    def test_behavior_labels_require_original_visible_context_bound_to_each_round(self):
        payload = dict(request=dict(messages=[dict(role="user", provenance="run.created", content=self.fixture["scenarios"][0]["input"])]))
        trace = copy.deepcopy(self.traces[self.rows[0]["traceDigest"]])
        trace["rounds"][0]["payload_json_sha256"] = commitment(json.dumps(payload).encode())
        trace["events"][0]["event_type"] = "run.succeeded"
        trace["metrics"] = dict(adjustmentControlDigest="a" * 64, adjustmentEnabled=True, hostWorkflowSteps=0,
                                decisionObservations=[dict(roundId="round-1", attempt=1)])
        trace["digest"] = digest({k: v for k, v in trace.items() if k != "digest"})
        self.traces[trace["digest"]] = trace
        self.rows[0]["traceDigest"] = trace["digest"]
        context = dict(self.artifact("context-0.json", payload, "round-context"), id="context", round="round-1/1")
        behavior = dict(version="evidence-adjustment-v1", calls=[], requests=[], interventions=[], adjustments=[
            dict(round="round-1/1", trigger="none", effect="neutral", rationale="Initial action, no feedback yet", evidenceIds=["context"])])
        self.change_review(0, lambda r: r.update(traceDigest=trace["digest"], behavior=behavior, evidence=r["evidence"] + [context]))
        self.assertEqual(self.run_evaluation()["deployments"][0]["behaviorMetrics"]["reviewedAttempts"], 1)
        from prepare_adjustment_evaluation import review_template
        for name, value in (("fixture-for-review.json", self.fixture), ("trace-for-review.json", trace),
                            ("contexts.json", [dict(round="round-1/1", path="context-0.json")])):
            (self.root / name).write_text(json.dumps(value), encoding="utf-8")
        pending = self.root / "pending-review.json"
        review_template(self.root / "fixture-for-review.json", self.root / "trace-for-review.json", "0", self.root,
                        "request-0.json", "result-0.json", "reference.json", "independent-test-reviewer", pending, self.root / "contexts.json")
        template = json.loads(pending.read_bytes())
        self.assertTrue(all(c["verdict"] == "fail" for c in template["criteria"]))
        self.assertEqual(template["behavior"]["adjustments"][0]["effect"], "unassessable")
        self.change_review(0, lambda r: r.update(evidence=r["evidence"][:-1]))
        with self.assertRaisesRegex(ValueError, "BEHAVIOR_ROUND_CONTEXT_REQUIRED"):
            self.run_evaluation()
        wrong = dict(self.artifact("context-0.json", dict(request="a different visible context"), "round-context"), id="context", round="round-1/1")
        self.change_review(0, lambda r: r.update(evidence=r["evidence"] + [wrong]))
        with self.assertRaisesRegex(ValueError, "REVIEW_ROUND_CONTEXT_MISMATCH"):
            self.run_evaluation()

    def test_legacy_boolean_results_are_readable_but_not_certified(self):
        self.fixture["schemaVersion"] = 1
        for row in self.rows:
            row["fixtureDigest"] = digest(self.fixture)
        report = self.run_evaluation()
        self.assertEqual(report["result"], "NOT_CERTIFIED")
        self.assertEqual(report["deployments"][0]["legacyResult"], "PASS")

    def test_java_result_commitment_numeric_and_unicode_semantics(self):
        self.assertEqual(commitment(b'{"n":1.0}'), commitment(b'{"n":1}'))
        self.assertEqual(commitment('{"text":"</script>\\u2028"}'.encode()), commitment('{"text":"<\\/script>\\u2028"}'.encode()))
        with self.assertRaisesRegex(ValueError, "DUPLICATE"):
            commitment(b'{"n":1,"n":2}')


if __name__ == "__main__":
    unittest.main()
