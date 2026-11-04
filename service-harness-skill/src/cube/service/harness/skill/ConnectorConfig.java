/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 连接器配置的封闭配置语言：只允许标量替换与 RFC6901 指针，不含任何代码。
 */
public final class ConnectorConfig {
    /**
     * 工具类，禁止实例化。
     */
    private ConnectorConfig() {
    }

    /**
     * 校验对象的键集合：只允许 required 与 optional 中声明的键，且 required 必须齐备。
     *
     * @param o        待校验的对象。
     * @param required 必填键，逗号分隔。
     * @param optional 可选键，逗号分隔。
     */
    public static void keys(JSONObject o, String required, String optional) {
        Set<String> allowed = new HashSet<>(Arrays.asList((required + "," + optional).split(",")));
        for (String key : o.keySet()) {
            if (!allowed.contains(key)) {
                bad();
            }
        }
        for (String key : required.split(",")) {
            if (!key.isEmpty() && !o.has(key)) {
                bad();
            }
        }
    }

    /**
     * 配置非法时抛出。
     */
    static void bad() {
        throw new StoreException("CONNECTOR_CONFIG_INVALID");
    }

    /**
     * 按 RFC6901 指针在 JSON 结构中取值。
     *
     * @param root 根对象。
     * @param path 指针路径，空串表示返回根对象。
     * @return 返回取到的值。
     */
    public static Object pointer(Object root, String path) {
        if (path.isEmpty()) {
            return root;
        }
        if (!path.startsWith("/")) {
            bad();
        }
        Object current = root;
        for (String raw : path.substring(1).split("/", -1)) {
            if (raw.matches(".*~(?:[^01]|$).*")) {
                bad();
            }
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current instanceof JSONObject && ((JSONObject) current).has(key)) {
                current = ((JSONObject) current).get(key);
            } else if (current instanceof JSONArray && key.matches("0|[1-9][0-9]*")) {
                try {
                    current = ((JSONArray) current).get(Integer.parseInt(key));
                } catch (Exception e) {
                    throw new StoreException("DATA_QUERY_INCOMPLETE");
                }
            } else {
                throw new StoreException("DATA_QUERY_INCOMPLETE");
            }
        }
        return current;
    }

    /**
     * 按 id 从集合中选出唯一条目，同时校验集合本身的合法性。
     *
     * @param config     配置对象。
     * @param collection 集合字段名。
     * @param id         目标条目 id。
     * @return 返回选中的条目。
     */
    public static JSONObject select(JSONObject config, String collection, String id) {
        if (!"1".equals(config.getString("version"))) {
            bad();
        }
        JSONArray values = config.getJSONArray(collection);
        Set<String> seen = new HashSet<>();
        JSONObject found = null;
        if (values.length() > 32) {
            bad();
        }
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            RuntimeSupport.id(value.getString("id"));
            if (!seen.add(value.getString("id"))) {
                bad();
            }
            if (value.getString("id").equals(id)) {
                found = value;
            }
        }
        if (found == null) {
            bad();
        }
        return found;
    }

    /**
     * 校验整份连接器配置。
     *
     * @param config 配置对象。
     */
    public static void validate(JSONObject config) {
        keys(config, "version,connectors", "");
        JSONArray list = config.getJSONArray("connectors");
        for (int i = 0; i < list.length(); i++) {
            connector(select(config, "connectors", list.getJSONObject(i).getString("id")));
        }
    }

    /**
     * 校验单个连接器定义。
     *
     * @param c 连接器定义。
     */
    public static void connector(JSONObject c) {
        String kind = c.getString("kind");
        if (kind.equals("snapshot")) {
            keys(c, "id,kind,inputArgument,inputSchema,response,media", "");
        } else if (kind.equals("http-json-readonly")) {
            keys(c, "id,kind,inputSchema,response,sourceRef,request,pagination,media", "");
            if (!c.getString("sourceRef").matches("[A-Za-z0-9_.:-]{1,128}@[A-Za-z0-9_.:-]{1,128}")) {
                bad();
            }
            request(c.getJSONObject("request"), "args");
            JSONObject pg = c.getJSONObject("pagination");
            keys(pg, "nextCursor,snapshotPointer,cursorParameter,snapshotParameter", "");
            if (pg.getString("cursorParameter").equals(pg.getString("snapshotParameter"))) {
                bad();
            }
            for (String k : Arrays.asList("cursorParameter", "snapshotParameter")) {
                if (c.getJSONObject("request").getJSONObject("query").has(pg.getString(k))) {
                    bad();
                }
            }
        } else {
            bad();
        }
        RuntimeSupport.toolSchema(c.getJSONObject("inputSchema"));
        JSONObject response = c.getJSONObject("response");
        keys(response, "items,fields", "");
        JSONObject fields = response.getJSONObject("fields");
        if (!fields.has("recordId") || fields.length() > 32 || fields.has("mediaRef")) {
            bad();
        }
        for (String key : fields.keySet()) {
            if (!(fields.get(key) instanceof String)) {
                bad();
            }
        }
        JSONObject media = c.getJSONObject("media");
        keys(media, "id,version,mediaType," + (kind.equals("snapshot") ? "inputId" : "request"), "");
        if (!kind.equals("snapshot")) {
            request(media.getJSONObject("request"), "media");
        }
    }

    /**
     * 校验请求模板：方法、路径与查询参数都受限。
     *
     * @param r     请求模板。
     * @param scope 允许的替换域，取值 args 或 media 。
     */
    static void request(JSONObject r, String scope) {
        keys(r, "method,path,query", "");
        String path = r.getString("path");
        if (!r.getString("method").equals("GET") || !path.matches("/[A-Za-z0-9_/-]+") || path.contains("..") || path.contains("//")) {
            bad();
        }
        JSONObject query = r.getJSONObject("query");
        if (query.length() > 16) {
            bad();
        }
        for (String key : query.keySet()) {
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                bad();
            }
            String value = query.getString(key);
            if (value.length() > 256 || (value.contains("${") && !value.matches("\\$\\{" + scope + "\\.[A-Za-z][A-Za-z0-9_]*}"))) {
                bad();
            }
            if (scope.equals("media") && value.contains("${") && !Arrays.asList("${media.id}", "${media.version}").contains(value)) {
                bad();
            }
        }
    }

    /**
     * 把 JSON 文本解析为对象，语法不合法时直接拒绝。
     *
     * @param text JSON 文本。
     * @return 返回解析后的对象。
     */
    static JSONObject arguments(String text) {
        return StrictJson.object(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把标量值转成字符串，只接受字符串、数字与布尔值。
     *
     * @param value 待转换的值。
     * @return 返回字符串形式的值。
     */
    static String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            bad();
        }
        return value.toString();
    }
}
