/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 搜索技能：调用管理员配置的 SearXNG 端点。
 *
 * <p>该端点与公网页面访问的网络策略相互独立；返回结果一律视为不可信来源数据。</p>
 */
public class SearchProvider extends AbstractProvider {
    /**
     * 搜索端点地址。
     */
    private final URI endpoint;
    /**
     * HTTP 客户端。
     */
    private final NetClient http;
    /**
     * 请求超时时间，单位毫秒。
     */
    private final int timeout;
    /**
     * 附加请求头。
     */
    private final Map<String, String> headers = new HashMap<>();

    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public SearchProvider(Properties p) {
        this(p, null);
    }

    /**
     * 构造函数。
     *
     * @param p      技能私有配置。
     * @param client 注入的 HTTP 客户端，为空时自行创建。
     */
    SearchProvider(Properties p, NetClient client) {
        super("search", "common.search.query", "web.search", p);
        endpoint = NetClient.uri(p.getProperty("search.url", ""), false, Collections.emptySet());
        if (endpoint.getRawQuery() != null) {
            throw new IllegalArgumentException("SEARCH_ENDPOINT_INVALID");
        }
        timeout = number(p, "network.timeout.millis", 5000, 100, 8000);
        String token = p.getProperty("search.token", "");
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("SEARCH_TOKEN_INVALID");
        }
        if (!token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        headers.put("Accept", "application/json");
        http = client == null ? new NetClient() : client;
    }

    /**
     * 校验调用参数：除通用校验外，查询词不能为空。
     *
     * @param args 调用参数。
     */
    public void validate(JSONObject args) {
        super.validate(args);
        if (args.getString("query").trim().isEmpty()) {
            throw new StoreException("SEARCH_QUERY_EMPTY");
        }
    }

    /**
     * 执行搜索。
     *
     * @param c    Broker 提供的可信调用上下文。
     * @param args 调用参数。
     * @return 返回搜索结果。
     */
    protected JSONObject execute(ProviderInvocation c, JSONObject args) {
        return search(args, c::cancelled);
    }

    /**
     * 调用搜索端点并整理结果。
     *
     * @param args      调用参数。
     * @param cancelled 取消标记。
     * @return 返回搜索结果，含结果列表与告警。
     */
    JSONObject search(JSONObject args, BooleanSupplier cancelled) {
        validate(args);
        String query = args.getString("query").trim();
        String params = "q=" + encode(query) + "&format=json&pageno=1&language="
                + encode(args.optString("language", "zh-CN"));
        if (!args.optString("timeRange").isEmpty()) {
            params += "&time_range=" + encode(args.getString("timeRange"));
        }
        NetClient.Response response = http.get(URI.create(endpoint.toASCIIString() + "?" + params), false,
                Collections.emptySet(), headers, NetClient.deadline(timeout), 262144, cancelled);
        if (response.status != 200) {
            throw new StoreException(response.status == 403 ? "SEARCH_FORBIDDEN"
                    : response.status == 429 ? "SEARCH_RATE_LIMIT" : "SEARCH_UPSTREAM_FAILED");
        }
        if (!response.media().equals("application/json")) {
            throw new StoreException("SEARCH_RESPONSE_INVALID");
        }
        JSONObject raw;
        try {
            raw = StrictJson.object(response.body);
        } catch (IllegalArgumentException e) {
            throw new StoreException("SEARCH_RESPONSE_INVALID");
        }
        JSONArray rows = raw.optJSONArray("results");
        if (rows == null) {
            throw new StoreException("SEARCH_RESPONSE_INVALID");
        }
        JSONArray results = new JSONArray();
        JSONArray warnings = new JSONArray();
        JSONArray failures = raw.optJSONArray("unresponsive_engines");
        if (failures != null && failures.length() > 0) {
            warnings.put("UPSTREAM_ENGINES_UNRESPONSIVE");
        }
        boolean skipped = false;
        Set<String> seen = new HashSet<>();
        int max = args.optInt("maxResults", 10);
        for (int i = 0; i < rows.length() && results.length() < max; i++) {
            if (cancelled.getAsBoolean()) {
                throw new StoreException("TOOL_CANCELLED");
            }
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                skipped = true;
                continue;
            }
            String url = row.optString("url", "");
            try {
                URI u = new URI(url);
                if (url.length() > 2048 || u.getHost() == null || u.getUserInfo() != null
                        || !("https".equals(u.getScheme()) || "http".equals(u.getScheme()))) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception e) {
                skipped = true;
                continue;
            }
            if (!seen.add(url)) {
                continue;
            }
            HtmlText content = HtmlText.parse(row.optString("content", ""));
            JSONObject item = new JSONObject().put("sourceId", RuntimeSupport.sha(url.getBytes(StandardCharsets.UTF_8)))
                    .put("title", Budget.prefix(HtmlText.parse(row.optString("title", "")).text, 512))
                    .put("url", url).put("snippet", Budget.prefix(content.text, 1024))
                    .put("engine", Budget.prefix(row.optString("engine", ""), 128));
            if (row.opt("publishedDate") instanceof String) {
                item.put("publishedAt", Budget.prefix(row.getString("publishedDate"), 128));
            }
            results.put(item);
        }
        if (skipped) {
            warnings.put("INVALID_RESULTS_SKIPPED");
        }
        JSONObject out = new JSONObject().put("query", query).put("searchedAt", Instant.now().toString())
                .put("trust", "untrusted-source-data").put("results", results)
                .put("partial", warnings.length() > 0).put("warnings", warnings)
                .put("truncated", rows.length() > results.length());
        Budget.items(out, "results", resultBytes);
        return out;
    }

    /**
     * 对查询参数做 URL 编码。
     *
     * @param value 待编码的文本。
     * @return 返回编码结果。
     */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 释放技能持有的网络资源。
     */
    public void close() {
        http.close();
    }
}
