/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 公网网页读取技能。
 *
 * <p>只读 HTTPS 静态文档，每一跳都做 DNS 校验，TLS 直接钉在 IP 上；
 * 重定向最多 3 跳，且每一跳都要重新通过策略校验。</p>
 */
public class WebPageProvider extends AbstractProvider {
    /**
     * 允许跟踪的重定向跳数上限。
     */
    private static final int MAX_REDIRECTS = 3;

    /**
     * 视为重定向的状态码。
     */
    private static final Set<Integer> REDIRECT_STATUSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(301, 302, 303, 307, 308)));

    /**
     * 允许读取的响应媒体类型。
     */
    private static final Set<String> READABLE_MEDIA = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("text/html", "text/plain", "application/json")));

    /**
     * 正文保留的最大字符数。
     */
    private static final int MAX_TEXT_CHARS = 16384;

    /**
     * 标题保留的最大字符数。
     */
    private static final int MAX_TITLE_CHARS = 512;

    /**
     * 响应体字节上限的最小允许值：1 KiB。
     */
    private static final int MIN_RESPONSE_BYTES = 1024;

    /**
     * 响应体字节上限的最大允许值：1 MiB，同时作为默认值。
     */
    private static final int MAX_RESPONSE_BYTES = 1048576;

    /**
     * 允许访问的主机名单，为空表示不限制。
     */
    private final Set<String> hosts = new HashSet<>();

    /**
     * HTTP 客户端。
     */
    private final NetClient http;

    /**
     * 请求超时时间，单位毫秒。
     */
    private final int timeout;

    /**
     * 响应体字节上限。
     */
    private final int maxBytes;

    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public WebPageProvider(Properties p) {
        this(p, null);
    }

    /**
     * 构造函数。
     *
     * @param p      技能私有配置。
     * @param client 注入的 HTTP 客户端，为空时自行创建。
     */
    WebPageProvider(Properties p, NetClient client) {
        super("web", "common.web.read", "web.read", p);
        this.timeout = number(p, "network.timeout.millis", 5000, 100, 8000);
        this.maxBytes = number(p, "web.max.bytes", MAX_RESPONSE_BYTES, MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES);
        for (String host : p.getProperty("web.hosts", "").split(",")) {
            String value = host.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("[a-z0-9]+([.-][a-z0-9]+)*")) {
                throw new IllegalArgumentException("WEB_HOST_INVALID");
            }
            this.hosts.add(value);
        }
        this.http = client == null ? new NetClient() : client;
    }

    /**
     * 校验调用参数：除通用校验外，URL 必须符合访问策略。
     *
     * @param args 调用参数。
     */
    @Override
    public void validate(JSONObject args) {
        super.validate(args);
        NetClient.uri(args.getString("url"), true, this.hosts);
    }

    /**
     * 读取网页。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数。
     * @return 返回网页内容与元数据。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) {
        return read(args, context::cancelled);
    }

    /**
     * 跟随重定向读取网页并抽取正文。
     *
     * @param args      调用参数。
     * @param cancelled 取消标记。
     * @return 返回网页内容与元数据。
     */
    JSONObject read(JSONObject args, BooleanSupplier cancelled) {
        validate(args);
        URI current = NetClient.uri(args.getString("url"), true, this.hosts);
        long deadline = NetClient.deadline(this.timeout);
        NetClient.Response response;
        int redirects = 0;
        Set<String> visited = new HashSet<>();
        while (true) {
            // 每一跳都重新校验，出现回环立即失败。
            if (!visited.add(current.toString())) {
                throw new StoreException("WEB_REDIRECT_LOOP");
            }
            response = this.http.get(current, true, this.hosts,
                    Collections.singletonMap("Accept", "text/html, text/plain, application/json"),
                    deadline, this.maxBytes, cancelled);
            if (!REDIRECT_STATUSES.contains(response.status)) {
                break;
            }
            if (++redirects > MAX_REDIRECTS) {
                throw new StoreException("WEB_REDIRECT_LIMIT");
            }
            String location = response.header("location");
            if (location.isEmpty()) {
                throw new StoreException("WEB_REDIRECT_INVALID");
            }
            try {
                // 相对地址按当前 URL 解析，解析结果同样要过策略校验。
                current = NetClient.uri(current.resolve(location).toString(), true, this.hosts);
            } catch (IllegalArgumentException e) {
                throw new StoreException("WEB_REDIRECT_INVALID");
            }
        }
        if (response.status != 200) {
            if (response.status == 429) {
                throw new StoreException("WEB_RATE_LIMIT");
            } else {
                throw new StoreException("WEB_UPSTREAM_FAILED");
            }
        }
        String media = response.media();
        if (!READABLE_MEDIA.contains(media)) {
            throw new StoreException("WEB_MEDIA_UNSUPPORTED");
        }
        String text = response.text();
        String title = "";
        if (media.equals("text/html")) {
            // HTML 只做静态文本抽取，不执行脚本、不加载外部资源。
            HtmlText parsed = HtmlText.parse(text);
            title = parsed.title;
            text = parsed.text;
        }
        JSONObject out = new JSONObject()
                .put("source", args.getString("url"))
                .put("finalUrl", current.toASCIIString())
                .put("fetchedAt", Instant.now().toString())
                .put("mediaType", media)
                .put("title", Budget.prefix(title, MAX_TITLE_CHARS))
                .put("sha256", RuntimeSupport.sha(response.body))
                .put("trust", "untrusted-source-data")
                .put("text", Budget.prefix(text, MAX_TEXT_CHARS))
                .put("truncated", text.length() > MAX_TEXT_CHARS);
        Budget.text(out, "text", this.resultBytes);
        return out;
    }

    /**
     * 释放技能持有的网络资源。
     */
    @Override
    public void close() {
        this.http.close();
    }
}
