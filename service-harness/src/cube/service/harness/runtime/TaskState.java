/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.InferenceStore;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 仅从已校验的 run 账本推导的、有界的决策辅助信息。
 */
public final class TaskState {

    /**
     * 结果不可变、可反复复用的只读工具。
     */
    private static final Set<String> IMMUTABLE = new HashSet<>(Arrays.asList(
            "resource.read", "resource.search", "resource.list", "context.read_result", "common.calculator.evaluate"));

    /**
     * 属于参数错误、可据证据修正的错误码集合。
     */
    private static final Set<String> PARAMETER_ERRORS = new HashSet<>(Arrays.asList(
            "SCHEMA_INVALID", "RESOURCE_NOT_FOUND", "TOOL_ARGUMENTS_INVALID", "INVALID_ARGUMENTS"));

    /**
     * 从账本推导任务状态：剩余预算、重复调用检测与工作状态。
     *
     * @param basis   基础数据，含 pin、事件与模型结果。
     * @param facts   已确认事实账本。
     * @param pending 待执行调用列表。
     * @param tools   已授权工具列表。
     * @return 任务状态对象。
     */
    public static JSONObject derive(JSONObject basis, JSONArray facts, JSONArray pending, JSONArray tools) {
        JSONObject execution = ExecutionProfiles.execution(basis.getJSONObject("pin"));
        JSONObject limits = execution.getJSONObject("executionLimits");
        long resumed = 0;
        int repairs = 0;
        int rounds = 0;
        int calls = 0;
        int consecutive = 0;
        String previous = null;
        String repeatedTool = "";
        String lastIntent = "";
        JSONObject feedback = null;
        JSONArray events = basis.getJSONArray("events");
        JSONArray evidence = new JSONArray();
        JSONArray errors = new JSONArray();
        JSONArray actions = new JSONArray();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (Arrays.asList("control.resume", "message.accepted").contains(event.getString("event_type"))) {
                resumed = event.getLong("event_seq");
            }
            if (event.getString("event_type").equals("workflow.repair")) {
                repairs++;
                feedback = new JSONObject(event.getString("data_json"));
            }
        }
        JSONArray outcomes = basis.optJSONArray("modelOutcomes");
        if (outcomes != null) {
            for (int i = 0; i < outcomes.length(); i++) {
                JSONObject outcome = new JSONObject(outcomes.getJSONObject(i).getString("outcome_json"));
                repairs += InferenceStore.repairs(outcome);
            }
        }
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.getJSONObject(i);
            if (fact.getString("kind").equals("round")) {
                rounds++;
                continue;
            }
            calls++;
            JSONObject payload = fact.getJSONObject("payload");
            JSONObject result = fact.optJSONObject("result");
            if (result == null) {
                consecutive = 0;
                previous = null;
                continue;
            }
            String name = payload.getString("tool");
            String status = result.getString("status");
            JSONObject item = new JSONObject()
                    .put("intent", fact.getString("intent"))
                    .put("tool", name)
                    .put("status", status)
                    .put("resultHash", fact.getString("resultHash"))
                    .put("argumentsHash", JdbcRunStore.hash(new JSONObject(payload.getString("argumentsJson"))))
                    .put("source", new JSONObject()
                            .put("kind", "tool-ledger")
                            .put("intent", fact.getString("intent")));
            if (status.equals("error")) {
                item.put("errorCode", result.optString("errorCode", "TOOL_ERROR_UNSPECIFIED"));
                errors.put(RuntimeSupport.copy(item));
            }
            evidence.put(item);
            if (fact.getLong("eventSeq") <= resumed) {
                consecutive = 0;
                previous = null;
                continue;
            }
            boolean immutable = status.equals("success") && IMMUTABLE.contains(name);
            boolean parameterError = status.equals("error") && PARAMETER_ERRORS.contains(result.optString("errorCode"));
            String fingerprint = immutable || parameterError
                    ? name + ":" + item.getString("argumentsHash") + ":" + (immutable ? fact.getString("resultHash") : result.getString("errorCode")) : null;
            consecutive = fingerprint == null ? 0 : fingerprint.equals(previous) ? consecutive + 1 : 1;
            previous = fingerprint;
            repeatedTool = name;
            lastIntent = fact.getString("intent");
        }
        for (int i = 0; i < tools.length(); i++) {
            actions.put(tools.getJSONObject(i).getString("name"));
        }
        JSONObject remaining = new JSONObject()
                .put("rounds", Math.max(0, limits.getInt("maxRounds") - rounds))
                .put("tools", Math.max(0, limits.getInt("maxTools") - calls))
                .put("repairs", Math.max(0, limits.getInt("maxRepairs") - repairs))
                .put("maxLifetimeMillis", limits.getLong("maxLifetimeMillis"))
                .put("deadlineEnforcedByCoordinator", true);
        int maximum = limits.optInt("maxRepeatedCalls", 0);
        JSONObject state = new JSONObject()
                .put("version", "task-state-v2")
                .put("goalSource", "run.created, control.resume and message.accepted user messages")
                .put("phase", pending.length() > 0 ? "pending-execution" : "continue-from-confirmed-evidence")
                .put("completion", "not-inferred; assess the user goal against confirmed evidence")
                .put("confirmedTools", calls - countPending(facts))
                .put("recentEvidence", tail(evidence, 16))
                .put("recentErrors", tail(errors, 8))
                .put("pending", pending)
                .put("authorizedTools", actions)
                .put("remaining", remaining)
                .put("lastResumeEventSeq", resumed)
                .put("consecutiveUnchangedCalls", consecutive)
                .put("workingMemory", TaskProgress.derive(basis, facts));
        if (feedback != null) {
            state.put("deliveryGaps", feedback);
        }
        if (maximum > 0 && consecutive >= maximum && pending.length() == 0) {
            state.put("blocked", new JSONObject()
                    .put("reason", "REPEATED_IMMUTABLE_READ_OR_PARAMETER_ERROR")
                    .put("tool", repeatedTool)
                    .put("intent", lastIntent)
                    .put("count", consecutive)
                    .put("action", "Reuse the confirmed result or correct the parameters using the cited evidence. Start a new request only with new task facts; do not repeat unknown execution."));
        }
        return state;
    }

    /**
     * 统计事实账本中尚无结果的待执行调用数。
     *
     * @param facts 已确认事实账本。
     * @return 待执行调用数。
     */
    private static int countPending(JSONArray facts) {
        int n = 0;
        for (int i = 0; i < facts.length(); i++) {
            if (facts.getJSONObject(i).getString("kind").equals("tool") && !facts.getJSONObject(i).has("result")) {
                n++;
            }
        }
        return n;
    }

    /**
     * 返回数组的末尾若干元素。
     *
     * @param input   原数组。
     * @param maximum 最多保留元素数。
     * @return 末尾元素组成的数组。
     */
    private static JSONArray tail(JSONArray input, int maximum) {
        JSONArray result = new JSONArray();
        for (int i = Math.max(0, input.length() - maximum); i < input.length(); i++) {
            result.put(input.get(i));
        }
        return result;
    }
}
