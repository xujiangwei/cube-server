/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * 业务工作流守卫：以宿主已确认的查询、数据集与分析事实为据，校验固定的业务需求是否闭环。
 *
 * <p>本类只读取证据账本中的事实，不信任模型自报结果；只有查询、处理、分析三段证据齐全
 * 才判定为满足，否则按缺口代码给出保守的补步提示。报告校验交由 {@link ReportValidator}，
 * 固定巡检推进交由 {@link FixedPatrol}。</p>
 */
public final class WorkflowGuard implements CompletionGuard {

    /**
     * 允许的最大修复次数上限，构造时由配置限定在 0 到 2 之间。
     */
    private final int repairCeiling;

    /**
     * 依据运行参数创建守卫。
     *
     * @param properties 运行参数；{@code workflow.max.repairs} 指定修复次数上限，缺省为 2。
     */
    public WorkflowGuard(Properties properties) {
        repairCeiling = Integer.parseInt(properties.getProperty("workflow.max.repairs", "2"));
        if (repairCeiling < 0 || repairCeiling > 2) {
            throw new IllegalArgumentException("WORKFLOW_REPAIR_CONFIG_INVALID");
        }
    }

    /**
     * 声明本守卫支持固定交互流程。
     *
     * @return 恒为 {@code true}。
     */
    @Override
    public boolean supportsInteraction() {
        return true;
    }

    /**
     * 声明本守卫支持报告校验。
     *
     * @return 恒为 {@code true}。
     */
    @Override
    public boolean supportsReports() {
        return true;
    }

    /**
     * 校验报告需求配置。
     *
     * @param resources 资源读取入口。
     * @throws Exception 配置非法或读取失败时抛出。
     */
    @Override
    public void validateReport(Resources resources) throws Exception {
        ReportValidator.validate(resources);
    }

    /**
     * 按固定巡检流程推进一步，返回宿主提案或终态，绝不返回模型决策。
     *
     * @param resources 资源读取入口。
     * @param evidence  宿主已确认的证据列表。
     * @param skill     当前技能名。
     * @return 宿主提案或终态对象。
     * @throws Exception 证据或配置不合法时抛出。
     */
    @Override
    public JSONObject advance(Resources resources, JSONArray evidence, String skill) throws Exception {
        if (RetrievalWorkflow.active(resources)) return RetrievalWorkflow.advance(resources, evidence, skill);
        return FixedPatrol.advance(this, resources, evidence, skill);
    }

    /**
     * 合并工作流检查与报告检查的最终结论。
     *
     * <p>先做工作流闭环检查，再做报告检查；把报告的缺口追加到工作流缺口（上限 32 条），
     * 累加缺口数并对满足标记取逻辑与。只有全部满足时才把报告对象写入结论。</p>
     *
     * @param resources    资源读取入口。
     * @param evidence     宿主已确认的证据列表。
     * @param decision     模型给出的决策。
     * @param skill        当前技能名。
     * @param reportSkills 允许提交报告的技能集合。
     * @return 合并后的最终结论。
     * @throws Exception 证据或配置不合法时抛出。
     */
    @Override
    public JSONObject checkFinal(Resources resources, JSONArray evidence, JSONObject decision,
                                 String skill, Set<String> reportSkills) throws Exception {
        JSONObject workflow = check(resources, evidence);
        JSONObject report = ReportValidator.check(resources, evidence, decision, skill, reportSkills, workflow);
        JSONArray missing = workflow.getJSONArray("missing");
        JSONArray reportMissing = report.getJSONArray("missing");
        for (int i = 0; i < reportMissing.length() && missing.length() < 32; i++) {
            missing.put(reportMissing.get(i));
        }
        workflow.put("missingCount", workflow.getInt("missingCount") + report.getInt("missingCount"))
                .put("satisfied", workflow.getBoolean("satisfied") && report.getBoolean("satisfied"));
        if (workflow.getBoolean("satisfied")) {
            workflow.put("report", report.getJSONObject("report"));
        }
        return workflow;
    }

    /**
     * 校验业务配置：需求文件、连接器、数据处理与分析档案、以及巡检绑定是否合法。
     *
     * <p>所有配置错误统一收敛为 {@code WORKFLOW_CONFIG_INVALID}；但存储层故障
     * （{@code STORE_} 前缀）须原样透传，以免被误判为配置问题。</p>
     *
     * @param resources 资源读取入口。
     * @throws Exception 配置非法或读取失败时抛出。
     */
    @Override
    public void validate(Resources resources) throws Exception {
        try {
            JSONObject requirements = resources.json(FILE);
            if (RetrievalWorkflow.active(resources)) {
                RetrievalWorkflow.validate(resources);
                return;
            }
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
            Set<String> ids = new HashSet<>();
            Set<String> mapped = new HashSet<>();
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
            // 每个可选连接器都必须有需求约束，否则多出的无约束路由会绕过工作流检查。
            require(mapped.size() == connectors.getJSONArray("connectors").length());
            FixedPatrol.validate(requirements, analysis);
        } catch (Exception error) {
            if (error instanceof StoreException && ((StoreException) error).code.startsWith("STORE_")) {
                throw error;
            }
            throw new StoreException("WORKFLOW_CONFIG_INVALID");
        }
    }

    /**
     * 条件不成立时拒绝。
     *
     * @param value 必须成立的条件。
     */
    private static void require(boolean value) {
        if (!value) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * 校验并返回符合资源 id 格式的值。
     *
     * @param value 待校验的 id。
     * @return 校验通过的 id 原值。
     */
    private static String id(String value) {
        require(value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"));
        return value;
    }

    /**
     * 计算查询域指纹，用于把同一查询的多页证据归为一组。
     *
     * @param page 页事实。
     * @return 查询域指纹。
     */
    private static String scope(JSONObject page) {
        return RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray()
                .put(page.getString("connectorId"))
                .put(page.getString("queryHash"))
                .put(page.getString("snapshotId"))));
    }

    /**
     * 缺口累计器：统计缺口总数，并保留最多 32 条缺口明细。
     */
    private static final class Gaps {
        /**
         * 缺口总数。
         */
        int count;

        /**
         * 缺口明细，最多 32 条。
         */
        final JSONArray items = new JSONArray();

        /**
         * 追加一条缺口。
         *
         * @param code    缺口代码。
         * @param details 缺口详情，会被写入 {@code code} 字段。
         */
        void add(String code, JSONObject details) {
            count++;
            if (items.length() < 32) {
                items.put(details.put("code", code));
            }
        }
    }

    /**
     * 计算稳定的页内容指纹：忽略每次调用各异的 mediaRef 身份，只比较记录与媒体来源的实质内容。
     *
     * @param page 页事实。
     * @return 内容指纹。
     */
    private static String content(JSONObject page) {
        JSONArray rows = new JSONArray();
        JSONArray records = page.getJSONArray("records");
        JSONArray media = page.getJSONArray("mediaRefs");
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = new JSONObject(records.getJSONObject(i).toString());
            record.remove("mediaRef");
            JSONObject origin = new JSONObject(media.getJSONObject(i).toString());
            origin.remove("mediaRef");
            rows.put(new JSONObject()
                    .put("record", record)
                    .put("media", origin));
        }
        return RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                .put("rows", rows)
                .put("next", page.optString("nextPosition", page.getString("nextCursor")))));
    }

    /**
     * 读取页当前的游标位置，优先取兼容字段。
     *
     * @param page 页事实。
     * @return 游标位置。
     */
    private static String position(JSONObject page) {
        return page.optString("cursorPosition", page.getString("cursor"));
    }

    /**
     * 读取页指向的下一位置，优先取兼容字段。
     *
     * @param page 页事实。
     * @return 下一位置；空串表示已到末页。
     */
    private static String nextPosition(JSONObject page) {
        return page.optString("nextPosition", page.getString("nextCursor"));
    }

    /**
     * 判断某条记录是否已被指定分析档案处理过。
     *
     * @param row      记录对象。
     * @param profile  分析档案 id。
     * @param hash     分析档案指纹。
     * @param analyses 已确认的分析指纹集合。
     * @return 已分析返回 {@code true}，否则 {@code false}。
     */
    private static boolean analyzed(JSONObject row, String profile, String hash, Set<String> analyses) {
        JSONArray sources = row.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            if (analyses.contains(RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray()
                    .put(sources.getJSONObject(i).getString("mediaRef"))
                    .put(profile)
                    .put(hash))))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计数据集中尚未被分析覆盖的次数。
     *
     * @param dataset  数据集对象。
     * @param profiles 需要的分析档案 id 列表。
     * @param analysis 分析档案配置。
     * @param analyses 已确认的分析指纹集合。
     * @return 缺少的分析次数。
     */
    private static int missingAnalyses(JSONObject dataset, JSONArray profiles, JSONObject analysis, Set<String> analyses) {
        int missing = 0;
        JSONArray rows = dataset.getJSONArray("records");
        for (int i = 0; i < profiles.length(); i++) {
            String id = profiles.getString(i);
            String hash = RuntimeSupport.sha(RuntimeSupport.bytes(ConnectorConfig.select(analysis, "profiles", id)));
            for (int j = 0; j < rows.length(); j++) {
                if (!analyzed(rows.getJSONObject(j), id, hash, analyses)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /**
     * 构造定位某条查询链路的详情对象。
     *
     * @param pipeline 流水线配置。
     * @param page     页事实。
     * @return 含流水线、连接器、查询与快照标识的详情。
     */
    private static JSONObject detail(JSONObject pipeline, JSONObject page) {
        return new JSONObject()
                .put("pipelineId", pipeline.getString("id"))
                .put("connectorId", pipeline.getString("connectorId"))
                .put("queryHash", page.getString("queryHash"))
                .put("snapshotId", page.getString("snapshotId"));
    }

    /**
     * 依据已确认的证据检查业务工作流是否闭环。
     *
     * <p>按查询域分组页证据：无查询证据报 {@code QUERY_REQUIRED}；同游标内容冲突报
     * {@code QUERY_PAGE_CONFLICT}；无法拼接出完整页链报 {@code QUERY_INCOMPLETE}；
     * 页链完整但缺少处理产物报 {@code PROCESS_REQUIRED}；已处理但分析未覆盖报
     * {@code ANALYSIS_REQUIRED}。结论中的满足标记仅在缺口为零时为真。</p>
     *
     * @param resources 资源读取入口。
     * @param evidence  宿主已确认的证据列表。
     * @return 含满足标记、缺口代码、修复上限与各阶段计数的检查结论。
     * @throws Exception 证据引用了未配置的连接器，或读取/校验失败时抛出。
     */
    @Override
    public JSONObject check(Resources resources, JSONArray evidence) throws Exception {
        if (RetrievalWorkflow.active(resources)) {
            JSONObject result = RetrievalWorkflow.check(resources, evidence);
            return result.put("maxRepairs", Math.min(repairCeiling, result.getInt("maxRepairs")));
        }
        JSONObject requirements = resources.json(FILE);
        JSONObject processing = resources.json("data-processing.json");
        JSONObject analysis = resources.json("analysis-profiles.json");
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
            JSONObject fact = evidence.getJSONObject(i);
            JSONObject value = fact.getJSONObject("value");
            String operation = fact.getString("operation");
            String kind = fact.getString("kind");
            if ("data-page".equals(kind) && "common.data.query.v1".equals(operation)) {
                if (!pipelines.containsKey(value.getString("connectorId"))) {
                    throw new StoreException("WORKFLOW_EVIDENCE_INVALID");
                }
                JSONObject page = new JSONObject(value.toString()).put("pageRef", fact.getString("id"));
                groups.computeIfAbsent(scope(page), key -> new ArrayList<>()).add(page);
            } else if ("data-set".equals(kind) && "common.processing.process.v1".equals(operation)) {
                datasets.add(new JSONObject(value.toString()).put("datasetRef", fact.getString("id")));
            } else if ("analysis".equals(kind) && "common.capability.invoke.v1".equals(operation)) {
                analyses.add(RuntimeSupport.sha(RuntimeSupport.bytes(new JSONArray()
                        .put(value.getString("mediaRef"))
                        .put(value.getString("profileId"))
                        .put(value.getString("profileHash")))));
            }
        }
        Gaps gaps = new Gaps();
        JSONArray datasetRefs = new JSONArray();
        int records = 0;
        int requiredAnalyses = 0;
        int confirmedAnalyses = 0;
        if (groups.isEmpty()) {
            gaps.add("QUERY_REQUIRED", new JSONObject().put("connectorIds", new JSONArray(pipelines.keySet())));
        }
        for (List<JSONObject> pages : groups.values()) {
            JSONObject first = pages.get(0);
            JSONObject pipeline = pipelines.get(first.getString("connectorId"));
            Map<String, JSONObject> byRef = new HashMap<>();
            Map<String, JSONObject> byCursor = new HashMap<>();
            boolean conflict = false;
            for (JSONObject page : pages) {
                byRef.put(page.getString("pageRef"), page);
                JSONObject previous = byCursor.putIfAbsent(position(page), page);
                if (previous != null && !content(previous).equals(content(page))) {
                    conflict = true;
                }
            }
            if (conflict) {
                gaps.add("QUERY_PAGE_CONFLICT", detail(pipeline, first));
                continue;
            }
            JSONArray chain = new JSONArray();
            Set<String> seen = new HashSet<>();
            String cursor = "";
            String continuation = "";
            while (byCursor.containsKey(cursor) && seen.add(cursor) && chain.length() < 64) {
                JSONObject page = byCursor.get(cursor);
                chain.put(page.getString("pageRef"));
                continuation = page.getString("nextCursor");
                cursor = nextPosition(page);
                if (cursor.isEmpty()) {
                    break;
                }
            }
            boolean complete = chain.length() > 0 && cursor.isEmpty() && seen.containsAll(byCursor.keySet());
            if (!complete) {
                gaps.add("QUERY_INCOMPLETE", detail(pipeline, first).put("cursor", continuation));
                continue;
            }
            JSONObject profile = ConnectorConfig.select(processing, "profiles", pipeline.getString("processingProfileId"));
            String profileHash = RuntimeSupport.sha(RuntimeSupport.bytes(profile));
            JSONObject chosen = null;
            int fewestMissing = Integer.MAX_VALUE;
            for (JSONObject dataset : datasets) {
                if (!dataset.has("pageRefs") || !dataset.has("connectorId")
                        || !dataset.getString("connectorId").equals(first.getString("connectorId"))
                        || !dataset.getString("profileHash").equals(profileHash)) {
                    continue;
                }
                JSONObject coverage = dataset.getJSONObject("coverage");
                if (!coverage.getBoolean("queryComplete") || !coverage.getString("queryHash").equals(first.getString("queryHash"))
                        || !coverage.getString("snapshotId").equals(first.getString("snapshotId"))) {
                    continue;
                }
                Set<String> positions = new HashSet<>();
                boolean bound = true;
                JSONArray refs = dataset.getJSONArray("pageRefs");
                for (int i = 0; i < refs.length(); i++) {
                    JSONObject page = byRef.get(refs.getString(i));
                    if (page == null) {
                        bound = false;
                        break;
                    }
                    positions.add(position(page));
                }
                if (bound && !positions.isEmpty() && positions.containsAll(byCursor.keySet())) {
                    int missing = missingAnalyses(dataset, pipeline.getJSONArray("analysisProfileIds"), analysis, analyses);
                    if (missing < fewestMissing) {
                        chosen = dataset;
                        fewestMissing = missing;
                    }
                }
            }
            if (chosen == null) {
                gaps.add("PROCESS_REQUIRED", detail(pipeline, first)
                        .put("profileId", pipeline.getString("processingProfileId"))
                        .put("pageRefs", chain));
                continue;
            }
            datasetRefs.put(chosen.getString("datasetRef"));
            JSONArray rows = chosen.getJSONArray("records");
            JSONArray requiredProfiles = pipeline.getJSONArray("analysisProfileIds");
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
                    if (found) {
                        confirmedAnalyses++;
                    } else {
                        gaps.add("ANALYSIS_REQUIRED", detail(pipeline, first)
                                .put("profileId", profileId)
                                .put("datasetRef", chosen.getString("datasetRef"))
                                .put("recordId", row.getJSONObject("record").getString("recordId"))
                                .put("mediaRef", origins.getJSONObject(0).getString("mediaRef")));
                    }
                }
            }
        }
        return new JSONObject()
                .put("satisfied", gaps.count == 0)
                .put("maxRepairs", Math.min(repairCeiling, requirements.getInt("maxRepairs")))
                .put("queryScopes", groups.size())
                .put("processedRecords", records)
                .put("datasetRefs", datasetRefs)
                .put("requiredAnalyses", requiredAnalyses)
                .put("confirmedAnalyses", confirmedAnalyses)
                .put("missingCount", gaps.count)
                .put("missing", gaps.items);
    }
}
