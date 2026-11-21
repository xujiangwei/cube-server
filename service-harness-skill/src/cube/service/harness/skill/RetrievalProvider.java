/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 经认证、可配置的图片/录像/文档等资源元数据检索。
 */
public final class RetrievalProvider extends AbstractProvider {
    /**
     * 数据网络访问器。
     */
    private final DataNetwork network;

    /**
     * 时钟，用于生成当前时刻。
     */
    private final Clock clock;

    /**
     * 构造检索 provider。
     *
     * @param p 技能私有配置。
     */
    public RetrievalProvider(Properties p) {
        this(p, Clock.systemUTC());
    }

    /**
     * 构造检索 provider 并注入时钟。
     *
     * @param p     技能私有配置。
     * @param clock 时钟。
     */
    RetrievalProvider(Properties p, Clock clock) {
        super("retrieval", "common.retrieval.query", "resource.retrieve", p);
        network = new DataNetwork(p);
        this.clock = clock;
    }

    /**
     * 返回本 provider 依赖的配置文件列表。
     *
     * @return 配置文件名集合。
     */
    @Override
    public Set<String> configurationFiles() {
        return Collections.singleton(RetrievalConfig.FILE);
    }

    /**
     * 校验检索配置。
     *
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    @Override
    public void validateConfiguration(String path, JSONObject config) {
        RetrievalConfig.validate(config);
    }

    /**
     * 受理前校验配置并授权全部数据源。
     *
     * @param lookup Unit 运行状态查询入口。
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    @Override
    public void preflightConfiguration(QueryProvider.ProviderInvocationLookup lookup, String path, JSONObject config) {
        RetrievalConfig.validate(config);
        for (String ref : sources(config)) {
            lookup.source(ref);
        }
    }

    /**
     * 收集配置涉及的全部数据源引用。
     *
     * @param config 检索配置。
     * @return 数据源引用集合。
     */
    private static Set<String> sources(JSONObject config) {
        Set<String> out = new TreeSet<>();
        JSONObject regions = config.getJSONObject("regions");
        if (regions.getString("kind").equals("http")) {
            out.add(regions.getString("sourceRef"));
        }
        for (Object p : config.getJSONArray("profiles")) {
            out.add(((JSONObject) p).getString("sourceRef"));
        }
        return out;
    }

    /**
     * 校验动作与参数组合。
     *
     * @param args 调用参数。
     */
    @Override
    public void validate(JSONObject args) {
        super.validate(args);
        boolean plan = args.getString("action").equals("plan");
        if (plan && (args.has("planRef") || args.has("cursor")) || !plan && !args.has("planRef")) {
            throw new StoreException("RETRIEVAL_ARGUMENTS_INVALID");
        }
    }

    /**
     * 拉取并解析 JSON 响应。
     *
     * @param context 调用上下文。
     * @param ref     数据源引用。
     * @param request 请求模板。
     * @param values  参数取值。
     * @param extra   附加参数。
     * @param limit   响应字节上限。
     * @return 解析后的 JSON。
     */
    private JSONObject json(ProviderInvocation context, String ref, JSONObject request, JSONObject values, JSONObject extra, int limit) {
        NetClient.Response response = network.get(context, ref, request, values, "args", extra, limit);
        if (!response.media().equals("application/json")) {
            throw new StoreException("RETRIEVAL_RESPONSE_INVALID");
        }
        return StrictJson.object(response.body);
    }

    /**
     * 读取区域目录。
     *
     * @param context 调用上下文。
     * @param config  检索配置。
     * @return 区域条目数组。
     */
    private JSONArray regions(ProviderInvocation context, JSONObject config) {
        JSONObject spec = config.getJSONObject("regions");
        if (spec.getString("kind").equals("inline")) {
            return RetrievalConfig.catalog(spec.getJSONArray("items"));
        }
        JSONObject raw = json(context, spec.getString("sourceRef"), spec.getJSONObject("request"), new JSONObject(), new JSONObject(), 131072);
        JSONObject response = spec.getJSONObject("response");
        if (!Boolean.TRUE.equals(ConnectorConfig.pointer(raw, response.getString("complete")))) {
            throw new StoreException("RETRIEVAL_REGION_DIRECTORY_INCOMPLETE");
        }
        JSONArray items = (JSONArray) ConnectorConfig.pointer(raw, response.getString("items"));
        JSONArray result = new JSONArray();
        if (items.length() > 128) {
            throw new StoreException("RETRIEVAL_REGION_DIRECTORY_LIMIT");
        }
        for (Object item : items) {
            JSONObject projected = new JSONObject();
            JSONObject fields = response.getJSONObject("fields");
            for (String key : fields.keySet()) {
                projected.put(key, ConnectorConfig.pointer(item, fields.getString(key)));
            }
            result.put(projected);
        }
        return RetrievalConfig.catalog(result);
    }

    /**
     * 生成对外可见的任务视图，剔除内部允许域列表。
     *
     * @param task 检索任务。
     * @return 任务视图。
     */
    private static JSONObject taskView(JSONObject task) {
        JSONObject view = new JSONObject(task.toString());
        if (view.has("region")) {
            view.getJSONObject("region").remove("allowedIds");
        }
        return view;
    }

    /**
     * 构建检索计划。
     *
     * @param context 调用上下文。
     * @param config  检索配置。
     * @param skill   当前技能名。
     * @return 检索计划结果。
     * @throws Exception 资源读取或计划构建失败。
     */
    private JSONObject plan(ProviderInvocation context, JSONObject config, String skill) throws Exception {
        JSONObject input = context.taskInput();
        JSONArray messages = input.getJSONArray("messages");
        JSONArray selections = new JSONArray();
        for (Object entry : messages) {
            JSONObject message = (JSONObject) entry;
            JSONObject selection = new JSONObject();
            selections.put(selection);
            JSONObject request = message.optJSONObject("request");
            JSONObject qcontext = message.optJSONObject("questionContext");
            if (!message.getString("kind").equals("answer") || request == null) {
                continue;
            }
            message.put("input", new JSONObject(request.getJSONObject("input").toString()));
            if (qcontext == null || !qcontext.has("retrievalPlanRef")) {
                continue;
            }
            JSONObject previous = context.confirmed("retrieval-plan", qcontext.getString("retrievalPlanRef"));
            if (!skill.equals(previous.getString("skill")) || !previous.getString("configDigest").equals(RuntimeSupport.sha(RuntimeSupport.bytes(config)))) {
                throw new StoreException("RETRIEVAL_PLAN_STALE");
            }
            Map<String, JSONObject> asked = new HashMap<>();
            for (Object q : previous.getJSONArray("questions")) {
                asked.put(((JSONObject) q).getString("questionId"), (JSONObject) q);
            }
            JSONArray answers = request.optJSONArray("answers");
            StringBuilder free = new StringBuilder(request.getJSONObject("input").getString("instruction"));
            if (answers != null) {
                for (Object a : answers) {
                    JSONObject answer = (JSONObject) a;
                    String id = answer.getString("questionId");
                    JSONObject q = asked.get(id);
                    if (q == null) {
                        throw new StoreException("RETRIEVAL_ANSWER_INVALID");
                    }
                    if (id.equals("retrieval.scope")) {
                        selection.put("resetScope", true);
                    }
                    if (answer.has("optionId")) {
                        String value = answer.getString("optionId");
                        boolean valid = false;
                        for (Object o : q.getJSONArray("options")) {
                            if (((JSONObject) o).getString("id").equals(value)) {
                                valid = true;
                            }
                        }
                        if (!valid) {
                            throw new StoreException("RETRIEVAL_ANSWER_INVALID");
                        }
                        if (id.equals("retrieval.profile")) {
                            selection.put("profileId", value);
                        } else if (id.equals("retrieval.region")) {
                            selection.put("regionId", value);
                        } else if (id.startsWith("retrieval.filter_")) {
                            if (!selection.has("filters")) {
                                selection.put("filters", new JSONObject());
                            }
                            selection.getJSONObject("filters").put(id.substring("retrieval.filter_".length()), value);
                        }
                    }
                    if (answer.has("text")) {
                        free.append(' ').append(answer.getString("text"));
                    }
                }
            }
            if ((answers == null || answers.length() == 0) && asked.containsKey("retrieval.scope")) {
                selection.put("resetScope", true);
            }
            message.getJSONObject("input").put("instruction", free.toString());
        }
        JSONArray directory = regions(context, config);
        JSONObject plan = RetrievalPlanner.plan(config, directory, input, selections, clock.instant())
                .put("skill", skill)
                .put("configDigest", RuntimeSupport.sha(RuntimeSupport.bytes(config)))
                .put("directoryDigest", RuntimeSupport.sha(RuntimeSupport.bytes(directory)));
        String ref = "retrieval-plan-" + RuntimeSupport.sha(RuntimeSupport.bytes(plan));
        plan.put("planRef", ref);
        JSONObject result = new JSONObject()
                .put("kind", "plan")
                .put("planRef", ref)
                .put("status", plan.getString("status"))
                .put("taskJson", taskView(plan.getJSONObject("task")).toString())
                .put("question", plan.getString("question"))
                .put("questionsJson", plan.getJSONArray("questions").toString());
        validateResult(new JSONObject(), result);
        context.remember("retrieval-plan", ref, plan);
        return result;
    }

    /**
     * 执行检索：计划校验、翻页拉取并投影结果。
     *
     * @param context 调用上下文。
     * @param args    调用参数。
     * @return 检索结果。
     * @throws Exception 资源读取或校验失败。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        String skill = args.getString("skill");
        JSONObject config = context.resource(skill, RetrievalConfig.FILE);
        RetrievalConfig.validate(config);
        if (args.getString("action").equals("plan")) {
            return plan(context, config, skill);
        }
        JSONObject plan = context.confirmed("retrieval-plan", args.getString("planRef"));
        JSONObject task = plan.getJSONObject("task");
        if (!skill.equals(plan.getString("skill")) || !plan.getString("configDigest").equals(RuntimeSupport.sha(RuntimeSupport.bytes(config)))
                || task.getLong("inputRevision") != context.taskInput().getLong("inputRevision")) {
            throw new StoreException("RETRIEVAL_PLAN_STALE");
        }
        if (!plan.getString("status").equals("ready")) {
            throw new StoreException("RETRIEVAL_CLARIFICATION_REQUIRED");
        }
        for (Object ref : plan.getJSONArray("sourceRefs")) {
            context.source(ref.toString());
        }
        JSONObject profile = RetrievalConfig.profile(config, task.getString("profileId"));
        JSONObject pg = profile.getJSONObject("pagination");
        JSONObject extra = new JSONObject();
        String cursor = args.optString("cursor", "");
        JSONObject prior = null;
        if (!cursor.isEmpty()) {
            prior = context.confirmed("retrieval-cursor", cursor);
            if (!prior.getString("planRef").equals(plan.getString("planRef"))) {
                throw new StoreException("RETRIEVAL_CURSOR_CONFLICT");
            }
            if (prior.getInt("page") >= profile.getInt("maxPages")) {
                throw new StoreException("RETRIEVAL_PAGE_LIMIT");
            }
            extra.put(pg.getString("cursorParameter"), prior.getString("upstream"))
                    .put(pg.getString("snapshotParameter"), prior.getString("snapshotId"));
        }
        JSONObject values = new JSONObject()
                .put("intent", "retrieve")
                .put("resourceType", task.getString("resourceType"))
                .put("from", task.has("time") ? task.getJSONObject("time").getString("from") : "")
                .put("to", task.has("time") ? task.getJSONObject("time").getString("to") : "")
                .put("regionId", task.has("region") ? task.getJSONObject("region").getString("id") : "")
                .put("regionScope", profile.getString("regionScope"))
                .put("query", task.getString("keywords"))
                .put("pageSize", profile.getInt("pageSize"));
        JSONArray filters = profile.optJSONArray("filters");
        if (filters != null) {
            for (Object f : filters) {
                String id = ((JSONObject) f).getString("id");
                values.put("filter_" + id, task.getJSONObject("filters").optString(id, ""));
            }
        }
        JSONObject raw = json(context, profile.getString("sourceRef"), profile.getJSONObject("request"), values, extra, 131072);
        JSONObject spec = profile.getJSONObject("response");
        JSONArray items = project(raw, spec, profile, task);
        String next = bounded(ConnectorConfig.pointer(raw, pg.getString("nextCursor")), 1024, true);
        String snapshot = bounded(ConnectorConfig.pointer(raw, pg.getString("snapshotId")), 1024, false);
        if (prior != null && !snapshot.equals(prior.getString("snapshotId"))) {
            throw new StoreException("RETRIEVAL_SNAPSHOT_CONFLICT");
        }
        JSONArray seen = prior == null ? new JSONArray() : prior.getJSONArray("seen");
        if (!next.isEmpty() && seen.toList().contains(next)) {
            throw new StoreException("RETRIEVAL_CURSOR_REPEATED");
        }
        String nextRef = "";
        JSONObject cursorFact = null;
        if (!next.isEmpty()) {
            seen.put(next);
            cursorFact = new JSONObject()
                    .put("planRef", plan.getString("planRef"))
                    .put("snapshotId", snapshot)
                    .put("upstream", next)
                    .put("seen", seen)
                    .put("page", prior == null ? 1 : prior.getInt("page") + 1);
            nextRef = "retrieval-cursor-" + RuntimeSupport.sha(RuntimeSupport.bytes(cursorFact));
        }
        String pageRef = "retrieval-page-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                .put("planRef", plan.getString("planRef"))
                .put("cursor", cursor)
                .put("snapshotId", snapshot)
                .put("items", items)
                .put("next", nextRef)));
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String ref = "resource-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                    .put("planRef", plan.getString("planRef"))
                    .put("snapshotId", snapshot)
                    .put("item", item)));
            item.put("resourceRef", ref);
        }
        JSONObject result = new JSONObject()
                .put("kind", "page")
                .put("planRef", plan.getString("planRef"))
                .put("status", "ready")
                .put("taskJson", taskView(task).toString())
                .put("pageRef", pageRef)
                .put("itemsJson", items.toString())
                .put("nextCursor", nextRef)
                .put("snapshotId", snapshot)
                .put("complete", nextRef.isEmpty());
        validateResult(args, result);
        if (cursorFact != null) {
            context.remember("retrieval-cursor", nextRef, cursorFact);
        }
        for (Object entry : items) {
            JSONObject item = (JSONObject) entry;
            context.remember("retrieval-resource", item.getString("resourceRef"), new JSONObject(item.toString())
                    .put("skill", skill)
                    .put("sourceRef", profile.getString("sourceRef"))
                    .put("planRef", plan.getString("planRef")));
        }
        context.remember("retrieval-page", pageRef, new JSONObject()
                .put("skill", skill)
                .put("sourceRefs", plan.getJSONArray("sourceRefs"))
                .put("planRef", plan.getString("planRef"))
                .put("task", task)
                .put("items", items)
                .put("cursor", cursor)
                .put("nextCursor", nextRef)
                .put("snapshotId", snapshot)
                .put("pageRef", pageRef)
                .put("inputRevision", task.getLong("inputRevision")));
        return result;
    }

    /**
     * 校验上游响应文本的长度与空值。
     *
     * @param value 上游值。
     * @param max   最大长度。
     * @param empty 是否允许空串。
     * @return 文本值。
     */
    static String bounded(Object value, int max, boolean empty) {
        if (!(value instanceof String) || ((String) value).length() > max || !empty && ((String) value).trim().isEmpty()) {
            throw new StoreException("RETRIEVAL_RESPONSE_INVALID");
        }
        return (String) value;
    }

    /**
     * 按档案定义投影上游条目，并核对范围与类型。
     *
     * @param raw      上游原始响应。
     * @param response 响应映射定义。
     * @param profile  检索档案。
     * @param task     检索任务。
     * @return 投影后的条目数组。
     */
    static JSONArray project(JSONObject raw, JSONObject response, JSONObject profile, JSONObject task) {
        JSONArray source = (JSONArray) ConnectorConfig.pointer(raw, response.getString("items"));
        JSONArray items = new JSONArray();
        if (source.length() > profile.getInt("pageSize")) {
            throw new StoreException("RETRIEVAL_RESULT_LIMIT");
        }
        JSONObject fields = response.getJSONObject("fields");
        Set<String> ids = new HashSet<>();
        for (Object entry : source) {
            JSONObject item = new JSONObject();
            for (String field : fields.keySet()) {
                item.put(field, ConnectorConfig.pointer(entry, fields.getString(field)));
            }
            for (String key : Arrays.asList("id", "version", "title", "mediaType")) {
                bounded(item.get(key), key.equals("title") ? 256 : 128, false);
            }
            if (!ids.add(new JSONArray().put(item.getString("id")).put(item.getString("version")).toString())) {
                throw new StoreException("RETRIEVAL_RESULT_CONFLICT");
            }
            if (!profile.getJSONArray("mediaTypes").toList().contains(item.getString("mediaType"))) {
                throw new StoreException("RETRIEVAL_MEDIA_TYPE_UNSUPPORTED");
            }
            if (item.has("summary")) {
                bounded(item.get("summary"), 512, true);
            }
            JSONArray configuredFilters = profile.optJSONArray("filters");
            if (configuredFilters != null) {
                for (Object f : configuredFilters) {
                    JSONObject filter = (JSONObject) f;
                    String value = bounded(item.get("filter_" + filter.getString("id")), 128, false);
                    boolean known = false;
                    for (Object v : filter.getJSONArray("values")) {
                        if (((JSONObject) v).getString("id").equals(value)) {
                            known = true;
                        }
                    }
                    if (!known) {
                        throw new StoreException("RETRIEVAL_RESPONSE_INVALID");
                    }
                }
            }
            for (String filter : task.getJSONObject("filters").keySet()) {
                if (!task.getJSONObject("filters").getString(filter).equals(item.optString("filter_" + filter))) {
                    throw new StoreException("RETRIEVAL_SCOPE_VIOLATION");
                }
            }
            if (task.has("region")) {
                if (!item.has("regionId") || !task.getJSONObject("region").getJSONArray("allowedIds").toList().contains(item.getString("regionId"))) {
                    throw new StoreException("RETRIEVAL_SCOPE_VIOLATION");
                }
            }
            String mode = profile.getJSONObject("time").getString("mode");
            if (!mode.equals("none")) {
                try {
                    Instant start = OffsetDateTime.parse(bounded(item.get("start"), 64, false)).toInstant();
                    Instant end = mode.equals("overlap") ? OffsetDateTime.parse(bounded(item.get("end"), 64, false)).toInstant() : start;
                    if (mode.equals("overlap") && !start.isBefore(end)) {
                        throw new IllegalArgumentException();
                    }
                    if (task.has("time")) {
                        Instant from = OffsetDateTime.parse(task.getJSONObject("time").getString("from")).toInstant();
                        Instant to = OffsetDateTime.parse(task.getJSONObject("time").getString("to")).toInstant();
                        if (mode.equals("overlap") ? !(start.isBefore(to) && end.isAfter(from)) : start.isBefore(from) || !start.isBefore(to)) {
                            throw new StoreException("RETRIEVAL_SCOPE_VIOLATION");
                        }
                    }
                } catch (StoreException e) {
                    throw e;
                } catch (Exception e) {
                    throw new StoreException("RETRIEVAL_RESPONSE_INVALID");
                }
            }
            item.put("resourceType", profile.getString("resourceType"));
            items.put(item);
        }
        return items;
    }

    /**
     * 释放技能持有的网络资源。
     */
    @Override
    public void close() {
        network.close();
    }
}
