/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.completion;

import org.json.JSONObject;

/**
 * Transport-independent error; adapters supply each provider's envelope.
 */
public class CompletionError extends RuntimeException {
    /** HTTP 状态码。 */
    public final int status;
    /** 错误码，与兼容层协议一致。 */
    public final String code;
    /** 出错参数名；非参数错误时为 {@code null} 。 */
    public final String param;

    public CompletionError(int status, String code, String message, String param) {
        super(message);
        this.status = status;
        this.code = code;
        this.param = param;
    }

    public CompletionError(JSONObject error) {
        this(error.optInt("status", 502), error.optString("code", "upstream_error"),
                error.optString("message", "Generation failed"),
                error.isNull("param") ? null : error.optString("param", null));
    }

    /**
     * 序列化为兼容层协议的错误体。
     *
     * @return 错误 JSON 。
     */
    public JSONObject toJSON() {
        return new JSONObject().put("status", status).put("code", code)
                .put("message", getMessage()).put("param", param == null ? JSONObject.NULL : param);
    }

    /**
     * 构造参数非法错误。
     *
     * @param message 错误描述。
     * @param param   出错参数名。
     * @return 错误对象。
     */
    public static CompletionError invalid(String message, String param) {
        return new CompletionError(400, "invalid_request_error", message, param);
    }
}
