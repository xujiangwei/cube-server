/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.CompletionGuard;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 固定且有界的依赖图；只有首个查询范围来自规划器。
 */
final class FixedPatrol {
    /**
     * 校验固定的执行配置与绑定图。
     *
     * @param requirements 能力请求 JSON。
     * @param profiles     分析档案 JSON。
     */
    static void validate(JSONObject requirements, JSONObject profiles) {
        if (!requirements.has("execution")) {
            return;
        }
        JSONObject config = requirements.getJSONObject("execution");
        ConnectorConfig.keys(config, "version,maxPages,bindings", "");
        if (!"fixed-patrol-v1".equals(config.getString("version")) || config.getInt("maxPages") < 1 || config.getInt("maxPages") > 64
                || config.getDouble("maxPages") != config.getInt("maxPages")) {
            invalid();
        }
        JSONArray bindings = config.getJSONArray("bindings");
        if (bindings.length() == 0 || bindings.length() > 8) {
            invalid();
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.getJSONObject(i);
            ConnectorConfig.keys(binding, "profileId,attribute,dimension,content", "");
            String id = binding.getString("profileId");
            String attribute = binding.getString("attribute");
            String dimension = binding.getString("dimension");
            if (!ids.add(id) || binding.getString("content").isEmpty() || binding.getString("content").length() > 2048) {
                invalid();
            }
            JSONObject profile = ConnectorConfig.select(profiles, "profiles", id);
            JSONObject options = ConnectorConfig.arguments(profile.getString("optionsJson"));
            if ("object-presence".equals(attribute) || "detection-boxes".equals(attribute)) {
                if (!"SpecializedDetection".equals(profile.getString("unit")) || !"thing".equals(options.optString("profile"))
                        || !dimension.equals("object-presence".equals(attribute) ? "presence" : "boxes-per-image")) {
                    invalid();
                }
            } else if ("visible-fire-smoke".equals(attribute)) {
                if (!"SpecializedDetection".equals(profile.getString("unit")) || !"fire-smoke".equals(options.optString("profile")) || !"presence".equals(dimension)) {
                    invalid();
                }
            } else if ("scene-description".equals(attribute) || "passage-obstruction".equals(attribute)) {
                if (!"OmniMultimodal".equals(profile.getString("unit")) || !"visible-scene".equals(dimension)) {
                    invalid();
                }
            } else {
                // 无法凭空生成唯一人员/跟踪与业务授权绑定。
                invalid();
            }
        }
        JSONArray pipelines = requirements.getJSONArray("pipelines");
        for (int i = 0; i < pipelines.length(); i++) {
            JSONArray required = pipelines.getJSONObject(i).getJSONArray("analysisProfileIds");
            for (int j = 0; j < required.length(); j++) {
                if (!ids.contains(required.getString(j))) {
                    invalid();
                }
            }
        }
    }

    /**
     * 抛出工作流绑定非法错误。
     */
    private static void invalid() {
        throw new StoreException("WORKFLOW_BINDING_INVALID");
    }

    /**
     * 构造一个已固定的工具调用提案。
     *
     * @param tool      工具名。
     * @param arguments 工具参数。
     * @return 工具调用提案。
     */
    private static JSONObject call(String tool, JSONObject arguments) {
        JSONObject call = new JSONObject()
                .put("tool", tool)
                .put("argumentsJson", JdbcRunStore.canonical(arguments));
        call.put("proposalId", "bound-" + RuntimeSupport.sha(RuntimeSupport.bytes(call)));
        return new JSONObject()
                .put("kind", "tool_call")
                .put("call", call);
    }

    /**
     * 依据已确认证据推进固定巡检工作流，返回下一步调用或规划提示。
     *
     * @param guard     工作流守卫。
     * @param resources 完成守护的资源集合。
     * @param evidence  已确认事实数组。
     * @param skill     当前技能名。
     * @return 下一步调用/规划对象；无执行配置返回 null。
     * @throws Exception 资源读取或校验失败。
     */
    static JSONObject advance(WorkflowGuard guard, CompletionGuard.Resources resources, JSONArray evidence, String skill) throws Exception {
        JSONObject requirements = resources.json(CompletionGuard.FILE);
        if (!requirements.has("execution")) {
            return null;
        }
        JSONObject config = requirements.getJSONObject("execution");
        Map<String, JSONObject> pages = new TreeMap<>();
        Map<String, JSONObject> roots = new TreeMap<>();
        Map<String, Integer> pageCounts = new HashMap<>();
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject fact = evidence.getJSONObject(i);
            if (!"data-page".equals(fact.getString("kind")) || !"common.data.query.v1".equals(fact.getString("operation"))) {
                continue;
            }
            JSONObject page = fact.getJSONObject("value");
            String scope = page.getString("queryHash") + ":" + page.getString("snapshotId");
            String key = scope + ":" + page.getString("cursor");
            if (pages.put(key, fact) != null) {
                throw new StoreException("WORKFLOW_NO_PROGRESS");
            }
            pageCounts.put(scope, pageCounts.getOrDefault(scope, 0) + 1);
            if (page.getString("cursor").isEmpty()) {
                roots.put(scope, fact);
            }
        }
        if (pages.isEmpty()) {
            JSONArray routes = new JSONArray();
            JSONArray connectors = resources.json("connectors.json").getJSONArray("connectors");
            for (int i = 0; i < connectors.length(); i++) {
                JSONObject connector = connectors.getJSONObject(i);
                JSONObject sample = new JSONObject();
                JSONArray required = connector.getJSONObject("inputSchema").getJSONArray("required");
                for (int r = 0; r < required.length(); r++) {
                    sample.put(required.getString(r), "<" + required.getString(r) + " from user>");
                }
                JSONObject toolArguments = new JSONObject()
                        .put("skill", skill)
                        .put("connectorId", connector.getString("id"))
                        .put("argumentsJson", JdbcRunStore.canonical(sample));
                routes.put(new JSONObject()
                        .put("skill", skill)
                        .put("connectorId", connector.getString("id"))
                        .put("argumentsSchema", connector.getJSONObject("inputSchema"))
                        .put("toolArgumentsExample", toolArguments)
                        .put("jsonDecisionExample", new JSONObject()
                                .put("kind", "tool_calls")
                                .put("calls", new JSONArray()
                                        .put(new JSONObject()
                                                .put("proposalId", "query1")
                                                .put("tool", "common.data.query")
                                                .put("argumentsJson", JdbcRunStore.canonical(toolArguments))))));
            }
            return new JSONObject()
                    .put("kind", "planning")
                    .put("tools", new JSONArray().put("common.data.query"))
                    .put("routes", routes)
                    .put("version", "fixed-patrol-v1");
        }
        if (roots.isEmpty() || roots.size() > 8) {
            throw new StoreException("QUERY_INCOMPLETE");
        }
        for (Map.Entry<String, JSONObject> root : roots.entrySet()) {
            String cursor = "";
            Set<String> seen = new HashSet<>();
            do {
                if (!seen.add(cursor)) {
                    throw new StoreException("QUERY_CURSOR_REPEATED");
                }
                JSONObject fact = pages.get(root.getKey() + ":" + cursor);
                if (fact == null) {
                    if (seen.size() > config.getInt("maxPages")) {
                        throw new StoreException("WORKFLOW_PAGE_LIMIT");
                    }
                    JSONObject args = new JSONObject(root.getValue().getJSONObject("arguments").toString()).put("cursor", cursor);
                    if (!skill.equals(args.getString("skill"))) {
                        invalid();
                    }
                    return call("common.data.query", args);
                }
                cursor = fact.getJSONObject("value").getString("nextCursor");
                if (seen.size() > config.getInt("maxPages")) {
                    throw new StoreException("WORKFLOW_PAGE_LIMIT");
                }
            } while (!cursor.isEmpty());
            if (seen.size() != pageCounts.get(root.getKey())) {
                throw new StoreException("QUERY_PAGE_CONFLICT");
            }
        }
        JSONObject workflow = guard.check(resources, evidence);
        if (workflow.getBoolean("satisfied")) {
            return ReportValidator.construct(resources, evidence, skill, workflow);
        }
        JSONObject gap = workflow.getJSONArray("missing").getJSONObject(0);
        if ("PROCESS_REQUIRED".equals(gap.getString("code"))) {
            return call("common.processing.process", new JSONObject()
                    .put("action", "process")
                    .put("skill", skill)
                    .put("profileId", gap.getString("profileId"))
                    .put("pageRefs", gap.getJSONArray("pageRefs")));
        }
        if ("ANALYSIS_REQUIRED".equals(gap.getString("code"))) {
            JSONObject binding = null;
            JSONArray bindings = config.getJSONArray("bindings");
            for (int i = 0; i < bindings.length(); i++) {
                if (bindings.getJSONObject(i).getString("profileId").equals(gap.getString("profileId"))) {
                    binding = bindings.getJSONObject(i);
                }
            }
            if (binding == null) {
                throw new StoreException("WORKFLOW_BINDING_INVALID");
            }
            return call("common.capability.invoke", new JSONObject()
                    .put("skill", skill)
                    .put("profileId", gap.getString("profileId"))
                    .put("mediaRef", gap.getString("mediaRef"))
                    .put("content", binding.getString("content")));
        }
        throw new StoreException(gap.getString("code"));
    }
}
