package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static cube.service.harness.skill.WorkflowGuardTest.*;

/** Independent submission mutations against real data-processing data and host-style confirmed facts. */
public final class ReportValidatorTest {
    private static CompletionGuard.Resources resources;
    private static JSONArray evidence;
    private static JSONObject valid;
    private static int checks;
    private static final WorkflowGuard guard = new WorkflowGuard(new Properties());
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    private static JSONObject run(JSONObject submission) throws Exception {
        return guard.checkFinal(resources, evidence, new JSONObject().put("kind", "final").put("text", submission.toString()), "fhe/patrol", Collections.singleton("fhe/patrol"));
    }
    private static JSONObject finding(JSONObject submission) {
        return submission.getJSONArray("reports").getJSONObject(0).getJSONArray("datasets").getJSONObject(0).getJSONArray("findings").getJSONObject(0);
    }
    private static void rejects(String code, java.util.function.Consumer<JSONObject> mutation) throws Exception {
        JSONObject changed = copy(valid); mutation.accept(changed);
        JSONObject result = run(changed);
        check(!result.getBoolean("satisfied") && result.getJSONArray("missing").toString().contains(code), code + ": " + result);
    }
    static JSONObject analysisFact(String ref, JSONObject record) {
        JSONObject fact = analysis(ref), value = fact.getJSONObject("value");
        JSONObject out = new JSONObject().put("profileId", "describe").put("mediaRef", ref).put("unit", "Fixture")
                .put("answer", "Fixture observation").put("payloadJson", "{\"fixture\":true}").put("warnings", new JSONArray().put("OBSERVATION_ONLY"));
        String digest = RuntimeSupport.sha(RuntimeSupport.bytes(out));
        JSONObject provenance = new JSONObject().put("profileId", "describe").put("mediaRef", ref)
                .put("mediaId", "m1").put("mediaVersion", "1").put("mediaType", "image/jpeg").put("sha256", String.join("", Collections.nCopies(64,"a")))
                .put("captureTimeTrust", "provider-declared").put("source", record).put("analysisResultSha256", digest);
        value.put("analysisResultSha256", digest).put("source", provenance)
                .put("artifact", new JSONObject().put("artifactId", "trusted-artifact").put("fileName", "media-source.json").put("sha256", String.join("",Collections.nCopies(64,"b"))));
        return fact.put("result", out);
    }
    public static void main(String[] args) throws Exception {
        Path source = Paths.get(args[0]);
        for (String file : Arrays.asList(CompletionGuard.FILE, CompletionGuard.REPORT_FILE, "connectors.json", "data-processing.json", "analysis-profiles.json", "report-submission.schema.json"))
            config.put(file, StrictJson.object(Files.readAllBytes(source.resolve(file))));
        resources = new CompletionGuard.Resources() {
            public JSONObject json(String path) { return config.get(path); }
            public byte[] bytes(String path) throws Exception { return Files.readAllBytes(source.resolve(path)); }
        };
        guard.validate(resources); guard.validateReport(resources);
        JSONObject page = page("p1", "", "", 1), data = dataset("d1", page);
        JSONObject record = data.getJSONObject("value").getJSONArray("records").getJSONObject(0).getJSONObject("record");
        record.put("capturedAt", JSONObject.NULL);
        // Keep the independent source page and dataset consistent after adding unknown time.
        page.getJSONObject("value").getJSONArray("records").getJSONObject(0).put("capturedAt", JSONObject.NULL);
        JSONObject actual = analysisFact("p1-media-1", copy(record));
        evidence = new JSONArray().put(page).put(data).put(actual);
        JSONObject assessment = new JSONObject().put("status", "not-assessed").put("reason", "No operational rule evaluated").put("ruleIds", new JSONArray());
        JSONObject finding = new JSONObject().put("recordIndex", 0).put("profileId", "describe").put("mediaRef", "p1-media-1")
                .put("observation", "Fixture observation").put("assessment", assessment);
        valid = new JSONObject().put("schemaVersion", 1).put("reports", new JSONArray().put(new JSONObject().put("skill", "fhe/patrol")
                .put("summary", "One fixture record").put("limitations", new JSONArray().put("Unknown capture time"))
                .put("datasets", new JSONArray().put(new JSONObject().put("datasetRef", "d1").put("findings", new JSONArray().put(finding))))));
        JSONObject normalized = run(valid).getJSONObject("report");
        JSONObject derived = normalized.getJSONArray("datasets").getJSONObject(0).getJSONArray("findings").getJSONObject(0);
        check(derived.getJSONObject("record").isNull("capturedAt"), "unknown time stays null");
        check(derived.getJSONObject("citation").getString("captureTimeTrust").equals("provider-declared"), "uploaded time not upgraded");
        check(derived.getJSONObject("citation").getString("artifactId").equals("trusted-artifact"), "artifact derived by host");
        check(normalized.getJSONArray("datasets").getJSONObject(0).getInt("recordCount") == 1, "actual record count");
        check(!derived.getJSONObject("assessment").getBoolean("semanticallyVerified"), "model judgment not labeled deterministic");
        JSONObject repeated = analysisFact("p1-media-1", copy(record)).put("id", "another-analysis");
        JSONObject repeatedResult = repeated.getJSONObject("result"); repeatedResult.put("answer", "A second confirmed observation");
        String repeatedHash = RuntimeSupport.sha(RuntimeSupport.bytes(repeatedResult));
        repeated.getJSONObject("value").put("analysisResultSha256", repeatedHash).getJSONObject("source").put("analysisResultSha256", repeatedHash);
        evidence.put(repeated);
        JSONObject repeatedSubmission = copy(valid); finding(repeatedSubmission).put("observation", "A second confirmed observation");
        check(run(repeatedSubmission).getBoolean("satisfied"), "any genuinely confirmed repeated answer can be cited");
        JSONObject multiple = copy(valid), other = copy(valid.getJSONArray("reports").getJSONObject(0)).put("skill", "other/report");
        multiple.getJSONArray("reports").put(other);
        check(guard.checkFinal(resources, evidence, new JSONObject().put("text", multiple.toString()), "fhe/patrol",
                new TreeSet<>(Arrays.asList("fhe/patrol", "other/report"))).getBoolean("satisfied"), "multiple active report skills are scoped independently");
        check(!guard.checkFinal(resources, evidence, new JSONObject().put("text", valid.toString()), "fhe/patrol",
                new TreeSet<>(Arrays.asList("fhe/patrol", "other/report"))).getBoolean("satisfied"), "missing active skill rejected");
        evidence = new JSONArray().put(page).put(data).put(actual);
        rejects("REPORT_FORMAT_INVALID", value -> value.put("unknown", true));
        rejects("REPORT_FORMAT_INVALID", value -> finding(value).put("capturedAt", "2026-11-11T13:43:00+08:00"));
        rejects("REPORT_FORMAT_INVALID", value -> finding(value).put("count", 3));
        rejects("REPORT_FORMAT_INVALID", value -> finding(value).put("confidence", 0.99));
        rejects("REPORT_FORMAT_INVALID", value -> finding(value).put("recordIndex", "0"));
        rejects("REPORT_FORMAT_INVALID", value -> value.remove("reports"));
        rejects("REPORT_SKILL_SET_INVALID", value -> value.getJSONArray("reports").getJSONObject(0).put("skill", "another/skill"));
        rejects("REPORT_SKILL_SET_INVALID", value -> value.getJSONArray("reports").put(copy(value.getJSONArray("reports").getJSONObject(0))));
        rejects("REPORT_OBSERVATION_MISMATCH", value -> finding(value).put("observation", "There are exactly three people"));
        rejects("REPORT_CITATION_INVALID", value -> finding(value).put("mediaRef", "forged"));
        rejects("REPORT_CITATION_INVALID", value -> finding(value).put("recordIndex", 1));
        rejects("REPORT_CITATION_INVALID", value -> finding(value).put("profileId", "people"));
        rejects("REPORT_CITATION_INVALID", value -> value.getJSONArray("reports").getJSONObject(0).getJSONArray("datasets").getJSONObject(0).put("datasetRef", "another-run"));
        rejects("REPORT_CITATION_INVALID", value -> value.getJSONArray("reports").getJSONObject(0).getJSONArray("datasets").getJSONObject(0).getJSONArray("findings").put(copy(finding(value))));
        rejects("REPORT_FINDINGS_INCOMPLETE", value -> value.getJSONArray("reports").getJSONObject(0).getJSONArray("datasets").getJSONObject(0).put("findings", new JSONArray()));
        rejects("REPORT_DATASETS_INCOMPLETE", value -> value.getJSONArray("reports").getJSONObject(0).put("datasets", new JSONArray()));
        rejects("REPORT_RULE_REQUIRED", value -> finding(value).getJSONObject("assessment").put("status", "abnormal"));
        rejects("REPORT_RULE_INVALID", value -> finding(value).getJSONObject("assessment").getJSONArray("ruleIds").put("invented-rule"));
        JSONObject ruled = copy(valid); finding(ruled).getJSONObject("assessment").put("status", "review-required").getJSONArray("ruleIds").put("patrol-observation-rules");
        check(run(ruled).getBoolean("satisfied"), "fixed rule citation accepted");
        JSONObject definitive = copy(ruled); finding(definitive).getJSONObject("assessment").put("status", "abnormal");
        JSONObject requirementsBefore = copy(config.get(CompletionGuard.REPORT_FILE));
        JSONObject strict = copy(requirementsBefore).put("version", "2")
                .put("assessmentPolicy", new JSONObject().put("version", "evidence-rules-v1").put("rules", new JSONArray()));
        config.put(CompletionGuard.REPORT_FILE, strict);
        check(!run(definitive).getBoolean("satisfied") && run(definitive).toString().contains("REPORT_ASSESSMENT_UNSUPPORTED"), "a valid natural-language rule citation does not prove abnormality");
        JSONObject predicate = new JSONObject().put("ruleId", "patrol-observation-rules").put("profileId", "describe").put("status", "abnormal")
                .put("conditions", new JSONArray().put(new JSONObject().put("source", "payload").put("path", "/fixture").put("operator", "eq").put("value", true)));
        strict.getJSONObject("assessmentPolicy").getJSONArray("rules").put(predicate);
        guard.validateReport(resources);
        JSONObject checkedAssessment = run(definitive).getJSONObject("report").getJSONArray("datasets").getJSONObject(0)
                .getJSONArray("findings").getJSONObject(0).getJSONObject("assessment");
        check(checkedAssessment.getJSONObject("evidenceReview").getString("status").equals("predicates-matched")
                && !checkedAssessment.getBoolean("semanticallyVerified"), "confirmed predicate is checked without certifying generated explanation");
        check(run(valid).getJSONObject("report").getJSONObject("summaryReview").getString("status").equals("review-required"), "generated summary always carries independent review status");
        strict.getJSONObject("assessmentPolicy").getJSONArray("rules").put(copy(predicate).put("status", "normal"));
        check(!run(definitive).getBoolean("satisfied") && run(definitive).toString().contains("REPORT_ASSESSMENT_CONFLICT"), "conflicting rules cannot be cherry-picked");
        config.put(CompletionGuard.REPORT_FILE, requirementsBefore);
        actual.getJSONObject("value").getJSONObject("source").put("analysisResultSha256", "wrong");
        check(!run(valid).getBoolean("satisfied"), "tampered analysis digest rejected");
        actual.getJSONObject("value").remove("source");
        check(!run(valid).getBoolean("satisfied"), "legacy workflow fact cannot impersonate report provenance");
        JSONObject requirements = config.get(CompletionGuard.REPORT_FILE);
        String saved = requirements.getJSONArray("rules").getJSONObject(0).getString("sha256");
        requirements.getJSONArray("rules").getJSONObject(0).put("sha256", String.join("",Collections.nCopies(64,"0")));
        try { guard.validateReport(resources); throw new AssertionError("wrong rule hash"); }
        catch (StoreException expected) { check(expected.code.equals("REPORT_CONFIG_INVALID"), "rule hash mismatch fails publication"); }
        requirements.getJSONArray("rules").getJSONObject(0).put("sha256", saved);
        JSONObject emptyPage = page("empty", "", ""), emptyDataset = dataset("empty-data", emptyPage);
        evidence = new JSONArray().put(emptyPage).put(emptyDataset);
        JSONObject emptySubmission = copy(valid); emptySubmission.getJSONArray("reports").getJSONObject(0).put("datasets",new JSONArray().put(new JSONObject().put("datasetRef","empty-data").put("findings",new JSONArray())));
        check(run(emptySubmission).getBoolean("satisfied"), "empty confirmed dataset report is valid");
        System.out.println("PASS report: " + checks + " assertions; structure, provenance, completeness, rules, unknown capture time; no live inference.");
    }
}
