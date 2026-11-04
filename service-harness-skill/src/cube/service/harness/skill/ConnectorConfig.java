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
 *
 * <p>配置里的每个键、每个正则与每处替换都在这里收敛，任何超出声明集合的内容
 * 都以 {@code CONNECTOR_CONFIG_INVALID} 拒绝。</p>
 */
public final class ConnectorConfig {
    /**
     * 单个集合允许的最大条目数。
     */
    private static final int MAX_ENTRIES = 32;

    /**
     * 请求模板允许的最大查询参数个数。
     */
    private static final int MAX_QUERY_PARAMETERS = 16;

    /**
     * 单个查询参数值的最大长度。
     */
    private static final int MAX_QUERY_VALUE_LENGTH = 256;

    /**
     * 数据源引用的格式：{@code <sourceId>@<version>} 。
     */
    private static final String SOURCE_REF_PATTERN = "[A-Za-z0-9_.:-]{1,128}@[A-Za-z0-9_.:-]{1,128}";

    /**
     * 请求路径的格式：以斜杠开头的固定字符集。
     */
    private static final String PATH_PATTERN = "/[A-Za-z0-9_/-]+";

    /**
     * 查询参数名的格式。
     */
    private static final String PARAMETER_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,63}";

    /**
     * 数组下标的格式：非负整数且没有前导零。
     */
    private static final String INDEX_PATTERN = "0|[1-9][0-9]*";

    /**
     * 检测 RFC6901 指针里非法转义序列（{@code ~} 之后不是 0 或 1）。
     */
    private static final String BAD_ESCAPE_PATTERN = ".*~(?:[^01]|$).*";

    /**
     * 工具类，禁止实例化。
     */
    private ConnectorConfig() {
    }

    /**
     * 校验对象的键集合：只允许 {@code required} 与 {@code optional} 中声明的键，且必填键必须齐备。
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
            if (raw.matches(BAD_ESCAPE_PATTERN)) {
                bad();
            }
            // 先还原 ~1 与 ~0，再按对象键或数组下标取值。
            String key = raw.replace("~1", "/").replace("~0", "~");
            if (current instanceof JSONObject && ((JSONObject) current).has(key)) {
                current = ((JSONObject) current).get(key);
            } else if (current instanceof JSONArray && key.matches(INDEX_PATTERN)) {
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
        if (values.length() > MAX_ENTRIES) {
            bad();
        }
        Set<String> seen = new HashSet<>();
        JSONObject found = null;
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
     * <p>两种连接器共享 response/media 结构，区别在于数据来源是受控输入（snapshot）
     * 还是只读 HTTP 数据源（http-json-readonly）。</p>
     *
     * @param c 连接器定义。
     */
    public static void connector(JSONObject c) {
        String kind = c.getString("kind");
        if (kind.equals("snapshot")) {
            keys(c, "id,kind,inputArgument,inputSchema,response,media", "");
        } else if (kind.equals("http-json-readonly")) {
            keys(c, "id,kind,inputSchema,response,sourceRef,request,pagination,media", "");
            if (!c.getString("sourceRef").matches(SOURCE_REF_PATTERN)) {
                bad();
            }
            request(c.getJSONObject("request"), "args");
            JSONObject pg = c.getJSONObject("pagination");
            keys(pg, "nextCursor,snapshotPointer,cursorParameter,snapshotParameter", "");
            if (pg.getString("cursorParameter").equals(pg.getString("snapshotParameter"))) {
                bad();
            }
            // 游标参数不能与请求模板里已声明的查询参数重名。
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
        if (!fields.has("recordId") || fields.length() > MAX_ENTRIES || fields.has("mediaRef")) {
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
     * @param scope 允许的替换域，取值 {@code args} 或 {@code media} 。
     */
    static void request(JSONObject r, String scope) {
        keys(r, "method,path,query", "");
        String path = r.getString("path");
        // 只允许只读 GET，路径不得出现穿越片段或空段。
        boolean badMethod = !r.getString("method").equals("GET");
        boolean badPath = !path.matches(PATH_PATTERN) || path.contains("..") || path.contains("//");
        if (badMethod || badPath) {
            bad();
        }
        JSONObject query = r.getJSONObject("query");
        if (query.length() > MAX_QUERY_PARAMETERS) {
            bad();
        }
        for (String key : query.keySet()) {
            if (!key.matches(PARAMETER_PATTERN)) {
                bad();
            }
            String value = query.getString(key);
            boolean hasPlaceholder = value.contains("${");
            if (value.length() > MAX_QUERY_VALUE_LENGTH) {
                bad();
            }
            // 占位符必须整段匹配，且只允许引用本域的参数。
            if (hasPlaceholder && !value.matches("\\$\\{" + scope + "\\.[A-Za-z][A-Za-z0-9_]*}")) {
                bad();
            }
            if (scope.equals("media") && hasPlaceholder
                    && !Arrays.asList("${media.id}", "${media.version}").contains(value)) {
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
