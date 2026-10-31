/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc;

import cube.common.state.AIGCStateCode;
import org.json.JSONObject;

/**
 * A detection failure retains both the Unit code and its HTTP meaning.
 */
public final class SpecializedDetectionException extends RuntimeException {
    public final int code;
    public final int httpStatus;
    public final String reason;

    public SpecializedDetectionException(int code, int httpStatus, String reason, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    public static SpecializedDetectionException invalid(String message) {
        return new SpecializedDetectionException(1, 400, "INVALID_PARAMETER", message);
    }

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

    public static SpecializedDetectionException timeout() {
        return new SpecializedDetectionException(31, 504, "DETECTION_TIMEOUT", "Detection request timed out; remote execution may still be running");
    }

    public JSONObject toJSON() {
        return new JSONObject().put("state", httpStatus).put("code", code).put("reason", reason).put("message", getMessage());
    }

    public static SpecializedDetectionException fromJSON(int code, JSONObject data) {
        JSONObject error = data.optJSONObject("error");
        return error == null ? unit(code) : new SpecializedDetectionException(code, error.optInt("state", unit(code).httpStatus),
                error.optString("reason", "DETECTION_FAILED"), error.optString("message", "Detection failed"));
    }
}
