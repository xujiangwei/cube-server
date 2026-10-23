package cube.service.harness.test;

import cube.aigc.completion.*;
import cube.service.harness.execution.*;
import cube.service.harness.store.*;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.RuntimeSupport;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.sql.*;
import static cube.service.harness.test.RuntimeTest.*;

/**
 * 推理网关离线集成测试：真实 JDBC 推理账本与 Java HTTP 解码，推理硬件以夹具
 * 模拟；属 执行编排的推理链路（Cell v3 inference_submit/poll/cancel），
 * 底层为一次性 sqlite 存储库。
 *
 * <p>覆盖跨语言 canonical 摘要向量、共享物理容量
 * 记账、提交幂等与冲突、未知完成隔离、JVM 崩溃窗口、迟到完成与权威 usage 结算，
 * 以及外部 OpenAI HTTP 的 JSON/SSE、工具参数增量、截断、拒绝与缺失 usage 解码。
 */
public final class InferenceTest {
    /**
     * 在 5 秒内轮询等待推理证据条件成立。
     *
     * @param condition 轮询条件
     * @throws Exception 条件中的受检异常或超时失败
     */
    static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        check(condition.getAsBoolean(), "Timed out waiting for inference evidence");
    }

    /**
     * @return 固定的 OpenAI 风格完成结果（content 为 final 决策 JSON，usage 合计 22 token）
     */
    static JSONObject result() {
        return new JSONObject().put("message", new JSONObject().put("role", "assistant").put("content", "{\"kind\":\"final\",\"text\":\"ok\"}"))
                .put("finish_reason", "stop").put("usage", new JSONObject().put("prompt_tokens", 17).put("completion_tokens", 5).put("total_tokens", 22));
    }

    /**
     * @return 固定的 Baize 请求体（stream、max_tokens=256、单条 user 消息）
     */
    static JSONObject wire() {
        return new JSONObject().put("model", "Baize").put("stream", true).put("max_tokens", 256)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
    }

    /**
     * 构造 protocolVersion=3 的推理信封；session 非空时附带会话标识。
     *
     * @param worker worker 标识
     * @param action 协议动作
     * @param session 会话标识；可为 null
     * @return 信封 JSON
     */
    static JSONObject envelope(String worker, String action, String session) {
        JSONObject e = ExecutionTest.envelope(worker, "i" + worker, action).put("protocolVersion", 3);
        if (session != null) {
            e.put("sessionId", session);
        }
        return e;
    }

    /**
     * @return openai-json-v2 adapter 的 Baize 模型描述符
     */
    static JSONObject descriptor() {
        return new JSONObject().put("model", "Baize").put("adapter", "openai-json-v2").put("nativeTools", false)
                .put("cancellation", "observe-only").put("budget", "transport-bytes-v1");
    }

    /**
     * 构造 inference_submit 请求：绑定 job、原 worker 实例、wireAttempt 与请求摘要。
     *
     * @param job 派发到的作业 JSON
     * @param session 会话标识
     * @return 提交请求 JSON
     */
    static JSONObject submit(JSONObject job, String session) {
        return envelope(job.getString("workerId"), "inference_submit", session)
                .put("jobId", job.getString("jobId")).put("jobInstanceId", job.getString("instanceId")).put("wireAttempt", 1)
                .put("requestHash", JdbcRunStore.hash(wire())).put("requestJson", wire().toString());
    }

    /**
     * 同 JVM CompletionProvider 夹具：按 inferenceId 登记 listener 并异步推送事件。
     *
     * <p>同一 id 重复提交视为重复物理推理而失败；completed 事件携带权威 result，
     * error 事件携带 MODEL_COMPLETION_UNKNOWN，用于验证未知隔离与迟到结算。
     */
    static final class Provider implements CompletionProvider {
        /** 进行中的物理推理：inferenceId -> listener；map 大小即占用的物理容量。 */
        final Map<String, Listener> listeners = new ConcurrentHashMap<>();

        /**
         * @param token 未使用的上游 token
         * @return 声明 Baize 文本能力的模型列表
         */
        public JSONArray completionModels(String token) {
            return new JSONArray().put(new JSONObject().put("id", "Baize").put("capabilities", new JSONObject().put("text", true)));
        }

        /**
         * @param token 未使用的上游 token
         * @param model 模型标识
         * @param tools 是否请求原生工具
         * @return 固定 2 注册 / 2 可用的容量视图
         */
        public JSONObject completionCapacity(String token, String model, boolean tools) {
            return new JSONObject().put("registered", 2).put("available", 2);
        }

        /**
         * 登记一次物理推理；同一 id 重复提交直接失败。
         *
         * @param token 未使用的上游 token
         * @param id inferenceId
         * @param body 请求体
         * @param timeout 未使用的超时
         * @param listener 事件回调
         */
        public void submitCompletion(String token, String id, JSONObject body, long timeout, Listener listener) {
            check(listeners.putIfAbsent(id, listener) == null, "Duplicate physical inference");
        }

        /**
         * 向指定推理推送事件：finished 为 completed+result，否则 error+未知错误码。
         *
         * @param id inferenceId
         * @param finished 是否物理完成
         */
        void emit(String id, boolean finished) {
            listeners.get(id).onEvent(new JSONObject().put("kind", finished ? "completed" : "error").put("actualFinished", finished)
                    .put("payload", finished ? new JSONObject().put("result", result())
                            : new JSONObject().put("error", new JSONObject().put("code", "MODEL_COMPLETION_UNKNOWN"))));
        }
    }

    /**
     * 推理账本主校验：跨语言 canonical 摘要、共享物理容量、提交幂等与冲突、
     * 未知完成隔离、JVM 崩溃窗口、迟到完成与权威 usage、协调器终态。
     *
     * @throws Exception 存储、派发或断言失败
     */
    static void ledger() throws Exception {
        JSONObject vector = new JSONObject(new String(
                cube.service.harness.protocol.Contract.resource("inference/canonical-vectors.json"), StandardCharsets.UTF_8));
        check(JdbcRunStore.canonical(vector.getJSONObject("value")).equals(vector.getString("canonical")),
                "Cross-language canonical digest vector failed");
        ExecutionTest.prepare(Files.createTempDirectory("harness-inference-"), System.getProperty("inference.test.config", ""));
        JSONObject model = ModelProfiles.model("Baize");
        check(!model.has("schemaVersion"), "Generated profile still requires schemaVersion");
        ProfileRegistry.validateDefinition(RuntimeSupport.copy(model).put("schemaVersion", 3));
        invalid(() -> ProfileRegistry.validateDefinition(RuntimeSupport.copy(model).put("schemaVersion", 1)));
        invalid(() -> ProfileRegistry.validateDefinition(RuntimeSupport.copy(model).put("schemaVersion", 2)));
        invalid(() -> ProfileRegistry.validateDefinition(RuntimeSupport.copy(model).put("schemaVersion", 4)));
        invalid(() -> ProfileRegistry.validateDefinition(RuntimeSupport.copy(model).put("schemaVersion", "3")));
        check(!model.has("adapterDigest") && !model.getJSONObject("tokenizer").has("digest")
                && !model.getJSONObject("template").has("digest"), "Generated profile still requires digests");
        // Legacy configured digests are ignored by validation and actual job routing.
        model.put("adapterDigest", "obsolete-adapter");
        model.getJSONObject("tokenizer").put("digest", "obsolete-tokenizer");
        model.getJSONObject("template").put("digest", "obsolete-template");
        profiles.publish(T, model);
        JSONObject task = task("2", release, "Baize", 1024).put("schemaVersion", 2)
                .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 16).put("maxRepairs", 1).put("maxLifetimeMillis", 60000));
        task.getJSONObject("model").put("version", "2");
        task.getJSONObject("contextPolicy").put("budgetVersion", "transport-bytes-v1").put("maxInputBytes", 48000);
        profiles.publish(T, task);
        profiles.bind(T, "task", "task", "task@2", 1);
        ModelJobStore jobs = new ModelJobStore(store, config.mysql());
        InferenceStore ledger = new InferenceStore(store, config.mysql());
        jobs.backend("legacy", ExecutionTest.descriptor("legacy"), 1);
        jobs.backend("legacy", new JSONObject().put("backendId", "legacy").put("type", "unit"), 1);
        jobs.backend("legacy", ExecutionTest.descriptor("legacy"), 1);
        Properties p = new Properties();
        for (String worker : Arrays.asList("a", "b")) {
            p.setProperty("worker." + worker + ".role", "model");
            p.setProperty("worker." + worker + ".model", "Baize");
            p.setProperty("worker." + worker + ".backend", "shared");
        }
        p.setProperty("backend.shared.capacity", "1");
        Provider provider = new Provider();
        try (InferenceGateway gateway = new InferenceGateway(ledger, p, () -> provider);
             RunCoordinator coordinator = new RunCoordinator(store, jobs, catalog, identity, resources)) {
            ModelDispatcher dispatcher = new ModelDispatcher(jobs, p, 60000, gateway);
            for (JSONObject unsupported : Arrays.asList(
                    descriptor().put("nativeTools", true),
                    descriptor().put("adapter", "scripted-v1"),
                    descriptor().put("cancellation", "interrupt"),
                    descriptor().put("budget", "unknown"))) {
                try {
                    dispatcher.apply(envelope("a", "describe", null).put("descriptor", unsupported), 1, () -> true);
                    throw new AssertionError("Unsupported model capability accepted");
                } catch (IllegalArgumentException expected) {
                    check("MODEL_PROFILE_MISMATCH".equals(expected.getMessage()), "wrong model capability rejection");
                }
            }
            String sa = dispatcher.apply(envelope("a", "describe", null).put("descriptor", descriptor()), 1, () -> true)
                    .getString("sessionId");
            String sb = dispatcher.apply(envelope("b", "describe", null).put("descriptor", descriptor()), 2, () -> true)
                    .getString("sessionId");
            String r1 = ExecutionTest.create("first");
            String r2 = ExecutionTest.create("second");
            for (int n = 0; n < 3; n++) {
                coordinator.step(r1);
                coordinator.step(r2);
            }
            JSONObject a = dispatcher.apply(envelope("a", "poll", sa), 1, () -> true).getJSONObject("job");
            JSONObject b = dispatcher.apply(envelope("b", "poll", sb), 2, () -> true).getJSONObject("job");
            // Python 处理槽就绪不得消耗物理推理容量。
            check(sharedOccupancy(jobs) == 0, "Python slots consumed physical capacity");
            JSONObject first = dispatcher.apply(submit(a, sa), 1, () -> true).getJSONObject("inference");
            check(dispatcher.apply(submit(a, sa), 1, () -> true).getJSONObject("inference").getString("inferenceId")
                            .equals(first.getString("inferenceId")),
                    "Replay created another inference");
            fail("INFERENCE_CONFLICT", () -> ledger.submit(a.getString("jobId"), "a", "ia", "ia", 1,
                    JdbcRunStore.hash(wire().put("temperature", 1)), wire().put("temperature", 1)));
            fail("JOB_EVIDENCE_MISMATCH", () -> ledger.page(a.getString("jobId"), "b", "ia", 1, 0, false));
            String second = dispatcher.apply(submit(b, sb), 2, () -> true).getJSONObject("inference").getString("inferenceId");
            String id = first.getString("inferenceId");
            gateway.start();
            until(() -> provider.listeners.size() == 1);
            // 无论哪一先排在队首，共享物理容量只允许一个在途推理。
            String active = provider.listeners.keySet().iterator().next();
            provider.emit(active, false);
            Thread.sleep(120);
            check(provider.listeners.size() == 1, "Unknown inference released physical capacity");
            // 模拟进程恢复：harness-7088 节点上的在途推理转 unknown 但不释放容量。
            ledger.recover("harness-7088", "new-process");
            provider.emit(active, true);
            provider.emit(active, true);
            until(() -> provider.listeners.size() == 2);
            provider.emit(active.equals(id) ? second : id, true);
            JSONObject page = ledger.page(a.getString("jobId"), "a", "ia", 1, 0, false);
            check(page.getBoolean("actualFinished") && page.has("response"), "Late result not reconciled");
            JSONObject report = ExecutionTest.report(a, new JSONObject().put("kind", "final").put("text", "ok"), true);
            dispatcher.apply(envelope("a", "actual_finished", sa).put("report", report), 1, () -> true);
            dispatcher.apply(envelope("a", "actual_finished", sa).put("report", report), 1, () -> true);
            JSONObject outcome = new JSONObject(jobs.jobById(a.getString("jobId")).getString("outcome_json"));
            // 权威 usage 来自 Java 账本，Python 断言不得覆盖。
            check(outcome.getJSONObject("usage").getInt("input") == 17, "Python usage overrode authoritative usage");
            for (int n = 0; n < 4; n++) {
                coordinator.step(a.getString("runId"));
            }
            check(store.read(T, S, a.getString("runId")).getJSONObject("snapshot").getString("state").equals("succeeded"),
                    "v3 coordinator failed");
            check(sharedOccupancy(jobs) == 0, "Finished inference leaked capacity");
            fail("INVALID_INFERENCE_CURSOR", () -> ledger.page(a.getString("jobId"), "a", "ia", 1, 1, false));
            // 模拟 JVM 在持久 claim 之后、调用提供方之前崩溃。
            gateway.close();
            String r3 = ExecutionTest.create("crash window");
            for (int n = 0; n < 3; n++) {
                coordinator.step(r3);
            }
            JSONObject crash = dispatcher.apply(envelope("a", "poll", sa), 1, () -> true).getJSONObject("job");
            JSONObject pending = ledger.submit(crash.getString("jobId"), "a", "ia", "ia", 1, JdbcRunStore.hash(wire()), wire());
            String crashId = pending.getString("inference_id");
            check(ledger.claim(crashId, "stable", "old"), "Crash reservation missing");
            ledger.recover("stable", "new");
            JSONObject unknown = ledger.page(crash.getString("jobId"), "a", "ia", 1, 0, true);
            check(unknown.getString("status").equals("unknown") && !unknown.getBoolean("actualFinished"),
                    "Crash/cancel falsely released capacity");
            check(sharedOccupancy(jobs) == 1, "Crash window lost the physical reservation");
            // 新实例可读取原推理记录，但不得以新 attempt 重新发起。
            check(ledger.submit(crash.getString("jobId"), "a", "ia", "new-instance", 1, JdbcRunStore.hash(wire()), wire())
                            .getString("inference_id").equals(crashId),
                    "New instance cannot read original inference");
            ledger.finish(crashId, OpenAICompletionProtocol.result(result(), false, crashId, "Baize", 1), null, true, null);
            fail("INFERENCE_NOT_ADMISSIBLE",
                    () -> ledger.submit(crash.getString("jobId"), "a", "ia", "new-instance", 2, JdbcRunStore.hash(wire()), wire()));
            ledger.submit(crash.getString("jobId"), "a", "ia", "ia", 2, JdbcRunStore.hash(wire()), wire());
            check(ledger.page(crash.getString("jobId"), "a", "ia", 2, 0, true).getBoolean("actualFinished"),
                    "Queued cancellation failed");
        }
        pass("Cell v3 dispatch, separate processing slots, shared inference capacity, idempotency, foreign identity, unknown quarantine, late completion and authoritative usage");
    }

    /**
     * 读取 shared backend 的当前物理占用。
     *
     * @param jobs 模型作业账本
     * @return occupied 数值
     */
    static int sharedOccupancy(ModelJobStore jobs) {
        JSONArray backends = jobs.capacity().getJSONArray("backends");
        for (int n = 0; n < backends.length(); n++) {
            if (backends.getJSONObject(n).getString("backend_id").equals("shared")) {
                return backends.getJSONObject(n).getInt("occupied");
            }
        }
        throw new AssertionError("Shared backend missing");
    }

    /**
     * 外部 OpenAI HTTP 适配校验：JSON/SSE、工具参数增量、finish_reason、精确
     * usage、截断、HTTP 拒绝、缺失 usage 与未知参数拒绝。
     *
     * @throws Exception HTTP、提供方或断言失败
     */
    static void http() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        List<JSONObject> received = new CopyOnWriteArrayList<>();
        // 本机测试上游：按消息内容分流 rejected/slow/json/sse 等响应形态。
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] raw = cube.service.harness.runtime.RuntimeSupport.read(exchange.getRequestBody(), 131072);
            JSONObject body = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            received.add(body);
            String mode = body.getJSONArray("messages").getJSONObject(0).getString("content");
            if (mode.equals("rejected")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            if (mode.equals("slow")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try {
                    for (int n = 0; n < 20; n++) {
                        exchange.getResponseBody().write('x');
                        exchange.getResponseBody().flush();
                        Thread.sleep(50);
                    }
                } catch (Exception expected) {
                } finally {
                    exchange.close();
                }
                return;
            }
            String response;
            if (mode.startsWith("json")) {
                JSONObject r = result();
                if (mode.equals("json-refusal")) {
                    r.getJSONObject("message").put("refusal", "declined");
                }
                JSONObject output = OpenAICompletionProtocol.result(r, false, "fixture", "actual", 1);
                if (mode.equals("json-no-usage")) {
                    output.remove("usage");
                }
                response = output.toString();
            } else {
                // SSE：首块带完整工具调用标识，随后移除 id/name 并补齐参数增量。
                JSONArray parts = new JSONArray().put(new JSONObject().put("index", 0).put("id", "call-1")
                        .put("type", "function").put("function", new JSONObject().put("name", "lookup").put("arguments", "{\"x\":")));
                JSONObject delta = new JSONObject().put("tool_calls", parts);
                JSONObject chunk = new JSONObject().put("choices",
                        new JSONArray().put(new JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL)));
                response = "data: " + chunk + "\n\n";
                parts.getJSONObject(0).remove("id");
                parts.getJSONObject(0).getJSONObject("function").remove("name");
                parts.getJSONObject(0).getJSONObject("function").put("arguments", "1}");
                chunk.getJSONArray("choices").getJSONObject(0).put("finish_reason", "tool_calls");
                response += "data: " + chunk + "\n\ndata: "
                        + new JSONObject().put("choices", new JSONArray()).put("usage", result().getJSONObject("usage")) + "\n\n";
                if (!mode.equals("truncated")) {
                    response += "data: [DONE]\n\n";
                }
            }
            exchange.getResponseHeaders().set("Content-Type", mode.startsWith("json") ? "application/json" : "text/event-stream");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (OpenAIHttpProvider provider = new OpenAIHttpProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "actual", "secret", 2)) {
            for (String mode : Arrays.asList("json", "sse", "truncated", "json-refusal", "json-no-usage", "rejected", "slow")) {
                JSONObject w = wire().put("stream", !mode.startsWith("json"));
                w.getJSONArray("messages").getJSONObject(0).put("content", mode);
                List<JSONObject> events = new CopyOnWriteArrayList<>();
                long start = System.nanoTime();
                provider.submitCompletion("", mode, OpenAICompletionProtocol.normalize(w, false),
                        mode.equals("slow") ? 200 : 2000, events::add);
                until(() -> !events.isEmpty()
                        && Arrays.asList("completed", "error").contains(events.get(events.size() - 1).getString("kind")));
                JSONObject last = events.get(events.size() - 1);
                if (mode.equals("truncated")) {
                    check(!last.getBoolean("actualFinished") && last.getString("kind").equals("error"), "Truncated SSE falsely finished");
                } else if (mode.equals("slow")) {
                    check(!last.getBoolean("actualFinished")
                                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500,
                            "SSE activity bypassed absolute deadline");
                } else if (mode.equals("rejected")) {
                    check(last.getBoolean("actualFinished")
                                    && last.getJSONObject("payload").getJSONObject("error").getString("code").equals("MODEL_HTTP_400"),
                            "HTTP rejection did not safely release");
                } else if (mode.equals("json-no-usage")) {
                    check(last.getBoolean("actualFinished")
                                    && last.getJSONObject("payload").getJSONObject("error").getString("code").equals("MODEL_USAGE_MISSING"),
                            "Missing usage fabricated or confused with ongoing inference");
                } else {
                    JSONObject r = last.getJSONObject("payload").getJSONObject("result");
                    check(r.getJSONObject("usage").getInt("total_tokens") == 22, "Usage missing");
                    if (mode.equals("sse")) {
                        check(r.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
                                        .getString("arguments").equals("{\"x\":1}"),
                                "Argument delta reconstruction");
                    }
                    if (mode.equals("json-refusal")) {
                        check(r.getJSONObject("message").getString("refusal").equals("declined"), "Refusal discarded");
                    }
                }
            }
            check(received.size() == 7 && received.get(0).getString("model").equals("actual"),
                    "Java external model routing failed");
            // 不支持的参数必须明确拒绝，不得静默忽略。
            try {
                OpenAICompletionProtocol.normalize(wire().put("unknown_parameter", 1), false);
                throw new AssertionError("Unknown option accepted");
            } catch (CompletionError expected) {
            }
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
        pass("Java external HTTP JSON/SSE, native tool argument fragments, finish reason, exact usage, truncation and unsupported parameter rejection");
    }

    /**
     * 测试入口：prepare 子命令仅生成 Baize/Baize2 夹具；否则依次执行迁移、账本、
     * 共享容量与 HTTP 校验。
     *
     * @param args 首参数为 prepare 时进入夹具模式，次参数为目标目录
     * @throws Exception 任何阶段失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("prepare")) {
            ExecutionTest.prepare(Files.createDirectory(java.nio.file.Paths.get(args[1])), "");
            for (String name : Arrays.asList("Baize", "Baize2")) {
                profiles.publish(T, ModelProfiles.model(name));
                JSONObject t = task("2", release, name, 1024).put("id", "inference-" + name).put("schemaVersion", 2)
                        .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 16).put("maxRepairs", 1).put("maxLifetimeMillis", 60000));
                t.getJSONObject("model").put("version", "2");
                t.getJSONObject("contextPolicy").put("budgetVersion", "transport-bytes-v1").put("maxInputBytes", 48000);
                profiles.publish(T, t);
                profiles.bind(T, "task", "inference-" + name, "inference-" + name + "@2", 0);
                profiles.configure(new Properties(), T, t, root.resolve("server-" + name));
            }
            return;
        }
        ledger();
        sharedCapacity();
        http();
        System.out.println("PASS: inference gateway offline integration");
    }

    /**
     * Relay 共享容量校验：跨 worker/模型/后端聚合 registeredUnits 与可执行并发，
     * 处理中别名不得占用另一后端的可执行路由，聚合视图不得重复计数。
     *
     * @throws Exception 存储、派发或断言失败
     */
    static void sharedCapacity() throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-relay-capacity-"), "");
        Properties p = new Properties();
        for (String worker : Arrays.asList("relay-a", "relay-b", "relay-c", "relay-d")) {
            p.setProperty("worker." + worker + ".role", "model");
            p.setProperty("worker." + worker + ".model", worker.equals("relay-c") || worker.equals("relay-d") ? "Baize2" : "Baize");
            p.setProperty("worker." + worker + ".backend", worker.equals("relay-d") ? "other" : "shared");
        }
        p.setProperty("backend.shared.capacity", "4");
        p.setProperty("backend.other.capacity", "4");
        final java.util.concurrent.atomic.AtomicInteger budget = new java.util.concurrent.atomic.AtomicInteger(1);
        final java.util.concurrent.atomic.AtomicBoolean firstAvailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicBoolean detailed = new java.util.concurrent.atomic.AtomicBoolean(true);
        // Relay 提供方夹具：按模型别名与预算旋钮报告 registered/available 与分组明细。
        CompletionProvider provider = new CompletionProvider() {
            /**
             * @param token 未使用的上游 token
             * @return Baize/Baize2 两个模型的注册列表
             */
            public JSONArray completionModels(String token) {
                return new JSONArray()
                        .put(new JSONObject().put("id", "Baize").put("capabilities", new JSONObject().put("text", true)))
                        .put(new JSONObject().put("id", "Baize2").put("capabilities", new JSONObject().put("text", true)));
            }

            /**
             * @param token 未使用的上游 token
             * @param model 模型标识
             * @param tools 是否请求原生工具
             * @return 按别名可用性与预算旋钮变化的容量视图
             */
            public JSONObject completionCapacity(String token, String model, boolean tools) {
                int free = model.equals("Baize") && !firstAvailable.get() ? 0 : 1;
                if (!detailed.get()) {
                    return new JSONObject().put("registered", 1).put("available", 1).put("capacityGroup", "shared-relay");
                }
                return new JSONObject().put("registered", 1).put("available", free).put("capacityGroup", "shared-relay")
                        .put("capacityGroups", new JSONArray().put(new JSONObject().put("capacityGroup", "shared-relay").put("route", model)
                                .put("registered", 1).put("available", free).put("groupCapacity", budget.get()).put("groupAvailable", budget.get())));
            }

            /** 夹具不发起真实推理。 */
            public void submitCompletion(String token, String id, JSONObject request, long timeout, Listener listener) {}
        };
        ModelJobStore jobs = new ModelJobStore(store, false);
        try (InferenceGateway gateway = new InferenceGateway(new InferenceStore(store, false), p, () -> provider)) {
            ModelDispatcher dispatcher = new ModelDispatcher(jobs, p, 60000, gateway);
            int transport = 0;
            for (String worker : Arrays.asList("relay-a", "relay-b", "relay-c", "relay-d")) {
                JSONObject d = descriptor().put("model", p.getProperty("worker." + worker + ".model"));
                dispatcher.apply(envelope(worker, "describe", null).put("descriptor", d), ++transport, () -> true);
            }
            // 四个 worker/两个模型/两个后端只应聚合出 1 个可执行并发。
            JSONObject capacity = dispatcher.capacity();
            check(capacity.getInt("registeredUnits") == 4 && capacity.getInt("available") == 1
                            && capacity.getInt("executableConcurrency") == 1,
                    "Relay capacity duplicated across models/workers/backends");
            // 组预算提高到 2：不同模型路由应能使用配置的共享容量。
            budget.set(2);
            capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 2 && capacity.getInt("executableConcurrency") == 2,
                    "Distinct Relay model routes cannot use configured shared capacity");
            // 一个别名不可用不得隐藏另一模型的空闲共享路由。
            budget.set(1);
            firstAvailable.set(false);
            capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 1, "Unavailable alias hid another model's free shared route");
            assertCapacityBounds(capacity);
            // 仅有聚合 capacityGroup 而无路由明细时不得重复计数。
            budget.set(1);
            detailed.set(false);
            capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 1 && capacity.getInt("executableConcurrency") == 1,
                    "Aggregate capacityGroup without route detail was counted multiple times");
            assertCapacityBounds(capacity);
            budget.set(2);
            firstAvailable.set(true);
            detailed.set(true);
            // Python worker 可忙于处理响应而物理推理空闲；同一模型在另一后端必须保留可执行槽。
            try (Connection c = config.connect(); Statement s = c.createStatement()) {
                s.execute("INSERT INTO harness_run VALUES ('capacity-run','tenant','subject','profile','running',1,1,NULL,0,0,'pending','settled',1,1)");
                s.execute("INSERT INTO harness_model_job (job_id,run_id,tenant,subject_id,round_id,attempt,origin_fence,intent_hash,model_id,profile_digest,request_hash,request_json,backend_id,worker_id,instance_id,session_id,status,actual_finished,cancel_requested,created_at,execution_protocol) "
                        + "VALUES ('capacity-job','capacity-run','tenant','subject','round',1,1,'intent','Baize2','profile','request','{}','shared','relay-c','irelay-c','session','assigned',0,0,1,3)");
            }
            capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 2 && capacity.getInt("executableConcurrency") == 2,
                    "Busy processing alias consumed another backend's registered inference route");
            assertCapacityBounds(capacity);
        }
        pass("Relay shared capacity across multiple workers, models and backend IDs");
    }

    /**
     * 断言每个 backend 的 available 不超过其 executableConcurrency。
     *
     * @param capacity 派发器容量视图
     */
    static void assertCapacityBounds(JSONObject capacity) {
        JSONArray backends = capacity.getJSONArray("backends");
        for (int i = 0; i < backends.length(); i++) {
            JSONObject backend = backends.getJSONObject(i);
            check(backend.getInt("available") <= backend.getInt("executableConcurrency"),
                    "Backend available capacity exceeds its executable concurrency: " + backend.getString("backend_id"));
        }
    }
}
