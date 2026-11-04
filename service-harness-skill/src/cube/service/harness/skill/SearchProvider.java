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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 搜索技能：调用管理员配置的 SearXNG 端点。
 *
 * <p>该端点与公网页面访问的网络策略相互独立；返回结果一律视为不可信来源数据。</p>
 */
public class SearchProvider extends AbstractProvider {
    /**
     * 搜索响应体的字节上限：256 KiB。
     */
    private static final int MAX_RESPONSE_BYTES = 262144;

    /**
     * 单个结果 URL 的最大长度。
     */
    private static final int MAX_URL_LENGTH = 2048;

    /**
     * 未指定时返回的结果条数。
     */
    private static final int DEFAULT_MAX_RESULTS = 10;

    /**
     * 结果标题保留的最大字符数。
     */
    private static final int MAX_TITLE_CHARS = 512;

    /**
     * 结果摘要保留的最大字符数。
     */
    private static final int MAX_SNIPPET_CHARS = 1024;

    /**
     * 引擎名与发布时间等元数据保留的最大字符数。
     */
    private static final int MAX_META_CHARS = 128;

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
        this.endpoint = NetClient.uri(p.getProperty("search.url", ""), false, Collections.emptySet());
        if (this.endpoint.getRawQuery() != null) {
            throw new IllegalArgumentException("SEARCH_ENDPOINT_INVALID");
        }
        this.timeout = number(p, "network.timeout.millis", 5000, 100, 8000);
        String token = p.getProperty("search.token", "");
        // 令牌会进入请求头，出现换行即视为头注入。
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("SEARCH_TOKEN_INVALID");
        }
        if (!token.isEmpty()) {
            this.headers.put("Authorization", "Bearer " + token);
        }
        this.headers.put("Accept", "application/json");
        this.http = client == null ? new NetClient() : client;
    }

    /**
     * 校验调用参数：除通用校验外，查询词不能为空。
     *
     * @param args 调用参数。
     */
    @Override
    public void validate(JSONObject args) {
        super.validate(args);
        if (args.getString("query").trim().isEmpty()) {
            throw new StoreException("SEARCH_QUERY_EMPTY");
        }
    }

    /**
     * 执行搜索。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数。
     * @return 返回搜索结果。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) {
        return search(args, context::cancelled);
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
        StringBuilder params = new StringBuilder()
                .append("q=").append(encode(query))
                .append("&format=json&pageno=1&language=").append(encode(args.optString("language", "zh-CN")));
        if (!args.optString("timeRange").isEmpty()) {
            params.append("&time_range=").append(encode(args.getString("timeRange")));
        }
        URI target = URI.create(this.endpoint.toASCIIString() + "?" + params);
        NetClient.Response response = this.http.get(target, false, Collections.emptySet(),
                this.headers, NetClient.deadline(this.timeout), MAX_RESPONSE_BYTES, cancelled);
        if (response.status != 200) {
            if (response.status == 403) {
                throw new StoreException("SEARCH_FORBIDDEN");
            } else if (response.status == 429) {
                throw new StoreException("SEARCH_RATE_LIMIT");
            } else {
                throw new StoreException("SEARCH_UPSTREAM_FAILED");
            }
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
        int max = args.optInt("maxResults", DEFAULT_MAX_RESULTS);
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
            if (!acceptable(url)) {
                skipped = true;
                continue;
            }
            // 同一 URL 只保留首条，避免不同引擎的重复结果。
            if (!seen.add(url)) {
                continue;
            }
            HtmlText content = HtmlText.parse(row.optString("content", ""));
            JSONObject item = new JSONObject()
                    .put("sourceId", RuntimeSupport.sha(url.getBytes(StandardCharsets.UTF_8)))
                    .put("title", Budget.prefix(HtmlText.parse(row.optString("title", "")).text, MAX_TITLE_CHARS))
                    .put("url", url)
                    .put("snippet", Budget.prefix(content.text, MAX_SNIPPET_CHARS))
                    .put("engine", Budget.prefix(row.optString("engine", ""), MAX_META_CHARS));
            if (row.opt("publishedDate") instanceof String) {
                item.put("publishedAt", Budget.prefix(row.getString("publishedDate"), MAX_META_CHARS));
            }
            results.put(item);
        }
        if (skipped) {
            warnings.put("INVALID_RESULTS_SKIPPED");
        }
        JSONObject out = new JSONObject()
                .put("query", query)
                .put("searchedAt", Instant.now().toString())
                .put("trust", "untrusted-source-data")
                .put("results", results)
                .put("partial", warnings.length() > 0)
                .put("warnings", warnings)
                .put("truncated", rows.length() > results.length());
        Budget.items(out, "results", this.resultBytes);
        return out;
    }

    /**
     * 判断上游给出的 URL 是否可作为结果返回：长度、主机与协议都受限。
     *
     * @param url 上游 URL 。
     * @return 返回 {@code true} 表示该结果可用。
     */
    private static boolean acceptable(String url) {
        try {
            URI uri = new URI(url);
            return url.length() <= MAX_URL_LENGTH
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
        } catch (Exception e) {
            return false;
        }
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
    @Override
    public void close() {
        this.http.close();
    }
}
