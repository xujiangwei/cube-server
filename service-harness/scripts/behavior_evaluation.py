"""Evidence-bound behavior reviews and paired task-level statistics; stdlib only."""
from collections import Counter
import random
import statistics


def require(condition, code):
    if not condition:
        raise ValueError(code)


def reviewed_behavior(review, trace, evidence_ids, required=False):
    behavior = review.get("behavior")
    if behavior is None:
        require(not required, "BEHAVIOR_REVIEW_REQUIRED")
        return None
    require(behavior.get("version") == "evidence-adjustment-v1", "BEHAVIOR_REVIEW_VERSION_INVALID")
    metrics = trace.get("metrics", {})
    control = metrics.get("adjustmentControlDigest")
    require(isinstance(control, str) and len(control) == 64 and all(c in "0123456789abcdef" for c in control)
            and type(metrics.get("adjustmentEnabled")) is bool, "BEHAVIOR_INSTRUMENTATION_REQUIRED")
    calls = {c["intent_id"] + "/" + str(c["attempt"]) for c in trace["calls"]}
    rounds = {r["intent_id"] + "/" + str(r["attempt"]) for r in trace["rounds"]}
    requests = {e["event_seq"]: e["event_type"] for e in trace["events"]
                if e.get("event_type") in ("run.waiting_input", "run.waiting_approval")}
    interventions = {e["event_seq"]: e["event_type"] for e in trace["events"]
                     if e.get("event_type") in ("message.accepted", "control.resume", "control.approval")}
    # interaction.answered/requested accompany these events and must not be counted twice.
    counts = Counter(humanRequests=len(requests), humanInterventions=len(interventions),
                     wastefulCalls=0, explorationCalls=0, uncertainCalls=0,
                     avoidableRequests=0, avoidableInterventions=0, uncertainInterventions=0, uncertainRequests=0,
                     necessaryClarifications=0, requiredApprovals=0, goalChanges=0, reportedHumanMillis=0, missingHumanDurationCount=0,
                     helpfulAdjustments=0, harmfulAdjustments=0, unassessableAdjustments=0, triggeredResponses=0)

    def entries(section, identities, key):
        items = behavior.get(section)
        require(isinstance(items, list), "BEHAVIOR_REVIEW_INCOMPLETE")
        require(all(isinstance(item, dict) and key in item for item in items), "BEHAVIOR_REVIEW_INVALID")
        supplied = [item[key] for item in items]
        require(len(supplied) == len(set(supplied)) and set(supplied) == set(identities), "BEHAVIOR_REVIEW_COVERAGE_INVALID")
        for item in items:
            refs = item.get("evidenceIds", [])
            require(isinstance(refs, list) and bool(refs) and set(refs) <= evidence_ids
                    and bool(item.get("rationale", "").strip()), "BEHAVIOR_REVIEW_EVIDENCE_REQUIRED")
        return items

    for item in entries("calls", calls, "intent"):
        label = item.get("classification")
        require(label in ("useful", "necessary_exploration", "wasteful", "uncertain"), "BEHAVIOR_CALL_CLASS_INVALID")
        if label in ("wasteful", "necessary_exploration", "uncertain"):
            counts[{"wasteful": "wastefulCalls", "necessary_exploration": "explorationCalls", "uncertain": "uncertainCalls"}[label]] += 1
    for section, identities in (("requests", requests), ("interventions", interventions)):
        for item in entries(section, identities, "eventSeq"):
            label = item.get("classification")
            allowed = ("necessary_clarification", "required_approval", "avoidable", "uncertain")
            require(label in allowed + (("goal_change",) if section == "interventions" else ()), "BEHAVIOR_INTERVENTION_CLASS_INVALID")
            approval = identities[item["eventSeq"]] in ("run.waiting_approval", "control.approval")
            require((label == "required_approval") == approval, "BEHAVIOR_APPROVAL_CLASS_INVALID")
            if label == "avoidable":
                counts["avoidableRequests" if section == "requests" else "avoidableInterventions"] += 1
            if section == "requests" and label == "uncertain":
                counts["uncertainRequests"] += 1
            if section == "interventions":
                duration = item.get("elapsedMillis")
                require(duration is None or type(duration) is int and duration >= 0, "BEHAVIOR_DURATION_INVALID")
                if duration is None:
                    counts["missingHumanDurationCount"] += 1
                else:
                    counts["reportedHumanMillis"] += duration
                column = {"necessary_clarification": "necessaryClarifications", "required_approval": "requiredApprovals",
                          "goal_change": "goalChanges", "uncertain": "uncertainInterventions"}.get(label)
                if column:
                    counts[column] += 1
    observed = metrics.get("decisionObservations", [])
    require({o["roundId"] + "/" + str(o["attempt"]) for o in observed} == rounds
            and len(observed) == len(rounds), "BEHAVIOR_INSTRUMENTATION_INCOMPLETE")
    for item in entries("adjustments", rounds, "round"):
        trigger, effect = item.get("trigger"), item.get("effect")
        require(trigger in ("confirmed_error", "information_gap", "evidence_conflict", "none")
                and effect in ("helpful", "harmful", "neutral", "unassessable")
                and (trigger != "none" or effect in ("neutral", "unassessable")), "BEHAVIOR_ADJUSTMENT_CLASS_INVALID")
        if effect == "unassessable":
            counts["unassessableAdjustments"] += 1
        if trigger != "none":
            counts["triggeredResponses"] += 1
            column = {"helpful": "helpfulAdjustments", "harmful": "harmfulAdjustments"}.get(effect)
            if column:
                counts[column] += 1
    return dict(counts, controlDigest=control, enabled=metrics["adjustmentEnabled"],
                hostWorkflowSteps=metrics.get("hostWorkflowSteps", 0))


def summarize(attempts):
    complete = [a["behavior"] for a in attempts if a.get("behavior") is not None]
    totals = Counter()
    for row in complete:
        totals.update({k: v for k, v in row.items() if type(v) is int})
    return dict(reviewedAttempts=len(complete), missingAttempts=len(attempts) - len(complete),
                totals=dict(totals) if len(complete) == len(attempts) and complete else None)


def paired_comparison(old, new, diagnostic):
    def slots(deployment):
        out = {}
        for attempt in deployment["attempts"]:
            out.setdefault((attempt["scenarioId"], attempt["repetition"]), []).append(attempt)
        return out
    left, right = slots(old), slots(new)
    if set(left) != set(right) or len(left) != old["requiredRuns"] or not left:
        return dict(comparable=False, reason="incomplete-paired-coverage", goalAchieved=False)
    if any(a.get("behavior") is None for values in list(left.values()) + list(right.values()) for a in values):
        return dict(comparable=False, reason="behavior-reviews-missing", goalAchieved=False)
    if any({a["behavior"]["controlDigest"] for a in left[key]} != {a["behavior"]["controlDigest"] for a in right[key]}
           or len({a["behavior"]["controlDigest"] for a in left[key] + right[key]}) != 1 for key in left):
        return dict(comparable=False, reason="authorization-model-tools-workflow-or-budget-changed", goalAchieved=False)
    if any(a["behavior"]["enabled"] for values in left.values() for a in values) or any(
            not a["behavior"]["enabled"] for values in right.values() for a in values):
        return dict(comparable=False, reason="expected-baseline-off-and-candidate-on", goalAchieved=False)
    if {a["traceDigest"] for values in left.values() for a in values} & {a["traceDigest"] for values in right.values() for a in values}:
        return dict(comparable=False, reason="paired-evidence-reused", goalAchieved=False)

    def measures(values):
        passed = all(a["passed"] for a in values) and sorted(a["attempt"] for a in values) == list(range(1, len(values) + 1))
        behavior = Counter()
        for a in values:
            behavior.update({k: v for k, v in a["behavior"].items() if type(v) is int})
        return dict(correct=int(passed), correctWithoutRescue=int(passed and not behavior["avoidableInterventions"]),
                    avoidableInterventions=behavior["avoidableInterventions"], avoidableRequests=behavior["avoidableRequests"],
                    wastefulCalls=behavior["wastefulCalls"], toolCalls=sum(a["toolCalls"] for a in values),
                    modelRounds=sum(a["modelRounds"] for a in values))
    task_deltas = {}
    for key in sorted(left):
        before, after = measures(left[key]), measures(right[key])
        task_deltas.setdefault(key[0], []).append({name: after[name] - before[name] for name in before})
    # Average repetitions inside a task before sampling; three runs are not three independent tasks.
    task_means = [{name: statistics.mean(r[name] for r in repetitions) for name in repetitions[0]}
                  for repetitions in task_deltas.values()]
    randomizer, intervals = random.Random(0), {}
    for name in task_means[0]:
        values = [task[name] for task in task_means]
        samples = sorted(statistics.mean(randomizer.choices(values, k=len(values))) for _ in range(4000)) if len(values) >= 2 else []
        interval = [samples[99], samples[3899]] if samples else None
        intervals[name] = dict(meanDelta=statistics.mean(values), confidence95=interval)
    uncertain = any(a["behavior"][name] for values in list(left.values()) + list(right.values()) for a in values
                    for name in ("uncertainCalls", "uncertainInterventions", "uncertainRequests", "unassessableAdjustments", "goalChanges"))
    def improved(name, positive):
        interval = intervals[name]["confidence95"]
        return interval is not None and (interval[0] > 0 if positive else interval[1] < 0)
    quality = improved("correct", True)
    human = improved("avoidableInterventions", False)
    waste = improved("wastefulCalls", False)
    clean = [key for key in left if left[key][0].get("category") == "clean"]
    clean_nonregressing = bool(clean) and all(measures(right[key])["correct"] >= measures(left[key])["correct"] for key in clean)
    model_controlled = not any(a["behavior"].get("hostWorkflowSteps", 0) for values in list(left.values()) + list(right.values()) for a in values)
    return dict(comparable=True, independentTasks=len(task_means), method="paired-task-cluster-bootstrap-4000-seed-0",
                metrics=intervals, uncertainOrGoalChange=uncertain,
                correctnessImproved=quality, humanRescueReduced=human, wasteReduced=waste,
                cleanTasksNonRegressing=clean_nonregressing, modelControlsActions=model_controlled,
                goalAchieved=not diagnostic and not uncertain and old["requiredRuns"] >= 150 and new["hardFailures"] == 0
                and new.get("result") == "PASS" and clean_nonregressing and model_controlled
                and intervals["avoidableRequests"]["confidence95"] is not None
                and intervals["avoidableRequests"]["confidence95"][1] <= 0 and quality and human and waste,
                limitation="Statistical evidence applies to this frozen suite; certification and clean-task regression checks remain separate.")
