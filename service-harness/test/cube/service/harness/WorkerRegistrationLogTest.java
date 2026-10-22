package cube.service.harness;

import cell.util.log.*;
import cube.service.harness.protocol.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 校验真实注册响应与公共 Logger 输出，含私密字段脱敏。
 *
 * <p>场景覆盖：registered=true ready=false 的成功日志、DUPLICATE_WORKER /
 * UNAUTHORIZED / FORBIDDEN 等预期拒绝的 WARN 日志、协议 2–3 describe 的
 * 版本化失败与成功格式、tool/sandbox 命名 Runner 的拒绝信封、旧数字 Runner
 * 选择器的 PROTOCOL_MISMATCH、心跳静默、非法 JSON 拒绝，以及内部异常的
 * ERROR 严重级；最终断言凭据、sessionId 与异常消息均不进入日志。
 */
public final class WorkerRegistrationLogTest {
    /**
     * 捕获 HarnessService 日志的内存 LogHandle。
     *
     * <p>仅记录 tag 为 HarnessService 全类名的条目，并按级别追加
     * "INFO "/"WARN "/"ERROR " 前缀，供逐条断言级别与内容。
     */
    static final class Capture implements LogHandle {
        /** 已捕获的日志行；测试按索引断言新增条目的级别前缀与关键片段。 */
        final List<String> messages = new ArrayList<>();

        /**
         * 返回日志句柄名称，仅用于测试标识。
         *
         * @return 固定名称 "harness-registration-test"
         */
        @Override
        public String getName() {
            return "harness-registration-test";
        }

        /**
         * 丢弃 DEBUG 级日志：本测试只断言 INFO/WARN/ERROR 三种级别。
         *
         * @param tag 日志标签
         * @param text 日志文本
         */
        @Override
        public void logDebug(String tag, String text) {
        }

        /**
         * 捕获 INFO 级日志：仅当 tag 为 HarnessService 全类名时记录。
         *
         * @param tag 日志标签；非 HarnessService 的条目全部忽略
         * @param text 日志文本，记录时加 "INFO " 前缀
         */
        @Override
        public void logInfo(String tag, String text) {
            if (tag.equals(HarnessService.class.getName())) {
                messages.add("INFO " + text);
            }
        }

        /**
         * 捕获 WARN 级日志：仅当 tag 为 HarnessService 全类名时记录。
         *
         * @param tag 日志标签；非 HarnessService 的条目全部忽略
         * @param text 日志文本，记录时加 "WARN " 前缀
         */
        @Override
        public void logWarning(String tag, String text) {
            if (tag.equals(HarnessService.class.getName())) {
                messages.add("WARN " + text);
            }
        }

        /**
         * 捕获 ERROR 级日志：仅当 tag 为 HarnessService 全类名时记录。
         *
         * @param tag 日志标签；非 HarnessService 的条目全部忽略
         * @param text 日志文本，记录时加 "ERROR " 前缀
         */
        @Override
        public void logError(String tag, String text) {
            if (tag.equals(HarnessService.class.getName())) {
                messages.add("ERROR " + text);
            }
        }
    }

    /**
     * 断言辅助：条件不成立时以给定消息抛出 AssertionError。
     *
     * @param ok 断言条件
     * @param message 失败时携带的断言消息
     * @throws AssertionError ok 为 false
     */
    static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    /**
     * 把 JSON 请求按 UTF-8 原始字节送入 HarnessService.process，模拟 Cell 传输。
     *
     * @param service 受测服务实例
     * @param request 请求 JSON
     * @param transport 模拟的 Cell transport 会话标识
     * @return 服务响应 JSON
     */
    static JSONObject send(HarnessService service, JSONObject request, long transport) {
        return service.process(request.toString().getBytes(StandardCharsets.UTF_8), transport);
    }

    /**
     * 测试入口：启动 HarnessService 后依次驱动注册、拒绝、版本化 describe、
     * 命名 Runner、旧协议拒绝与脱敏断言。
     *
     * @param args 未使用的命令行参数
     * @throws Exception 服务启动/停止或线程中断
     */
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.setProperty("worker.credential", "private-registration-test-credential");
        p.setProperty("http.port", "0");
        p.setProperty("worker.model-1.role", "model");
        HarnessConfig config = new HarnessConfig(p);
        Capture capture = new Capture();
        LogManager.getInstance().addHandle(capture);
        HarnessService service = new HarnessService(config);
        try {
            service.start();
            capture.messages.clear();
            JSONObject request = new JSONObject().put("protocolVersion", 1).put("action", "register").put("workerId", "model-1")
                    .put("instanceId", "instance-1").put("role", "model").put("requestId", "register-1")
                    .put("capabilities", new JSONArray()).put("credential", p.getProperty("worker.credential"));
            JSONObject registered = send(service, request, 1);
            // 无能力的 worker 可以 registered 但 ready=false：两个标志不得混为一谈。
            check(registered.getBoolean("registered") && !registered.getBoolean("ready"), "registration and readiness conflated");
            // 成功且未就绪时只打印一条 INFO，且包含 registered=true ready=false。
            check(capture.messages.size() == 1 && capture.messages.get(0).startsWith("INFO ")
                    && capture.messages.get(0).contains("registered=true ready=false"), "not-ready success log");
            // 同 workerId 的另一实例注册返回 DUPLICATE_WORKER，并打印一条 WARN。
            JSONObject duplicate = send(service, new JSONObject(request.toString()).put("instanceId", "other"), 2);
            check(duplicate.getString("errorCode").equals("DUPLICATE_WORKER") && capture.messages.size() == 2
                    && capture.messages.get(1).contains("WARN ") && capture.messages.get(1).contains("DUPLICATE_WORKER"), "duplicate rejection log");
            // 错误凭据返回 UNAUTHORIZED。
            send(service, new JSONObject(request.toString()).put("credential", "never-log-this-private-credential"), 1);
            check(capture.messages.get(2).contains("UNAUTHORIZED"), "authentication rejection missing");
            // 未在配置中授权的 workerId 返回 FORBIDDEN。
            send(service, new JSONObject(request.toString()).put("workerId", "forbidden"), 3);
            check(capture.messages.get(3).contains("FORBIDDEN"), "authorization rejection missing");
            // 正常心跳不得重复打印注册日志。
            int before = capture.messages.size();
            send(service, new JSONObject(request.toString()).put("action", "heartbeat").put("sessionId", registered.getString("sessionId")), 1);
            check(capture.messages.size() == before, "heartbeat repeated registration log");
            // 协议 2–3 的 describe 各打印一条；未发布 descriptor 的失败含 "registration rejected"。
            for (int protocol = 2; protocol <= 3; protocol++) {
                JSONObject model = new JSONObject(request.toString()).put("protocolVersion", protocol).put("action", "describe")
                        .put("descriptor", new JSONObject().put("model", "Baize2"));
                before = capture.messages.size();
                send(service, model, protocol);
                check(capture.messages.size() == before + 1
                        && capture.messages.get(before).contains("registration rejected"), "versioned failure must log once");
                // 直接调用 outcome 验证成功格式：registered=true ready=true。
                WorkerRegistrationLog.outcome(model, new JSONObject().put("ok", true).put("registered", true).put("ready", true), config, protocol, null);
                check(capture.messages.get(capture.messages.size() - 1).contains("registered=true ready=true"), "versioned success format");
            }
            // 命名 Runner（tool/sandbox）：错误信封同时携带类型和当前协议版本。
            for (String type : new String[]{"tool", "sandbox"}) {
                JSONObject runner = new JSONObject(request.toString());
                runner.put("runnerType", type).put("role", "runner").put("action", "describe")
                        .put("capabilities", new JSONArray().put("tool.execute"))
                        .put("descriptor", new JSONObject().put("runtimeId", "runtime-test").put("capacity", 1)
                                .put("runtime", "trusted-python-v1"));
                // sandbox 单元追加执行 profile 与两份 64 位十六进制摘要。
                if (type.equals("sandbox")) {
                    runner.getJSONObject("descriptor")
                            .put("executionProfile", "linux-firecracker-python-v1")
                            .put("sandboxProfileDigest", String.join("", Collections.nCopies(64, "a")))
                            .put("certificateDigest", String.join("", Collections.nCopies(64, "b")));
                }
                // 工具执行未启用（execution.runners 缺失）时命名 Runner 按 RUNNER_DISABLED 拒绝。
                JSONObject rejected = send(service, runner, 7);
                check(rejected.getString("errorCode").equals("RUNNER_DISABLED")
                        && rejected.getString("runnerType").equals(type) && rejected.getInt("protocolVersion") == 1, "named Runner error envelope");
                // 拒绝日志须含 runnerType=<type>。
                check(capture.messages.get(capture.messages.size() - 1).contains("runnerType=" + type), "Runner type missing from log");
                // 不支持的协议版本拒绝，缺失版本也不能被默认为兼容。
                check(send(service, new JSONObject(runner.toString()).put("protocolVersion", 5), 7)
                        .getString("errorCode").equals("PROTOCOL_MISMATCH"), "unsupported Runner version accepted");
                JSONObject missingVersion = new JSONObject(runner.toString());
                missingVersion.remove("protocolVersion");
                check(send(service, missingVersion, 7).getString("errorCode").equals("PROTOCOL_MISMATCH"), "missing Runner version accepted");
                check(send(service, new JSONObject(runner.toString()).put("capabilities", new JSONArray().put("model.round")), 7)
                        .getString("errorCode").equals("SCHEMA_INVALID"), "unsupported Runner capability accepted");
                JSONObject oldDigest = new JSONObject(runner.toString());
                oldDigest.getJSONObject("descriptor").put("sourceDigest", String.join("", Collections.nCopies(64, "a")));
                check(send(service, oldDigest, 7).getString("errorCode").equals("SCHEMA_INVALID"), "obsolete source identity accepted");
                // 未知 runnerType 按 RUNNER_TYPE_INVALID 拒绝。
                check(send(service, new JSONObject(runner.toString()).put("runnerType", "unknown"), 7)
                        .getString("errorCode").equals("RUNNER_TYPE_INVALID"), "unknown Runner type accepted");
            }
            // 旧协议 4–6 的数字 Runner 选择器按 PROTOCOL_MISMATCH 拒绝。
            for (int legacy : new int[]{4, 5, 6}) {
                check(send(service, new JSONObject(request.toString()).put("protocolVersion", legacy), 8)
                        .getString("errorCode").equals("PROTOCOL_MISMATCH"), "legacy numeric Runner selector accepted");
            }
            // 非法 JSON：拒绝日志含 "worker request rejected"。
            service.process("{invalid".getBytes(StandardCharsets.UTF_8), 1);
            check(capture.messages.get(capture.messages.size() - 1).contains("worker request rejected"), "invalid JSON rejection missing");
            // 内部异常按 ERROR 记录，且只保留异常类名（消息夹带的凭据不得外泄）。
            WorkerRegistrationLog.outcome(request, HarnessService.error("register-1", "INTERNAL_ERROR"), config, 1,
                    new IllegalStateException("never-log-this-private-credential"));
            check(capture.messages.get(capture.messages.size() - 1).startsWith("ERROR "), "internal exception log severity");
            // 信封级 HARNESS_BUSY 拒绝同样落日志。
            HarnessService.logWorkerRejection("HARNESS_BUSY", 1);
            check(capture.messages.get(capture.messages.size() - 1).contains("HARNESS_BUSY"), "queue rejection missing");
            // 脱敏总断言：凭据、sessionId 与异常消息都不得出现在任何日志行。
            String all = capture.messages.toString();
            check(!all.contains("never-log-this-private-credential") && !all.contains(p.getProperty("worker.credential"))
                    && !all.contains(registered.getString("sessionId")), "private fields leaked into logs");
            System.out.println("PASS: registration success/not-ready, probe/model versions, named Runner types, legacy rejection, heartbeat silence and private-field redaction");
        } finally {
            service.stop();
            LogManager.getInstance().removeHandle(capture);
        }
    }
}
