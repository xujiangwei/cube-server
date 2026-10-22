package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import static cube.service.harness.test.RuntimeTest.*;

/**
 * 可信 CPU Runner 与工具 broker 集成测试：真实 JDBC 账本、HTTP 边界、
 * MockConsole 上游与 RunnerDispatcher；默认使用一次性 sqlite 存储库。
 *
 * <p>覆盖持久派发/接受/结果重放与 ACK 摘要、证据与参数冲突、grant 绑定与吊销、
 * unknown/取消占位、提交不确定性（STORE_COMMIT_OUTCOME_UNKNOWN / STORE_UNAVAILABLE）、
 * Broker 重启隔离与 DB 重试不重发 HTTP，以及 Runner 租约的重复会话、排水与过期。
 */
public final class ToolTest {
    /** 工具作业账本；与 broker 共享持久作业、runner identity 与容量状态。 */
    static ToolJobStore tools;

    /** 工具 broker；授权、provider 选择与结果落库的入口。 */
    static ToolBroker broker;

    /** broker 与 MockConsole provider 的共享配置。 */
    static Properties p;

    /** MockConsole 工具名 test.status.query。 */
    static final String TOOL = "test.status.query";

    /**
     * @return 标准查询参数：targets=[node-a]，fields=[cpuPercent]
     */
    static JSONObject args() {
        return new JSONObject().put("targets", new JSONArray().put("node-a")).put("fields", new JSONArray().put("cpuPercent"));
    }

    /**
     * 准备工具测试夹具：在执行夹具基础上发布 trusted-python-v1 runtime、包仓库与
     * MockConsole SKILL/TaskProfile，并落盘 tool-fixture.json。
     *
     * @param dir 测试运行根目录
     * @param file 可选的外部一次性存储配置路径；为空时使用默认 sqlite 一次性库
     * @throws Exception 配置、迁移、上传或文件写入失败
     */
    static void prepare(Path dir, String file) throws Exception {
        ExecutionTest.prepare(dir, file);
        runtime = new JSONObject().put("schemaVersion", 2).put("id", "cpu").put("version", "1").put("kind", "runtime").put("mode", "trusted-python-v1")
                .put("capabilities", new JSONArray().put("resources").put("tool.execute"))
                .put("maxWorkspaceBytes", 4096).put("maxWorkspaceFiles", 8).put("certification", "offline-trusted-runner");
        profiles.publish(T, runtime);
        repo = new PackageRepository(catalog, new AssetFiles(root.resolve("repository"), 8000000, "runtime-test-repo"), Collections.emptyMap(), true, true);
        resources = new ResourceTools(catalog, repo);
        Map<String, byte[]> e = entries("Activate this release, then call test.status.query for the requested nodes/fields. Only MockConsole is supported.");
        e.put("input.schema.json", RuntimeSupport.bytes(ToolBroker.parameters()));
        e.put("output.schema.json", RuntimeSupport.bytes(ToolBroker.outputSchema()));
        e.put("main.py",
                bytes("from runner_sdk import context,query_status,result\nimport time\ntime.sleep(0.3)\nresult(query_status(**context()['arguments']))\n"));
        JSONObject m = manifest(e).put("schemaVersion", 6).put("name", "test/status").put("runtime", ref("cpu"));
        for (int i = 0; i < m.getJSONArray("files").length(); i++) {
            JSONObject f = m.getJSONArray("files").getJSONObject(i);
            if (f.getString("path").equals("main.py")) {
                f.put("role", "entrypoint");
            }
        }
        m.getJSONArray("tools").put(new JSONObject().put("name", TOOL).put("kind", "executable").put("entrypoint", "main.py")
                .put("inputSchema", "input.schema.json").put("outputSchema", "output.schema.json")
                .put("permission", "console.status.read").put("effect", "read-only")
                .put("brokerOperation", "mockconsole.query_status").put("runnerType", "tool"));
        release = upload(zip(m, e)).getString("digest");
        catalog.lifecycle(T, release, 1, "ready");
        JSONObject t = task("1", release, "scripted", 4096).put("id", "tools").put("schemaVersion", 2).put("runtime", ref("cpu")).put("toolSetVersion", "broker-tools")
                .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"))
                .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 16).put("maxRepairs", 1).put("maxLifetimeMillis", 60000));
        t.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
        profiles.publish(T, t);
        catalog.bind(T, "task", "tools", "tools@1", 0);
        Files.write(root.resolve("tool-fixture.json"), RuntimeSupport.bytes(new JSONObject().put("release", release).put("tenant", T).put("subject", S)));
    }

    /**
     * 单次工具调用测试用例的快照：run、租约、intent 与 broker 作业/派发表。
     *
     * <p>make/assign/accepted/report 等辅助方法在其上推进状态；wire 持有一次派发
     * 的 grant 与 jobHash，作业行随 accepted 等事件刷新。
     */
    static final class Case {
        /** 目标 runId。 */
        String run;

        /** 调用标识（proposalId / intent id）。 */
        String id;

        /** 该 run 的当前租约；fence/version 随 store 推进。 */
        JdbcRunStore.Lease lease;

        /** call intent 行（origin_fence/intent_hash 等）。 */
        JSONObject intent;

        /** broker 作业行（job_id/status 等）。 */
        JSONObject job;

        /** runner 拉取到的派发表（jobId/jobHash/grant）。 */
        JSONObject wire;
    }

    /**
     * 创建一个就绪用例：建 run、取租约、激活资源、写入 call intent 并入队 broker。
     *
     * @return 填充 run/lease/intent/job 的用例
     * @throws Exception 创建、激活或入队失败
     */
    static Case make() throws Exception {
        Case c = new Case();
        c.run = profiles.createRun(identity, key(), "tools", request("status").put("taskProfileId", "tools"))
                .getString("runId");
        c.lease = running(c.run, 60000);
        resources.activate(identity, c.run, release);
        c.id = "call";
        intent(c.lease, "call", c.id, new JSONObject().put("proposalId", c.id).put("tool", TOOL)
                .put("argumentsJson", JdbcRunStore.canonical(args())));
        c.intent = row(c.run, "call", c.id);
        c.job = broker.enqueue(c.lease, c.intent, resources.executable(identity, c.run, TOOL));
        return c;
    }

    /**
     * 以指定 worker/runtime 领取作业，并校验领到的正是该用例的作业。
     *
     * @param c 目标用例
     * @param worker runner worker 标识
     * @param runtime runner runtime 标识
     */
    static void assign(Case c, String worker, String runtime) {
        tools.identity(worker, runtime, 1);
        c.wire = tools.poll(worker, runtime, "instance", true, broker::wire);
        check(c.wire.getString("jobId").equals(c.job.getString("job_id")), "wrong claim");
        c.job = tools.job(c.wire.getString("jobId"));
    }

    /**
     * 构造工具结果证据。
     *
     * @param c 目标用例（取其 wire 的 jobId/jobHash）
     * @param ended 是否 actualFinished
     * @param effect effectStatus（completed/none/unknown）
     * @param value resultJson 文本
     * @return 证据 JSON
     */
    static JSONObject report(Case c, boolean ended, String effect, String value) {
        return new JSONObject().put("jobId", c.wire.getString("jobId")).put("jobHash", c.wire.getString("jobHash"))
                .put("actualFinished", ended).put("effectStatus", effect).put("resultJson", value);
    }

    /**
     * 完成 broker 授权与 durable accepted，并刷新作业行；重复调用验证幂等。
     *
     * @param c 目标用例
     */
    static void accepted(Case c) {
        broker.authorize(c.job, c.wire.getJSONObject("grant"));
        tools.accepted(c.wire.getString("jobId"), c.wire.getString("jobHash"));
        c.job = tools.job(c.wire.getString("jobId"));
    }

    /**
     * 轮询 broker 查询直至 finished，返回解析后的 dataJson。
     *
     * @param c 目标用例
     * @return 查询结果 JSON
     * @throws Exception 查询失败或 5 秒内未 settled
     */
    static JSONObject query(Case c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        JSONObject out;
        do {
            out = broker.query(c.job, c.wire.getJSONObject("grant"), args().getJSONArray("targets"),
                    args().getJSONArray("fields"));
            if (out.getString("status").equals("finished")) {
                return new JSONObject(out.getString("dataJson"));
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < end);
        throw new AssertionError("Broker did not settle");
    }

    /**
     * 工具 broker 主校验：持久派发/接受/结果重放、ACK 摘要、证据冲突、
     * 租约/grant/目标/字段绑定、脚本前过滤、unknown/取消占位、撤销与过期、
     * 提交不确定性与 Broker 重启隔离、DB 重试不重发 HTTP 及 Runner 租约。
     *
     * @throws Exception 存储、HTTP 或断言失败
     */
    static void checks() throws Exception {
        AtomicInteger upstream = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // MockConsole 上游：校验 Bearer token、计数调用并返回含 Cookie/额外字段的响应。
        http.createContext("/status", x -> {
            try {
                check(x.getRequestHeaders().getFirst("Authorization").equals("Bearer " + p.getProperty("broker.mock.token")),
                        "missing server credential");
                upstream.incrementAndGet();
                RuntimeSupport.read(x.getRequestBody(), 4096);
                byte[] b = bytes("{\"nodes\":[{\"target\":\"node-a\",\"queriedAt\":1234,\"cpuPercent\":7,\"memoryMiB\":1024,\"cookie\":\"never-forward\"},{\"target\":\"other-node\",\"queriedAt\":1,\"cpuPercent\":99}],\"token\":\"never-forward\"}");
                x.sendResponseHeaders(200, b.length);
                x.getResponseBody().write(b);
            } finally {
                x.close();
            }
        });
        http.start();
        p = new Properties();
        p.setProperty("runner.grant.key", key() + key());
        p.setProperty("broker.node.id", "test-node");
        p.setProperty("broker.mock.enabled", "true");
        p.setProperty("broker.mock.url", "http://127.0.0.1:" + http.getAddress().getPort() + "/status");
        p.setProperty("broker.mock.token", key());
        p.setProperty("broker.mock.targets", "node-a");
        tools = new ToolJobStore(store, config.mysql());
        broker = new ToolBroker(tools, identity, p);
        try {
            Case a = make();
            assign(a, "a", "runtime-a");
            // 丢失的派发被重放时 attempt 不变：wire 摘要必须与首次一致。
            check(JdbcRunStore.hash(a.wire)
                            .equals(JdbcRunStore.hash(tools.poll("a", "runtime-a", "replacement", true, broker::wire))),
                    "lost dispatch changed attempt");
            fail("RUNNER_IDENTITY_CONFLICT", () -> tools.identity("a", "replacement-journal", 1));
            fail("TOOL_EVIDENCE_MISMATCH", () -> tools.evidence("b", "runtime-a", a.wire.getString("jobId"), a.wire.getString("jobHash")));
            // 伪造签名的 grant 必须被拒绝。
            JSONObject forged = RuntimeSupport.copy(a.wire.getJSONObject("grant"))
                    .put("signature", new String(new char[64]).replace('\0', '0'));
            fail("GRANT_INVALID", () -> broker.authorize(a.job, forged));
            // 异域/异节点身份使用原 grant 必须被拒绝。
            ToolBroker foreign = new ToolBroker(tools, new ProfileRegistry.Identity("foreign", S, identity.permissions), copyNode("foreign-node"));
            try {
                fail("GRANT_REVOKED_OR_STALE", () -> foreign.authorize(a.job, a.wire.getJSONObject("grant")));
            } finally {
                foreign.close();
            }
            accepted(a);
            accepted(a);
            // 目标/字段与固定绑定不一致即冲突。
            fail("BROKER_ARGUMENT_CONFLICT", () -> broker.query(a.job, a.wire.getJSONObject("grant"),
                    new JSONArray().put("other-node"), args().getJSONArray("fields")));
            fail("BROKER_ARGUMENT_CONFLICT", () -> broker.query(a.job, a.wire.getJSONObject("grant"),
                    args().getJSONArray("targets"), new JSONArray().put("memoryMiB")));
            // 上游 Cookie/额外字段/额外节点必须在返回脚本前过滤；重试不得二次请求上游。
            JSONObject data = query(a);
            check(data.toString().indexOf("never-forward") == -1 && !data.toString().contains("memoryMiB")
                            && !data.toString().contains("other-node"),
                    "filter ran after child boundary");
            query(a);
            check(upstream.get() == 1, "transport retry repeated upstream");
            JSONObject good = report(a, true, "completed", new JSONObject().put("status", "success")
                    .put("dataJson", data.toString()).put("artifacts", new JSONArray()).toString());
            // ACK 摘要必须与证据一致；重复 report 幂等；不同结果冲突。
            JSONObject saved = broker.report(a.job, good);
            check(saved.getString("report_hash").equals(JdbcRunStore.hash(good)), "ACK digest differs");
            broker.report(a.job, good);
            fail("TOOL_RESULT_CONFLICT",
                    () -> broker.report(a.job, RuntimeSupport.copy(good).put("resultJson", ToolJobStore.error("CHANGED").toString())));
            pass("durable dispatch/accepted/result replay, ACK hash, conflict, runtime identity, tenant/grant/target/field binding and pre-script filtering; upstream once");
            // 非法原始输出：持久保存 TOOL_OUTPUT_INVALID 且仍可 ACK，不进入模型。
            Case bad = make();
            assign(bad, "a", "runtime-a");
            accepted(bad);
            JSONObject invalid = report(bad, true, "completed", "{\"status\":\"success\",\"status\":\"error\",\"artifacts\":[]}");
            JSONObject rejected = broker.report(bad.job, invalid);
            check(new JSONObject(rejected.getString("result_json")).getString("errorCode").equals("TOOL_OUTPUT_INVALID"), "invalid raw output escaped");
            check(rejected.getString("report_hash").equals(JdbcRunStore.hash(invalid)), "bad output not ACKable");
            // unknown 报告保留物理槽；取消同样不释放；迟到历史结果在新 fence 下对账后释放。
            Case unknown = make();
            assign(unknown, "a", "runtime-a");
            accepted(unknown);
            broker.report(unknown.job,
                    report(unknown, false, "unknown", ToolJobStore.error("RUNNER_UNKNOWN").put("status", "unknown").toString()));
            check(tools.occupied("a") == 1 && tools.poll("a", "runtime-a", "new-instance", true, broker::wire) == null,
                    "unknown freed capacity");
            tools.cancel(unknown.lease, unknown.job.getString("job_id"));
            check(tools.occupied("a") == 1, "cancel released physical slot");
            JSONObject late = report(unknown, true, "none", ToolJobStore.error("TOOL_CANCELLED").toString());
            broker.report(unknown.job, late);
            check(tools.occupied("a") == 0, "actual finish did not release slot");
            store.release(unknown.lease, version(unknown.run));
            JdbcRunStore.Lease next = store.acquire(T, S, unknown.run, "new-owner", version(unknown.run), 60000);
            store.confirmIntent(next, version(unknown.run), "call", unknown.id, 1, unknown.intent.getLong("origin_fence"),
                    unknown.intent.getString("intent_hash"),
                    new JSONObject(tools.job(unknown.job.getString("job_id")).getString("result_json")));
            pass("invalid output persisted and ACKed; unknown/cancel retains capacity; late historical result reconciles under a new fence");
            // 旧 fence：租约被新 owner 接管后，原 grant 查询在上游前即被拒绝。
            Case stale = make();
            assign(stale, "a", "runtime-a");
            accepted(stale);
            store.release(stale.lease, version(stale.run));
            store.acquire(T, S, stale.run, "new-owner", version(stale.run), 60000);
            fail("GRANT_REVOKED_OR_STALE", () -> broker.query(stale.job, stale.wire.getJSONObject("grant"),
                    args().getJSONArray("targets"), args().getJSONArray("fields")));
            broker.report(stale.job, report(stale, true, "none", ToolJobStore.error("TOOL_REVOKED").toString()));
            // 持久撤销：撤销后授权拒绝，且结果竞态不能恢复权威。
            Case revoked = make();
            assign(revoked, "a", "runtime-a");
            accepted(revoked);
            tools.revoke(T, S, revoked.run, "test-revocation");
            fail("GRANT_REVOKED_OR_STALE", () -> broker.authorize(tools.job(revoked.job.getString("job_id")), revoked.wire.getJSONObject("grant")));
            check(tools.occupied("a") == 1, "revocation released capacity");
            broker.report(revoked.job, report(revoked, true, "completed", good.getString("resultJson")));
            check(new JSONObject(tools.job(revoked.job.getString("job_id")).getString("result_json"))
                    .getString("errorCode").equals("TOOL_REVOKED"), "cancel/result race lost revocation");
            pass("old fence rejected before upstream; durable revoke and result race cannot restore authority");
            // 签名正确但已过期的 grant 必须被拒绝（测试专用存储期 fixtures）。
            Case ttl = make();
            assign(ttl, "a", "runtime-a");
            JSONObject grant = ttl.wire.getJSONObject("grant");
            JSONObject claims = new JSONObject(grant.getString("bodyJson")).put("expiresAt", 0);
            String body = JdbcRunStore.canonical(claims);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(bytes(p.getProperty("runner.grant.key")), "HmacSHA256"));
            StringBuilder signature = new StringBuilder();
            for (byte b : mac.doFinal(bytes(body))) {
                signature.append(String.format("%02x", b & 255));
            }
            grant.put("bodyJson", body).put("signature", signature.toString());
            try (java.sql.Connection db = config.connect();
                 java.sql.PreparedStatement sql = db.prepareStatement("UPDATE harness_tool_job SET wire_json=? WHERE job_id=?")) {
                sql.setString(1, JdbcRunStore.canonical(ttl.wire));
                sql.setString(2, ttl.job.getString("job_id"));
                sql.executeUpdate();
            }
            ttl.job = tools.job(ttl.job.getString("job_id"));
            fail("GRANT_REVOKED_OR_STALE", () -> broker.authorize(ttl.job, grant));
            broker.report(ttl.job, report(ttl, true, "none", ToolJobStore.error("TOOL_REVOKED").toString()));
            pass("correctly signed but expired grant rejected (test-only stored grant expiry fixture)");
            // 提交后丢失响应：可重放 durable accepted；提交前失败：扣留 ACK。
            Case commit = make();
            assign(commit, "a", "runtime-a");
            AtomicBoolean once = new AtomicBoolean(true);
            ToolJobStore faulty = new ToolJobStore(new JdbcRunStore(config, b -> {
                if (b.equals("afterCommit") && once.compareAndSet(true, false)) {
                    throw new RuntimeException("lost DB commit response");
                }
            }), config.mysql());
            fail("STORE_COMMIT_OUTCOME_UNKNOWN",
                    () -> faulty.accepted(commit.job.getString("job_id"), commit.wire.getString("jobHash")));
            check(tools.job(commit.job.getString("job_id")).getString("status").equals("accepted"), "commit evidence lost");
            accepted(commit);
            AtomicBoolean rollback = new AtomicBoolean(true);
            ToolJobStore failSave = new ToolJobStore(new JdbcRunStore(config, b -> {
                if (b.equals("beforeCommit") && rollback.compareAndSet(true, false)) {
                    throw new RuntimeException("fail save");
                }
            }), config.mysql());
            JSONObject evidence = report(commit, true, "none", ToolJobStore.error("TOOL_CANCELLED").toString());
            fail("STORE_UNAVAILABLE", () -> failSave.report(commit.job, evidence, ToolJobStore.error("TOOL_CANCELLED")));
            check(tools.occupied("a") == 1, "failed result commit ACKed");
            broker.report(commit.job, evidence);
            broker.report(commit.job, evidence);
            // Broker 发送窗口重启：保持 unknown，不重放 HTTP。
            Case crash = make();
            assign(crash, "a", "runtime-a");
            accepted(crash);
            tools.brokerExchange(crash.job.getString("job_id"), JdbcRunStore.hash(args()), "crashed-node", true);
            tools.brokerRestart("crashed-node");
            fail("BROKER_OUTCOME_UNKNOWN", () -> broker.query(crash.job, crash.wire.getJSONObject("grant"),
                    args().getJSONArray("targets"), args().getJSONArray("fields")));
            check(upstream.get() == 1, "unknown request replayed HTTP");
            pass("after-commit loss replays durable accepted; before-commit result failure withholds ACK; Broker send-window restart stays unknown without HTTP replay");
            // 结果 DB 失败只重试提交内存结果，不得二次请求上游。
            Case retry = make();
            assign(retry, "b", "runtime-b");
            accepted(retry);
            AtomicInteger failures = new AtomicInteger(2);
            ToolJobStore delayed = new ToolJobStore(new JdbcRunStore(config, b -> {
                if (b.equals("beforeCommit")
                        && Arrays.stream(Thread.currentThread().getStackTrace())
                                .anyMatch(frame -> frame.getMethodName().equals("brokerFinish"))
                        && failures.getAndDecrement() > 0) {
                    throw new RuntimeException("result DB unavailable");
                }
            }), config.mysql());
            ToolBroker retryBroker = new ToolBroker(delayed, identity, copyNode("retry-node"));
            try {
                int initial = upstream.get();
                JSONObject response = retryBroker.query(retry.job, retry.wire.getJSONObject("grant"),
                        args().getJSONArray("targets"), args().getJSONArray("fields"));
                check(response.getString("status").equals("pending"), "network executed in Cell request");
                long end = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < end
                        && !tools.brokerExchange(retry.job.getString("job_id"), JdbcRunStore.hash(args()), "retry-node", false)
                                .getString("status").equals("finished")) {
                    Thread.sleep(30);
                }
                check(tools.brokerExchange(retry.job.getString("job_id"), JdbcRunStore.hash(args()), "retry-node", false)
                                .getString("status").equals("finished"),
                        "DB-only retry did not settle");
                check(upstream.get() == initial + 1, "result persistence retry reissued HTTP");
            } finally {
                retryBroker.close();
            }
            pass("asynchronous Broker result DB failures reconcile from bounded memory without a second HTTP request");
            // durable unknown 隔离容量为 2 的 Runner，即使心跳声称健康也不能领新作业。
            Case quarantine = make();
            tools.identity("q", "runtime-q", 2);
            quarantine.wire = tools.poll("q", "runtime-q", "iq", true, broker::wire);
            quarantine.job = tools.job(quarantine.wire.getString("jobId"));
            accepted(quarantine);
            broker.report(quarantine.job,
                    report(quarantine, false, "unknown", ToolJobStore.error("RUNNER_UNKNOWN").put("status", "unknown").toString()));
            make();
            check(tools.occupied("q") == 1 && !tools.healthy("q")
                            && tools.poll("q", "runtime-q", "replacement", true, broker::wire) == null,
                    "healthy claim bypassed durable quarantine with spare capacity");
            pass("durable unknown quarantines a capacity-two Runner even when its heartbeat claims healthy");
            // Runner 租约：重复会话、排水与过期。
            for (String w : Arrays.asList("r1", "r2")) {
                p.setProperty("worker." + w + ".role", "runner");
                p.setProperty("worker." + w + ".capacity", "1");
            }
            RunnerDispatcher registry = new RunnerDispatcher(broker, p, 150);
            JSONObject e = new JSONObject().put("protocolVersion", 1).put("runnerType", "tool").put("requestId", key()).put("credential", key())
                    .put("workerId", "r1").put("instanceId", "i1").put("role", "runner")
                    .put("capabilities", new JSONArray().put("tool.execute")).put("action", "describe")
                    .put("descriptor", new JSONObject().put("runtimeId", "registry-runtime").put("capacity", 1)
                            .put("runtime", "trusted-python-v1"));
            String session = registry.apply(e, 100, () -> true).getString("sessionId");
            fail("DUPLICATE_WORKER", () -> registry.apply(RuntimeSupport.copy(e).put("instanceId", "other"), 101, () -> true));
            JSONObject heartbeat = RuntimeSupport.copy(e);
            heartbeat.remove("descriptor");
            heartbeat.put("action", "heartbeat").put("sessionId", session);
            fail("STALE_SESSION", () -> registry.apply(heartbeat, 101, () -> true));
            registry.apply(RuntimeSupport.copy(heartbeat).put("action", "drain"), 100, () -> true);
            check(!registry.snapshot().getJSONObject(0).getBoolean("ready"), "drain still ready");
            Thread.sleep(170);
            fail("STALE_SESSION", () -> registry.apply(heartbeat, 100, () -> true));
            pass("Runner lease binds instance/transport/nonce; duplicate session, drain and expiry enforced");
        } finally {
            broker.close();
            http.stop(0);
        }
    }

    /**
     * 复制当前 broker 配置并替换节点标识，模拟另一个 Java 节点。
     *
     * @param node 新的 broker.node.id
     * @return 使用新节点标识的配置副本
     */
    static Properties copyNode(String node) {
        Properties x = new Properties();
        x.putAll(p);
        x.setProperty("broker.node.id", node);
        return x;
    }

    /**
     * 测试入口：prepare 子命令仅生成夹具；否则完成准备并执行全部校验。
     *
     * @param values 首参数为 prepare 时进入夹具模式，次参数为目标目录
     * @throws Exception 任何阶段失败
     */
    public static void main(String[] values) throws Exception {
        if (values.length > 0 && values[0].equals("prepare")) {
            prepare(Files.createDirectory(Paths.get(values[1])), "");
            return;
        }
        prepare(Files.createTempDirectory("harness-tools-"), System.getProperty("tools.test.config", ""));
        checks();
        System.out.println("PASS: real JDBC/MockConsole on " + config.dialect);
    }
}
