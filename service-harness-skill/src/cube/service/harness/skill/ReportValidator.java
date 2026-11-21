/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 校验模型报告的结构与引用，并从已确认的宿主证据推导事实字段。
 */
final class ReportValidator {
    /**
     * 报告提交 schema。
     */
    private static final JSONObject SCHEMA;

    static {
        try (InputStream in = ReportValidator.class.getResourceAsStream("report-submission.schema.json")) {
            if (in == null) {
                throw new IOException("REPORT_SCHEMA_MISSING");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            SCHEMA = StrictJson.object(bytes.toByteArray());
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    /**
     * 校验报告配置与规则声明。
     *
     * @param resources 完成守护的资源集合。
     * @throws Exception 配置非法时抛出。
     */
    static void validate(CompletionGuard.Resources resources) throws Exception {
        try {
            JSONObject config = resources.json(CompletionGuard.REPORT_FILE);
            ConnectorConfig.keys(config, "version,format,rules", "assessmentPolicy");
            require(Arrays.asList("1", "2").contains(config.getString("version")) && "evidence-report-v1".equals(config.getString("format")));
            require(RuntimeSupport.sha(RuntimeSupport.bytes(resources.json("report-submission.schema.json")))
                    .equals(RuntimeSupport.sha(RuntimeSupport.bytes(SCHEMA))));
            JSONArray rules = config.getJSONArray("rules");
            require(rules.length() <= 8);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                ConnectorConfig.keys(rule, "id,path,sha256", "");
                require(rule.getString("id").matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}") && ids.add(rule.getString("id")));
                require(rule.getString("sha256").matches("[a-f0-9]{64}"));
                require(RuntimeSupport.sha(resources.bytes(rule.getString("path"))).equals(rule.getString("sha256")));
            }
            AssessmentPolicy.validate(resources, config);
        } catch (Exception error) {
            if (error instanceof StoreException && ((StoreException) error).code.startsWith("STORE_")) {
                throw error;
            }
            throw new StoreException("REPORT_CONFIG_INVALID");
        }
    }

    /**
     * 条件不成立即拒绝配置。
     *
     * @param value 待校验条件。
     */
    private static void require(boolean value) {
        if (!value) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * 条件不成立即拒绝引用。
     *
     * @param value 待校验条件。
     */
    private static void ref(boolean value) {
        if (!value) {
            throw new StoreException("REPORT_CITATION_INVALID");
        }
    }

    /**
     * 收集去重后的字符串集合。
     *
     * @param values 字符串数组。
     * @return 去重后的集合。
     */
    private static Set<String> strings(JSONArray values) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < values.length(); i++) {
            require(out.add(values.getString(i)));
        }
        return out;
    }

    /**
     * 构造单项缺失结果。
     *
     * @param code    缺失码。
     * @param details 缺失细节。
     * @return 缺失结果。
     */
    private static JSONObject result(String code, JSONObject details) {
        return new JSONObject()
                .put("satisfied", false)
                .put("missingCount", 1)
                .put("missing", new JSONArray().put(details.put("code", code)));
    }

    /**
     * 复制已确认事实与保守评估，再用同一严格校验器复核。
     *
     * @param resources 完成守护的资源集合。
     * @param evidence  已确认事实数组。
     * @param skill     当前技能名。
     * @param workflow  工作流状态。
     * @return 最终报告决策。
     * @throws Exception 事实缺失或校验失败。
     */
    static JSONObject construct(CompletionGuard.Resources resources, JSONArray evidence, String skill, JSONObject workflow) throws Exception {
        JSONArray submitted = new JSONArray();
        JSONArray expected = workflow.getJSONArray("datasetRefs");
        Map<String, JSONObject> datasets = new HashMap<>();
        Map<String, JSONObject> analyses = new TreeMap<>();
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject fact = evidence.getJSONObject(i);
            if ("data-set".equals(fact.getString("kind")) && "common.processing.process.v1".equals(fact.getString("operation"))) {
                datasets.put(fact.getString("id"), fact.getJSONObject("value"));
            }
            if ("analysis".equals(fact.getString("kind")) && "common.capability.invoke.v1".equals(fact.getString("operation"))) {
                analyses.put(fact.getString("id"), fact);
            }
        }
        JSONObject profiles = resources.json("analysis-profiles.json");
        JSONArray pipelines = resources.json(CompletionGuard.FILE).getJSONArray("pipelines");
        for (int d = 0; d < expected.length(); d++) {
            String ref = expected.getString(d);
            JSONObject dataset = datasets.get(ref);
            JSONObject pipeline = null;
            if (dataset == null) {
                throw new StoreException("REPORT_DATASETS_INCOMPLETE");
            }
            for (int p = 0; p < pipelines.length(); p++) {
                if (pipelines.getJSONObject(p).getString("connectorId").equals(dataset.getString("connectorId"))) {
                    pipeline = pipelines.getJSONObject(p);
                }
            }
            if (pipeline == null) {
                throw new StoreException("WORKFLOW_BINDING_INVALID");
            }
            JSONArray findings = new JSONArray();
            JSONArray rows = dataset.getJSONArray("records");
            JSONArray required = pipeline.getJSONArray("analysisProfileIds");
            for (int r = 0; r < rows.length(); r++) {
                for (int p = 0; p < required.length(); p++) {
                    String profile = required.getString(p);
                    String hash = RuntimeSupport.sha(RuntimeSupport.bytes(ConnectorConfig.select(profiles, "profiles", profile)));
                    JSONArray sources = rows.getJSONObject(r).getJSONArray("sources");
                    JSONObject actual = null;
                    for (JSONObject fact : analyses.values()) {
                        JSONObject value = fact.getJSONObject("value");
                        if (!profile.equals(value.getString("profileId")) || !hash.equals(value.getString("profileHash")) || !fact.has("result")) {
                            continue;
                        }
                        for (int s = 0; s < sources.length(); s++) {
                            if (sources.getJSONObject(s).getString("mediaRef").equals(value.getString("mediaRef"))) {
                                actual = fact;
                                break;
                            }
                        }
                        if (actual != null) {
                            break;
                        }
                    }
                    if (actual == null) {
                        throw new StoreException("REPORT_FINDINGS_INCOMPLETE");
                    }
                    findings.put(new JSONObject()
                            .put("recordIndex", r)
                            .put("profileId", profile)
                            .put("mediaRef", actual.getJSONObject("value").getString("mediaRef"))
                            .put("observation", actual.getJSONObject("result").getString("answer"))
                            .put("assessment", new JSONObject()
                                    .put("status", "not-assessed")
                                    .put("reason", "仅记录已确认的可见观察；授权、区域性质及业务异常条件尚未核实。")
                                    .put("ruleIds", new JSONArray())));
                }
            }
            submitted.put(new JSONObject()
                    .put("datasetRef", ref)
                    .put("findings", findings));
        }
        JSONObject report = new JSONObject()
                .put("skill", skill)
                .put("summary", "已完成查询覆盖、去重和逐记录分析；观察与业务异常分别记录。")
                .put("limitations", new JSONArray().put("检测框数不等于唯一人数；未确认的时间保持为空；自由文本观察需要语义复核。"))
                .put("datasets", submitted);
        JSONObject input = new JSONObject()
                .put("schemaVersion", 1)
                .put("reports", new JSONArray().put(report));
        Contract.validate(input, SCHEMA);
        return new JSONObject()
                .put("kind", "final")
                .put("text", JdbcRunStore.canonical(input));
    }

    /**
     * 校验模型提交的最终报告并给出规范化结果。
     *
     * @param resources 完成守护的资源集合。
     * @param evidence  已确认事实数组。
     * @param decision  模型最终决策。
     * @param skill     当前技能名。
     * @param skills    期望的技能集合。
     * @param workflow  工作流状态。
     * @return 校验结果。
     * @throws Exception 配置非法时抛出。
     */
    static JSONObject check(CompletionGuard.Resources resources, JSONArray evidence, JSONObject decision,
                            String skill, Set<String> skills, JSONObject workflow) throws Exception {
        JSONObject input;
        try {
            byte[] raw = decision.getString("text").getBytes(StandardCharsets.UTF_8);
            require(raw.length <= 65536);
            input = StrictJson.object(raw);
            Contract.validate(input, SCHEMA);
        } catch (Exception invalid) {
            return result("REPORT_FORMAT_INVALID", new JSONObject()
                    .put("format", "evidence-report-v1")
                    .put("instruction", "final.text must be a JSON object with schemaVersion=1 and reports; read the fixed report submission schema/resource."));
        }
        JSONObject selected = null;
        try {
            Set<String> names = new TreeSet<>();
            JSONArray reports = input.getJSONArray("reports");
            for (int i = 0; i < reports.length(); i++) {
                JSONObject report = reports.getJSONObject(i);
                require(names.add(report.getString("skill")));
                if (skill.equals(report.getString("skill"))) {
                    selected = report;
                }
            }
            require(names.equals(skills) && selected != null);
        } catch (Exception invalid) {
            return result("REPORT_SKILL_SET_INVALID", new JSONObject().put("skills", new JSONArray(skills)));
        }
        // 工作流反馈给出完成工作流所需的实际页/数据集/媒体引用。
        if (!workflow.getBoolean("satisfied")) {
            return new JSONObject()
                    .put("satisfied", true)
                    .put("missingCount", 0)
                    .put("missing", new JSONArray());
        }
        JSONArray expected = workflow.getJSONArray("datasetRefs");
        try {
            return new JSONObject()
                    .put("satisfied", true)
                    .put("missingCount", 0)
                    .put("missing", new JSONArray())
                    .put("report", normalize(resources, evidence, selected, expected));
        } catch (StoreException invalid) {
            if (invalid.code.startsWith("STORE_")) {
                throw invalid;
            }
            JSONObject details = new JSONObject()
                    .put("skill", skill)
                    .put("datasetRefs", expected);
            if (invalid.code.startsWith("REPORT_ASSESSMENT_")) {
                details.put("instruction",
                        "A rule citation alone cannot prove normal/abnormal. Read report-requirements.json; use only matching configured predicates over confirmed facts. If evidence is missing or conflicting, submit review-required and explain the limitation. Do not invent facts or repeat unknown calls.");
            }
            return result(invalid.code, details);
        } catch (Exception invalid) {
            return result("REPORT_CITATION_INVALID", new JSONObject()
                    .put("skill", skill)
                    .put("datasetRefs", expected));
        }
    }

    /**
     * 规范化报告：逐条核对引用与证据，并输出带复核标记的结构。
     *
     * @param resources 完成守护的资源集合。
     * @param evidence  已确认事实数组。
     * @param input    模型提交的报告。
     * @param expected 期望的数据集引用数组。
     * @return 规范化报告。
     * @throws Exception 引用无效或证据缺失时抛出。
     */
    private static JSONObject normalize(CompletionGuard.Resources resources, JSONArray evidence, JSONObject input, JSONArray expected) throws Exception {
        Map<String, JSONObject> datasets = new HashMap<>();
        Map<String, JSONObject> analyses = new TreeMap<>();
        Map<String, JSONObject> rules = new HashMap<>();
        JSONObject requirements = resources.json(CompletionGuard.REPORT_FILE);
        JSONArray declaredRules = requirements.getJSONArray("rules");
        for (int i = 0; i < declaredRules.length(); i++) {
            rules.put(declaredRules.getJSONObject(i).getString("id"), declaredRules.getJSONObject(i));
        }
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject fact = evidence.getJSONObject(i);
            if (fact.getString("kind").equals("data-set") && fact.getString("operation").equals("common.processing.process.v1")) {
                datasets.put(fact.getString("id"), fact.getJSONObject("value"));
            }
            if (fact.getString("kind").equals("analysis") && fact.getString("operation").equals("common.capability.invoke.v1")) {
                analyses.put(fact.getString("id"), fact);
            }
        }
        Set<String> wanted = strings(expected);
        Set<String> seen = new HashSet<>();
        JSONArray output = new JSONArray();
        JSONArray submitted = input.getJSONArray("datasets");
        JSONObject profiles = resources.json("analysis-profiles.json");
        JSONObject workflow = resources.json(CompletionGuard.FILE);
        for (int d = 0; d < submitted.length(); d++) {
            JSONObject entry = submitted.getJSONObject(d);
            String datasetRef = entry.getString("datasetRef");
            ref(wanted.contains(datasetRef) && seen.add(datasetRef) && datasets.containsKey(datasetRef));
            JSONObject dataset = datasets.get(datasetRef);
            JSONArray rows = dataset.getJSONArray("records");
            JSONArray findings = entry.getJSONArray("findings");
            JSONArray normalized = new JSONArray();
            Set<String> covered = new HashSet<>();
            for (int f = 0; f < findings.length(); f++) {
                JSONObject finding = findings.getJSONObject(f);
                int index = finding.getInt("recordIndex");
                String profileId = finding.getString("profileId");
                String mediaRef = finding.getString("mediaRef");
                ref(index >= 0 && index < rows.length() && covered.add(index + ":" + profileId));
                JSONObject row = rows.getJSONObject(index);
                JSONObject origin = null;
                JSONArray sources = row.getJSONArray("sources");
                for (int s = 0; s < sources.length(); s++) {
                    if (mediaRef.equals(sources.getJSONObject(s).getString("mediaRef"))) {
                        origin = sources.getJSONObject(s);
                    }
                }
                ref(origin != null);
                String hash = RuntimeSupport.sha(RuntimeSupport.bytes(ConnectorConfig.select(profiles, "profiles", profileId)));
                JSONObject actual = null;
                for (JSONObject fact : analyses.values()) {
                    JSONObject value = fact.getJSONObject("value");
                    if (mediaRef.equals(value.getString("mediaRef")) && profileId.equals(value.getString("profileId")) && hash.equals(value.getString("profileHash"))) {
                        // 存在仅工作流旧证据时，优先选完整的报告溯源。
                        if (value.has("source") && value.has("artifact") && fact.has("result")) {
                            if (actual == null) {
                                actual = fact;
                            }
                            // 重复的已确认分析可能答案不同；接受报告逐字引用的任一真实结果。
                            if (finding.getString("observation").equals(fact.getJSONObject("result").getString("answer"))) {
                                actual = fact;
                                break;
                            }
                        }
                    }
                }
                ref(actual != null);
                JSONObject value = actual.getJSONObject("value");
                JSONObject source = value.getJSONObject("source");
                JSONObject analysis = actual.getJSONObject("result");
                JSONObject artifact = value.getJSONObject("artifact");
                String digest = RuntimeSupport.sha(RuntimeSupport.bytes(analysis));
                ref(digest.equals(value.getString("analysisResultSha256")) && digest.equals(source.getString("analysisResultSha256"))
                        && profileId.equals(source.getString("profileId")) && mediaRef.equals(source.getString("mediaRef"))
                        && profileId.equals(analysis.getString("profileId")) && mediaRef.equals(analysis.getString("mediaRef"))
                        && source.getString("mediaId").equals(origin.getString("mediaId")) && source.getString("mediaVersion").equals(origin.getString("mediaVersion"))
                        && source.getString("captureTimeTrust").equals(origin.getString("timeTrust"))
                        && artifact.getString("fileName").equals("media-source.json") && artifact.getString("sha256").matches("[a-f0-9]{64}"));
                JSONObject originalRecord = new JSONObject(source.getJSONObject("source").toString());
                originalRecord.remove("mediaRef");
                ref(RuntimeSupport.sha(RuntimeSupport.bytes(originalRecord)).equals(RuntimeSupport.sha(RuntimeSupport.bytes(row.getJSONObject("record")))));
                if (!finding.getString("observation").equals(analysis.getString("answer"))) {
                    throw new StoreException("REPORT_OBSERVATION_MISMATCH");
                }
                JSONObject assessment = finding.getJSONObject("assessment");
                Set<String> cited = strings(assessment.getJSONArray("ruleIds"));
                JSONArray ruleRefs = new JSONArray();
                for (String rule : cited) {
                    if (!rules.containsKey(rule)) {
                        throw new StoreException("REPORT_RULE_INVALID");
                    }
                    ruleRefs.put(rules.get(rule));
                }
                if (Arrays.asList("normal", "abnormal").contains(assessment.getString("status")) && cited.isEmpty()) {
                    throw new StoreException("REPORT_RULE_REQUIRED");
                }
                JSONObject assessmentReview = AssessmentPolicy.check(requirements,
                        ConnectorConfig.select(profiles, "profiles", profileId), analysis, originalRecord, assessment);
                JSONObject citation = new JSONObject()
                        .put("analysisRef", actual.getString("id"))
                        .put("mediaRef", mediaRef)
                        .put("profileId", profileId)
                        .put("profileHash", hash)
                        .put("analysisResultSha256", digest)
                        .put("artifactId", artifact.getString("artifactId"))
                        .put("artifactSha256", artifact.getString("sha256"))
                        .put("mediaId", source.getString("mediaId"))
                        .put("mediaVersion", source.getString("mediaVersion"))
                        .put("mediaType", source.getString("mediaType"))
                        .put("sha256", source.getString("sha256"))
                        .put("captureTimeTrust", source.getString("captureTimeTrust"));
                JSONObject findingOutput = new JSONObject()
                        .put("recordIndex", index)
                        .put("record", originalRecord)
                        .put("observation", analysis.getString("answer"))
                        .put("payloadJson", analysis.getString("payloadJson"))
                        .put("warnings", analysis.getJSONArray("warnings"))
                        .put("citation", citation)
                        .put("assessment", new JSONObject()
                                .put("status", assessment.getString("status"))
                                .put("reason", assessment.getString("reason"))
                                .put("ruleRefs", ruleRefs)
                                .put("semanticallyVerified", false)
                                .put("evidenceReview", assessmentReview));
                findingOutput.put("semanticReview", ObservationReview.review(
                        ConnectorConfig.select(profiles, "profiles", profileId), analysis, assessment.getString("status")));
                normalized.put(findingOutput);
            }
            JSONArray pipelines = workflow.getJSONArray("pipelines");
            JSONObject pipeline = null;
            for (int p = 0; p < pipelines.length(); p++) {
                if (pipelines.getJSONObject(p).getString("connectorId").equals(dataset.getString("connectorId"))) {
                    pipeline = pipelines.getJSONObject(p);
                }
            }
            ref(pipeline != null);
            JSONArray required = pipeline.getJSONArray("analysisProfileIds");
            for (int r = 0; r < rows.length(); r++) {
                for (int p = 0; p < required.length(); p++) {
                    if (!covered.contains(r + ":" + required.getString(p))) {
                        throw new StoreException("REPORT_FINDINGS_INCOMPLETE");
                    }
                }
            }
            output.put(new JSONObject()
                    .put("datasetRef", datasetRef)
                    .put("coverage", dataset.getJSONObject("coverage"))
                    .put("stats", dataset.getJSONObject("stats"))
                    .put("groups", dataset.getJSONArray("groups"))
                    .put("warnings", dataset.getJSONArray("warnings"))
                    .put("recordCount", rows.length())
                    .put("findings", normalized));
        }
        if (!seen.equals(wanted)) {
            throw new StoreException("REPORT_DATASETS_INCOMPLETE");
        }
        return new JSONObject()
                .put("summary", input.getString("summary"))
                .put("summarySemanticallyVerified", false)
                .put("summaryReview", new JSONObject()
                        .put("status", "review-required")
                        .put("reason", "GENERATED_SUMMARY_REQUIRES_INDEPENDENT_REVIEW"))
                .put("limitations", input.getJSONArray("limitations"))
                .put("datasets", output);
    }
}
