"""Synthetic scoring/attribution tests; never real model performance evidence."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from behavior_evaluation import paired_comparison, reviewed_behavior, summarize


class BehaviorReviewTest(unittest.TestCase):
    def setUp(self):
        self.trace = dict(calls=[dict(intent_id="call-1", attempt=1)], rounds=[dict(intent_id="round-1", attempt=1)],
                          events=[dict(event_seq=2, event_type="run.waiting_input"),
                                  dict(event_seq=3, event_type="interaction.requested"),
                                  dict(event_seq=4, event_type="interaction.answered"),
                                  dict(event_seq=5, event_type="message.accepted")],
                          metrics=dict(adjustmentControlDigest="a" * 64, adjustmentEnabled=True,
                                       decisionObservations=[dict(roundId="round-1", attempt=1)], hostWorkflowSteps=0))
        self.review = dict(behavior=dict(version="evidence-adjustment-v1",
            calls=[dict(intent="call-1/1", classification="necessary_exploration", rationale="First failed lookup disclosed the available path", evidenceIds=["source"])],
            requests=[dict(eventSeq=2, classification="necessary_clarification", rationale="Only the user knows the requested unit", evidenceIds=["source"])],
            interventions=[dict(eventSeq=5, classification="necessary_clarification", rationale="The user supplied the unit", evidenceIds=["source"])],
            adjustments=[dict(round="round-1/1", trigger="information_gap", effect="helpful", rationale="Requested the missing fact", evidenceIds=["source"])]))

    def test_exploration_is_not_waste_and_interaction_events_are_deduplicated(self):
        scored = reviewed_behavior(self.review, self.trace, {"source"}, True)
        self.assertEqual((scored["humanRequests"], scored["humanInterventions"], scored["explorationCalls"], scored["wastefulCalls"]), (1, 1, 1, 0))
        self.assertEqual(summarize([dict(behavior=scored), dict(behavior=None)])["totals"], None)

    def test_premature_finals_are_bound_to_the_observed_round(self):
        self.trace["metrics"]["decisionObservations"][0]["decision"] = dict(kind="final")
        self.review["behavior"]["finals"] = [dict(round="round-1/1", classification="premature",
                                                rationale="The last required page was never read", evidenceIds=["source"])]
        scored = reviewed_behavior(self.review, self.trace, {"source"}, True)
        self.assertEqual(summarize([dict(behavior=scored)])["finalTiming"]["prematureRate"], 1)
        self.assertIsNone(summarize([dict(behavior=scored), dict(behavior=None)])["finalTiming"]["prematureRate"])
        self.review["behavior"]["finals"][0]["round"] = "absent/1"
        with self.assertRaisesRegex(ValueError, "COVERAGE"):
            reviewed_behavior(self.review, self.trace, {"source"}, True)

    def test_missing_entries_and_fake_zeroes_are_rejected(self):
        self.review["behavior"]["calls"] = []
        with self.assertRaisesRegex(ValueError, "COVERAGE"):
            reviewed_behavior(self.review, self.trace, {"source"}, True)
        with self.assertRaisesRegex(ValueError, "REQUIRED"):
            reviewed_behavior({}, self.trace, {"source"}, True)
        self.assertIsNone(reviewed_behavior({}, self.trace, {"source"}, False))

    def test_review_requires_bound_evidence_and_full_instrumentation(self):
        with self.assertRaisesRegex(ValueError, "EVIDENCE"):
            reviewed_behavior(self.review, self.trace, set(), True)
        self.trace["metrics"]["decisionObservations"] = []
        with self.assertRaisesRegex(ValueError, "INSTRUMENTATION_INCOMPLETE"):
            reviewed_behavior(self.review, self.trace, {"source"}, True)

    def test_approval_cannot_be_relabelled_as_avoidable_rescue(self):
        self.trace["events"][0]["event_type"] = "run.waiting_approval"
        with self.assertRaisesRegex(ValueError, "APPROVAL_CLASS"):
            reviewed_behavior(self.review, self.trace, {"source"}, True)


class PairedBehaviorTest(unittest.TestCase):
    def deployment(self, candidate, count=50):
        attempts = []
        for i in range(count):
            for repetition in (1, 2, 3):
                attempts.append(dict(scenarioId=str(i), repetition=repetition, attempt=1, passed=candidate or i % 5 == 0,
                    category="clean" if i % 5 == 0 else "information-gap", traceDigest=("new" if candidate else "old") + str(i) + str(repetition),
                    modelRounds=2, toolCalls=2 if candidate else 4, behavior=dict(controlDigest="a" * 64, enabled=candidate,
                        avoidableInterventions=0 if candidate or i % 5 == 0 else 1, avoidableRequests=0,
                        wastefulCalls=0 if candidate or i % 5 == 0 else 2, uncertainCalls=0, uncertainInterventions=0,
                        uncertainRequests=0, unassessableAdjustments=0, goalChanges=0, hostWorkflowSteps=0)))
        return dict(attempts=attempts, requiredRuns=count * 3, hardFailures=0, result="PASS")

    def test_task_clusters_and_all_three_outcomes_are_required(self):
        result = paired_comparison(self.deployment(False), self.deployment(True), False)
        self.assertEqual(result["independentTasks"], 50)
        self.assertTrue(result["goalAchieved"])
        self.assertGreater(result["metrics"]["correct"]["confidence95"][0], 0)
        self.assertTrue(paired_comparison(self.deployment(False), self.deployment(True), True)["correctnessImproved"])
        self.assertFalse(paired_comparison(self.deployment(False), self.deployment(True), True)["goalAchieved"])

    def test_changed_authority_or_reused_evidence_cannot_establish_incremental_gain(self):
        old, new = self.deployment(False), self.deployment(True)
        new["attempts"][0]["behavior"]["controlDigest"] = "b" * 64
        self.assertFalse(paired_comparison(old, new, False)["comparable"])
        new = self.deployment(True)
        new["attempts"][0]["traceDigest"] = old["attempts"][0]["traceDigest"]
        self.assertFalse(paired_comparison(old, new, False)["comparable"])

    def test_failed_original_survives_retry_and_host_workflow_cannot_claim_model_autonomy(self):
        old, new = self.deployment(False), self.deployment(True)
        failed = copy.deepcopy(new["attempts"][0]); failed["passed"] = False
        retry = copy.deepcopy(new["attempts"][0]); retry["attempt"] = 2; retry["traceDigest"] += "-retry"
        new["attempts"][0] = failed; new["attempts"].append(retry)
        new["attempts"][0]["behavior"]["hostWorkflowSteps"] = 1
        result = paired_comparison(old, new, False)
        self.assertLess(result["metrics"]["correct"]["meanDelta"], .8)
        self.assertFalse(result["goalAchieved"])
        self.assertFalse(result["cleanTasksNonRegressing"])

    def test_unchanged_quality_does_not_become_success_by_reducing_calls(self):
        old, new = self.deployment(False), self.deployment(True)
        for a, b in zip(old["attempts"], new["attempts"]): b["passed"] = a["passed"]
        result = paired_comparison(old, new, False)
        self.assertFalse(result["correctnessImproved"])
        self.assertFalse(result["goalAchieved"])


if __name__ == "__main__":
    unittest.main()
