/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.aigc.completion;

import java.util.Properties;

/**
 * 完成网关的启动配置。
 *
 * <p>由系统属性或配置文件解析得到，创建后不可变；关闭时整套 OpenAI/Claude 兼容层都不会注册。</p>
 */
public final class CompletionConfig {

    /** 是否启用 OpenAI/Claude 兼容路由。 */
    public final boolean enabled;
    /** 单次请求的等待超时，单位毫秒。 */
    public final long timeoutMs;
    /** 请求体上限、事件缓冲条数上限与缓冲字节上限。 */
    public final int bodyBytes, bufferEvents, bufferBytes;

    public CompletionConfig(Properties properties) {
        Properties p = properties == null ? new Properties() : properties;
        enabled = Boolean.parseBoolean(p.getProperty("completion.rest.enabled", "true"));
        timeoutMs = positive(p, "completion.rest.timeout.seconds", 300) * 1000L;
        bodyBytes = positive(p, "completion.rest.body.bytes", 32 * 1024 * 1024);
        bufferEvents = positive(p, "completion.rest.buffer.events", 256);
        bufferBytes = positive(p, "completion.rest.buffer.bytes", 4 * 1024 * 1024);
    }

    private static int positive(Properties p, String key, int fallback) {
        int value = Integer.parseInt(p.getProperty(key, String.valueOf(fallback)));
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
}
