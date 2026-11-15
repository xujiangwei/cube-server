package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed, bounded predicates over confirmed facts. Never evaluates code or model prose. */
final class AssessmentPolicy {
    private static final Set<String> DEFINITIVE = new HashSet<>(Arrays.asList("normal", "abnormal"));

    static void validate(CompletionGuard.Resources resources, JSONObject requirements) throws Exception {
        boolean current = "2".equals(requirements.getString("version"));
        if (!current) {
            if (requirements.has("assessmentPolicy")) throw new IllegalArgumentException("POLICY_REQUIRES_VERSION_2");
            return;
        }
        JSONObject policy = requirements.getJSONObject("assessmentPolicy");
        ConnectorConfig.keys(policy, "version,rules", "");
        require("evidence-rules-v1".equals(policy.getString("version")));
        JSONArray rules = policy.getJSONArray("rules");
        require(rules.length() <= 32);
        Set<String> declared = new HashSet<>(), unique = new HashSet<>();
        JSONArray references = requirements.getJSONArray("rules");
        for (int i = 0; i < references.length(); i++) declared.add(references.getJSONObject(i).getString("id"));
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            ConnectorConfig.keys(rule, "ruleId,profileId,status,conditions", "");
            require(declared.contains(rule.getString("ruleId")) && DEFINITIVE.contains(rule.getString("status")));
            ConnectorConfig.select(resources.json("analysis-profiles.json"), "profiles", rule.getString("profileId"));
            require(unique.add(rule.getString("ruleId") + "/" + rule.getString("profileId") + "/" + rule.getString("status")));
            JSONArray conditions = rule.getJSONArray("conditions");
            require(conditions.length() >= 1 && conditions.length() <= 8);
            for (int c = 0; c < conditions.length(); c++) {
                JSONObject condition = conditions.getJSONObject(c);
                ConnectorConfig.keys(condition, "source,path,operator,value", "");
                String source = condition.getString("source"), path = condition.getString("path"), op = condition.getString("operator");
                require(Arrays.asList("record", "payload", "dimensions").contains(source));
                require(path.startsWith("/") && path.length() <= 256 && path.split("/", -1).length <= 17
                        && !path.matches(".*~(?:[^01]|$).*"));
                // These dimensions are derived independently from the detection payload.
                // In particular a model's proposed businessAbnormality cannot prove itself.
                if (source.equals("dimensions")) require(Arrays.asList("/presence", "/boxCount", "/uniqueTargetCount").contains(path));
                require(Arrays.asList("eq", "gt", "gte", "lt", "lte").contains(op));
                Object value = condition.get("value");
                require(value instanceof Boolean || value instanceof Number || value instanceof String);
                if (value instanceof String) require(!((String)value).isEmpty() && ((String)value).length() <= 512);
                if (value instanceof Number) require(Double.isFinite(((Number)value).doubleValue()));
                if (!op.equals("eq")) require(value instanceof Number);
            }
        }
    }

    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("ASSESSMENT_POLICY_INVALID"); }

    static JSONObject check(JSONObject requirements, JSONObject profile, JSONObject analysis, JSONObject record,
                            JSONObject assessment) {
        String status = assessment.getString("status");
        boolean definitive = DEFINITIVE.contains(status);
        JSONObject review = new JSONObject().put("method", "evidence-rules-v1")
                .put("scope", "configured-predicates-only").put("semanticallyVerified", false)
                .put("explanationReviewRequired", true);
        if (!"2".equals(requirements.getString("version"))) {
            return review.put("status", "review-required").put("reason", "LEGACY_RULE_CITATION_ONLY");
        }
        JSONObject payload = null;
        try { payload = StrictJson.object(analysis.getString("payloadJson").getBytes(StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException invalid) { /* Missing structured facts are unknown, never zero. */ }
        JSONObject dimensions = ObservationReview.review(profile, analysis, status).getJSONObject("dimensions");
        JSONArray rules = requirements.getJSONObject("assessmentPolicy").getJSONArray("rules"), checked = new JSONArray();
        Set<String> matches = new HashSet<>(), cited = new HashSet<>();
        JSONArray ids = assessment.getJSONArray("ruleIds");
        for (int i = 0; i < ids.length(); i++) cited.add(ids.getString(i));
        boolean supported = false, conflictingUnknown = false;
        for (int r = 0; r < rules.length(); r++) {
            JSONObject rule = rules.getJSONObject(r);
            if (!profile.getString("id").equals(rule.getString("profileId"))) continue;
            String outcome = "matched";
            JSONArray conditions = rule.getJSONArray("conditions");
            for (int c = 0; c < conditions.length(); c++) {
                JSONObject condition = conditions.getJSONObject(c);
                Object root = condition.getString("source").equals("record") ? record
                        : condition.getString("source").equals("payload") ? payload : dimensions;
                String result = condition(root, condition);
                if (result.equals("not-matched")) { outcome = result; break; }
                if (result.equals("unknown")) outcome = result;
            }
            checked.put(new JSONObject().put("ruleId", rule.getString("ruleId")).put("status", rule.getString("status")).put("result", outcome));
            if (outcome.equals("unknown") && !status.equals(rule.getString("status"))) conflictingUnknown = true;
            if (outcome.equals("matched")) {
                matches.add(rule.getString("status"));
                if (cited.contains(rule.getString("ruleId")) && status.equals(rule.getString("status"))) supported = true;
            }
        }
        if (definitive && matches.size() > 1) throw new StoreException("REPORT_ASSESSMENT_CONFLICT");
        if (definitive && (!supported || conflictingUnknown)) throw new StoreException("REPORT_ASSESSMENT_UNSUPPORTED");
        return review.put("status", definitive ? "predicates-matched" : "review-required")
                .put("checks", checked).put("reason", definitive ? "EXPLANATION_NOT_INDEPENDENTLY_REVIEWED" : "NO_DEFINITIVE_BUSINESS_ASSESSMENT");
    }

    private static String condition(Object root, JSONObject condition) {
        Object actual;
        try { actual = ConnectorConfig.pointer(root, condition.getString("path")); }
        catch (RuntimeException missing) { return "unknown"; }
        if (actual == null || actual == JSONObject.NULL || actual instanceof String && ((String)actual).isEmpty()) return "unknown";
        Object expected = condition.get("value");
        String op = condition.getString("operator");
        if (actual instanceof Number && expected instanceof Number) {
            if (!Double.isFinite(((Number)actual).doubleValue())) return "unknown";
            int compared = new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString()));
            boolean matched = op.equals("eq") ? compared == 0 : op.equals("gt") ? compared > 0 : op.equals("gte") ? compared >= 0
                    : op.equals("lt") ? compared < 0 : compared <= 0;
            return matched ? "matched" : "not-matched";
        }
        if (!op.equals("eq") || actual.getClass() != expected.getClass()) return "unknown";
        return actual.equals(expected) ? "matched" : "not-matched";
    }
}
