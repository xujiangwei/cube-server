/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc;

import cube.common.state.AIGCStateCode;
import org.json.JSONObject;

/**
 * 检测失败异常，同时保留 Unit 状态码及其对应的 HTTP 语义。
 */
public final class SpecializedDetectionException extends RuntimeException {
    /** Unit 状态码。 */
    public final int code;
    /** 与 Unit 状态码对应的 HTTP 状态码。 */
    public final int httpStatus;
    /** 失败原因标识，用于响应体的 reason 字段。 */
    public final String reason;

    /**
     * 构造方法。
     *
     * @param code       Unit 状态码
     * @param httpStatus 对应的 HTTP 状态码
     * @param reason     失败原因标识
     * @param message    失败描述
     */
    public SpecializedDetectionException(int code, int httpStatus, String reason, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    /**
     * 构造参数非法的异常，对应 HTTP 400。
     *
     * @param message 失败描述
     * @return 异常实例
     */
    public static SpecializedDetectionException invalid(String message) {
        return new SpecializedDetectionException(1, 400, "INVALID_PARAMETER", message);
    }

    /**
     * 按 Unit 状态码构造异常，并映射为对应的 HTTP 状态码。
     *
     * @param code Unit 状态码
     * @return 异常实例
     */
    public static SpecializedDetectionException unit(int code) {
        int http;
        switch (AIGCStateCode.parse(code)) {
            case InvalidParameter:
                http = 400;
                break;
            case NotFound:
                http = 404;
                break;
            case Processing:
            case Busy:
                http = 429;
                break;
            case NoToken:
            case InconsistentToken:
                http = 401;
                break;
            case IllegalOperation:
                http = 403;
                break;
            case UnitNoReady:
                http = 503;
                break;
            case FileError:
                http = 422;
                break;
            default:
                http = 502;
        }
        return new SpecializedDetectionException(code, http, "UNIT_REJECTED", "Specialized detection failed (code " + code + ")");
    }

    /**
     * 构造检测超时的异常，对应 HTTP 504。
     * 此时远端执行可能仍在进行，调用方不应据此认定任务未运行。
     *
     * @return 异常实例
     */
    public static SpecializedDetectionException timeout() {
        return new SpecializedDetectionException(31, 504, "DETECTION_TIMEOUT", "Detection request timed out; remote execution may still be running");
    }

    /**
     * 将异常序列化为响应体中的 error 对象。
     *
     * @return JSON 对象，包含 state、code、reason 与 message
     */
    public JSONObject toJSON() {
        return new JSONObject().put("state", httpStatus).put("code", code).put("reason", reason).put("message", getMessage());
    }

    /**
     * 从响应体中的 error 对象还原异常。
     * 当 data 中不含 error 对象时，退化为按 Unit 状态码构造。
     *
     * @param code Unit 状态码
     * @param data 响应体数据
     * @return 异常实例
     */
    public static SpecializedDetectionException fromJSON(int code, JSONObject data) {
        JSONObject error = data.optJSONObject("error");
        return error == null ? unit(code) : new SpecializedDetectionException(code, error.optInt("state", unit(code).httpStatus),
                error.optString("reason", "DETECTION_FAILED"), error.optString("message", "Detection failed"));
    }
}
