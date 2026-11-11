/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/** Processes only confirmed pages from the current run and retains immutable source references. */
public final class DataProcessProvider extends AbstractProvider {
    public DataProcessProvider(Properties properties) {
        super("processing", "common.processing.process", "data.process", properties);
    }

    @Override
    public Set<String> configurationFiles() {
        return Collections.singleton("data-processing.json");
    }

    @Override
    public void validateConfiguration(String path, JSONObject config) {
        DataProcessing.validate(config);
    }

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
            if (!args.has("pageRefs") || args.has("datasetRef") || args.has("offset")) invalid();
            JSONArray pages = new JSONArray();
            Set<String> seen = new HashSet<>();
            JSONArray refs = args.getJSONArray("pageRefs");
            for (int i = 0; i < refs.length(); i++) {
                check(context);
                String pageRef = refs.getString(i);
                if (!seen.add(pageRef)) invalid();
                JSONObject page = context.confirmed("data-page", pageRef);
                if (!skill.equals(page.getString("skill"))) throw new StoreException("MEDIA_REF_FORBIDDEN");
                authorize(context, page);
                pages.put(new JSONObject(page.toString()).put("pageRef", pageRef));
            }
            JSONObject parameters = ConnectorConfig.arguments(args.optString("argumentsJson", "{}"));
            dataset = DataProcessing.process(profile, parameters, pages);
            dataset.put("skill", skill).put("profileHash", profileHash);
            if (RuntimeSupport.bytes(dataset).length > 1048576) throw new StoreException("RESULT_BUDGET_EXCEEDED");
            // A fact is confirmed by its originating tool call. Different calls must not share that ownership.
            ref = "dataset-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                    .put("job", context.jobId).put("dataset", dataset)));
        } else {
            if (!args.has("datasetRef") || args.has("pageRefs") || args.has("argumentsJson")) invalid();
            ref = args.getString("datasetRef");
            dataset = context.confirmed("data-set", ref);
            if (!skill.equals(dataset.getString("skill")) || !profileHash.equals(dataset.getString("profileHash"))) {
                throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
            JSONArray sources = dataset.getJSONArray("sourceRefs");
            for (int i = 0; i < sources.length(); i++) context.source(sources.getString(i));
        }
        JSONObject result = slice(dataset, ref, args);
        validateResult(args, result);
        if (args.getString("action").equals("process")) context.remember("data-set", ref, dataset);
        return result;
    }

    private static void authorize(ProviderInvocation context, JSONObject page) {
        if (page.has("sourceRef")) context.source(page.getString("sourceRef"));
    }

    /** Shrink a page to the existing Broker byte budget; a single oversized item fails explicitly. */
    private JSONObject slice(JSONObject dataset, String ref, JSONObject args) {
        String view = args.optString("view", "records");
        JSONArray all = dataset.getJSONArray(view);
        int offset = args.optInt("offset", 0);
        if (offset > all.length()) invalid();
        int end = Math.min(all.length(), offset + args.optInt("limit", 8));
        for (;;) {
            JSONArray items = new JSONArray();
            for (int i = offset; i < end; i++) items.put(all.get(i));
            JSONObject result = new JSONObject().put("datasetRef", ref).put("profileId", args.getString("profileId"))
                    .put("view", view).put("itemsJson", items.toString()).put("offset", offset)
                    .put("nextOffset", end < all.length() ? end : -1).put("totalItems", all.length())
                    .put("coverage", dataset.getJSONObject("coverage")).put("stats", dataset.getJSONObject("stats"))
                    .put("warnings", dataset.getJSONArray("warnings"));
            if (result.getString("itemsJson").length() <= 8192 && bytes(result) <= resultBytes && Budget.runnerBytes(result) < 32768) {
                validateResult(args, result);
                return result;
            }
            if (end <= offset + 1) throw new StoreException("TOOL_RESULT_LIMIT");
            end--;
        }
    }

    static void invalid() { throw new StoreException("DATA_PROCESS_ARGUMENT_INVALID"); }
}
