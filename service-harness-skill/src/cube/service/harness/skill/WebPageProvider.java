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
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 公网网页读取技能。
 *
 * <p>只读 HTTPS 静态文档，每一跳都做 DNS 校验，TLS 直接钉在 IP 上；
 * 重定向最多 3 跳，且每一跳都要重新通过策略校验。</p>
 */
public class WebPageProvider extends AbstractProvider {
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
        timeout = number(p, "network.timeout.millis", 5000, 100, 8000);
        maxBytes = number(p, "web.max.bytes", 1048576, 1024, 1048576);
        for (String host : p.getProperty("web.hosts", "").split(",")) {
            String value = host.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                if (!value.matches("[a-z0-9]+([.-][a-z0-9]+)*")) {
                    throw new IllegalArgumentException("WEB_HOST_INVALID");
                }
                hosts.add(value);
            }
        }
        http = client == null ? new NetClient() : client;
    }

    /**
     * 校验调用参数：除通用校验外，URL 必须符合访问策略。
     *
     * @param args 调用参数。
     */
    public void validate(JSONObject args) {
        super.validate(args);
        NetClient.uri(args.getString("url"), true, hosts);
    }

    /**
     * 读取网页。
     *
     * @param c    Broker 提供的可信调用上下文。
     * @param args 调用参数。
     * @return 返回网页内容与元数据。
     */
    protected JSONObject execute(ProviderInvocation c, JSONObject args) {
        return read(args, c::cancelled);
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
        URI current = NetClient.uri(args.getString("url"), true, hosts);
        long deadline = NetClient.deadline(timeout);
        NetClient.Response response;
        int redirects = 0;
        Set<String> visited = new HashSet<>();
        while (true) {
            if (!visited.add(current.toString())) {
                throw new StoreException("WEB_REDIRECT_LOOP");
            }
            response = http.get(current, true, hosts,
                    Collections.singletonMap("Accept", "text/html, text/plain, application/json"),
                    deadline, maxBytes, cancelled);
            if (Arrays.asList(301, 302, 303, 307, 308).contains(response.status)) {
                if (++redirects > 3) {
                    throw new StoreException("WEB_REDIRECT_LIMIT");
                }
                String location = response.header("location");
                if (location.isEmpty()) {
                    throw new StoreException("WEB_REDIRECT_INVALID");
                }
                try {
                    current = NetClient.uri(current.resolve(location).toString(), true, hosts);
                } catch (IllegalArgumentException e) {
                    throw new StoreException("WEB_REDIRECT_INVALID");
                }
            } else {
                break;
            }
        }
        if (response.status != 200) {
            throw new StoreException(response.status == 429 ? "WEB_RATE_LIMIT" : "WEB_UPSTREAM_FAILED");
        }
        String media = response.media();
        String text;
        String title = "";
        if (!Arrays.asList("text/html", "text/plain", "application/json").contains(media)) {
            throw new StoreException("WEB_MEDIA_UNSUPPORTED");
        }
        text = response.text();
        if (media.equals("text/html")) {
            HtmlText parsed = HtmlText.parse(text);
            title = parsed.title;
            text = parsed.text;
        }
        JSONObject out = new JSONObject().put("source", args.getString("url")).put("finalUrl", current.toASCIIString())
                .put("fetchedAt", Instant.now().toString()).put("mediaType", media)
                .put("title", Budget.prefix(title, 512)).put("sha256", RuntimeSupport.sha(response.body))
                .put("trust", "untrusted-source-data").put("text", Budget.prefix(text, 16384))
                .put("truncated", text.length() > 16384);
        Budget.text(out, "text", resultBytes);
        return out;
    }

    /**
     * 释放技能持有的网络资源。
     */
    public void close() {
        http.close();
    }
}
