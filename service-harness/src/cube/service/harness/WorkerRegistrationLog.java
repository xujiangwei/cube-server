/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.util.log.Logger;
import org.json.JSONObject;

/**
 * Log only bounded identity fields and server error codes, including schema/authentication rejections.
 */
final class WorkerRegistrationLog {
    private WorkerRegistrationLog() {
    }

    private static String field(Object value) {
        return value instanceof String && ((String) value).matches("[A-Za-z0-9_.:@/-]{1,128}") ? (String) value : "-";
    }

    static void outcome(JSONObject request, JSONObject response, HarnessConfig config, long transport, Exception unexpected) {
        String action = request == null ? "" : request.optString("action", "");
        boolean registration = action.equals("register") || action.equals("describe");
        boolean stopping = action.equals("drain") || action.equals("unregister");
        boolean ok = response != null && response.optBoolean("ok");
        if (!registration && !stopping && ok) return;
        int protocol = request == null ? 0 : request.optInt("protocolVersion", 0);
        String worker = request == null ? "-" : field(request.opt("workerId"));
        String role = protocol >= 4 && protocol <= 6 ? "runner" : protocol == 2 || protocol == 3 ? "model"
                : request == null ? "-" : field(request.opt("role"));
        String message = "#process - Harness " + (registration ? "registration " : stopping ? "worker " + action + " " : "worker request ")
                + (ok ? "succeeded" : "rejected") + ": workerId=" + worker + " role=" + role
                + " instanceId=" + (request == null ? "-" : field(request.opt("instanceId")))
                + " protocol=" + protocol + " requestId=" + (request == null ? "-" : field(request.opt("requestId")))
                + " transport=" + transport;
        if (role.equals("model")) {
            JSONObject descriptor = request == null ? null : request.optJSONObject("descriptor");
            message += " model=" + (descriptor == null ? "-" : field(descriptor.opt("model")))
                    + " backend=" + (config == null ? "-" : field(config.values.getProperty("worker." + worker + ".backend")));
        }
        if (ok) {
            boolean ready = response.optBoolean("ready");
            Logger.i(HarnessService.class, message + " registered=" + response.optBoolean("registered")
                    + " ready=" + ready + (registration && !ready ? " (registered; backend or worker is not ready)" : ""));
        } else {
            message += " errorCode=" + field(response == null ? null : response.opt("errorCode"));
            if (unexpected == null) {
                Logger.w(HarnessService.class, message);
            } else {
                // Throwable messages and causes can contain a credential or an entire rejected JSON object.
                Exception safe = new Exception(unexpected.getClass().getSimpleName());
                safe.setStackTrace(unexpected.getStackTrace());
                Logger.e(HarnessService.class, message, safe);
            }
        }
    }
}
