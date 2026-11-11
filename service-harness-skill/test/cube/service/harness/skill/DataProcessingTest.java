package cube.service.harness.skill;

import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent data fixtures exercise counting semantics, exact provenance, scope and missing values. */
public final class DataProcessingTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void fails(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RuntimeException error) { check(code.equals(error.getMessage()), code + ": " + error.getMessage()); }
    }
    private static JSONObject profile() {
        return new JSONObject("{\"id\":\"summary\",\"connectorId\":\"records\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false},"
                + "\"filters\":[],\"deduplicate\":true,\"groupBy\":[\"region\"],\"aggregates\":[{\"name\":\"records\",\"operation\":\"count\"},{\"name\":\"total\",\"operation\":\"sum\",\"field\":\"amount\"},{\"name\":\"smallest\",\"operation\":\"min\",\"field\":\"amount\"},{\"name\":\"largest\",\"operation\":\"max\",\"field\":\"amount\"}]}");
    }
    private static JSONObject row(String id, Object amount) {
        return new JSONObject().put("recordId", id).put("recordVersion", "1").put("region", "north")
                .put("amount", amount).put("capturedAt", "2026-11-10T08:00:00+08:00");
    }
    private static JSONObject page(String name, String cursor, String next, JSONObject... rows) {
        JSONArray records = new JSONArray(), refs = new JSONArray();
        for (int i = 0; i < rows.length; i++) {
            JSONObject r = new JSONObject(rows[i].toString());
            String ref = name + "-media-" + i;
            records.put(r.put("mediaRef", ref));
            refs.put(new JSONObject().put("mediaRef", ref).put("recordId", r.getString("recordId"))
                    .put("mediaId", r.getString("recordId")).put("mediaVersion", "1").put("mediaType", "image/jpeg"));
        }
        return new JSONObject().put("pageRef", name).put("connectorId", "records").put("queryHash", "query")
                .put("snapshotId", "snapshot").put("cursor", cursor).put("nextCursor", next)
                .put("warnings", new JSONArray()).put("records", records).put("mediaRefs", refs);
    }
    private static JSONObject process(JSONObject profile, JSONObject... pages) {
        return DataProcessing.process(profile, new JSONObject(), new JSONArray(pages));
    }
    private static JSONObject values(JSONObject output) { return output.getJSONArray("groups").getJSONObject(0).getJSONObject("values"); }

    public static void main(String[] args) {
        JSONObject p = profile();
        JSONObject config = new JSONObject().put("version", "1").put("profiles", new JSONArray().put(p));
        DataProcessing.validate(config);
        JSONObject first = page("p1", "", "c1", row("a", "0.1"), row("b", "0.2"));
        JSONObject last = page("p2", "c1", "", row("a", "0.1"));
        JSONObject output = process(p, first, last);
        check(output.getJSONObject("coverage").getBoolean("queryComplete"), "full page chain");
        check(output.getJSONObject("stats").getInt("duplicateRecords") == 1, "exact cross-page duplicate");
        check(output.getJSONArray("records").getJSONObject(0).getJSONArray("sources").length() == 2, "all duplicate origins retained");
        check(values(output).getInt("records") == 2 && values(output).getString("total").equals("0.3"), "record count and exact decimal sum");
        check(values(output).getString("smallest").equals("0.1") && values(output).getString("largest").equals("0.2"), "numeric min/max");
        check(output.getJSONArray("groups").getJSONObject(0).getJSONArray("recordIndexes").length() == 2, "aggregates refer to full record indexes");
        check(process(p, first, last).similar(output), "deterministic processing");
        check(!process(p, first).getJSONObject("coverage").getBoolean("queryComplete"), "missing final page");
        check(!process(p, last).getJSONObject("coverage").getBoolean("queryComplete"), "missing first page");
        check(!process(p, last, first).getJSONObject("coverage").getBoolean("queryComplete"), "reversed pages cannot claim completion");
        JSONObject other = new JSONObject(last.toString()).put("snapshotId", "changed");
        fails("DATA_PROCESS_SCOPE_MISMATCH", () -> process(p, first, other));
        other.put("snapshotId", "snapshot").put("queryHash", "other");
        fails("DATA_PROCESS_SCOPE_MISMATCH", () -> process(p, first, other));
        fails("DATA_PROCESS_RECORD_CONFLICT", () -> process(p, first, page("p2", "c1", "", row("a", "99"))));
        JSONObject version = row("a", "0.1").put("recordVersion", "2");
        check(process(p, first, page("p2", "c1", "", version)).getJSONArray("records").length() == 3, "different versions are distinct");
        JSONObject missing = row("a", JSONObject.NULL); missing.remove("recordVersion");
        JSONObject unversioned = process(p, page("only", "", "", missing, missing));
        check(unversioned.getJSONArray("records").length() == 2, "unversioned records never guessed to be duplicate");
        check(values(unversioned).isNull("total") && unversioned.getJSONArray("groups").getJSONObject(0).getJSONObject("missingValues").getInt("total") == 2, "all missing values stay null with count");
        JSONObject noDedup = new JSONObject(p.toString()).put("deduplicate", false);
        check(process(noDedup, first, last).getJSONArray("records").length() == 3, "dedup optional");
        JSONObject filtered = new JSONObject(p.toString());
        filtered.put("filters", new JSONArray().put(new JSONObject().put("field", "amount").put("operator", "gte").put("value", "0.2")));
        check(process(filtered, first, last).getJSONObject("stats").getInt("filteredRecords") == 2, "numeric filtering before aggregation");
        fails("DATA_PROCESS_RECORD_CONFLICT", () -> process(filtered, first, page("conflict", "c1", "", row("a", "99"))));
        filtered.put("filters", new JSONArray().put(new JSONObject().put("field", "capturedAt").put("operator", "atOrAfter").put("value", "2026-11-10T00:00:00Z")));
        check(process(filtered, first).getJSONArray("records").length() == 2, "timestamp comparison honors offsets");
        filtered.getJSONArray("filters").getJSONObject(0).put("value", "2026-11-10T00:00:01Z");
        check(process(filtered, first).getJSONArray("records").length() == 0, "timestamp boundary");
        filtered.put("filters", new JSONArray().put(new JSONObject().put("field", "absent").put("operator", "ne").put("value", "x")));
        check(process(filtered, first).getJSONArray("warnings").toList().contains("MISSING_FILTER_VALUE"), "missing is disclosed and excluded");
        JSONObject all = new JSONObject(p.toString()).put("groupBy", new JSONArray());
        JSONObject empty = process(all, page("empty", "", ""));
        check(values(empty).getInt("records") == 0 && values(empty).isNull("total"), "empty count zero, sum unknown");
        fails("DATA_PROCESS_VALUE_INVALID", () -> process(p, page("only", "", "", row("x", "not-number"))));
        fails("DATA_PROCESS_VALUE_INVALID", () -> process(p, page("only", "", "", row("x", "1e999999"))));
        JSONObject invalidConfig = new JSONObject(config.toString());
        invalidConfig.getJSONArray("profiles").getJSONObject(0).put("script", "arbitrary code");
        fails("DATA_PROCESS_CONFIG_INVALID", () -> DataProcessing.validate(invalidConfig));
        JSONObject param = new JSONObject(p.toString());
        param.getJSONObject("inputSchema").getJSONObject("properties").put("region", new JSONObject().put("type", "string").put("maxLength", 128));
        param.getJSONObject("inputSchema").getJSONArray("required").put("region");
        param.put("filters", new JSONArray().put(new JSONObject().put("field", "region").put("operator", "eq").put("parameter", "region")));
        DataProcessing.validate(new JSONObject().put("version", "1").put("profiles", new JSONArray().put(param)));
        check(DataProcessing.process(param, new JSONObject().put("region", "north"), new JSONArray().put(first)).getJSONArray("records").length() == 2, "declared parameter");
        fails("SCHEMA_INVALID", () -> DataProcessing.process(param, new JSONObject(), new JSONArray().put(first)));
        JSONArray manyPages = new JSONArray();
        for (int page = 0; page < 9; page++) {
            JSONObject[] rows = new JSONObject[16];
            for (int i = 0; i < rows.length; i++) rows[i] = row("r" + page + "-" + i, "1").put("region", "region-" + page + "-" + i);
            manyPages.put(page("p" + page, page == 0 ? "" : "c" + page, page == 8 ? "" : "c" + (page + 1), rows));
        }
        fails("RESULT_BUDGET_EXCEEDED", () -> DataProcessing.process(p, new JSONObject(), manyPages));
        System.out.println("PASS M2 processing: " + assertions + " assertions; deterministic data fixtures, no model inference.");
    }
}
