package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/** M3: fixed business requirements checked against confirmed query, dataset and analysis facts. */
public final class WorkflowGuard implements CompletionGuard {
    private final int repairCeiling;

    public WorkflowGuard(Properties properties) {
        repairCeiling = Integer.parseInt(properties.getProperty("workflow.max.repairs", "2"));
        if (repairCeiling < 0 || repairCeiling > 2) throw new IllegalArgumentException("WORKFLOW_REPAIR_CONFIG_INVALID");
    }

    @Override public boolean supportsReports() { return true; }
    @Override public void validateReport(Resources resources) throws Exception { ReportValidator.validate(resources); }
    @Override public JSONObject advance(Resources resources, JSONArray evidence, String skill) throws Exception {
        return FixedPatrol.advance(this, resources, evidence, skill);
    }

    @Override public JSONObject checkFinal(Resources resources, JSONArray evidence, JSONObject decision,
                                            String skill, Set<String> reportSkills) throws Exception {
        JSONObject workflow = check(resources, evidence);
        JSONObject report = ReportValidator.check(resources, evidence, decision, skill, reportSkills, workflow);
        JSONArray missing = workflow.getJSONArray("missing"), reportMissing = report.getJSONArray("missing");
        for (int i = 0; i < reportMissing.length() && missing.length() < 32; i++) missing.put(reportMissing.get(i));
        workflow.put("missingCount", workflow.getInt("missingCount") + report.getInt("missingCount"))
                .put("satisfied", workflow.getBoolean("satisfied") && report.getBoolean("satisfied"));
        if (workflow.getBoolean("satisfied")) workflow.put("report", report.getJSONObject("report"));
        return workflow;
    }

    @Override
    public void validate(Resources resources) throws Exception {
        try {
            JSONObject requirements = resources.json(FILE);
            ConnectorConfig.keys(requirements, "version,maxRepairs,pipelines", "execution");
            require("1".equals(requirements.getString("version")));
            int repairs = requirements.getInt("maxRepairs");
            require(repairs >= 0 && repairs <= 2 && requirements.get("maxRepairs") instanceof Number
                    && requirements.getDouble("maxRepairs") == repairs);
            JSONObject connectors = resources.json("connectors.json");
            JSONObject processing = resources.json("data-processing.json");
            JSONObject analysis = resources.json("analysis-profiles.json");
            ConnectorConfig.validate(connectors);
            DataProcessing.validate(processing);
            AnalysisProfiles.validate(analysis);
            JSONArray pipelines = requirements.getJSONArray("pipelines");
            require(pipelines.length() > 0 && pipelines.length() <= 8);
            Set<String> ids = new HashSet<>(), mapped = new HashSet<>();
            for (int i = 0; i < pipelines.length(); i++) {
                JSONObject pipeline = pipelines.getJSONObject(i);
                ConnectorConfig.keys(pipeline, "id,connectorId,processingProfileId,analysisProfileIds", "");
                require(ids.add(id(pipeline.getString("id"))));
                String connector = id(pipeline.getString("connectorId"));
                require(mapped.add(connector));
                ConnectorConfig.select(connectors, "connectors", connector);
                JSONObject profile = ConnectorConfig.select(processing, "profiles", id(pipeline.getString("processingProfileId")));
                require(connector.equals(profile.getString("connectorId")));
                JSONArray profiles = pipeline.getJSONArray("analysisProfileIds");
                require(profiles.length() <= 8);
                Set<String> names = new HashSet<>();
                for (int j = 0; j < profiles.length(); j++) {
                    String name = id(profiles.getString(j));
                    require(names.add(name));
                    ConnectorConfig.select(analysis, "profiles", name);
                }
            }
            // Every selectable connector must have requirements: an extra unguarded route cannot bypass M3.
            require(mapped.size() == connectors.getJSONArray("connectors").length());
            FixedPatrol.validate(requirements, analysis);
        } catch (Exception error) {
            if (error instanceof StoreException && ((StoreException) error).code.startsWith("STORE_")) throw error;
            throw new StoreException("WORKFLOW_CONFIG_INVALID");
        }
    }

    private static void require(boolean value) { if (!value) throw new IllegalArgumentException(); }
    private static String id(String value) {
        require(value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"));
        return value;
    }
    private static String scope(JSONObject page) {
        return RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray().put(page.getString("connectorId"))
                .put(page.getString("queryHash")).put(page.getString("snapshotId"))));
    }

    private static final class Gaps {
        int count;
        final JSONArray items = new JSONArray();
        void add(String code, JSONObject details) {
            count++;
            if (items.length() < 32) items.put(details.put("code", code));
        }
    }

    /** Stable page content fingerprint ignores per-call mediaRef identities. */
    private static String content(JSONObject page) {
        JSONArray rows = new JSONArray();
        JSONArray records = page.getJSONArray("records"), media = page.getJSONArray("mediaRefs");
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = new JSONObject(records.getJSONObject(i).toString());
            record.remove("mediaRef");
            JSONObject origin = new JSONObject(media.getJSONObject(i).toString());
            origin.remove("mediaRef");
            rows.put(new JSONObject().put("record", record).put("media", origin));
        }
        return RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject().put("rows", rows)
                .put("next", page.optString("nextPosition", page.getString("nextCursor")))));
    }

    private static String position(JSONObject page) { return page.optString("cursorPosition", page.getString("cursor")); }
    private static String nextPosition(JSONObject page) { return page.optString("nextPosition", page.getString("nextCursor")); }

    private static boolean analyzed(JSONObject row, String profile, String hash, Set<String> analyses) {
        JSONArray sources = row.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            if (analyses.contains(RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray()
                    .put(sources.getJSONObject(i).getString("mediaRef")).put(profile).put(hash))))) return true;
        }
        return false;
    }

    private static int missingAnalyses(JSONObject dataset, JSONArray profiles, JSONObject analysis, Set<String> analyses) {
        int missing = 0;
        JSONArray rows = dataset.getJSONArray("records");
        for (int i = 0; i < profiles.length(); i++) {
            String id = profiles.getString(i);
            String hash = RuntimeSupport.sha(RuntimeSupport.bytes(ConnectorConfig.select(analysis, "profiles", id)));
            for (int j = 0; j < rows.length(); j++) if (!analyzed(rows.getJSONObject(j), id, hash, analyses)) missing++;
        }
        return missing;
    }

    private static JSONObject detail(JSONObject pipeline, JSONObject page) {
        return new JSONObject().put("pipelineId", pipeline.getString("id"))
                .put("connectorId", pipeline.getString("connectorId"))
                .put("queryHash", page.getString("queryHash")).put("snapshotId", page.getString("snapshotId"));
    }

    @Override
    public JSONObject check(Resources resources, JSONArray evidence) throws Exception {
        JSONObject requirements = resources.json(FILE);
        JSONObject processing = resources.json("data-processing.json"), analysis = resources.json("analysis-profiles.json");
        Map<String, JSONObject> pipelines = new TreeMap<>();
        JSONArray configured = requirements.getJSONArray("pipelines");
        for (int i = 0; i < configured.length(); i++) {
            JSONObject pipeline = configured.getJSONObject(i);
            pipelines.put(pipeline.getString("connectorId"), pipeline);
        }
        Map<String, List<JSONObject>> groups = new TreeMap<>();
        List<JSONObject> datasets = new ArrayList<>();
        Set<String> analyses = new HashSet<>();
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject fact = evidence.getJSONObject(i), value = fact.getJSONObject("value");
            String operation = fact.getString("operation"), kind = fact.getString("kind");
            if ("data-page".equals(kind) && "common.data.query.v1".equals(operation)) {
                if (!pipelines.containsKey(value.getString("connectorId"))) throw new StoreException("WORKFLOW_EVIDENCE_INVALID");
                JSONObject page = new JSONObject(value.toString()).put("pageRef", fact.getString("id"));
                groups.computeIfAbsent(scope(page), key -> new ArrayList<>()).add(page);
            } else if ("data-set".equals(kind) && "common.processing.process.v1".equals(operation)) {
                datasets.add(new JSONObject(value.toString()).put("datasetRef", fact.getString("id")));
            } else if ("analysis".equals(kind) && "common.capability.invoke.v1".equals(operation)) {
                analyses.add(RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray().put(value.getString("mediaRef"))
                        .put(value.getString("profileId")).put(value.getString("profileHash")))));
            }
        }
        Gaps gaps = new Gaps();
        JSONArray datasetRefs = new JSONArray();
        int records = 0, requiredAnalyses = 0, confirmedAnalyses = 0;
        if (groups.isEmpty()) gaps.add("QUERY_REQUIRED", new JSONObject().put("connectorIds", new JSONArray(pipelines.keySet())));
        for (List<JSONObject> pages : groups.values()) {
            JSONObject first = pages.get(0), pipeline = pipelines.get(first.getString("connectorId"));
            Map<String, JSONObject> byRef = new HashMap<>(), byCursor = new HashMap<>();
            boolean conflict = false;
            for (JSONObject page : pages) {
                byRef.put(page.getString("pageRef"), page);
                JSONObject previous = byCursor.putIfAbsent(position(page), page);
                if (previous != null && !content(previous).equals(content(page))) conflict = true;
            }
            if (conflict) { gaps.add("QUERY_PAGE_CONFLICT", detail(pipeline, first)); continue; }
            JSONObject profile = ConnectorConfig.select(processing, "profiles", pipeline.getString("processingProfileId"));
            String profileHash = RuntimeSupport.sha(RuntimeSupport.bytes(profile));
            JSONObject chosen = null;
            int fewestMissing = Integer.MAX_VALUE;
            for (JSONObject dataset : datasets) {
                if (!dataset.has("pageRefs") || !dataset.has("connectorId")
                        || !dataset.getString("connectorId").equals(first.getString("connectorId"))
                        || !dataset.getString("profileHash").equals(profileHash)) continue;
                JSONObject coverage = dataset.getJSONObject("coverage");
                if (!coverage.getBoolean("queryComplete") || !coverage.getString("queryHash").equals(first.getString("queryHash"))
                        || !coverage.getString("snapshotId").equals(first.getString("snapshotId"))) continue;
                Set<String> positions = new HashSet<>();
                boolean bound = true;
                JSONArray refs = dataset.getJSONArray("pageRefs");
                for (int i = 0; i < refs.length(); i++) {
                    JSONObject page = byRef.get(refs.getString(i));
                    if (page == null) { bound = false; break; }
                    positions.add(position(page));
                }
                if (bound && !positions.isEmpty() && positions.containsAll(byCursor.keySet())) {
                    int missing = missingAnalyses(dataset, pipeline.getJSONArray("analysisProfileIds"), analysis, analyses);
                    if (missing < fewestMissing) { chosen = dataset; fewestMissing = missing; }
                }
            }
            if (chosen == null) {
                // Provide a real continuation/ref list, never synthesize a tool execution.
                JSONArray refs = new JSONArray();
                Set<String> seen = new HashSet<>();
                String cursor = "";
                String continuation = "";
                while (byCursor.containsKey(cursor) && seen.add(cursor) && refs.length() < 64) {
                    JSONObject page = byCursor.get(cursor);
                    refs.put(page.getString("pageRef"));
                    continuation = page.getString("nextCursor");
                    cursor = nextPosition(page);
                    if (cursor.isEmpty()) break;
                }
                boolean complete = refs.length() > 0 && cursor.isEmpty() && seen.containsAll(byCursor.keySet());
                if (!complete) gaps.add("QUERY_INCOMPLETE", detail(pipeline, first).put("cursor", continuation));
                else gaps.add("PROCESS_REQUIRED", detail(pipeline, first).put("profileId", pipeline.getString("processingProfileId")).put("pageRefs", refs));
                continue;
            }
            datasetRefs.put(chosen.getString("datasetRef"));
            JSONArray rows = chosen.getJSONArray("records"), requiredProfiles = pipeline.getJSONArray("analysisProfileIds");
            records += rows.length();
            for (int rowIndex = 0; rowIndex < rows.length(); rowIndex++) {
                JSONObject row = rows.getJSONObject(rowIndex);
                JSONArray origins = row.getJSONArray("sources");
                for (int j = 0; j < requiredProfiles.length(); j++) {
                    requiredAnalyses++;
                    String profileId = requiredProfiles.getString(j);
                    JSONObject spec = ConnectorConfig.select(analysis, "profiles", profileId);
                    String analysisHash = RuntimeSupport.sha(RuntimeSupport.bytes(spec));
                    boolean found = analyzed(row, profileId, analysisHash, analyses);
                    if (found) confirmedAnalyses++;
                    else gaps.add("ANALYSIS_REQUIRED", detail(pipeline, first).put("profileId", profileId)
                            .put("datasetRef", chosen.getString("datasetRef")).put("recordId", row.getJSONObject("record").getString("recordId"))
                            .put("mediaRef", origins.getJSONObject(0).getString("mediaRef")));
                }
            }
        }
        return new JSONObject().put("satisfied", gaps.count == 0)
                .put("maxRepairs", Math.min(repairCeiling, requirements.getInt("maxRepairs")))
                .put("queryScopes", groups.size()).put("processedRecords", records)
                .put("datasetRefs", datasetRefs)
                .put("requiredAnalyses", requiredAnalyses).put("confirmedAnalyses", confirmedAnalyses)
                .put("missingCount", gaps.count).put("missing", gaps.items);
    }
}
