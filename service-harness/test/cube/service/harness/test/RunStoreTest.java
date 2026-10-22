package cube.service.harness.test;

import cube.service.harness.store.*;
import cube.service.harness.protocol.Contract;
import cube.service.harness.HarnessConfig;
import cube.service.harness.HarnessService;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RunStore 账本集成测试：同一套场景在 SQLite 与真实专用 MySQL 目标上等价执行，不使用数据库 mock。
 *
 * <p>默认在本进程临时目录创建一次性 SQLite 库；{@code store.test.config} 可指向显式
 * disposable MySQL 测试库，此时强制要求 {@code store.test.disposable=true}，否则抛
 * DISPOSABLE_TEST_DATABASE_REQUIRED，防止误写既有部署。场景覆盖启动自检与身份校验、
 * 服务安装、并发幂等、原子失败、真实 JVM 崩溃、跨进程 CAS 与租约抢占、fence 与恢复、
 * 控制回执与事件分页、终态/用量结算以及 Outbox/Inbox 故障。跨进程场景由内部类
 * ProcessWorker 以子 JVM 执行；启动建表使用全新 disposable 数据库验证。
 */
public final class RunStoreTest {
    /** 各场景 PASS 名称的收集器，最终汇总进结果 JSON。 */
    private static final JSONArray checks = new JSONArray();

    /** 当前测试库配置（SQLite 或 disposable MySQL），驱动方言相关分支。 */
    private static StoreConfig config;

    /** 原始配置属性；bootstrap()/serviceIntegration() 在此基础上派生错误身份与开关夹具。 */
    private static Properties properties;

    /** 私有配置文件路径；子进程探针复用同一配置与同一一次性库。 */
    private static Path configFile;

    /** 主连接持有者；多数场景直接以 a 发起写入。 */
    private static JdbcRunStore a;

    /** 第二连接持有者；模拟另一 coordinator/owner，与 a 互相独立。 */
    private static JdbcRunStore b;

    /** 固定测试租户。 */
    private static final String T = "tenant-a";

    /** 固定测试主体。 */
    private static final String S = "subject-a";

    /**
     * 生成随机幂等键/标识串。
     *
     * @return 随机 UUID 字符串
     */
    private static String key() {
        return UUID.randomUUID().toString();
    }

    /**
     * 构造单字段载荷对象。
     *
     * @param name value 字段内容
     * @return 形如 {"value": name} 的 JSON
     */
    private static JSONObject object(String name) {
        return new JSONObject().put("value", name);
    }

    /**
     * 断言辅助：条件不成立时以给定消息抛出 AssertionError。
     *
     * @param ok 断言条件
     * @param message 失败消息
     */
    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    /**
     * 记录并通过标准输出播报一个场景 PASS。
     *
     * @param name 场景名称，同时进入 checks 汇总
     */
    private static void pass(String name) {
        checks.put(name);
        System.out.println("PASS: " + name);
    }

    /**
     * 期望以指定 StoreException 错误码失败；未失败或错误码不符即断言失败。
     *
     * @param code 期望的错误码
     * @param body 预期抛出 StoreException 的动作
     */
    private static void expect(String code, Runnable body) {
        try {
            body.run();
            throw new AssertionError("Expected " + code);
        } catch (StoreException e) {
            check(code.equals(e.code), code + " != " + e.code);
        }
    }

    /**
     * 读取指定 run 的快照版本号。
     *
     * @param run runId
     * @return 快照中的 version
     */
    private static long version(String run) {
        return a.read(T, S, run).getJSONObject("snapshot").getLong("version");
    }

    /**
     * 以固定 profile 与随机键创建一个 run。
     *
     * @return 新建 run 的 runId
     */
    private static String create() {
        return a.create(T, S, key(), "profile-v1", object("request")).getString("runId");
    }

    /**
     * 以指定 owner 与租期获取 run 租约。
     *
     * @param run runId
     * @param owner owner 标识
     * @param duration 租约时长（毫秒）
     * @return 租约对象
     */
    private static JdbcRunStore.Lease lease(String run, String owner, long duration) {
        return a.acquire(T, S, run, owner, version(run), duration);
    }

    /**
     * 获取租约并把 run 推进到 running，携带 checkpoint 载荷。
     *
     * @param run runId
     * @return 生效的租约
     */
    private static JdbcRunStore.Lease running(String run) {
        JdbcRunStore.Lease lease = lease(run, "coordinator-a", 60000);
        a.transition(lease, version(run), "running", object("checkpoint"));
        return lease;
    }

    /**
     * 统计表中行数，用于验证原子提交/回滚后的账本行数不变量。
     *
     * @param table 表名
     * @return 行数
     * @throws Exception 连接或查询失败
     */
    private static long count(String table) throws Exception {
        try (Connection c = config.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    /**
     * 构造一条用量证据 JSON（round/attempt/evidenceId 与输入输出用量）。
     *
     * @param round roundId
     * @param key evidenceId
     * @param revision expectedRevision
     * @param in 输入用量
     * @param out 输出用量
     * @return 证据 JSON
     */
    private static JSONObject evidence(String round, String key, long revision, long in, long out) {
        return new JSONObject().put("roundId", round).put("attempt", 1).put("evidenceId", key)
                .put("expectedRevision", revision).put("input", in).put("output", out);
    }

    /**
     * 记录并确认一个 round intent，返回原始 intent 行。
     *
     * @param lease 当前租约
     * @param round roundId
     * @return intent 行 JSON（含 origin_fence、intent_hash）
     */
    private static JSONObject addRound(JdbcRunStore.Lease lease, String round) {
        a.intent(lease, version(lease.runId), "round", round, 1, object("model-request"), 10, 20);
        JSONObject intent = a.intents(T, S, lease.runId, "round").getJSONObject(0);
        a.confirmIntent(lease, version(lease.runId), "round", round, 1, intent.getLong("origin_fence"),
                intent.getString("intent_hash"), object("model-result"));
        return intent;
    }

    /**
     * 启动自检与配置校验场景：重复建表安全、错误 store 身份与遗留目标拒绝、外来 schema 拒绝。
     *
     * @throws Exception 存储或断言失败
     */
    private static void bootstrap() throws Exception {
        StoreBootstrap.selfCheck(config);
        try (Connection c = config.connect()) {
            StoreBootstrap.verify(c, config);
        }
        // 错误身份必须拒绝打开库，防止误连其它部署库。
        Properties wrong = new Properties();
        wrong.putAll(properties);
        wrong.setProperty("store.identity", "wrong-identity-123456789");
        try {
            new JdbcRunStore(new StoreConfig(wrong));
            throw new AssertionError("Wrong DB identity accepted");
        } catch (StoreException e) {
            check(e.code.equals("STORE_IDENTITY_MISMATCH"), e.code);
        }
        // 非 harness_ 前缀的遗留业务库名直接拒绝。
        wrong.setProperty("store.database", "legacy_business");
        try {
            new StoreConfig(wrong);
            throw new AssertionError("Legacy target accepted");
        } catch (StoreException e) {
            check(e.code.equals("STORE_TARGET_REFUSED"), e.code);
        }
        if (!config.mysql()) {
            // 仅 SQLite：含外来表的库不得被初始化。
            Properties foreign = new Properties();
            foreign.putAll(properties);
            foreign.setProperty("store.database", "harness_foreign");
            foreign.setProperty("store.sqlite.file", configFile.getParent().resolve("harness_foreign.db").toString());
            StoreConfig target = new StoreConfig(foreign);
            try (Connection c = target.connect(); Statement statement = c.createStatement()) {
                statement.execute("CREATE TABLE legacy_table(id INT)");
            }
            try {
                StoreBootstrap.selfCheck(target);
                throw new AssertionError("Foreign schema initialized");
            } catch (StoreException e) {
                check(e.code.equals("STORE_SCHEMA_MISMATCH"), e.code);
            }
        }
        pass("bootstrap repeat, identity mismatch and foreign target refusal");
    }

    private static long connectionId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CONNECTION_ID()")) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Verify pooled MySQL transaction cleanup, exclusive borrowing and shutdown on a disposable database. */
    private static void pooledConnections() throws Exception {
        if (!config.mysql()) return;
        long firstId;
        Connection first = config.connect();
        try {
            firstId = connectionId(first);
            first.setAutoCommit(false);
            try (PreparedStatement s = first.prepareStatement("UPDATE harness_store_identity SET store_id=? WHERE singleton=1")) {
                s.setString(1, "uncommitted-pool-probe-identity");
                s.executeUpdate();
            }
        } finally {
            first.close();
            first.close();
        }
        check(first.isClosed(), "Returned logical connection is still open");
        try {
            first.createStatement();
            throw new AssertionError("Returned logical connection can still execute SQL");
        } catch (SQLException expected) { }
        try (Connection next = config.connect(); Connection concurrent = config.connect()) {
            check(connectionId(next) == firstId, "Connection was not reused from common pool");
            check(connectionId(concurrent) != firstId, "Double close returned the physical connection twice");
            check(next.getAutoCommit(), "Transaction state leaked to the next borrower");
            StoreBootstrap.verify(next, config);
        }
        // A broken physical connection must be discarded and replaced.
        long brokenId;
        try (Connection broken = config.connect()) {
            brokenId = connectionId(broken);
            broken.abort(Runnable::run);
        }
        try (Connection replacement = config.connect()) {
            check(connectionId(replacement) != brokenId, "Broken connection was reused");
        }
        // Closing a pool also handles connections that are still checked out.
        try (StoreConfig separate = new StoreConfig(properties)) {
            Connection active = separate.connect();
            separate.close();
            active.close();
            try {
                separate.connect();
                throw new AssertionError("Closed store reopened its pool");
            } catch (SQLException expected) { }
        }
        pass("common MySQL pool reuse, rollback on return, exclusive leases, broken connection and pool shutdown");
    }

    /**
     * 服务集成场景：HarnessService 安装校验通过的 store、错误身份 fail-closed、
     * 关闭 store 不改变 未启用任务执行时的启动行为。
     *
     * @throws Exception 服务启动/停止或断言失败
     */
    private static void serviceIntegration() throws Exception {
        Properties values = new Properties();
        values.putAll(properties);
        values.setProperty("mode", "development");
        values.setProperty("http.port", "0");
        values.setProperty("worker.credential", "private-worker-test-credential-12345");
        values.setProperty("store.enabled", "true");
        HarnessService service = new HarnessService(new HarnessConfig(values));
        try {
            service.start();
            check(service.isStarted() && service.runStore() != null, "Store not installed in service");
        } finally {
            service.stop();
        }
        // 身份错误的库必须使启动失败关闭，而不是带病运行。
        values.setProperty("store.identity", "incorrect-identity-123456");
        HarnessService refused = new HarnessService(new HarnessConfig(values));
        try {
            refused.start();
            check(!refused.isStarted() && refused.runStore() == null, "Wrong store did not fail closed");
        } finally {
            refused.stop();
        }
        // 显式关闭存储时服务仍按未启用任务执行时的行为启动，不安装账本。
        values.setProperty("store.enabled", "false");
        HarnessService disabled = new HarnessService(new HarnessConfig(values));
        try {
            disabled.start();
            check(disabled.isStarted() && disabled.runStore() == null, "Disabled ledger changed startup without task execution");
        } finally {
            disabled.stop();
        }
        pass("service installs verified store, refuses wrong identity, disabled store preserves startup without task execution");
    }

    /**
     * 幂等与隔离场景：六路并发同键创建只产生一个 run、载荷/profile 冲突拒绝、租户与主体
     * 互相隔离、跨租户查询拒绝、规范摘要稳定。
     *
     * @throws Exception 存储、并发或断言失败
     */
    private static void idempotency() throws Exception {
        String key = key();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        Set<String> runs = new HashSet<>();
        try {
            List<Future<JSONObject>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> new JdbcRunStore(config).create(T, S, key, "profile-v1", object("same"))));
            }
            for (Future<JSONObject> f : futures) {
                runs.add(f.get(20, TimeUnit.SECONDS).getString("runId"));
            }
        } finally {
            pool.shutdownNow();
        }
        check(runs.size() == 1, "Concurrent creates diverged");
        expect("IDEMPOTENCY_CONFLICT", () -> a.create(T, S, key, "profile-v1", object("changed")));
        expect("IDEMPOTENCY_CONFLICT", () -> a.create(T, S, key, "profile-v2", object("same")));
        String run = runs.iterator().next();
        check(!a.create("tenant-b", S, key, "profile-v1", object("same")).getString("runId").equals(run), "Tenant idempotency collision");
        check(!a.create(T, "subject-b", key, "profile-v1", object("same")).getString("runId").equals(run), "Subject idempotency collision");
        expect("RUN_NOT_FOUND", () -> a.read("tenant-b", S, run));
        expect("RUN_NOT_FOUND", () -> a.read(T, "subject-b", run));
        expect("RUN_NOT_FOUND", () -> a.events("tenant-b", S, run, 0, 10));
        expect("RUN_NOT_FOUND", () -> a.intents("tenant-b", S, run, "round"));
        expect("RUN_NOT_FOUND", () -> a.submitControl("tenant-b", S, run, key(), "cancel", version(run), object("cancel")));
        check(a.recover("other-tenant", "", 500).length() == 0, "Recovery crossed tenant");
        check(JdbcRunStore.hash(new JSONObject("{\"b\":2,\"a\":1.0}")).equals(JdbcRunStore.hash(new JSONObject("{\"a\":1,\"b\":2}"))), "Canonical digest unstable");
        pass("six concurrent idempotent creates, payload/profile conflicts, tenant/subject isolation");
    }

    /**
     * 原子失败场景：提交前崩溃完全回滚不残留任何行；提交后响应丢失时重试保持幂等。
     *
     * @throws Exception 存储或断言失败
     */
    private static void atomicFailures() throws Exception {
        long runCount = count("harness_run");
        long eventCount = count("harness_event");
        long outboxCount = count("harness_outbox");
        long requestCount = count("harness_request");
        // beforeCommit 故障：整个事务回滚，四类表行数不变。
        JdbcRunStore broken = new JdbcRunStore(config, stage -> {
            if (stage.equals("beforeCommit")) {
                throw new StoreException("INJECTED_CRASH");
            }
        });
        expect("INJECTED_CRASH", () -> broken.create(T, S, key(), "profile-v1", object("rollback")));
        check(count("harness_run") == runCount && count("harness_event") == eventCount
                && count("harness_outbox") == outboxCount && count("harness_request") == requestCount, "Partial transaction survived");
        // afterCommit 故障：提交已生效，同键重试只返回原回执，不重复落事件。
        String key = key();
        JdbcRunStore lost = new JdbcRunStore(config, stage -> {
            if (stage.equals("afterCommit")) {
                throw new StoreException("LOST_REPLY");
            }
        });
        expect("LOST_REPLY", () -> lost.create(T, S, key, "profile-v1", object("lost")));
        JSONObject retried = a.create(T, S, key, "profile-v1", object("lost"));
        check(a.events(T, S, retried.getString("runId"), 0, 10).length() == 1, "Lost reply duplicated commit");
        pass("rollback leaves no run/event/outbox/receipt, lost commit reply is idempotent");
    }

    /**
     * 组装并启动子 JVM 探针进程，复用当前 classpath 与私有配置。
     *
     * @param args 透传给 ProcessWorker 的参数
     * @return 已启动的子进程
     * @throws Exception 进程启动失败
     */
    private static Process child(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(ProcessWorker.class.getName());
        cmd.add(configFile.toString());
        Collections.addAll(cmd, args);
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    /**
     * 真实进程崩溃场景：提交前/后由子 JVM 直接 halt，验证持久化结果与重试幂等。
     *
     * @throws Exception 进程控制或断言失败
     */
    private static void processCrashes() throws Exception {
        long before = count("harness_run");
        for (String boundary : Arrays.asList("beforeCommit", "afterCommit")) {
            String key = key();
            Process process = child("crash", key, boundary);
            check(process.waitFor(25, TimeUnit.SECONDS), "Crash writer timeout");
            check(process.exitValue() == 73, "Crash hook did not halt process");
            // beforeCommit 时子进程整体回滚不落库；afterCommit 时提交生效，
            // 连同上一轮父进程同键重试落库的 run，计数为基线 +2。
            long expected = boundary.equals("beforeCommit") ? before : before + 2;
            check(count("harness_run") == expected, "Crash persistence mismatch at " + boundary);
            // 父进程按同键重试：崩溃不得造成重复 run 或重复事件。
            JSONObject saved = a.create(T, S, key, "profile-v1", object("process-crash"));
            check(a.events(T, S, saved.getString("runId"), 0, 10).length() == 1, "Crash retry duplicated event");
        }
        check(count("harness_run") == before + 2, "Crash retry duplicated run");
        pass("real JVM halt immediately before and after commit, restart/retry recovers");
    }

    private static void awaitReady(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            // common 连接池也会输出启动日志，READY 才是探针的同步信号。
            if (line.equals("READY")) return;
        }
        throw new AssertionError("Owner process exited before READY");
    }

    /**
     * 并发 CAS 与跨进程租约场景：八路独立连接竞争同一版本只允许一次推进；两个真实 JVM
     * owner 竞争同一 run 只允许一个获取租约。
     *
     * @throws Exception 并发、进程或断言失败
     */
    private static void concurrentCas() throws Exception {
        String run = create();
        JdbcRunStore.Lease lease = running(run);
        long v = version(run);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    try {
                        new JdbcRunStore(config).transition(lease, v, "running", object(key()));
                        winners.incrementAndGet();
                    } catch (StoreException e) {
                        check(e.code.equals("VERSION_CONFLICT"), e.code);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : all) {
                f.get(25, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        check(winners.get() == 1 && version(run) == v + 1, "CAS had multiple winners");
        pass("eight independent connections race one version: exactly one CAS advances");

        // 双 JVM 探针：两个 owner 进程打印 READY 后同时 acquire，退出码 0/74 区分胜败。
        String fresh = create();
        long initial = version(fresh);
        Process one = child("acquire", fresh, Long.toString(initial), "owner-process-a");
        Process two = child("acquire", fresh, Long.toString(initial), "owner-process-b");
        try {
            awaitReady(one);
            awaitReady(two);
            one.getOutputStream().write(10);
            one.getOutputStream().flush();
            two.getOutputStream().write(10);
            two.getOutputStream().flush();
            check(one.waitFor(25, TimeUnit.SECONDS) && two.waitFor(25, TimeUnit.SECONDS), "Owner processes timeout");
            check((one.exitValue() == 0 && two.exitValue() == 74) || (two.exitValue() == 0 && one.exitValue() == 74), "Two owner processes won");
        } finally {
            if (one.isAlive()) {
                one.destroyForcibly();
            }
            if (two.isAlive()) {
                two.destroyForcibly();
            }
        }
        pass("two real JVM owner processes contend: exactly one lease acquired");
    }

    /**
     * fence 与恢复场景：租约过期后 recover 可被新 owner 接管且 fence 递增；旧 owner 的一切
     * 写入被拒绝，历史证据可对账但不盲目重放。
     *
     * @throws Exception 存储或断言失败
     */
    private static void fencingAndRecovery() throws Exception {
        String run = create();
        JdbcRunStore.Lease old = lease(run, "old-owner", 200);
        a.transition(old, version(run), "running", object("checkpoint"));
        a.intent(old, version(run), "call", "call-1", 1, object("fixed-arguments"), 0, 0);
        JSONObject intent = a.intents(T, S, run, "call").getJSONObject(0);
        // 租约未过期时新 owner 无法抢占。
        expect("LEASE_BUSY", () -> b.acquire(T, S, run, "new-owner", version(run), 1000));
        Thread.sleep(350);
        check(a.recover(T, "", 500).toString().contains(run), "Expired run absent from recovery");
        JdbcRunStore.Lease current = b.acquire(T, S, run, "new-owner", version(run), 60000);
        check(current.fence > old.fence, "Fence did not advance");
        // 旧 owner 的推进/续约/确认/终态全部按过期 owner 拒绝。
        expect("STALE_OWNER", () -> a.transition(old, version(run), "running", object("late")));
        expect("STALE_OWNER", () -> a.renew(old, 1000));
        expect("STALE_OWNER", () -> a.confirmIntent(old, version(run), "call", "call-1", 1, old.fence, intent.getString("intent_hash"), object("late")));
        expect("STALE_OWNER", () -> a.finish(old, version(run), "failed", "unknown", object("late"), new JSONArray()));
        // 未确认 attempt 存在时新 owner 不得盲目重试，必须先对账。
        expect("INTENT_RECONCILIATION_REQUIRED", () -> a.intent(current, version(run), "call", "call-1", 2, object("fixed-arguments"), 0, 0));
        // 历史证据必须用原 fence 对账，伪造 fence 不匹配即拒绝。
        expect("INTENT_EVIDENCE_MISMATCH", () -> a.confirmIntent(current, version(run), "call", "call-1", 1, old.fence + 1, intent.getString("intent_hash"), object("historical")));
        // 补偿意图按幂等键重放返回等价回执。
        String compensationKey = key();
        long compensationVersion = version(run);
        JSONObject compensation = b.requestCompensation(current, compensationVersion, compensationKey, "call", "call-1", 1, intent.getString("intent_hash"), object("query-status-first"));
        check(JdbcRunStore.canonical(compensation).equals(JdbcRunStore.canonical(a.requestCompensation(current, compensationVersion, compensationKey,
                "call", "call-1", 1, intent.getString("intent_hash"), object("query-status-first")))), "Compensation duplicated");
        // 用原 fence 对账历史证据成功；释放后旧连接 renewal 仍被拒绝。
        b.confirmIntent(current, version(run), "call", "call-1", 1, old.fence, intent.getString("intent_hash"), object("historical"));
        b.renew(current, 60000);
        b.release(current, version(run));
        expect("STALE_OWNER", () -> a.renew(current, 1000));
        pass("lease expiry/recovery, stale-owner rejection, no blind replay, historical evidence reconciliation");
    }

    /**
     * 控制回执与事件分页场景：控制意图同键重放等价、异参/异版本冲突；事件按游标分页且
     * 快照符合契约 schema，游标非法与事件缺口明确拒绝。
     *
     * @throws Exception 存储或断言失败
     */
    private static void controlsAndEvents() throws Exception {
        String run = create();
        String key = key();
        long v = version(run);
        JSONObject response = a.submitControl(T, S, run, key, "cancel", v, object("cancel"));
        JSONObject replay = b.submitControl(T, S, run, key, "cancel", v, object("cancel"));
        check(JdbcRunStore.canonical(response).equals(JdbcRunStore.canonical(replay)), "Control receipt changed");
        // 同 key 异载荷按幂等冲突；新 key 但版本过期按版本冲突。
        expect("IDEMPOTENCY_CONFLICT", () -> a.submitControl(T, S, run, key, "cancel", v, object("changed")));
        expect("VERSION_CONFLICT", () -> a.submitControl(T, S, run, key(), "cancel", v, object("cancel")));
        // 分页大小与游标语义：limit 生效、游标连续、事件与快照符合契约 schema。
        JSONArray page = a.events(T, S, run, 0, 1);
        check(page.length() == 1, "Event limit ignored");
        Contract.validate(page.getJSONObject(0), new JSONObject(new String(Contract.resource("schemas/event.json"), StandardCharsets.UTF_8)));
        long seq = page.getJSONObject(0).getLong("eventSeq");
        check(b.events(T, S, run, seq, 500).getJSONObject(0).getLong("eventSeq") == seq + 1, "Event cursor gap");
        Contract.validate(a.read(T, S, run).getJSONObject("snapshot"), new JSONObject(new String(Contract.resource("schemas/run-snapshot.json"), StandardCharsets.UTF_8)));
        expect("INVALID_EVENT_CURSOR", () -> a.events(T, S, run, 999999, 5));
        expect("INVALID_PAGE_SIZE", () -> a.events(T, S, run, 0, 501));
        // 直接删掉某 run 的 outbox/event 行制造缺口：读取必须报告 EVENT_GAP。
        String gap = create();
        try (Connection c = config.connect(); PreparedStatement out = c.prepareStatement("DELETE FROM harness_outbox WHERE run_id=?");
             PreparedStatement event = c.prepareStatement("DELETE FROM harness_event WHERE run_id=?")) {
            out.setString(1, gap);
            out.executeUpdate();
            event.setString(1, gap);
            event.executeUpdate();
        }
        expect("EVENT_GAP", () -> a.events(T, S, gap, 0, 10));
        pass("durable control receipts, expectedVersion conflicts, schema-valid snapshots and cursor pages");
    }

    /**
     * 终态与用量场景：final/result/usage/event 原子回滚、重复 final 幂等、估算与实际分列；
     * provisional 终态可由新 owner 对账迟到用量且不重开结果。
     *
     * @throws Exception 存储或断言失败
     */
    private static void finalAndUsage() throws Exception {
        String run = create();
        JdbcRunStore.Lease lease = running(run);
        addRound(lease, "round-1");
        long v = version(run);
        long finals = count("harness_result");
        long adjustments = count("harness_usage_adjustment");
        JSONObject actual = evidence("round-1", "provider-receipt", 0, 12, 19);
        // 提交前崩溃：result/usage/version 全部回滚。
        JdbcRunStore broken = new JdbcRunStore(config, stage -> {
            if (stage.equals("beforeCommit")) {
                throw new StoreException("FINAL_CRASH");
            }
        });
        expect("FINAL_CRASH", () -> broken.finish(lease, v, "succeeded", "confirmed", object("final"), new JSONArray().put(actual)));
        check(count("harness_result") == finals && count("harness_usage_adjustment") == adjustments && version(run) == v, "Final rollback incomplete");
        // 正式提交后同参重放等价；异参 final 冲突。
        JSONObject committed = a.finish(lease, v, "succeeded", "confirmed", object("final"), new JSONArray().put(actual));
        check(JdbcRunStore.canonical(committed).equals(JdbcRunStore.canonical(b.finish(lease, v, "succeeded", "confirmed", object("final"), new JSONArray().put(actual)))), "Final receipt changed");
        check(count("harness_result") == finals + 1 && count("harness_usage_adjustment") == adjustments + 1, "Duplicate final or usage row");
        expect("FINAL_CONFLICT", () -> a.finish(lease, v, "succeeded", "confirmed", object("different"), new JSONArray().put(actual)));
        JSONObject read = a.read(T, S, run);
        check(read.getString("usageSettlement").equals("settled"), "Actual usage not settled");
        check(read.getJSONObject("usage").getLong("estimatedInput") == 10 && read.getJSONObject("usage").getLong("actualInput") == 12, "Estimate/actual merged");
        expect("INVALID_STATE_TRANSITION", () -> a.transition(lease, version(run), "running", object("reopen")));
        pass("final/result/usage/event atomic rollback, duplicate final idempotent, estimates separate");

        // 迟到用量：终态时无实际证据记 provisional，recover 可见，新 owner 可对账。
        String late = create();
        JdbcRunStore.Lease lateLease = running(late);
        addRound(lateLease, "late-round");
        a.finish(lateLease, version(late), "succeeded", "confirmed", object("immutable"), new JSONArray());
        check(a.read(T, S, late).getString("usageSettlement").equals("provisional"), "Missing usage marked actual");
        a.release(lateLease, version(late));
        check(a.recover(T, "", 500).toString().contains(late), "Late usage lost from recovery scan");
        JdbcRunStore.Lease current = b.acquire(T, S, late, "settlement-owner", version(late), 60000);
        JSONObject first = evidence("late-round", "evidence-1", 0, 15, 25);
        a.settleUsage(current, version(late), first);
        long once = version(late);
        // 旧版本重放不推进版本；同 revision 异值/异 evidence 均冲突。
        a.settleUsage(current, once - 1, first);
        check(version(late) == once, "Duplicate usage advanced version");
        expect("USAGE_EVIDENCE_CONFLICT", () -> a.settleUsage(current, version(late), evidence("late-round", "evidence-1", 0, 99, 99)));
        expect("USAGE_VERSION_CONFLICT", () -> a.settleUsage(current, version(late), evidence("late-round", "evidence-stale", 0, 99, 99)));
        expect("INVALID_USAGE", () -> a.settleUsage(current, version(late), evidence("late-round", "fraction", 1, 99, 99).put("input", 1.5)));
        // revision 递增的修正只追加调整行，不重复累加，也不重开终态结果。
        a.settleUsage(current, version(late), evidence("late-round", "correction-2", 1, 16, 26));
        JSONObject readLate = a.read(T, S, late);
        check(readLate.getJSONObject("usage").getLong("actualInput") == 16 && readLate.getJSONObject("usage").getLong("actualOutput") == 26, "Correction accumulated twice");
        check(readLate.getJSONObject("snapshot").getString("state").equals("succeeded") && readLate.getJSONObject("result").getJSONObject("result").getString("value").equals("immutable"), "Late settlement reopened final");
        pass("provisional terminal usage recovered by new owner, deduped actuals and revisioned corrections");
    }

    /**
     * Outbox/Inbox 场景：通知丢失可靠重发现、发送失败重试、ACK 丢失后 claim fence 转移、
     * 重复消费拒绝与业务效果同事务回滚。
     *
     * @throws Exception 存储、线程或断言失败
     */
    private static void outbox() throws Exception {
        // 此前测试故意丢弃了全部通知；轮询仍能重新发现所有发送。
        AtomicInteger effects = new AtomicInteger();
        int sent = a.dispatch("dispatcher-drain", 500, message -> {
            a.consume(message.getString("tenant"), "receiver-main", message.getString("message_id"), c -> effects.incrementAndGet());
        });
        check(sent > 0 && effects.get() == sent, "Outbox polling did not recover lost notifications");
        String run = create();
        JSONArray messages = a.claimOutbox("dispatcher-a", 10, 200);
        check(messages.length() == 1, "Unexpected outbox pending count");
        JSONObject m = messages.getJSONObject(0);
        String msg = m.getString("message_id");
        long fence = m.getLong("claim_fence");
        // 跨租户消费按消息不存在拒绝，不产生任何效果。
        expect("MESSAGE_NOT_FOUND", () -> a.consume("tenant-b", "receiver-main", msg, c -> { throw new AssertionError("Cross-tenant effect"); }));
        // 首次消费执行同事务业务效果并返回 true。
        check(a.consume(T, "receiver-main", msg, c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE harness_run SET updated_at=updated_at+1 WHERE run_id=?")) {
                s.setString(1, run);
                check(s.executeUpdate() == 1, "Inbox effect missing");
            }
        }), "First consume rejected");
        // 模拟发送成功但 ACK 丢失：claim 过期后所有权转移并重投，身份与 fence 变化可见。
        Thread.sleep(350);
        JSONObject again = b.claimOutbox("dispatcher-b", 10, 60000).getJSONObject(0);
        check(again.getString("message_id").equals(msg) && again.getLong("claim_fence") > fence, "Message identity changed on retry");
        expect("STALE_OUTBOX_CLAIM", () -> a.ackOutbox("dispatcher-a", msg, fence));
        // 已消费消息再次执行业务效果被拒绝（Inbox 去重）。
        check(!b.consume(T, "receiver-main", msg, c -> { throw new AssertionError("Duplicate business effect"); }), "Duplicate consumed");
        // 重试后重新 claim、ACK，之后不再重投。
        b.retryOutbox("dispatcher-b", msg, again.getLong("claim_fence"), 0);
        JSONObject retried = a.claimOutbox("dispatcher-c", 10, 60000).getJSONObject(0);
        a.ackOutbox("dispatcher-c", msg, retried.getLong("claim_fence"));
        check(a.claimOutbox("dispatcher-d", 10, 1000).length() == 0, "Delivered message redelivered");

        // 消费内抛错：业务效果随事务回滚，之后可重新消费。
        String failRun = create();
        JSONObject failMessage = a.claimOutbox("dispatcher-fail", 10, 60000).getJSONObject(0);
        String failId = failMessage.getString("message_id");
        expect("EFFECT_ROLLBACK", () -> a.consume(T, "receiver-rollback", failId, c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE harness_run SET state='failed' WHERE run_id=?")) {
                s.setString(1, failRun);
                s.executeUpdate();
            }
            throw new StoreException("EFFECT_ROLLBACK");
        }));
        check(a.read(T, S, failRun).getJSONObject("snapshot").getString("state").equals("queued"), "Receiver effect not rolled back");
        check(a.consume(T, "receiver-rollback", failId, c -> {}), "Rolled-back inbox survived");
        a.ackOutbox("dispatcher-fail", failId, failMessage.getLong("claim_fence"));
        create();
        // 发送持续失败不得 ACK：消息保留，退避后由另一 dispatcher 重试成功。
        check(a.dispatch("dispatcher-network-fail", 10, message -> { throw new IOException("Injected network failure"); }) == 0, "Failed send ACKed");
        Thread.sleep(1100);
        check(b.dispatch("dispatcher-network-retry", 10, message -> b.consume(message.getString("tenant"),
                "receiver-main", message.getString("message_id"), c -> {})) == 1, "Failed network send was lost");
        pass("lost notifications, failed sends, ACK loss, claim fencing, duplicate Inbox and transactional effects");
    }

    /**
     * 测试入口：解析存储目标（默认一次性 SQLite，或显式 disposable 配置），依次执行全部
     * 场景，并输出汇总 JSON（可经 store.test.output 落盘）。
     *
     * @param args 未使用的命令行参数
     * @throws Exception 环境准备或场景执行失败
     */
    public static void main(String[] args) throws Exception {
        String configured = System.getProperty("store.test.config", "");
        properties = new Properties();
        if (configured.isEmpty() || configured.startsWith("${")) {
            Path dir = Files.createTempDirectory("cube-harness-store-");
            properties.setProperty("store.dialect", "sqlite");
            properties.setProperty("store.database", "harness_test");
            properties.setProperty("store.identity", "test-identity-" + key());
            properties.setProperty("store.sqlite.root", dir.toString());
            properties.setProperty("store.sqlite.file", dir.resolve("harness_test.db").toString());
            configFile = dir.resolve("private.properties");
            try (OutputStream out = Files.newOutputStream(configFile)) {
                properties.store(out, "Disposable Harness test only");
            }
        } else {
            // 显式外部库必须标记为一次性测试库，防止误写既有部署数据。
            configFile = Paths.get(configured).toAbsolutePath();
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            }
            if (!"true".equals(properties.getProperty("store.test.disposable"))) {
                throw new StoreException("DISPOSABLE_TEST_DATABASE_REQUIRED");
            }
        }
        config = new StoreConfig(properties);
        StoreBootstrap.selfCheck(config);
        a = new JdbcRunStore(config);
        b = new JdbcRunStore(config);
        bootstrap();
        pooledConnections();
        serviceIntegration();
        idempotency();
        atomicFailures();
        processCrashes();
        concurrentCas();
        fencingAndRecovery();
        controlsAndEvents();
        finalAndUsage();
        outbox();
        JSONObject summary = new JSONObject().put("dialect", config.dialect).put("checks", checks).put("result", "PASS")
                .put("databaseProduct", databaseProduct()).put("schemaHash", StoreBootstrap.digest(config));
        String output = System.getProperty("store.test.output", "");
        if (!output.isEmpty() && !output.startsWith("${")) {
            Files.write(Paths.get(output), summary.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("PASS: " + checks.length() + " RunStore scenarios on " + config.dialect);
    }

    /**
     * 读取数据库产品名与版本，进入汇总 JSON。
     *
     * @return "产品名 版本" 字符串
     * @throws Exception 连接或元数据读取失败
     */
    private static String databaseProduct() throws Exception {
        try (Connection c = config.connect()) {
            return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion();
        }
    }

    /**
     * 子 JVM 探针：以独立进程执行真实崩溃与租约竞争，父进程通过退出码判定结果。
     *
     * <p>crash 模式在指定提交边界直接 halt(73)，使父进程观察持久化结果；acquire 模式
     * 打印 READY 后等待父进程放行，再尝试获取租约，VERSION_CONFLICT/LEASE_BUSY 表示
     * 竞争失败并以 74 退出。
     */
    public static final class ProcessWorker {
        /**
         * 探针入口：按 args[1] 模式执行崩溃注入或租约竞争。
         *
         * @param args [0]=配置文件路径，[1]=模式（crash/acquire），其余为模式参数
         * @throws Exception 存储或进程控制失败
         */
        public static void main(String[] args) throws Exception {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
                p.load(in);
            }
            StoreConfig config = new StoreConfig(p);
            if (args[1].equals("crash")) {
                JdbcRunStore store = new JdbcRunStore(config, boundary -> {
                    if (boundary.equals(args[3])) {
                        Runtime.getRuntime().halt(73);
                    }
                });
                store.create(T, S, args[2], "profile-v1", object("process-crash"));
                throw new AssertionError("Crash hook missed");
            }
            JdbcRunStore store = new JdbcRunStore(config);
            System.out.println("READY");
            System.out.flush();
            System.in.read();
            try {
                store.acquire(T, S, args[2], args[4], Long.parseLong(args[3]), 60000);
            } catch (StoreException e) {
                if (!(e.code.equals("VERSION_CONFLICT") || e.code.equals("LEASE_BUSY"))) {
                    throw e;
                }
                System.exit(74);
            }
        }
    }
}
