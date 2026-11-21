/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 只处理当前 run 已确认的数据页，并保留不可变的来源引用。
 */
public final class DataProcessProvider extends AbstractProvider {
    /**
     * 构造数据处理 provider。
     *
     * @param properties 技能私有配置。
     */
    public DataProcessProvider(Properties properties) {
        super("processing", "common.processing.process", "data.process", properties);
    }

    /**
     * 返回本 provider 依赖的配置文件列表。
     *
     * @return 配置文件名集合。
     */
    @Override
    public Set<String> configurationFiles() {
        return Collections.singleton("data-processing.json");
    }

    /**
     * 校验数据处理配置。
     *
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    @Override
    public void validateConfiguration(String path, JSONObject config) {
        DataProcessing.validate(config);
    }

    /**
     * 执行数据处理或读取已保存的数据集。
     *
     * @param context 调用上下文。
     * @param args    调用参数。
     * @return 处理结果。
     * @throws Exception 资源读取或处理异常。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        String skill = args.getString("skill");
        JSONObject config = context.resource(skill, "data-processing.json");
        DataProcessing.validate(config);
        JSONObject profile = ConnectorConfig.select(config, "profiles", args.getString("profileId"));
        String profileHash = RuntimeSupport.sha(RuntimeSupport.bytes(profile));
        JSONObject dataset;
        String ref;
        if (args.getString("action").equals("process")) {
            if (!args.has("pageRefs") || args.has("datasetRef") || args.has("offset")) {
                invalid();
            }
            JSONArray pages = new JSONArray();
            Set<String> seen = new HashSet<>();
            JSONArray refs = args.getJSONArray("pageRefs");
            for (int i = 0; i < refs.length(); i++) {
                check(context);
                String pageRef = refs.getString(i);
                if (!seen.add(pageRef)) {
                    invalid();
                }
                JSONObject page = context.confirmed("data-page", pageRef);
                if (!skill.equals(page.getString("skill"))) {
                    throw new StoreException("MEDIA_REF_FORBIDDEN");
                }
                authorize(context, page);
                pages.put(new JSONObject(page.toString()).put("pageRef", pageRef));
            }
            JSONObject parameters = ConnectorConfig.arguments(args.optString("argumentsJson", "{}"));
            dataset = DataProcessing.process(profile, parameters, pages);
            dataset.put("skill", skill)
                    .put("profileHash", profileHash)
                    .put("connectorId", profile.getString("connectorId"))
                    .put("pageRefs", refs);
            if (RuntimeSupport.bytes(dataset).length > 1048576) {
                throw new StoreException("RESULT_BUDGET_EXCEEDED");
            }
            // 事实由其来源工具调用确认，不同调用不得共享该归属。
            ref = "dataset-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                    .put("job", context.jobId)
                    .put("dataset", dataset)));
        } else {
            if (!args.has("datasetRef") || args.has("pageRefs") || args.has("argumentsJson")) {
                invalid();
            }
            ref = args.getString("datasetRef");
            dataset = context.confirmed("data-set", ref);
            if (!skill.equals(dataset.getString("skill")) || !profileHash.equals(dataset.getString("profileHash"))) {
                throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
            JSONArray sources = dataset.getJSONArray("sourceRefs");
            for (int i = 0; i < sources.length(); i++) {
                context.source(sources.getString(i));
            }
        }
        JSONObject result = slice(dataset, ref, args);
        validateResult(args, result);
        if (args.getString("action").equals("process")) {
            context.remember("data-set", ref, dataset);
        }
        return result;
    }

    /**
     * 授权数据页的来源引用。
     *
     * @param context 调用上下文。
     * @param page    数据页。
     */
    private static void authorize(ProviderInvocation context, JSONObject page) {
        if (page.has("sourceRef")) {
            context.source(page.getString("sourceRef"));
        }
    }

    /**
     * 按 Broker 的字节预算收缩页面；单个超大条目直接失败。
     *
     * @param dataset 数据集。
     * @param ref     数据集引用。
     * @param args    调用参数。
     * @return 收缩后的结果。
     */
    private JSONObject slice(JSONObject dataset, String ref, JSONObject args) {
        String view = args.optString("view", "records");
        JSONArray all = dataset.getJSONArray(view);
        int offset = args.optInt("offset", 0);
        if (offset > all.length()) {
            invalid();
        }
        int end = Math.min(all.length(), offset + args.optInt("limit", 8));
        for (; ; ) {
            JSONArray items = new JSONArray();
            for (int i = offset; i < end; i++) {
                items.put(all.get(i));
            }
            JSONObject result = new JSONObject()
                    .put("datasetRef", ref)
                    .put("profileId", args.getString("profileId"))
                    .put("view", view)
                    .put("itemsJson", items.toString())
                    .put("offset", offset)
                    .put("nextOffset", end < all.length() ? end : -1)
                    .put("totalItems", all.length())
                    .put("coverage", dataset.getJSONObject("coverage"))
                    .put("stats", dataset.getJSONObject("stats"))
                    .put("warnings", dataset.getJSONArray("warnings"));
            if (result.getString("itemsJson").length() <= 8192 && bytes(result) <= resultBytes && Budget.runnerBytes(result) < 32768) {
                validateResult(args, result);
                return result;
            }
            if (end <= offset + 1) {
                throw new StoreException("TOOL_RESULT_LIMIT");
            }
            end--;
        }
    }

    /**
     * 抛出数据过程参数非法错误。
     */
    static void invalid() {
        throw new StoreException("DATA_PROCESS_ARGUMENT_INVALID");
    }
}
