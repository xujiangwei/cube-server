/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.execution.QueryProvider;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 数据查询技能：一次有界的分页拉取，或一次绑定的快照。
 *
 * <p>不保存模型自行给出的任意 URL；游标是不透明状态，只能由服务端签发。</p>
 */
public final class DataQueryProvider extends AbstractProvider {
    /**
     * 输入文件与上游响应体的字节上限：1 MiB。
     */
    private static final int MAX_PAYLOAD_BYTES = 1048576;

    /**
     * 单页允许返回的最大记录数。
     */
    private static final int MAX_ITEMS = 16;

    /**
     * 上游游标允许的最大长度。
     */
    private static final int MAX_CURSOR_LENGTH = 1024;

    /**
     * 已见过的游标保留条数上限，用于识别上游重复签发。
     */
    private static final int MAX_SEEN_CURSORS = 128;

    /**
     * 游标状态序列化后的字节上限。
     */
    private static final int MAX_STATE_BYTES = 32768;

    /**
     * 标识类字段的最大长度。
     */
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    /**
     * 支持登记的媒体类型。
     */
    private static final Set<String> MEDIA_TYPES = new HashSet<>(
            Arrays.asList("image/jpeg", "image/png"));

    /**
     * 外部数据源访问器。
     */
    private final DataNetwork network;

    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public DataQueryProvider(Properties p) {
        super("data", "common.data.query", "data.query", p);
        this.network = new DataNetwork(p);
    }

    /**
     * 返回技能需要读取的配置文件清单。
     *
     * @return 返回配置文件名集合。
     */
    @Override
    public Set<String> configurationFiles() {
        return Collections.singleton("connectors.json");
    }

    /**
     * 校验技能配置。
     *
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    @Override
    public void validateConfiguration(String path, JSONObject config) {
        ConnectorConfig.validate(config);
    }

    /**
     * 受理前检查：声明了 {@code sourceRef} 的连接器，其数据源必须当前可用。
     *
     * @param lookup 数据源状态查询入口。
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    @Override
    public void preflightConfiguration(QueryProvider.ProviderInvocationLookup lookup, String path, JSONObject config) {
        validateConfiguration(path, config);
        JSONArray list = config.getJSONArray("connectors");
        for (int i = 0; i < list.length(); i++) {
            JSONObject connector = list.getJSONObject(i);
            if (connector.has("sourceRef")) {
                lookup.source(connector.getString("sourceRef"));
            }
        }
    }

    /**
     * 执行一次查询：取回一页记录或一份快照，并登记媒体引用。
     *
     * @param context 可信调用上下文。
     * @param args    调用参数。
     * @return 返回查询结果，含记录、媒体引用与下一页游标。
     * @throws Exception 读取输入或访问数据源过程中的异常。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) throws Exception {
        // 1) 解析连接器定义与调用参数，并算出本次查询的指纹。
        String skill = args.getString("skill");
        JSONObject config = context.resource(skill, "connectors.json");
        ConnectorConfig.validate(config);
        JSONObject connector = ConnectorConfig.select(config, "connectors", args.getString("connectorId"));
        JSONObject input = ConnectorConfig.arguments(args.getString("argumentsJson"));
        Contract.validate(input, connector.getJSONObject("inputSchema"));
        boolean snapshot = connector.getString("kind").equals("snapshot");
        JSONObject query = new JSONObject()
                .put("skill", skill)
                .put("connector", connector)
                .put("arguments", input);
        String queryHash = RuntimeSupport.sha(RuntimeSupport.bytes(query));

        // 2) 续传游标必须属于同一次查询，防止跨查询复用。
        JSONObject previous = null;
        String cursor = args.optString("cursor", "");
        if (!cursor.isEmpty()) {
            if (snapshot) {
                ConnectorConfig.bad();
            }
            previous = context.confirmed("cursor", cursor);
            if (!previous.getString("queryHash").equals(queryHash)) {
                throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
        }

        // 3) 取回上游内容：快照读受控输入，分页走只读数据源。
        JSONObject response;
        if (snapshot) {
            String inputId = input.getString(connector.getString("inputArgument"));
            byte[] raw = context.input(inputId, MAX_PAYLOAD_BYTES, Collections.singleton("application/json"));
            response = StrictJson.object(raw);
        } else {
            JSONObject page = connector.getJSONObject("pagination");
            JSONObject extra = new JSONObject();
            if (previous != null) {
                extra.put(page.getString("cursorParameter"), previous.getString("upstream"))
                        .put(page.getString("snapshotParameter"), previous.getString("snapshot"));
            }
            NetClient.Response raw = this.network.get(context, connector.getString("sourceRef"),
                    connector.getJSONObject("request"), input, "args", extra, MAX_PAYLOAD_BYTES);
            if (!raw.media().equals("application/json")) {
                throw new StoreException("DATA_QUERY_INCOMPLETE");
            }
            response = StrictJson.object(raw.body);
        }

        // 4) 按声明的字段投影逐条组装记录，并为每条记录登记媒体引用。
        JSONObject responseSpec = connector.getJSONObject("response");
        JSONArray items = (JSONArray) ConnectorConfig.pointer(response, responseSpec.getString("items"));
        if (items.length() > MAX_ITEMS) {
            throw new StoreException("RESULT_BUDGET_EXCEEDED");
        }
        JSONArray records = new JSONArray();
        JSONArray refs = new JSONArray();
        JSONArray warnings = new JSONArray();
        JSONObject fields = responseSpec.getJSONObject("fields");
        JSONObject media = connector.getJSONObject("media");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject record = new JSONObject();
            for (String field : fields.keySet()) {
                record.put(field, ConnectorConfig.pointer(item, fields.getString(field)));
            }
            identifier(record.get("recordId"));
            if (record.has("recordVersion")) {
                identifier(record.get("recordVersion"));
            }
            if (record.has("capturedAt") && !record.isNull("capturedAt")) {
                try {
                    OffsetDateTime.parse(record.getString("capturedAt"));
                } catch (Exception invalid) {
                    throw new StoreException("DATA_QUERY_INCOMPLETE");
                }
            }
            String id = identifier(ConnectorConfig.pointer(item, media.getString("id")));
            String version = identifier(ConnectorConfig.pointer(item, media.getString("version")));
            String type = ConnectorConfig.scalar(ConnectorConfig.pointer(item, media.getString("mediaType")));
            if (!MEDIA_TYPES.contains(type)) {
                throw new StoreException("MEDIA_TYPE_UNSUPPORTED");
            }
            JSONObject fact = new JSONObject()
                    .put("skill", skill)
                    .put("connectorId", args.getString("connectorId"))
                    .put("record", record)
                    .put("mediaId", id)
                    .put("mediaVersion", version)
                    .put("mediaType", type)
                    .put("queryHash", queryHash)
                    .put("timeTrust", snapshot ? "provider-declared" : "authorized-source");
            if (snapshot) {
                String inputId = ConnectorConfig.scalar(ConnectorConfig.pointer(item, media.getString("inputId")));
                context.input(inputId, MAX_PAYLOAD_BYTES, Collections.singleton("application/octet-stream"));
                fact.put("inputId", inputId);
            } else {
                fact.put("sourceRef", connector.getString("sourceRef"));
            }
            JSONObject seed = new JSONObject().put("job", context.jobId).put("fact", fact);
            String ref = "media-" + RuntimeSupport.sha(RuntimeSupport.bytes(seed));
            context.remember("media", ref, fact);
            record.put("mediaRef", ref);
            records.put(record);
            refs.put(new JSONObject()
                    .put("mediaRef", ref)
                    .put("recordId", record.getString("recordId"))
                    .put("mediaId", id)
                    .put("mediaVersion", version)
                    .put("mediaType", type));
        }

        // 5) 计算下一页游标：上游游标不得重复签发，快照必须保持不变。
        String next = "";
        String snapshotId = snapshot ? "input:" + input.getString(connector.getString("inputArgument")) : "";
        if (!snapshot) {
            JSONObject page = connector.getJSONObject("pagination");
            String upstream = (String) ConnectorConfig.pointer(response, page.getString("nextCursor"));
            String snap = (String) ConnectorConfig.pointer(response, page.getString("snapshotPointer"));
            snapshotId = snap;
            if (snap.isEmpty() || snap.length() > MAX_CURSOR_LENGTH || upstream.length() > MAX_CURSOR_LENGTH) {
                throw new StoreException("DATA_QUERY_INCOMPLETE");
            }
            if (previous != null
                    && (!previous.getString("snapshot").equals(snap)
                    || previous.getJSONArray("seen").toList().contains(upstream))) {
                throw new StoreException("DATA_QUERY_INCOMPLETE");
            }
            if (!upstream.isEmpty()) {
                JSONArray seen = previous == null ? new JSONArray() : previous.getJSONArray("seen");
                if (seen.length() >= MAX_SEEN_CURSORS) {
                    throw new StoreException("DATA_QUERY_INCOMPLETE");
                }
                seen.put(upstream);
                JSONObject state = new JSONObject()
                        .put("queryHash", queryHash)
                        .put("snapshot", snap)
                        .put("upstream", upstream)
                        .put("seen", seen);
                if (RuntimeSupport.bytes(state).length > MAX_STATE_BYTES) {
                    throw new StoreException("DATA_QUERY_INCOMPLETE");
                }
                JSONObject seed = new JSONObject().put("job", context.jobId).put("state", state);
                next = "page-" + RuntimeSupport.sha(RuntimeSupport.bytes(seed));
                context.remember("cursor", next, state);
            }
        } else {
            warnings.put("SNAPSHOT_ONLY_PROVIDER_DECLARED_TIME");
        }

        // 6) 组装结果：没有下一页游标即表示本次查询已完整。
        JSONObject pageFact = new JSONObject().put("skill", skill).put("connectorId", args.getString("connectorId"))
                .put("queryHash", queryHash).put("snapshotId", snapshotId).put("cursor", cursor)
                .put("nextCursor", next).put("records", records).put("mediaRefs", refs).put("warnings", warnings);
        if (!snapshot) pageFact.put("sourceRef", connector.getString("sourceRef"));
        String pageRef = "data-page-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject()
                .put("job", context.jobId).put("page", pageFact)));
        JSONObject out = new JSONObject()
                .put("connectorId", args.getString("connectorId"))
                .put("pageRef", pageRef)
                .put("recordsJson", records.toString())
                .put("mediaRefs", refs)
                .put("nextCursor", next)
                .put("complete", next.isEmpty())
                .put("warnings", warnings);
        validateResult(args, out);
        context.remember("data-page", pageRef, pageFact);
        return out;
    }

    /**
     * 校验标识类字段：必须是非空且长度受限的字符串。
     *
     * @param value 待校验的值。
     * @return 返回字符串形式的标识。
     */
    private static String identifier(Object value) {
        if (!(value instanceof String)
                || ((String) value).isEmpty()
                || ((String) value).length() > MAX_IDENTIFIER_LENGTH) {
            throw new StoreException("DATA_QUERY_INCOMPLETE");
        }
        return (String) value;
    }

    /**
     * 释放技能持有的网络资源。
     */
    @Override
    public void close() {
        this.network.close();
    }
}
