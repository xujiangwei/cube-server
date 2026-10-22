package cube.service.harness.test;
import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/**
 * N06/N07 执行编排集成测试：真实 JDBC 协调器/调度器行为校验，并为真实
 * Cell/Python smoke 准备夹具；默认使用一次性 sqlite 存储库。
 *
 * <p>覆盖共享物理容量记账、不可变派发重放、断连与未知完成隔离、迟到
 * actual_finished 对账、重复/冲突证据拒绝、唯一终态与 owner 租约、
 * Baize/Baize2 v2 profile 选择，以及 need_input 与逻辑超时语义。
 */
public final class ExecutionTest {
    /** 模型作业账本；协调器与派发器共享其持久作业行与 backend 容量行。 */
    static ModelJobStore jobs;

    /** run 协调器；以租约/fence/checkpoint 推进资源循环并做终态对账。 */
    static RunCoordinator coordinator;

    /** Cell v3 模型派发器；管理 worker 会话、共享处理槽与证据回放。 */
    static ModelDispatcher dispatcher;

    /**
     * 构造 scripted 后端描述符，供 describe 动作注册模型 worker。
     *
     * @param backend 后端标识；共享同一物理引擎的 worker 使用同一 backendId 与容量
     * @return 含 model/adapter/capacity/cancellation/budget 能力声明的描述符
     */
    static JSONObject descriptor(String backend) {
        return new JSONObject().put("model", "scripted").put("backendId", backend)
                .put("adapter", "scripted-v1").put("capacity", 1).put("nativeTools", false)
                .put("cancellation", "observe-only").put("budget", "transport-bytes-v1");
    }

    /**
     * 构造 protocolVersion=2 的模型 worker 信封；describe 之外的动作附带健康标记。
     *
     * @param worker worker 标识
     * @param instance 实例标识
     * @param action 协议动作（describe/poll/actual_finished 等）
     * @return 填充协议版本、请求标识、凭据、角色与能力后的信封
     */
    static JSONObject envelope(String worker, String instance, String action) {
        JSONObject e = new JSONObject().put("protocolVersion", 2).put("requestId", key())
                .put("credential", "test-worker-credential-123456").put("workerId", worker)
                .put("instanceId", instance).put("role", "model")
                .put("capabilities", new JSONArray().put("model.round")).put("action", action);
        if (!action.equals("describe")) {
            e.put("healthy", true);
        }
        return e;
    }

    /**
     * 准备执行测试夹具：建立一次性存储，发布资源/模型/任务 profile 与预算变体，
     * 并生成慢消费者 run 与 fixture.json 供真实 smoke 使用。
     *
     * @param directory 测试运行根目录；资产、SQLite 文件与导出文件均置于其下
     * @param file 可选的外部一次性存储配置路径；为空时使用默认 sqlite 一次性库
     * @throws Exception 配置加载、迁移、上传或文件写入失败
     */
    static void prepare(Path directory, String file) throws Exception {
        root = directory;
        Properties p = new Properties();
        if (!file.isEmpty()) {
            try (InputStream in = Files.newInputStream(Paths.get(file))) {
                p.load(in);
            }
            check("true".equals(p.getProperty("store.test.disposable")), "Disposable store required");
        } else {
            p.setProperty("store.dialect", "sqlite");
            p.setProperty("store.database", "harness_execution");
            p.setProperty("store.identity", "execution-test-identity-123456");
            p.setProperty("store.sqlite.root", root.toString());
            p.setProperty("store.sqlite.file", root.resolve("harness_execution.db").toString());
        }
        config = new StoreConfig(p);
        StoreMigrations.migrate(config, true);
        String runtimeConfig = System.getProperty("execution.test.runtime", "");
        if (!runtimeConfig.isEmpty()) {
            Properties rp = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(runtimeConfig))) {
                rp.load(in);
            }
            check("true".equals(rp.getProperty("store.test.disposable")), "Disposable runtime required");
            p = rp;
            config = new StoreConfig(p);
        }
        setup();
        Map<String, byte[]> entries = entries("A resource used by the real Harness coordinator.");
        release = upload(zip(manifest(entries), entries)).getString("digest");
        catalog.lifecycle(T, release, 1, "ready");
        JSONObject t = task("1", release, "scripted", 1024).put("schemaVersion", 2)
                .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 16)
                        .put("maxRepairs", 1).put("maxLifetimeMillis", 60000));
        t.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
        profiles.publish(T, t);
        catalog.bind(T, "task", "task", "task@1", 0);
        // 严格决策、轮次/工具预算与 waiting_input 变体共享同一固定的资源/模型 profile。
        for (String variant : Arrays.asList("one-round", "no-tools", "lifetime")) {
            JSONObject v = RuntimeSupport.copy(t).put("id", variant);
            v.getJSONObject("executionLimits").put(
                    variant.equals("one-round") ? "maxRounds" : variant.equals("no-tools") ? "maxTools" : "maxLifetimeMillis",
                    variant.equals("lifetime") ? 1000 : variant.equals("no-tools") ? 0 : 1);
            profiles.publish(T, v);
            catalog.bind(T, "task", variant, variant + "@1", 0);
        }
        Files.write(root.resolve("store.local.properties"), bytes(properties(p)), StandardOpenOption.CREATE_NEW);
        String slow = store.create(T, S, key(), "events-only", new JSONObject().put("fixture", "slow-consumer")).getString("runId");
        JdbcRunStore.Lease sl = store.acquire(T, S, slow, "events-fixture", version(slow), 60000);
        String large = new String(new char[60000]).replace('\0', 'x');
        for (int i = 0; i < 80; i++) {
            store.transition(sl, version(slow), "running", new JSONObject().put("text", large));
        }
        store.finish(sl, version(slow), "succeeded", "confirmed", new JSONObject().put("fixture", true), new JSONArray());
        Files.write(root.resolve("fixture.json"),
                bytes(new JSONObject().put("release", release).put("tenant", T).put("subject", S)
                        .put("slowRun", slow).toString(2)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * 把配置按 key 排序序列化为 properties 文本；值中的反斜杠统一为正斜杠。
     *
     * @param p 待序列化的配置
     * @return 逐行 key=value 的文本，供后续运行读取
     */
    static String properties(Properties p) {
        StringBuilder b = new StringBuilder();
        for (String k : new TreeSet<>(p.stringPropertyNames())) {
            b.append(k).append('=').append(p.getProperty(k).replace("\\", "/")).append('\n');
        }
        return b.toString();
    }

    /**
     * 构造 actual_finished 证据；finished 时携带决策与权威 usage，否则标记未知完成。
     *
     * @param j 承载 jobId/requestHash/instanceId 的作业 JSON
     * @param decision 模型决策；未结束时可为 null
     * @param finished 是否物理结束
     * @return 可回放的证据 JSON；未知完成带 MODEL_COMPLETION_UNKNOWN 错误码
     */
    static JSONObject report(JSONObject j, JSONObject decision, boolean finished) {
        JSONObject outcome = new JSONObject().put("status", finished ? "success" : "unknown")
                .put("audit", new JSONArray()).put("cancellationObserved", false);
        if (finished) {
            outcome.put("decision", decision)
                    .put("usage", new JSONObject().put("input", 10).put("output", 5));
        } else {
            outcome.put("errorCode", "MODEL_COMPLETION_UNKNOWN");
        }
        return new JSONObject().put("jobId", j.getString("jobId"))
                .put("requestHash", j.getString("requestHash"))
                .put("instanceId", j.getString("instanceId"))
                .put("actualFinished", finished)
                .put("outcomeJson", outcome.toString());
    }

    /**
     * 以固定 task profile 创建 run（幂等 key）。
     *
     * @param text 任务指令文本
     * @return 新建或幂等重放得到的 runId
     */
    static String create(String text) {
        return profiles.createRun(identity, key(), "task", request(text)).getString("runId");
    }

    /**
     * 以指定 worker 会话拉取派发作业；transport 序号按 worker 区分。
     *
     * @param w worker 标识；a 使用序号 1，其余使用序号 2
     * @param instance 实例标识
     * @param session describe 取得的会话标识
     * @return 派发响应；无作业时不含 job 键
     */
    static JSONObject poll(String w, String instance, String session) {
        return dispatcher.apply(envelope(w, instance, "poll").put("sessionId", session),
                w.equals("a") ? 1 : 2, () -> true);
    }

    /**
     * N06/N07 主校验：共享容量、不可变派发、断连/未知隔离、迟到与冲突证据、
     * 唯一终态/owner、need_input 持久化与逻辑超时对账。
     *
     * @throws Exception 存储、派发或断言失败
     */
    static void checks() throws Exception {
        jobs = new ModelJobStore(store, config.mysql());
        coordinator = new RunCoordinator(store, jobs, catalog, identity, resources);
        Properties p = new Properties();
        for (String w : Arrays.asList("a", "b")) {
            p.setProperty("worker." + w + ".role", "model");
            p.setProperty("worker." + w + ".model", "scripted");
            p.setProperty("worker." + w + ".backend", "shared");
        }
        p.setProperty("backend.shared.capacity", "1");
        dispatcher = new ModelDispatcher(jobs, p, 1000);
        String sa = dispatcher.apply(envelope("a", "ia", "describe").put("descriptor", descriptor("shared")), 1, () -> true)
                .getString("sessionId");
        String sb = dispatcher.apply(envelope("b", "ib", "describe").put("descriptor", descriptor("shared")), 2, () -> true)
                .getString("sessionId");
        String r1 = create("first");
        String r2 = create("second");
        for (int i = 0; i < 3; i++) {
            coordinator.step(r1);
            coordinator.step(r2);
        }
        // 共享容量为 1：a 取得作业后，b 不得再取得同一物理处理槽。
        JSONObject a = poll("a", "ia", sa).getJSONObject("job");
        check(!poll("b", "ib", sb).has("job"), "Shared capacity counted twice");
        check(poll("a", "ia", sa).getJSONObject("job").toString().equals(a.toString()), "Lost dispatch not replayed");
        dispatcher.disconnect(1);
        check(!poll("b", "ib", sb).has("job"), "Disconnect released slot");
        String sa2 = dispatcher.apply(envelope("a", "ia", "describe").put("descriptor", descriptor("shared")), 1, () -> true)
                .getString("sessionId");
        // 未知完成只结束观察，不证明物理结束：不得释放物理槽。
        JSONObject unknown = report(a, null, false);
        dispatcher.apply(envelope("a", "ia", "actual_finished").put("sessionId", sa2).put("report", unknown), 1, () -> true);
        check(!poll("b", "ib", sb).has("job"), "Unknown completion released slot");
        JSONObject complete = report(a, new JSONObject().put("kind", "final").put("text", "ok"), true);
        dispatcher.apply(envelope("a", "ia", "actual_finished").put("sessionId", sa2).put("report", complete), 1, () -> true);
        dispatcher.apply(envelope("a", "ia", "actual_finished").put("sessionId", sa2).put("report", complete), 1, () -> true);
        JSONObject second = poll("b", "ib", sb).getJSONObject("job");
        String completedRun = a.getString("runId");
        for (int i = 0; i < 4; i++) {
            coordinator.step(completedRun);
        }
        check(store.read(T, S, completedRun).getJSONObject("snapshot").getString("state").equals("succeeded"), "Coordinator final missing");
        long seq = store.read(T, S, completedRun).getJSONObject("snapshot").getLong("eventSeq");
        coordinator.step(completedRun);
        check(seq == store.read(T, S, completedRun).getJSONObject("snapshot").getLong("eventSeq"), "Final repeated");
        fail("JOB_RESULT_CONFLICT", () -> jobs.report("a", report(a, new JSONObject().put("kind", "final").put("text", "changed"), true)));
        check(jobs.capacity().getJSONArray("backends").getJSONObject(0).getInt("occupied") == 1, "Wrong actual occupancy");
        fail("LEASE_BUSY", () -> store.acquire(T, S, completedRun, "other-owner", version(completedRun), 1000));
        for (String m : new String[]{"Baize", "Baize2"}) {
            profiles.publish(T, ModelProfiles.model(m));
        }
        jobs.report("b", report(second, new JSONObject().put("kind", "final").put("text", "second"), true));
        for (int i = 0; i < 4; i++) {
            coordinator.step(second.getString("runId"));
        }
        // need_input：问题持久化后 run 进入 waiting_input，且不产生忙等 checkpoint。
        String waiting = create("question");
        JSONObject question = dispatch(waiting, "b", "ib", sb);
        jobs.report("b", report(question, new JSONObject().put("kind", "need_input").put("question", "Which file?"), true));
        for (int i = 0; i < 4; i++) {
            coordinator.step(waiting);
        }
        check(store.read(T, S, waiting).getJSONObject("snapshot").getString("state").equals("waiting_input"), "need_input not durable");
        long waitSeq = store.read(T, S, waiting).getJSONObject("snapshot").getLong("eventSeq");
        coordinator.step(waiting);
        check(waitSeq == store.read(T, S, waiting).getJSONObject("snapshot").getLong("eventSeq"), "Waiting emitted busy checkpoints");
        // 逻辑超时：lifetime 变体 1 秒到期转 timed_out，但物理槽保留至迟到完成对账。
        JSONObject req = request("late").put("taskProfileId", "lifetime");
        String late = profiles.createRun(identity, key(), "lifetime", req).getString("runId");
        JSONObject running = dispatch(late, "b", "ib", sb);
        Thread.sleep(1100);
        coordinator.step(late);
        JSONObject terminal = store.read(T, S, late);
        check(terminal.getJSONObject("snapshot").getString("state").equals("timed_out"), "Lifetime not enforced");
        check(jobs.capacity().getJSONArray("backends").getJSONObject(0).getInt("occupied") == 1, "Logical timeout freed physical slot");
        jobs.report("b", report(running, new JSONObject().put("kind", "final").put("text", "late physical completion"), true));
        // 测试专用显式释放，避免等待十秒；下一步必须取得新 fence 后再对账。
        store.release(new JdbcRunStore.Lease(T, S, late, terminal.getString("owner"), terminal.getLong("fence")), version(late));
        for (int i = 0; i < 4; i++) {
            coordinator.step(late);
        }
        JSONObject settled = store.read(T, S, late);
        check(settled.getJSONObject("snapshot").getString("state").equals("timed_out")
                        && settled.getString("usageSettlement").equals("settled")
                        && settled.getJSONObject("snapshot").getString("executionSettlement").equals("confirmed"),
                "Late terminal reconciliation lost");
        pass("need_input releases capacity without busy-loop events; logical timeout retains slot; late physical completion and usage reconcile under a new fence without changing final state");
        coordinator.close();
        pass("N06/N07 JDBC: shared capacity, immutable dispatch, disconnect/unknown quarantine, late actual_finished, duplicate/conflicting evidence, unique final/owner, Baize/Baize2 v2 profiles");
    }

    /**
     * 推进 run 直至派发出作业，并校验选中的正是目标 run。
     *
     * @param run 目标 runId
     * @param worker 拉取作业的 worker 标识
     * @param instance 实例标识
     * @param session 会话标识
     * @return 派发到该 run 的作业 JSON
     * @throws Exception 推进、轮询或断言失败
     */
    static JSONObject dispatch(String run, String worker, String instance, String session) throws Exception {
        for (int i = 0; i < 3; i++) {
            coordinator.step(run);
        }
        JSONObject response = poll(worker, instance, session);
        check(response.has("job"), "No dispatch");
        JSONObject job = response.getJSONObject("job");
        check(job.getString("runId").equals(run), "Wrong run selected");
        return job;
    }

    /**
     * 测试入口：prepare 子命令仅生成夹具；否则在临时目录完成准备并执行全部校验。
     *
     * @param args 首参数为 prepare 时进入夹具模式，次参数为目标目录
     * @throws Exception 任何阶段失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("prepare")) {
            prepare(Files.createDirectory(Paths.get(args[1])), args.length > 2 ? args[2] : "");
            return;
        }
        prepare(Files.createTempDirectory("harness-execution-"), System.getProperty("execution.test.config", ""));
        checks();
        System.out.println("PASS: N06/N07 execution on " + config.dialect + " at " + root);
    }
}
