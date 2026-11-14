package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.store.StoreException;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static cube.service.harness.skill.WorkflowGuardTest.*;

/** Independent scope/coverage mutations and real processing; does not call a model. */
public final class FixedPatrolTest {
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        Path source = Paths.get(args[0]);
        for (String file : Arrays.asList(CompletionGuard.FILE, CompletionGuard.REPORT_FILE, "connectors.json", "data-processing.json", "analysis-profiles.json", "report-submission.schema.json"))
            config.put(file, new JSONObject(new String(Files.readAllBytes(source.resolve(file)), "UTF-8")));
        JSONObject execution = new JSONObject().put("version", "fixed-patrol-v1").put("maxPages", 4)
                .put("bindings", new JSONArray().put(new JSONObject().put("profileId", "describe")
                        .put("attribute", "scene-description").put("dimension", "visible-scene").put("content", "仅描述可见场景")));
        config.get(CompletionGuard.FILE).put("execution", execution);
        CompletionGuard.Resources resources = new CompletionGuard.Resources() {
            public JSONObject json(String path) { return config.get(path); }
            public byte[] bytes(String path) throws Exception { return Files.readAllBytes(source.resolve(path)); }
        };
        WorkflowGuard guard = new WorkflowGuard(new Properties()); guard.validate(resources);
        check(guard.advance(resources, new JSONArray(), "fhe/patrol").getString("kind").equals("planning"), "unplanned scope offers only a constrained model query, never executes");
        JSONObject arguments = new JSONObject().put("skill", "fhe/patrol").put("connectorId", "patrol-records")
                .put("argumentsJson", "{\"regionId\":\"西区\",\"from\":\"2026-11-13T00:00:00+08:00\",\"to\":\"2026-11-14T00:00:00+08:00\"}");
        JSONObject first = page("first", "", "complete-opaque-cursor", 1).put("arguments", arguments);
        JSONArray evidence = new JSONArray().put(first);
        JSONObject next = guard.advance(resources, evidence, "fhe/patrol").getJSONObject("call");
        JSONObject bound = new JSONObject(next.getString("argumentsJson"));
        check(bound.getString("cursor").equals("complete-opaque-cursor") && bound.getString("argumentsJson").equals(arguments.getString("argumentsJson")), "cursor and scope copied exactly at distinct levels");
        try { guard.advance(resources, new JSONArray().put(first).put(first), "fhe/patrol"); throw new AssertionError("repeated first page"); }
        catch (StoreException expected) { check("WORKFLOW_NO_PROGRESS".equals(expected.code), expected.code); }
        JSONObject last = page("last", "complete-opaque-cursor", "", 1, 2).put("arguments", arguments);
        evidence.put(last);
        check(guard.advance(resources, evidence, "fhe/patrol").getJSONObject("call").getString("tool").equals("common.processing.process"), "complete coverage precedes processing");
        JSONObject data = dataset("deduplicated", first, last); evidence.put(data);
        check(data.getJSONObject("value").getJSONArray("records").length() == 2, "real processing deduplicates three source records");
        next = guard.advance(resources, evidence, "fhe/patrol").getJSONObject("call");
        check(next.getString("tool").equals("common.capability.invoke"), "each missing record requires confirmed analysis");
        JSONArray rows = data.getJSONObject("value").getJSONArray("records");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); String ref = row.getJSONArray("sources").getJSONObject(0).getString("mediaRef");
            JSONObject fact = ReportValidatorTest.analysisFact(ref, new JSONObject(row.getJSONObject("record").toString()));
            JSONObject origin = row.getJSONArray("sources").getJSONObject(0);
            fact.getJSONObject("value").getJSONObject("source").put("mediaId", origin.getString("mediaId"))
                    .put("mediaVersion", origin.getString("mediaVersion")).put("captureTimeTrust", origin.getString("timeTrust"));
            evidence.put(fact);
        }
        JSONObject finalDecision = guard.advance(resources, evidence, "fhe/patrol");
        check(finalDecision.getString("kind").equals("final"), "report constructed only after all analyses");
        check(guard.checkFinal(resources, evidence, finalDecision, "fhe/patrol", Collections.singleton("fhe/patrol")).getBoolean("satisfied"), "constructed report passes independent source and reference validator");
        execution.getJSONArray("bindings").getJSONObject(0).put("attribute", "unique-people");
        try { guard.validate(resources); throw new AssertionError("invented unique count capability"); }
        catch (StoreException expected) { check("WORKFLOW_CONFIG_INVALID".equals(expected.code), expected.code); }
        System.out.println("PASS fixed patrol: exact scope/cursor, duplicate detection, real deduplication, per-record analysis, constructed citations and rejected unverified dimensions.");
    }
}
