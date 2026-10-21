/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.util.log.Logger;
import org.json.JSONObject;

/**
 * worker 注册/停机结果的结构化日志：只记录受控身份字段与服务端错误码。
 * 注册类（register/describe）与停机类（drain/unregister）请求全量记录，
 * 成功 INFO、预期拒绝 WARN、内部异常 ERROR，沿用 {@code #process - ...} 格式；
 * 正常心跳与 echo 不打印；凭据、sessionId 与完整报文一律不进入日志。
 */
final class WorkerRegistrationLog {

    /**
     * 纯工具类，禁止实例化。
     */
    private WorkerRegistrationLog() {
    }

    /**
     * 把候选值收敛为可安全记日志的字符串：仅接受长度 1–128 且字符集为
     * {@code [A-Za-z0-9_.:@/-]} 的字符串，其余（含 null 与非字符串）统一替换为 "-"。
     *
     * @param value 请求/响应中的候选字段值
     * @return 可记日志的字段值或 "-"
     */
    private static String field(Object value) {
        return value instanceof String && ((String) value).matches("[A-Za-z0-9_.:@/-]{1,128}") ? (String) value : "-";
    }

    /**
     * 记录一次 worker 请求的结果：注册类与停机类请求全量记录，其余动作仅在失败时记录。
     * model 节点附带 model/backend 字段；registered=true 且 ready=false 时追加
     * "后端或节点未就绪"说明；拒绝时附服务端 errorCode；内部异常只保留异常类名，
     * 避免异常消息或原因链中夹带凭据或被拒报文。
     *
     * @param request    worker 请求 JSON；null 表示请求尚未解析（如信封非法）
     * @param response   处理结果 JSON；null 表示未产生响应
     * @param config     私有配置，用于补查 model worker 的 backend 键；可为 null
     * @param transport  发起请求的 Cell transport 会话标识
     * @param unexpected 非预期内部异常；null 表示拒绝来自业务校验而非异常
     */
    static void outcome(JSONObject request, JSONObject response, HarnessConfig config, long transport, Exception unexpected) {
        String action = request == null ? "" : request.optString("action", "");
        boolean registration = action.equals("register") || action.equals("describe");
        boolean stopping = action.equals("drain") || action.equals("unregister");
        boolean ok = response != null && response.optBoolean("ok");
        if (!registration && !stopping && ok) {
            return;
        }
        int protocol = request == null ? 0 : request.optInt("protocolVersion", 0);
        String runnerType = request == null ? "-" : field(request.opt("runnerType"));
        String worker = request == null ? "-" : field(request.opt("workerId"));
        String role = !runnerType.equals("-") ? "runner" : protocol == 2 || protocol == 3 ? "model"
                : request == null ? "-" : field(request.opt("role"));
        String message = "#process - Harness " + (registration ? "registration " : stopping ? "worker " + action + " " : "worker request ")
                + (ok ? "succeeded" : "rejected") + ": workerId=" + worker + " role=" + role
                + " instanceId=" + (request == null ? "-" : field(request.opt("instanceId")))
                + (role.equals("runner") ? " runnerType=" + runnerType : " protocol=" + protocol)
                + " requestId=" + (request == null ? "-" : field(request.opt("requestId")))
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
                // Throwable 的消息与原因链可能夹带凭据或整份被拒 JSON，只保留异常类名。
                Exception safe = new Exception(unexpected.getClass().getSimpleName());
                safe.setStackTrace(unexpected.getStackTrace());
                Logger.e(HarnessService.class, message, safe);
            }
        }
    }
}
