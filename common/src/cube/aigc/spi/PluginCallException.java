/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.spi;

import cube.common.state.AIGCStateCode;

/**
 * 插件调用异常。
 *
 * <p>携带状态码与 HTTP 状态，供宿主直接转成协议响应。</p>
 */
public final class PluginCallException extends RuntimeException {
    /** 状态码。 */
    public final AIGCStateCode state;
    /** 建议返回的 HTTP 状态码。 */
    public final int httpStatus;

    public PluginCallException(AIGCStateCode state, int httpStatus, String message) {
        super(message);
        this.state = state;
        this.httpStatus = httpStatus;
    }
}
