package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.util.*;

/** Counterexamples to rule-name-only assessment: unknowns, type coercion and conflicting rules. */
public final class AssessmentPolicyTest {
    private static int checks;
    private static final JSONObject PROFILE = new JSONObject().put("id", "describe").put("unit", "OmniMultimodal").put("optionsJson", "{}");
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static JSONObject policy(JSONArray rules) {
        return new JSONObject().put("version", "2").put("rules", new JSONArray().put(new JSONObject().put("id", "site-rules")))
                .put("assessmentPolicy", new JSONObject().put("version", "evidence-rules-v1").put("rules", rules));
    }
    private static JSONObject condition(String source, String path, String operator, Object value) {
        return new JSONObject().put("source", source).put("path", path).put("operator", operator).put("value", value);
    }
    private static JSONObject rule(String status, JSONObject condition) {
        return new JSONObject().put("ruleId", "site-rules").put("profileId", "describe").put("status", status)
                .put("conditions", new JSONArray().put(condition));
    }
    private static JSONObject run(JSONObject policy, JSONObject payload, JSONObject record, String status) {
        return AssessmentPolicy.check(policy, PROFILE, new JSONObject().put("answer", "Confirmed observation").put("payloadJson", payload.toString()),
                record, new JSONObject().put("status", status).put("ruleIds", new JSONArray().put("site-rules")));
    }
    private static void rejects(String code, Runnable action) {
        try { action.run(); throw new AssertionError(code); }
        catch (StoreException expected) { check(expected.code.equals(code), code); }
    }
    public static void main(String[] args) throws Exception {
        JSONObject high = policy(new JSONArray().put(rule("abnormal", condition("payload", "/temperature", "gt", 40))));
        JSONObject result = run(high, new JSONObject().put("temperature", 41), new JSONObject(), "abnormal");
        check(result.getString("status").equals("predicates-matched") && !result.getBoolean("semanticallyVerified"), "predicate match does not certify interpretation");
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(high, new JSONObject().put("temperature", 40), new JSONObject(), "abnormal"));
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(high, new JSONObject().put("temperature", "41"), new JSONObject(), "abnormal"));
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(high, new JSONObject().put("temperature", JSONObject.NULL), new JSONObject(), "abnormal"));
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(high, new JSONObject(), new JSONObject(), "abnormal"));
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(policy(new JSONArray()), new JSONObject(), new JSONObject(), "normal"));
        JSONObject unknownTime = policy(new JSONArray().put(rule("normal", condition("record", "/capturedAt", "eq", "2026-11-15T10:00:00+08:00"))));
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(unknownTime, new JSONObject(), new JSONObject().put("capturedAt", JSONObject.NULL), "normal"));
        JSONObject booleanRule = rule("normal", condition("payload", "/authorized", "eq", false));
        JSONObject boolPolicy = policy(new JSONArray().put(booleanRule));
        check(run(boolPolicy, new JSONObject().put("authorized", false), new JSONObject(), "normal").getString("status").equals("predicates-matched"), "false is a fact, not a missing value");
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(boolPolicy, new JSONObject().put("authorized", 0), new JSONObject(), "normal"));
        JSONObject conflict = policy(new JSONArray().put(booleanRule).put(rule("abnormal", condition("payload", "/authorized", "eq", false))));
        rejects("REPORT_ASSESSMENT_CONFLICT", () -> run(conflict, new JSONObject().put("authorized", false), new JSONObject(), "normal"));
        check(run(conflict, new JSONObject().put("authorized", false), new JSONObject(), "review-required").getString("status").equals("review-required"), "conflicts may be delivered explicitly for review");
        JSONObject uncertain = policy(new JSONArray().put(booleanRule).put(rule("abnormal", condition("payload", "/otherFact", "eq", true))));
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> run(uncertain, new JSONObject().put("authorized", false), new JSONObject(), "normal"));
        CompletionGuard.Resources resources = new CompletionGuard.Resources() {
            public JSONObject json(String path) { return new JSONObject().put("version", "1").put("profiles", new JSONArray().put(PROFILE)); }
        };
        AssessmentPolicy.validate(resources, high);
        JSONObject circular = policy(new JSONArray().put(rule("normal", condition("dimensions", "/businessAbnormality", "eq", "normal"))));
        try { AssessmentPolicy.validate(resources, circular); throw new AssertionError("self-proof accepted"); }
        catch (IllegalArgumentException expected) { check(true, "model status cannot prove itself"); }
        JSONObject legacy = new JSONObject().put("version", "1");
        check(run(legacy, new JSONObject(), new JSONObject(), "normal").getString("reason").equals("LEGACY_RULE_CITATION_ONLY"),
                "legacy citation-only assessment stays explicitly unverified");
        JSONObject detection = new JSONObject().put("id", "people").put("unit", "SpecializedDetection")
                .put("optionsJson", "{\"profile\":\"thing\",\"classes\":[\"person\"]}");
        JSONObject restricted = rule("abnormal", condition("record", "/restrictedArea", "eq", true)).put("profileId", "people");
        restricted.getJSONArray("conditions").put(condition("dimensions", "/presence", "eq", true));
        JSONObject presencePolicy = policy(new JSONArray().put(restricted));
        JSONObject assessment = new JSONObject().put("status", "abnormal").put("ruleIds", new JSONArray().put("site-rules"));
        JSONObject analysis = new JSONObject().put("answer", "").put("payloadJson",
                "{\"profile\":\"thing\",\"refs\":[{\"content\":\"person\",\"boxes\":[{\"x1\":1,\"y1\":1,\"x2\":10,\"y2\":10,\"confidence\":0.9}]}]}");
        JSONObject site = new JSONObject().put("restrictedArea", true);
        check(AssessmentPolicy.check(presencePolicy, detection, analysis, site, assessment).getString("status").equals("predicates-matched"),
                "a configured business fact and parsed detection both support the condition");
        analysis.put("payloadJson", "{\"profile\":\"thing\",\"refs\":[]}");
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> AssessmentPolicy.check(presencePolicy, detection, analysis, site, assessment));
        analysis.put("payloadJson", "{\"profile\":\"thing\",\"refs\":[{\"content\":\"person\",\"boxes\":[{}]}]}");
        rejects("REPORT_ASSESSMENT_UNSUPPORTED", () -> AssessmentPolicy.check(presencePolicy, detection, analysis, site, assessment));
        System.out.println("PASS assessment policy: " + checks + " assertions; typed facts, missing evidence, thresholds, conflicting and circular rules.");
    }
}
