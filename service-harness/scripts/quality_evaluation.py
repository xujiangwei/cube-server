"""Offline task quality evaluation. Reviews are evidence, never executable instructions."""
import argparse
import hashlib
import json
import math
import re
from collections import Counter
from decimal import Decimal
from pathlib import Path
from replay_trace import digest, load, pairs, replay
from behavior_evaluation import reviewed_behavior, summarize as summarize_behavior, paired_comparison

DIMENSIONS = ("protocol", "task", "grounding", "limitations")
SHA256 = re.compile(r"[a-f0-9]{64}\Z")


def require(condition, code):
    if not condition:
        raise ValueError(code)


def validate_fixture(fixture):
    version = fixture.get("schemaVersion")
    require(version in (1, 2) and fixture.get("status") == "FROZEN", "FIXTURE_NOT_FROZEN")
    required = {"id", "input", "expectedTools", "expectedScope", "assertions", "faults", "budget", "expectedState", "quality"}
    scenes = fixture.get("scenarios", [])
    require(bool(scenes) and all(required <= s.keys() for s in scenes), "FIXTURE_INCOMPLETE")
    require(len({s["id"] for s in scenes}) == len(scenes), "FIXTURE_DUPLICATE_SCENARIO")
    deployments = fixture.get("deployments", [])
    require(bool(deployments), "DEPLOYMENTS_REQUIRED")
    keys = [(d["model"], d["profile"], d["configDigest"]) for d in deployments]
    require(len(set(keys)) == len(keys), "DEPLOYMENT_DUPLICATE")
    if version == 1:
        require(len(scenes) >= 50, "FIXTURE_INCOMPLETE")
        return
    require(fixture.get("behaviorEvaluation", False) in (False, "evidence-adjustment-v1"), "BEHAVIOR_EVALUATION_VERSION_INVALID")
    rubric = fixture["rubric"]
    require(bool(rubric["id"]) and bool(rubric["version"]), "RUBRIC_INVALID")
    criteria = {c["id"]: c for c in rubric["criteria"]}
    require(len(criteria) == len(rubric["criteria"]) and bool(criteria), "RUBRIC_INVALID")
    require(all(c.get("dimension") in DIMENSIONS and bool(c.get("description")) for c in criteria.values()), "RUBRIC_INVALID")
    # Renaming an identical input must not inflate independent scenario coverage.
    require(len({commitment(json.dumps(s["input"]).encode()) for s in scenes}) == len(scenes), "SCENARIO_INPUT_REUSED")
    for scene in scenes:
        require(scene.get("split") in ("development", "holdout") and bool(scene.get("category")), "SCENARIO_SPLIT_REQUIRED")
        selected = scene.get("qualityCriteria", [])
        require(bool(selected) and len(set(selected)) == len(selected) and set(selected) <= criteria.keys(), "SCENARIO_RUBRIC_INVALID")
        require({criteria[c]["dimension"] for c in selected} == set(DIMENSIONS), "SCENARIO_DIMENSIONS_INCOMPLETE")
        policy = scene.get("toolPolicy", {"mode": "exact"})
        require(policy.get("mode") in ("exact", "bounded"), "TOOL_POLICY_INVALID")
        if policy["mode"] == "bounded":
            allowed, needed = policy.get("allowed", []), policy.get("required", [])
            require({"allowed", "required"} <= policy.keys() and len(set(allowed)) == len(allowed) and len(set(needed)) == len(needed)
                    and set(needed) <= set(allowed), "TOOL_POLICY_INVALID")
        require(any(a["kind"] == "allPhysicalFinished" for a in scene["assertions"]), "PHYSICAL_ASSERTION_REQUIRED")
        for limit in ("maxRounds", "maxCalls"):
            require(type(scene["budget"].get(limit)) is int and scene["budget"][limit] >= 0, "SCENARIO_BUDGET_INVALID")


def hard_assertions(scene, deployment, trace):
    replay(trace)
    if trace.get("config", {}).get("digest") != deployment["configDigest"]:
        return False
    tools = [call.get("tool") for call in trace["calls"]]
    policy = scene.get("toolPolicy", {"mode": "exact"})
    if policy["mode"] == "exact":
        if sorted(tools) != sorted(scene["expectedTools"]):
            return False
    elif not set(policy["required"]) <= set(tools) <= set(policy["allowed"]):
        return False
    if trace["snapshot"]["state"] != scene["expectedState"]:
        return False
    if len(trace["rounds"]) > scene["budget"]["maxRounds"] or len(trace["calls"]) > scene["budget"]["maxCalls"]:
        return False
    for column, limit in (("actual_input", "maxInputTokens"), ("actual_output", "maxOutputTokens")):
        if limit in scene["budget"]:
            amounts = [u.get(column) for u in trace.get("usage", [])]
            if not amounts or any(type(v) is not int or v < 0 for v in amounts) or sum(amounts) > scene["budget"][limit]:
                return False
    for assertion in scene["assertions"]:
        if assertion["kind"] == "argumentsHash":
            if not any(c.get("argumentsHash") == assertion["value"] for c in trace["calls"]):
                return False
        elif assertion["kind"] == "fileSha256":
            if not any(f["sha256"] == assertion["value"] and f["status"] in ("ready", "deleted") for f in trace["files"]):
                return False
        elif assertion["kind"] == "allPhysicalFinished":
            if any(j.get("actual_finished") not in (True, 1) for j in trace["toolJobs"] + trace["modelJobs"] + trace.get("inferences", [])):
                return False
            if trace["snapshot"].get("executionSettlement") != "confirmed":
                return False
            if any(j.get("effect_status") == "unknown" for j in trace["toolJobs"]):
                return False
        else:
            raise ValueError("UNKNOWN_HARD_ASSERTION")
    if scene["expectedScope"] and not any(a["kind"] == "argumentsHash" for a in scene["assertions"]):
        raise ValueError("SCOPE_COMMITMENT_REQUIRED")
    return True


def checked_artifact(root, relative, expected):
    require(root is not None and isinstance(relative, str) and SHA256.fullmatch(expected or ""), "REVIEW_EVIDENCE_REQUIRED")
    base = Path(root).resolve()
    path = base / relative
    require(not Path(relative).is_absolute() and ".." not in Path(relative).parts
            and path.resolve().is_relative_to(base), "EVIDENCE_PATH_INVALID")
    current = path
    while current != base:
        require(not current.is_symlink(), "EVIDENCE_PATH_INVALID")
        current = current.parent
    with path.open("rb") as stream:
        raw = stream.read(1048577)
    require(len(raw) <= 1048576 and hashlib.sha256(raw).hexdigest() == expected, "REVIEW_EVIDENCE_DIGEST_MISMATCH")
    return raw


def commitment(raw):
    """Match the Java ledger's semantic JSON digest, including decimal and UTF-16 key ordering."""
    def canonical(value):
        if value is None:
            return "null"
        if type(value) is bool:
            return "true" if value else "false"
        if isinstance(value, (int, Decimal)):
            number = Decimal(value)
            require(number.is_finite(), "INVALID_NUMBER")
            if not number:
                return "0"
            plain = format(number, "f")
            return plain.rstrip("0").rstrip(".") if "." in plain else plain
        if isinstance(value, str):
            quoted = json.dumps(value, ensure_ascii=False).replace("</", "<\\/")
            return "".join("\\u%04x" % ord(c) if 0x80 <= ord(c) < 0xa0 or 0x2000 <= ord(c) < 0x2100 else c for c in quoted)
        if isinstance(value, list):
            return "[" + ",".join(canonical(v) for v in value) + "]"
        return "{" + ",".join(canonical(k) + ":" + canonical(value[k]) for k in sorted(value, key=lambda k: k.encode("utf-16-be"))) + "}"
    value = json.loads(raw, parse_float=Decimal, object_pairs_hook=pairs,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("INVALID_NUMBER")))
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def redacted(value):
    """Reproduce TraceStore's content commitments without revealing source text."""
    if isinstance(value, dict):
        return {"keySha256:" + commitment(json.dumps(k).encode()): redacted(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redacted(v) for v in value]
    if isinstance(value, str):
        return dict(redactedSha256=commitment(json.dumps(value).encode()))
    return value


def trace_has_input(trace, original):
    key = lambda name: "keySha256:" + commitment(json.dumps(name).encode())
    expected = commitment(json.dumps(redacted(original)).encode())
    for round_ in trace["rounds"]:
        request = round_.get("payload_json", {}).get(key("request"), {})
        for message in request.get(key("messages"), []):
            if (message.get(key("role")) == redacted("user")
                    and message.get(key("provenance")) == redacted("run.created")
                    and commitment(json.dumps(message.get(key("content"))).encode()) == expected):
                return True
    return False


def reviewed_dimensions(fixture, scene, row, trace, reviews, evidence_root):
    review_hash = row.get("reviewDigest", "")
    require(SHA256.fullmatch(review_hash) and reviews is not None and review_hash in reviews, "INDEPENDENT_REVIEW_REQUIRED")
    review = reviews[review_hash]
    require(digest(review) == review_hash and review.get("schemaVersion") == 1, "REVIEW_IDENTITY_MISMATCH")
    for key, expected in (("fixtureDigest", digest(fixture)), ("scenarioId", scene["id"]),
                          ("traceDigest", row["traceDigest"]), ("runId", trace["runId"]),
                          ("rubricId", fixture["rubric"]["id"]), ("rubricVersion", fixture["rubric"]["version"])):
        require(review.get(key) == expected, "REVIEW_IDENTITY_MISMATCH")
    reviewer = review.get("reviewer", {})
    require(reviewer.get("kind") == "human" and bool(reviewer.get("id"))
            and bool(row.get("producerId")) and reviewer["id"] != row["producerId"]
            and bool(review.get("recordedAt")), "INDEPENDENT_REVIEW_REQUIRED")
    require(trace_has_input(trace, scene["input"]), "TRACE_REQUEST_COMMITMENT_MISMATCH")
    evidence, evidence_ids, kinds = review.get("evidence", []), set(), set()
    round_payloads = {r["intent_id"] + "/" + str(r["attempt"]): r.get("payload_json_sha256") for r in trace["rounds"]}
    context_rounds, context_ids = set(), set()
    require(1 <= len(evidence) <= 64, "REVIEW_EVIDENCE_REQUIRED")
    for item in evidence:
        require(bool(item.get("id")) and item["id"] not in evidence_ids, "REVIEW_EVIDENCE_INVALID")
        evidence_ids.add(item["id"])
        kinds.add(item.get("kind"))
        raw = checked_artifact(evidence_root, item["path"], item["sha256"])
        if item.get("kind") == "result":
            require(commitment(raw) == (trace.get("resultCommitment") or {}).get("result_hash"), "REVIEW_RESULT_COMMITMENT_MISMATCH")
        if item.get("kind") == "request":
            require(commitment(raw) == commitment(json.dumps(scene["input"]).encode()), "REVIEW_REQUEST_MISMATCH")
        if item.get("kind") == "round-context":
            identity = item.get("round")
            require(identity in round_payloads and identity not in context_rounds
                    and round_payloads[identity] == commitment(raw), "REVIEW_ROUND_CONTEXT_MISMATCH")
            context_rounds.add(identity)
            context_ids.add(item["id"])
    require({"request", "result", "reference"} <= kinds, "REVIEW_EVIDENCE_REQUIRED")
    criteria = review.get("criteria", [])
    require(len(criteria) == len(scene["qualityCriteria"])
            and {c["id"] for c in criteria} == set(scene["qualityCriteria"]), "REVIEW_CRITERIA_INCOMPLETE")
    dimensions = {name: True for name in DIMENSIONS}
    rubric = {c["id"]: c for c in fixture["rubric"]["criteria"]}
    for criterion in criteria:
        refs = criterion.get("evidenceIds", [])
        require(criterion.get("verdict") in ("pass", "fail") and bool(criterion.get("rationale", "").strip())
                and bool(refs) and set(refs) <= evidence_ids, "REVIEW_CRITERION_INVALID")
        dimensions[rubric[criterion["id"]]["dimension"]] &= criterion["verdict"] == "pass"
    if review.get("behavior") is not None or fixture.get("behaviorEvaluation"):
        require(context_rounds == set(round_payloads), "BEHAVIOR_ROUND_CONTEXT_REQUIRED")
        for section in ("calls", "requests", "interventions", "adjustments"):
            for item in review.get("behavior", {}).get(section, []):
                require(not round_payloads or bool(set(item.get("evidenceIds", [])) & context_ids), "BEHAVIOR_VISIBLE_CONTEXT_EVIDENCE_REQUIRED")
    behavior = reviewed_behavior(review, trace, evidence_ids, bool(fixture.get("behaviorEvaluation")))
    return dimensions, behavior


def score_slots(slots):
    return sum(all(a["passed"] for a in attempts)
               and sorted(a["attempt"] for a in attempts) == list(range(1, len(attempts) + 1)) for attempts in slots.values())


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def evaluate(fixture, observations, traces=None, reviews=None, evidence_root=None, diagnostic=False):
    validate_fixture(fixture)
    scenes = {s["id"]: s for s in fixture["scenarios"]}
    version, frozen = fixture["schemaVersion"], digest(fixture)
    deployments = {(d["model"], d["profile"], d["configDigest"]) for d in fixture["deployments"]}
    rows, seen = [], set()
    for original in observations:
        row = dict(original)
        identity = (row["model"], row["profile"], row["configDigest"])
        require(row["fixtureDigest"] == frozen and row["scenarioId"] in scenes and identity in deployments
                and type(row["repetition"]) is int and row["repetition"] in (1, 2, 3)
                and type(row["attempt"]) is int and row["attempt"] >= 1, "OBSERVATION_IDENTITY_INVALID")
        require(row["mode"] in ("real", "mock", "skip"), "OBSERVATION_MODE_INVALID")
        key = identity + (row["scenarioId"], row["repetition"], row["attempt"])
        require(key not in seen, "DUPLICATE_OBSERVATION")
        seen.add(key)
        rows.append(row)
    real_digests = [r["traceDigest"] for r in rows if r["mode"] == "real"]
    require(len(real_digests) == len(set(real_digests)), "REAL_TRACE_REUSED")
    reports, real_runs = [], set()
    for deployment in fixture["deployments"]:
        matching = [r for r in rows if all(r[k] == deployment[k] for k in ("model", "profile", "configDigest"))]
        real = [r for r in matching if r["mode"] == "real"]
        slots, groups, failures, attempts_report = {}, {}, Counter(), []
        durations, inputs, outputs = [], [], []
        missing_usage, rounds, calls = 0, 0, 0
        for row in real:
            require(isinstance(row.get("hardPassed"), bool) and bool(row.get("recordedAt")), "REAL_EVIDENCE_REQUIRED")
            require(traces is not None and row["traceDigest"] in traces, "TRACE_ARTIFACT_REQUIRED")
            trace, scene = traces[row["traceDigest"]], scenes[row["scenarioId"]]
            require(trace.get("digest") == row["traceDigest"], "TRACE_IDENTITY_MISMATCH")
            require(trace["runId"] not in real_runs, "REAL_RUN_REUSED")
            real_runs.add(trace["runId"])
            hard = hard_assertions(scene, deployment, trace) and row["hardPassed"]
            if version == 2:
                dimensions, behavior = reviewed_dimensions(fixture, scene, row, trace, reviews, evidence_root)
            else:
                require(isinstance(row.get("languagePassed"), bool), "REAL_EVIDENCE_REQUIRED")
                dimensions = {name: row["languagePassed"] for name in DIMENSIONS}
                behavior = None
            failures.update({"execution": int(not hard), **{k: int(not v) for k, v in dimensions.items()}})
            failures["language"] += int(not all(dimensions.values()))
            attempt = dict(attempt=row["attempt"], passed=hard and all(dimensions.values()))
            attempts_report.append(dict(scenarioId=row["scenarioId"], repetition=row["repetition"], **attempt,
                                        executionPassed=hard, dimensions=dimensions, traceDigest=row["traceDigest"], reviewDigest=row.get("reviewDigest"),
                                        behavior=behavior, category=scene.get("category"), split=scene.get("split"),
                                        modelRounds=len(trace["rounds"]), toolCalls=len(trace["calls"])))
            slot = (row["scenarioId"], row["repetition"])
            slots.setdefault(slot, []).append(attempt)
            for kind in ("split", "category"):
                groups.setdefault((kind, scene.get(kind, "legacy")), {}).setdefault(slot, []).append(attempt)
            if type(row.get("elapsedMillis")) is int and row["elapsedMillis"] >= 0:
                durations.append(row["elapsedMillis"])
            rounds += len(trace["rounds"])
            calls += len(trace["calls"])
            usage = trace.get("usage", [])
            missing_usage += int(not usage)
            for entry in usage:
                for column, values in (("actual_input", inputs), ("actual_output", outputs)):
                    amount = entry.get(column)
                    if type(amount) is int and amount >= 0:
                        values.append(amount)
                    else:
                        missing_usage += 1
        total = len(scenes) * 3
        passed, threshold = score_slots(slots), (total * 95 + 99) // 100
        legacy_pass = len(slots) == total and failures["execution"] == 0 and passed >= threshold
        holdout = [s for s in scenes.values() if s.get("split") == "holdout"]
        holdout_pass = bool(holdout) and score_slots(groups.get(("split", "holdout"), {})) >= math.ceil(len(holdout) * 3 * .95)
        certified = version == 2 and not diagnostic and len(scenes) >= 50 and len(holdout) * 5 >= len(scenes) and len(holdout) < len(scenes) and legacy_pass and holdout_pass
        breakdown = {}
        for (kind, label), grouped in sorted(groups.items()):
            expected = sum(s.get(kind, "legacy") == label for s in scenes.values()) * 3
            breakdown.setdefault(kind, {})[label] = dict(requiredRuns=expected, coveredRuns=len(grouped), passed=score_slots(grouped))
        reports.append(dict(**deployment, result="PASS" if certified else "NOT_CERTIFIED", requiredRuns=total,
                            requiredPasses=threshold, coveredRuns=len(slots), passed=passed,
                            hardFailures=failures["execution"], languageFailures=failures["language"],
                            failuresByDimension={k: failures[k] for k in ("execution",) + DIMENSIONS}, allAttempts=len(real), attempts=attempts_report,
                            firstAttemptPassed=sum(any(a["attempt"] == 1 and a["passed"] for a in values) for values in slots.values()),
                            mock=sum(r["mode"] == "mock" for r in matching), skip=sum(r["mode"] == "skip" for r in matching),
                            legacyResult="PASS" if version == 1 and legacy_pass else None, breakdown=breakdown,
                            metrics=dict(modelRounds=rounds, toolCalls=calls, measuredDurationAttempts=len(durations),
                                         p50Millis=percentile(durations, .5), p95Millis=percentile(durations, .95),
                                         reportedInputTokens=sum(inputs) if inputs else None,
                                         reportedOutputTokens=sum(outputs) if outputs else None, missingUsageEntries=missing_usage),
                            behaviorMetrics=summarize_behavior(attempts_report)))
    return dict(schemaVersion=2, fixtureDigest=frozen,
                suiteDigest=digest({k: fixture[k] for k in ("schemaVersion", "scenarios", "rubric", "behaviorEvaluation") if k in fixture}),
                evidenceVersion=version, diagnostic=diagnostic, deployments=reports,
                result="PASS" if reports and all(r["result"] == "PASS" for r in reports) else "NOT_CERTIFIED")


def compare(baseline, candidate):
    require(baseline.get("suiteDigest") == candidate.get("suiteDigest") and baseline.get("evidenceVersion") == candidate.get("evidenceVersion") == 2,
            "BASELINE_SUITE_MISMATCH")
    changes = []
    require(len(baseline["deployments"]) == len(candidate["deployments"]), "BASELINE_DEPLOYMENT_MISMATCH")
    for new in candidate["deployments"]:
        old = [r for r in baseline["deployments"] if (r["model"], r["profile"]) == (new["model"], new["profile"])]
        require(len(old) == 1, "BASELINE_DEPLOYMENT_MISMATCH")
        old = old[0]
        comparable = old["coveredRuns"] == old["requiredRuns"] == new["coveredRuns"] == new["requiredRuns"]
        holdout_old = old["breakdown"]["split"].get("holdout", {}).get("passed", 0)
        holdout_new = new["breakdown"]["split"].get("holdout", {}).get("passed", 0)
        dimension_deltas = {d: new["failuresByDimension"][d] - old["failuresByDimension"][d]
                            for d in ("execution",) + DIMENSIONS}
        metrics = {name: new["metrics"][name] - old["metrics"][name]
                   if new["metrics"][name] is not None and old["metrics"][name] is not None else None
                   for name in ("modelRounds", "toolCalls", "p50Millis", "p95Millis", "reportedInputTokens", "reportedOutputTokens")}
        non_regressing = (comparable and new["passed"] >= old["passed"] and holdout_new >= holdout_old
                          and new["hardFailures"] == 0 and all(v <= 0 for v in dimension_deltas.values()))
        behavior = paired_comparison(old, new, baseline.get("diagnostic", True) or candidate.get("diagnostic", True))
        behavior["goalAchieved"] = behavior["goalAchieved"] and non_regressing
        changes.append(dict(model=new["model"], profile=new["profile"], comparable=comparable,
                            baselineConfigDigest=old["configDigest"], candidateConfigDigest=new["configDigest"],
                            passedDelta=new["passed"] - old["passed"], holdoutPassedDelta=holdout_new - holdout_old,
                            failureDeltasByDimension=dimension_deltas, metricDeltas=metrics,
                            nonRegressing=non_regressing, behaviorComparison=behavior))
    return changes


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("observations", type=Path)
    parser.add_argument("--traces-root", type=Path)
    parser.add_argument("--reviews-root", type=Path)
    parser.add_argument("--evidence-root", type=Path)
    parser.add_argument("--diagnostic", action="store_true", help="Small development suite; never certify it")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    observations = [json.loads(line) for line in args.observations.read_text(encoding="utf-8").splitlines() if line.strip()]
    traces, reviews = {}, {}
    for row in observations:
        if row["mode"] == "real":
            require(args.traces_root is not None and SHA256.fullmatch(row["traceDigest"]), "TRACE_ARTIFACT_REQUIRED")
            path = args.traces_root / (row["traceDigest"] + ".json")
            require(not path.is_symlink(), "TRACE_FILE_INVALID")
            traces[row["traceDigest"]] = load(path)
            if row.get("reviewDigest"):
                require(args.reviews_root is not None and SHA256.fullmatch(row["reviewDigest"]), "INDEPENDENT_REVIEW_REQUIRED")
                path = args.reviews_root / (row["reviewDigest"] + ".json")
                require(not path.is_symlink(), "REVIEW_FILE_INVALID")
                reviews[row["reviewDigest"]] = load(path)
    report = evaluate(json.loads(args.fixture.read_text(encoding="utf-8")), observations, traces, reviews, args.evidence_root, args.diagnostic)
    if args.baseline:
        report["comparison"] = compare(json.loads(args.baseline.read_text(encoding="utf-8")), report)
    with args.output.open("x", encoding="utf-8") as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 0 if report["result"] == "PASS" else 2
