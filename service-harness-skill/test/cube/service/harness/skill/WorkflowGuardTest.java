package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real M2 datasets and independent query/analysis facts; no model inference. */
public final class WorkflowGuardTest {
    static final Map<String, JSONObject> config = new HashMap<>();
    private static final WorkflowGuard guard = new WorkflowGuard(new Properties());
    private static int assertions;
    private static CompletionGuard.Resources resources() { return config::get; }
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static JSONObject profile(String file, String id) {
        return ConnectorConfig.select(config.get(file), "profiles", id);
    }
    private static JSONObject fact(String kind, String id, JSONObject value) {
        String operation = kind.equals("data-page") ? "common.data.query.v1" : kind.equals("data-set")
                ? "common.processing.process.v1" : "common.capability.invoke.v1";
        return new JSONObject().put("kind", kind).put("id", id).put("operation", operation).put("value", value);
    }
    static JSONObject page(String id, String cursor, String next, int... ids) {
        JSONArray rows = new JSONArray(), media = new JSONArray();
        for (int number : ids) {
            String ref = id + "-media-" + number;
            rows.put(new JSONObject().put("recordId", "r" + number).put("recordVersion", "1")
                    .put("locationId", "north").put("locationName", "North").put("mediaRef", ref));
            media.put(new JSONObject().put("mediaRef", ref).put("recordId", "r" + number)
                    .put("mediaId", "m" + number).put("mediaVersion", "1").put("mediaType", "image/jpeg"));
        }
        return fact("data-page", id, new JSONObject().put("pageRef", id).put("skill", "fhe/patrol")
                .put("connectorId", "patrol-records").put("queryHash", "q1").put("snapshotId", "s1")
                .put("cursor", cursor).put("nextCursor", next).put("cursorPosition", cursor).put("nextPosition", next)
                .put("records", rows).put("mediaRefs", media).put("warnings", new JSONArray()));
    }
    static JSONObject dataset(String id, JSONObject... pages) {
        JSONObject spec = profile("data-processing.json", "patrol-summary");
        JSONArray inputs = new JSONArray(), refs = new JSONArray();
        for (JSONObject page : pages) { inputs.put(page.getJSONObject("value")); refs.put(page.getString("id")); }
        JSONObject value = DataProcessing.process(spec, new JSONObject(), inputs)
                .put("connectorId", "patrol-records").put("pageRefs", refs)
                .put("profileHash", RuntimeSupport.sha(RuntimeSupport.bytes(spec)));
        return fact("data-set", id, value);
    }
    static JSONObject analysis(String ref) {
        return fact("analysis", ref, new JSONObject().put("mediaRef", ref).put("profileId", "describe")
                .put("profileHash", RuntimeSupport.sha(RuntimeSupport.bytes(profile("analysis-profiles.json", "describe")))));
    }
    private static JSONObject assess(JSONObject... evidence) throws Exception { return guard.check(resources(), new JSONArray(evidence)); }
    private static void gap(String code, JSONObject... evidence) throws Exception {
        JSONObject result = assess(evidence);
        check(!result.getBoolean("satisfied") && result.getJSONArray("missing").getJSONObject(0).getString("code").equals(code), code + ": " + result);
    }
    private static void invalid(Runnable change) throws Exception {
        JSONObject before = new JSONObject(config.get(CompletionGuard.FILE).toString());
        change.run();
        try { guard.validate(resources()); throw new AssertionError("invalid config accepted"); }
        catch (StoreException expected) { check(expected.code.equals("WORKFLOW_CONFIG_INVALID"), expected.code); }
        finally { config.put(CompletionGuard.FILE, before); }
    }
    public static void main(String[] args) throws Exception {
        Path source = Paths.get(args[0]);
        for (String file : Arrays.asList(CompletionGuard.FILE, "connectors.json", "data-processing.json", "analysis-profiles.json"))
            config.put(file, new JSONObject(new String(Files.readAllBytes(source.resolve(file)), StandardCharsets.UTF_8)));
        guard.validate(resources());
        gap("QUERY_REQUIRED");
        JSONObject first = page("p1", "", "c1", 1), last = page("p2", "c1", "", 1, 2);
        gap("QUERY_INCOMPLETE", first);
        check(assess(first).getJSONArray("missing").getJSONObject(0).getString("cursor").equals("c1"), "real continuation");
        gap("PROCESS_REQUIRED", first, last);
        gap("PROCESS_REQUIRED", first, last, dataset("partial", first));
        JSONObject data = dataset("d1", first, last);
        gap("ANALYSIS_REQUIRED", first, last, data);
        JSONObject analyzed1 = analysis("p1-media-1"), analyzed2 = analysis("p2-media-2");
        check(assess(first, last, data, analyzed1).getInt("missingCount") == 1, "one missing record");
        JSONObject good = assess(first, last, data, analyzed1, analyzed2);
        check(good.getBoolean("satisfied") && good.getInt("processedRecords") == 2 && good.getInt("confirmedAnalyses") == 2, "complete actual chain");
        check(assess(first, last, data, analysis("p2-media-1"), analyzed2).getBoolean("satisfied"), "any exact duplicate origin suffices");
        JSONObject wrong = analysis("p1-media-1"); wrong.getJSONObject("value").put("profileHash", "wrong");
        gap("ANALYSIS_REQUIRED", first, last, data, wrong, analyzed2);
        wrong.getJSONObject("value").put("profileId", "people");
        gap("ANALYSIS_REQUIRED", first, last, data, wrong, analyzed2);
        JSONObject wrongOperation = analysis("p1-media-1").put("operation", "model.authored");
        gap("ANALYSIS_REQUIRED", first, last, data, wrongOperation, analyzed2);
        JSONObject otherScope = page("other", "", "", 9); otherScope.getJSONObject("value").put("queryHash", "q2");
        check(!assess(first, last, data, analyzed1, analyzed2, otherScope).getBoolean("satisfied"), "another completed query cannot conceal an unprocessed scope");
        JSONObject wrongData = new JSONObject(data.toString());
        wrongData.getJSONObject("value").getJSONObject("coverage").put("snapshotId", "other");
        gap("PROCESS_REQUIRED", first, last, wrongData, analyzed1, analyzed2);
        wrongData.getJSONObject("value").getJSONObject("coverage").put("snapshotId", "s1");
        wrongData.getJSONObject("value").put("connectorId", "uploaded-records");
        gap("PROCESS_REQUIRED", first, last, wrongData, analyzed1, analyzed2);
        JSONObject empty = page("empty", "", "");
        check(assess(empty, dataset("empty-dataset", empty)).getBoolean("satisfied"), "empty confirmed query plus processing needs no analysis");
        JSONObject conflict = page("conflict", "", "c1", 3);
        gap("QUERY_PAGE_CONFLICT", first, conflict, last, data, analyzed1, analyzed2);
        // Retrying a page changes capability refs/cursors, but not its upstream page position.
        JSONObject replay = page("replay", "", "new-c1", 1);
        replay.getJSONObject("value").put("nextPosition", "c1");
        JSONObject replayLast = page("replay-last", "new-c1", "", 1, 2);
        replayLast.getJSONObject("value").put("cursorPosition", "c1");
        JSONObject alternative = dataset("d2", replay, replayLast);
        check(assess(first, last, replay, replayLast, data, alternative, analysis("replay-media-1"), analysis("replay-last-media-2"))
                .getBoolean("satisfied"), "choose a fully analyzed equivalent dataset across page retries");
        int[] ids = new int[40]; for (int i = 0; i < ids.length; i++) ids[i] = i;
        JSONObject many = page("many", "", "", ids), missing = assess(many, dataset("many-dataset", many));
        check(!missing.getBoolean("satisfied") && missing.getInt("missingCount") == 40 && missing.getJSONArray("missing").length() == 32, "feedback truncation cannot waive gaps");
        invalid(() -> config.get(CompletionGuard.FILE).put("maxRepairs", 3));
        invalid(() -> config.get(CompletionGuard.FILE).put("maxRepairs", 1.5));
        invalid(() -> config.get(CompletionGuard.FILE).put("script", "arbitrary"));
        invalid(() -> config.get(CompletionGuard.FILE).getJSONArray("pipelines").getJSONObject(0).put("processingProfileId", "uploaded-summary"));
        invalid(() -> config.get(CompletionGuard.FILE).getJSONArray("pipelines").getJSONObject(0).put("analysisProfileIds", new JSONArray().put("unknown")));
        invalid(() -> config.get(CompletionGuard.FILE).getJSONArray("pipelines").remove(0));
        Properties ceiling = new Properties(); ceiling.setProperty("workflow.max.repairs", "1");
        check(new WorkflowGuard(ceiling).check(resources(), new JSONArray()).getInt("maxRepairs") == 1, "deployment repair ceiling");
        System.out.println("PASS M3 workflow rules: " + assertions + " assertions; no model inference.");
    }
}
