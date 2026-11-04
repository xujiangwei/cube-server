/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 外部数据源访问器。
 *
 * <p>只访问管理员批准过的不可变数据源，永远不跟随重定向；每次请求都带签名头，
 * 请求期间权限被撤销也要失败关闭。</p>
 */
final class DataNetwork implements AutoCloseable {
    /**
     * 技能私有配置，提供数据源密钥。
     */
    private final Properties config;
    /**
     * 底层 HTTP 客户端。
     */
    private final NetClient http = new NetClient();

    /**
     * 构造函数。
     *
     * @param config 技能私有配置。
     */
    DataNetwork(Properties config) {
        this.config = config;
    }

    /**
     * 按请求模板向数据源发起一次只读 GET 请求。
     *
     * @param c       Broker 提供的可信调用上下文。
     * @param ref     数据源引用，格式为 {@code <sourceId>@<version>} 。
     * @param request 请求模板。
     * @param values  标量替换域。
     * @param scope   替换域名称，取值 args 或 media 。
     * @param extra   额外追加的查询参数。
     * @param limit   响应体字节上限。
     * @return 返回上游响应。
     */
    NetClient.Response get(ProviderInvocation c, String ref, JSONObject request, JSONObject values, String scope, JSONObject extra, int limit) {
        JSONObject source = c.source(ref);
        String path = request.getString("path");
        boolean allowed = false;
        JSONArray paths = source.getJSONArray("allowedPaths");
        for (int i = 0; i < paths.length(); i++) {
            if (paths.getString(i).equals(path)) {
                allowed = true;
            }
        }
        if (!allowed) {
            throw new StoreException("DATA_SOURCE_FORBIDDEN");
        }
        TreeMap<String, String> query = new TreeMap<>();
        JSONObject template = request.getJSONObject("query");
        for (String key : template.keySet()) {
            String value = template.getString(key);
            String prefix = "${" + scope + ".";
            if (value.startsWith(prefix)) {
                value = ConnectorConfig.scalar(values.get(value.substring(prefix.length(), value.length() - 1)));
            }
            query.put(key, value);
        }
        for (String key : extra.keySet()) {
            if (query.containsKey(key)) {
                ConnectorConfig.bad();
            }
            query.put(key, extra.getString(key));
        }
        StringBuilder target = new StringBuilder(path);
        for (Map.Entry<String, String> e : query.entrySet()) {
            target.append(target.indexOf("?") < 0 ? '?' : '&')
                    .append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        String issued = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String secret = config.getProperty("secret." + source.getString("secretRef"), "");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new StoreException("DATA_SOURCE_FORBIDDEN");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Harness-Tenant", c.tenant);
        headers.put("X-Harness-Subject", c.subject);
        headers.put("X-Harness-Issued-At", issued);
        headers.put("X-Harness-Nonce", nonce);
        headers.put("X-Harness-Source", ref);
        String canonical = "GET\n" + target + "\n" + ref + "\n" + c.tenant + "\n" + c.subject + "\n" + issued + "\n" + nonce;
        headers.put("X-Harness-Signature", sign(secret, canonical));
        URI uri = NetClient.uri(source.getString("baseUrl") + target, false, Collections.emptySet());
        NetClient.Response response = http.get(uri, false, Collections.emptySet(), headers, NetClient.deadline(5000), limit, c::cancelled);
        if (response.status == 403 || response.status == 401) {
            throw new StoreException("DATA_SOURCE_FORBIDDEN");
        }
        if (response.status == 409 || response.status == 410) {
            throw new StoreException("MEDIA_VERSION_CHANGED");
        }
        if (response.status != 200) {
            throw new StoreException("DATA_QUERY_INCOMPLETE");
        }
        // 网络请求期间授权被撤销，同样必须失败关闭。
        c.source(ref);
        return response;
    }

    /**
     * 对查询参数的键与值做 URL 编码。
     *
     * @param text 待编码的文本。
     * @return 返回编码结果。
     */
    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * 计算请求签名头的值。
     *
     * @param secret 数据源密钥。
     * @param text   待签名的规范串。
     * @return 返回十六进制小写的 HMAC-SHA256 签名。
     */
    static String sign(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : mac.doFinal(text.getBytes(StandardCharsets.UTF_8))) {
                out.append(String.format("%02x", b & 255));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 释放底层网络资源。
     */
    public void close() {
        http.close();
    }
}
