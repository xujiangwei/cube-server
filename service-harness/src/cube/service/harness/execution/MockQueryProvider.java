/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.*;

/**
 * 显式启用的 loopback-only 离线夹具 provider（mockconsole.query_status），仅用于协议回归。
 *
 * <p>它不是业务集成：核心不包含 Console 业务实现，真实业务由各 SKILL/provider 独立提供。
 * 地址仅允许 http + loopback（禁 userInfo/query/ref），token 至少 24 字符；目标与字段必须
 * 落在 broker.mock.targets / broker.mock.fields 声明的范围内，查询结果按请求投影，核心再按
 * 固定 schema 校验。
 */
public final class MockQueryProvider implements QueryProvider {

    /**
     * mock broker 地址：仅 http + loopback，且不带 userInfo/query/ref。
     */
    private final URL endpoint;

    /**
     * 上游 Bearer token（broker.mock.token，至少 24 字符）。
     */
    private final String token;

    /**
     * 允许查询的目标标识集合（已规范化，有序）。
     */
    private final Set<String> targets = new TreeSet<>();

    /**
     * 允许查询的字段集合（cpuPercent/memoryMiB 的子集，有序）。
     */
    private final Set<String> fields = new TreeSet<>();

    /**
     * 从私有配置构造夹具 provider 并校验全部范围约束。
     *
     * @param p Harness 配置（broker.mock.url/token/targets/fields）
     * @throws Exception                URL 构造或 DNS 解析失败
     * @throws IllegalArgumentException 地址非 loopback http（MOCK_BROKER_LOOPBACK_REQUIRED）、
     *                                  token 过短（BROKER_PRIVATE_TOKEN_REQUIRED）或范围为空/字段越界
     *                                  （BROKER_SCOPE_REQUIRED）
     */
    public MockQueryProvider(Properties p) throws Exception {
        endpoint = new URL(p.getProperty("broker.mock.url", ""));
        if (!endpoint.getProtocol().equals("http")
                || !InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getRef() != null) {
            throw new IllegalArgumentException("MOCK_BROKER_LOOPBACK_REQUIRED");
        }
        token = p.getProperty("broker.mock.token", "");
        if (token.length() < 24) {
            throw new IllegalArgumentException("BROKER_PRIVATE_TOKEN_REQUIRED");
        }
        for (String t : p.getProperty("broker.mock.targets", "").split(",")) {
            if (!t.isEmpty()) {
                targets.add(RuntimeSupport.id(t));
            }
        }
        for (String f : p.getProperty("broker.mock.fields", "cpuPercent,memoryMiB").split(",")) {
            if (!f.isEmpty()) {
                fields.add(RuntimeSupport.id(f));
            }
        }
        if (targets.isEmpty()
                || fields.isEmpty()
                || !new HashSet<>(Arrays.asList("cpuPercent", "memoryMiB")).containsAll(fields)) {
            throw new IllegalArgumentException("BROKER_SCOPE_REQUIRED");
        }
    }

    /**
     * 返回工具操作名。
     *
     * @return 固定为 mockconsole.query_status
     */
    public String operation() {
        return "mockconsole.query_status";
    }

    /**
     * 返回执行该操作所需的权限。
     *
     * @return 固定为 console.status.read
     */
    public String permission() {
        return "console.status.read";
    }

    /**
     * 返回输入 schema（与 {@link #parameters} 相同）。
     *
     * @return 参数 JSON schema
     */
    public JSONObject inputSchema() {
        return parameters();
    }

    /**
     * 返回结果 schema（与 {@link #outputSchema} 相同）。
     *
     * @return 结果 JSON schema
     */
    public JSONObject resultSchema() {
        return MockQueryProvider.outputSchema();
    }

    /**
     * 构造 query 参数的 JSON schema：targets 1..32 个受限标识，fields 1..2 个枚举字段。
     *
     * @return 参数 schema 对象
     */
    public static JSONObject parameters() {
        JSONObject id =
                new JSONObject()
                        .put("type", "string")
                        .put("pattern", "^[A-Za-z0-9_.:-]{1,128}$")
                        .put("maxLength", 128);
        return new JSONObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JSONObject()
                                .put(
                                        "targets",
                                        new JSONObject()
                                                .put("type", "array")
                                                .put("items", id)
                                                .put("minItems", 1)
                                                .put("maxItems", 32))
                                .put(
                                        "fields",
                                        new JSONObject()
                                                .put("type", "array")
                                                .put(
                                                        "items",
                                                        new JSONObject()
                                                                .put("type", "string")
                                                                .put("maxLength", 128)
                                                                .put("enum", new JSONArray().put("cpuPercent").put("memoryMiB")))
                                                .put("minItems", 1)
                                                .put("maxItems", 2)))
                .put("required", new JSONArray().put("targets").put("fields"))
                .put("additionalProperties", false);
    }

    /**
     * 构造 query 结果的 JSON schema：nodes 1..32 行，每行含 target/queriedAt 及所请求字段。
     *
     * <p>三个数值属性各自独立构造：queriedAt 与 memoryMiB 取非负安全整数上界
     * （2 的 53 次方减 1），cpuPercent 取百分比上界 100。必须用独立实例而非
     * 复用同一个 JSONObject，否则两个属性会共享同一份 schema 引用而互相牵连。
     *
     * @return 结果 schema 对象
     */
    public static JSONObject outputSchema() {
        JSONObject row =
                new JSONObject()
                        .put("type", "object")
                        .put(
                                "properties",
                                new JSONObject()
                                        .put("target", new JSONObject().put("type", "string").put("maxLength", 128))
                                        .put(
                                                "queriedAt",
                                                new JSONObject()
                                                        .put("type", "integer")
                                                        .put("minimum", 0)
                                                        .put("maximum", 9007199254740991L))
                                        .put(
                                                "cpuPercent",
                                                new JSONObject()
                                                        .put("type", "integer")
                                                        .put("minimum", 0)
                                                        .put("maximum", 100))
                                        .put(
                                                "memoryMiB",
                                                new JSONObject()
                                                        .put("type", "integer")
                                                        .put("minimum", 0)
                                                        .put("maximum", 9007199254740991L)))
                        .put("required", new JSONArray().put("target").put("queriedAt"))
                        .put("additionalProperties", false);
        return new JSONObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JSONObject()
                                .put(
                                        "nodes",
                                        new JSONObject()
                                                .put("type", "array")
                                                .put("items", row)
                                                .put("minItems", 1)
                                                .put("maxItems", 32)))
                .put("required", new JSONArray().put("nodes"))
                .put("additionalProperties", false);
    }

    /**
     * 校验参数：先过 schema，再核对目标/字段落在配置范围内。
     *
     * @param args 工具参数（targets、fields）
     * @throws StoreException 越出配置范围，错误码 BROKER_SCOPE_FORBIDDEN
     */
    public void validate(JSONObject args) {
        Contract.validate(args, inputSchema());
        if (!targets.containsAll(RuntimeSupport.strings(args.getJSONArray("targets")))
                || !fields.containsAll(RuntimeSupport.strings(args.getJSONArray("fields")))) {
            throw new StoreException("BROKER_SCOPE_FORBIDDEN");
        }
    }

    /**
     * 向 mock broker 发起一次状态查询：POST JSON，3 秒硬截止，按请求投影目标与字段。
     *
     * <p>非 200 拒绝（BROKER_UPSTREAM_REJECTED）；行数超过 32、目标重复或未覆盖全部请求目标
     * 均以无消息 IllegalArgumentException 拒绝；响应体上限 65536 字节。
     *
     * @param args 工具参数（targets、fields）
     * @return 仅含 nodes 数组的结果对象
     * @throws Exception                连接/读取失败
     * @throws IOException              上游非 200（BROKER_UPSTREAM_REJECTED）
     * @throws IllegalArgumentException 行数超限、目标重复或目标缺失（均无消息）
     */
    public JSONObject query(JSONObject args) throws Exception {
        validate(args);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        java.util.concurrent.ScheduledExecutorService deadlines =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ScheduledFuture<?> abort =
                deadlines.schedule(
                        connection::disconnect, 3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(RuntimeSupport.bytes(args));
            }
            if (connection.getResponseCode() != 200) {
                throw new IOException("BROKER_UPSTREAM_REJECTED");
            }
            JSONObject raw;
            try (InputStream in = connection.getInputStream()) {
                raw = StrictJson.object(RuntimeSupport.read(in, 65536));
            }
            JSONArray nodes = new JSONArray();
            Set<String> seen = new HashSet<>();
            Set<String> wanted = RuntimeSupport.strings(args.getJSONArray("targets"));
            JSONArray rows = raw.getJSONArray("nodes");
            if (rows.length() > 32) {
                throw new IllegalArgumentException();
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject source = rows.getJSONObject(i);
                String target = source.getString("target");
                if (!wanted.contains(target)) {
                    continue;
                }
                if (!seen.add(target)) {
                    throw new IllegalArgumentException();
                }
                JSONObject row =
                        new JSONObject().put("target", target).put("queriedAt", source.get("queriedAt"));
                for (String field : RuntimeSupport.strings(args.getJSONArray("fields"))) {
                    row.put(field, source.get(field));
                }
                nodes.put(row);
            }
            if (!seen.equals(wanted)) {
                throw new IllegalArgumentException();
            }
            return new JSONObject().put("nodes", nodes);

        } finally {
            abort.cancel(false);
            deadlines.shutdownNow();
            connection.disconnect();
        }
    }

    /**
     * 校验 provider 返回的结果：字段、目标与唯一性必须与请求参数一致。
     *
     * @param args 原始工具参数（targets、fields）
     * @param data provider 结果（nodes 数组）
     * @throws IllegalArgumentException 字段越界、目标越界、目标重复或目标缺失，
     *                                  错误码 TOOL_OUTPUT_INVALID
     */
    public void validateResult(JSONObject args, JSONObject data) {
        Set<String> allowedFields = RuntimeSupport.strings(args.getJSONArray("fields"));
        Set<String> allowedTargets = RuntimeSupport.strings(args.getJSONArray("targets"));
        allowedFields.add("target");
        allowedFields.add("queriedAt");
        Set<String> seen = new HashSet<>();
        JSONArray nodes = data.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject row = nodes.getJSONObject(i);
            if (!allowedFields.containsAll(row.keySet())
                    || !allowedTargets.contains(row.getString("target"))
                    || !seen.add(row.getString("target"))) {
                throw new IllegalArgumentException("TOOL_OUTPUT_INVALID");
            }
        }
        if (!seen.equals(allowedTargets)) {
            throw new IllegalArgumentException("TOOL_OUTPUT_INVALID");
        }
    }

    /**
     * 关闭钩子：无状态连接，无资源需要释放。
     */
    public void close() {
    }
}
