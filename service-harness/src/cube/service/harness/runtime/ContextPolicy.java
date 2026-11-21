/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import org.json.JSONObject;

/**
 * 封闭部署 schema 之外的跨字段约束；旧的固定策略仍然有效。
 */
public final class ContextPolicy {

    /**
     * 工具类，禁止实例化。
     */
    private ContextPolicy() {
    }

    /**
     * 校验上下文策略的跨字段一致性。
     *
     * @param policy 已通过 schema 校验的上下文策略对象。
     */
    public static void validate(JSONObject policy) {
        if (policy.optBoolean("evidenceAdjustment", false) && !policy.optBoolean("taskState", false)) {
            throw RuntimeSupport.error("EVIDENCE_ADJUSTMENT_POLICY_INVALID");
        }
        if (policy.has("compressionTriggerPercent") != policy.has("compressionTargetPercent")
                || policy.optInt("compressionTargetPercent", 60) >= policy.optInt("compressionTriggerPercent", 80)
                || policy.has("compressionTriggerPercent") && !policy.optBoolean("adaptiveHistory")) {
            throw RuntimeSupport.error("CONTEXT_THRESHOLDS_INVALID");
        }
        JSONObject summary = policy.optJSONObject("semanticSummary");
        if (summary != null && summary.getBoolean("enabled") && (!policy.optBoolean("adaptiveHistory") || !policy.optBoolean("finalWireGuard"))) {
            throw RuntimeSupport.error("CONTEXT_SUMMARY_POLICY_INVALID");
        }
    }
}
