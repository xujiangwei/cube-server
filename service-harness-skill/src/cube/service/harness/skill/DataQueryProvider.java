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
import java.util.Properties;
import java.util.Set;

/**
 * 数据查询技能：一次有界的分页拉取，或一次绑定的快照。
 *
 * <p>不保存模型自行给出的任意 URL；游标是不透明状态，只能由服务端签发。</p>
 */
public final class DataQueryProvider extends AbstractProvider {
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
        network = new DataNetwork(p);
    }

    /**
     * 返回技能需要读取的配置文件清单。
     *
     * @return 返回配置文件名集合。
     */
    public Set<String> configurationFiles() {
        return Collections.singleton("connectors.json");
    }

    /**
     * 校验技能配置。
     *
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    public void validateConfiguration(String path, JSONObject config) {
        ConnectorConfig.validate(config);
    }

    /**
     * 受理前检查：声明了 sourceRef 的连接器，其数据源必须当前可用。
     *
     * @param lookup 数据源状态查询入口。
     * @param path   配置文件路径。
     * @param config 配置内容。
     */
    public void preflightConfiguration(QueryProvider.ProviderInvocationLookup lookup, String path, JSONObject config) {
        validateConfiguration(path, config);
        JSONArray list = config.getJSONArray("connectors");
        for (int i = 0; i < list.length(); i++) {
            if (list.getJSONObject(i).has("sourceRef")) {
                lookup.source(list.getJSONObject(i).getString("sourceRef"));
            }
        }
    }

    /**
     * 执行一次查询：取回一页记录或一份快照，并登记媒体引用。
     *
     * @param c    Broker 提供的可信调用上下文。
     * @param args 调用参数。
     * @return 返回查询结果，含记录、媒体引用与下一页游标。
     * @throws Exception 读取输入或访问数据源过程中的异常。
     */
    protected JSONObject execute(ProviderInvocation c, JSONObject args) throws Exception {
        JSONObject config = c.resource(args.getString("skill"), "connectors.json");
        ConnectorConfig.validate(config);
        JSONObject connector = ConnectorConfig.select(config, "connectors", args.getString("connectorId"));
        JSONObject input = ConnectorConfig.arguments(args.getString("argumentsJson"));
        Contract.validate(input, connector.getJSONObject("inputSchema"));
        boolean snapshot = connector.getString("kind").equals("snapshot");
        String queryHash = RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject().put("skill", args.getString("skill")).put("connector", connector).put("arguments", input)));
        JSONObject previous = null;
        JSONObject response;
        String cursor = args.optString("cursor", "");
        if (!cursor.isEmpty()) {
            if (snapshot) {
                ConnectorConfig.bad();
            }
            previous = c.confirmed("cursor", cursor);
            if (!previous.getString("queryHash").equals(queryHash)) {
                throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
        }
        if (snapshot) {
            response = StrictJson.object(c.input(input.getString(connector.getString("inputArgument")), 1048576, Collections.singleton("application/json")));
        } else {
            JSONObject extra = new JSONObject();
            JSONObject page = connector.getJSONObject("pagination");
            if (previous != null) {
                extra.put(page.getString("cursorParameter"), previous.getString("upstream"))
                        .put(page.getString("snapshotParameter"), previous.getString("snapshot"));
            }
            NetClient.Response raw = network.get(c, connector.getString("sourceRef"), connector.getJSONObject("request"), input, "args", extra, 1048576);
            if (!raw.media().equals("application/json")) {
                throw new StoreException("DATA_QUERY_INCOMPLETE");
            }
            response = StrictJson.object(raw.body);
        }
        JSONArray items = (JSONArray) ConnectorConfig.pointer(response, connector.getJSONObject("response").getString("items"));
        if (items.length() > 16) {
            throw new StoreException("RESULT_BUDGET_EXCEEDED");
        }
        JSONArray records = new JSONArray();
        JSONArray refs = new JSONArray();
        JSONArray warnings = new JSONArray();
        JSONObject fields = connector.getJSONObject("response").getJSONObject("fields");
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
            if (!Arrays.asList("image/jpeg", "image/png").contains(type)) {
                throw new StoreException("MEDIA_TYPE_UNSUPPORTED");
            }
            JSONObject fact = new JSONObject().put("skill", args.getString("skill")).put("connectorId", args.getString("connectorId"))
                    .put("record", record).put("mediaId", id).put("mediaVersion", version).put("mediaType", type).put("queryHash", queryHash)
                    .put("timeTrust", snapshot ? "provider-declared" : "authorized-source");
            if (snapshot) {
                String inputId = ConnectorConfig.scalar(ConnectorConfig.pointer(item, media.getString("inputId")));
                c.input(inputId, 1048576, Collections.singleton("application/octet-stream"));
                fact.put("inputId", inputId);
            } else {
                fact.put("sourceRef", connector.getString("sourceRef"));
            }
            String ref = "media-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject().put("job", c.jobId).put("fact", fact)));
            c.remember("media", ref, fact);
            record.put("mediaRef", ref);
            records.put(record);
            refs.put(new JSONObject().put("mediaRef", ref).put("recordId", record.getString("recordId")).put("mediaId", id).put("mediaVersion", version).put("mediaType", type));
        }
        String next = "";
        if (!snapshot) {
            JSONObject pg = connector.getJSONObject("pagination");
            String upstream = (String) ConnectorConfig.pointer(response, pg.getString("nextCursor"));
            String snap = (String) ConnectorConfig.pointer(response, pg.getString("snapshotPointer"));
            if (snap.isEmpty() || snap.length() > 1024 || upstream.length() > 1024
                    || (previous != null && (!previous.getString("snapshot").equals(snap) || previous.getJSONArray("seen").toList().contains(upstream)))) {
                throw new StoreException("DATA_QUERY_INCOMPLETE");
            }
            if (!upstream.isEmpty()) {
                JSONArray seen = previous == null ? new JSONArray() : previous.getJSONArray("seen");
                if (seen.length() >= 128) {
                    throw new StoreException("DATA_QUERY_INCOMPLETE");
                }
                seen.put(upstream);
                JSONObject state = new JSONObject().put("queryHash", queryHash).put("snapshot", snap).put("upstream", upstream).put("seen", seen);
                if (RuntimeSupport.bytes(state).length > 32768) {
                    throw new StoreException("DATA_QUERY_INCOMPLETE");
                }
                next = "page-" + RuntimeSupport.sha(RuntimeSupport.bytes(new JSONObject().put("job", c.jobId).put("state", state)));
                c.remember("cursor", next, state);
            }
        } else {
            warnings.put("SNAPSHOT_ONLY_PROVIDER_DECLARED_TIME");
        }
        JSONObject out = new JSONObject().put("connectorId", args.getString("connectorId")).put("recordsJson", records.toString()).put("mediaRefs", refs)
                .put("nextCursor", next).put("complete", next.isEmpty()).put("warnings", warnings);
        validateResult(args, out);
        return out;
    }

    /**
     * 校验标识类字段：必须是非空且长度受限的字符串。
     *
     * @param value 待校验的值。
     * @return 返回字符串形式的标识。
     */
    private static String identifier(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > 128) {
            throw new StoreException("DATA_QUERY_INCOMPLETE");
        }
        return (String) value;
    }

    /**
     * 释放技能持有的网络资源。
     */
    public void close() {
        network.close();
    }
}
