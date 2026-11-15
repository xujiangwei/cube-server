package cube.service.harness.runtime;

import org.json.JSONObject;

/** Cross-field constraints beyond the closed deployment schema. Old pinned policies remain valid. */
public final class ContextPolicy {
    private ContextPolicy() {}
    public static void validate(JSONObject policy) {
        if (policy.has("compressionTriggerPercent") != policy.has("compressionTargetPercent")
                || policy.optInt("compressionTargetPercent", 60) >= policy.optInt("compressionTriggerPercent", 80)
                || policy.has("compressionTriggerPercent") && !policy.optBoolean("adaptiveHistory"))
            throw RuntimeSupport.error("CONTEXT_THRESHOLDS_INVALID");
        JSONObject summary = policy.optJSONObject("semanticSummary");
        if (summary != null && summary.getBoolean("enabled") && (!policy.optBoolean("adaptiveHistory") || !policy.optBoolean("finalWireGuard")))
            throw RuntimeSupport.error("CONTEXT_SUMMARY_POLICY_INVALID");
    }
}
