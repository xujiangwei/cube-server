package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.HarnessConfig;
import cube.service.harness.execution.ExecutionRuntime;
import cube.service.harness.files.FileService;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.json.*;

/**
 * N16 韧性演练：一次性专用存储上的接单 drain、文件回收/积压与发布兼容性 drill，绝不使用既有部署库。
 *
 * <p>覆盖：关闭接单后的 HARNESS_DRAINING、重放/冲突与严格 drained 判定、独立管理凭据校验；
 * 96 次上传与多轮扫描的字节回收、下载 pin、unknown artifact、配额拒绝、关闭重启、两个真实
 * JVM 竞争同一文件根以及无关 Runner 根保护；active task release 切换与兼容回退不破坏在途
 * 不可变 pin。子进程模式 "oom"（受限堆 OOM）与 "sweep"（独立清扫进程）由 main 识别。
 */
public final class ResilienceTest {
    /**
     * 构造隔离演练配置：一次性 store.local.properties 叠加仓库路径、命名空间与关闭接单。
     *
     * @return 演练用配置
     * @throws Exception 配置文件读取失败
     */
    static Properties settings() throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) {
            p.load(in);
        }
        p.setProperty("execution.repository", root.resolve("repository").toString());
        p.setProperty("execution.repository.namespace", "runtime-test-repo");
        p.setProperty("execution.accepting", "false");
        return p;
    }

    /**
     * 接单与 drain 场景：关闭后新创建返回 HARNESS_DRAINING，同键重放与冲突语义保持；
     * 排队中、unknown 执行不计入 drained，执行结算确认后才报告 drained；管理凭据不得与
     * worker 凭据相同。
     *
     * @throws Exception 运行时、配置或断言失败
     */
    static void drain() throws Exception {
        Properties p = settings();
        try (ExecutionRuntime runtime = new ExecutionRuntime(store, p, 3000)) {
            fail("HARNESS_DRAINING", () -> runtime.create(identity, key(), request("closed")));
            runtime.admission(true);
            String k = key();
            JSONObject receipt = runtime.create(identity, k, request("in flight"));
            // 关闭后同键同参仍可幂等重放，异参按冲突拒绝。
            runtime.admission(false);
            check(
                    runtime.create(identity, k, request("in flight")).toString().equals(receipt.toString()),
                    "closed replay lost");
            fail("IDEMPOTENCY_CONFLICT", () -> runtime.create(identity, k, request("different")));
            // 队列中（queued）run 未物理结束，不得报告 drained。
            check(!runtime.admission(null).getBoolean("drained"), "queued run ignored");
            String r = receipt.getString("runId");
            JdbcRunStore.Lease l = running(r, 60000);
            store.finish(l, version(r), "failed", "unknown", new JSONObject(), new JSONArray());
            // unknown 执行保持容量隔离，同样不计入 drained。
            check(
                    !runtime.admission(null).getBoolean("drained"),
                    "unknown effect reported drained");
            ControlFileTest.sql(
                    "UPDATE harness_run SET execution_settlement='confirmed' WHERE run_id=?", r);
            check(runtime.admission(null).getBoolean("drained"), "confirmed drain stuck");
        }
        // 管理凭据与 worker 凭据共享必须被配置加载拒绝。
        Properties c = new Properties();
        c.setProperty("mode", "development");
        c.setProperty("worker.credential", "test-worker-credential-123456");
        for (String field : Arrays.asList("worker.credential")) {
            c.setProperty("admission.admin.credential", c.getProperty(field));
            try {
                new HarnessConfig(c);
                throw new AssertionError("shared administrator accepted");
            } catch (IOException expected) {
            }
        }
        pass("closed admission, replay/conflict, queued and unknown drain, independent admin");
    }

    /**
     * 递归统计目录下全部常规文件的实际字节数，用于验证回收效果。
     *
     * @param path 目录路径
     * @return 字节总数
     * @throws Exception 目录遍历或大小读取失败
     */
    static long bytesOnDisk(Path path) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(
                            f -> {
                                try {
                                    return Files.size(f);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                    .sum();
        }
    }

    /**
     * 独立清扫进程：持锁打开文件服务并执行多轮 scan，验证 FILE_BUSY 退避与收敛。
     *
     * <p>由 churn() 以真实子 JVM 启动（与主进程竞争同一文件根），也可由 main 的 "sweep"
     * 模式直接执行；启动与扫描期间的 FILE_BUSY 均按预期退避重试。
     *
     * @param p 清扫进程私有配置
     * @throws Exception 文件服务打开、扫描或断言失败
     */
    static void sweep(Properties p) throws Exception {
        StoreConfig sc = new StoreConfig(p);
        FileService opened = null;
        long deadline = System.nanoTime() + 10000000000L;
        // 另一 JVM 可能正持根锁：FILE_BUSY 期间退避重试，直到超时。
        while (opened == null && System.nanoTime() < deadline) {
            try {
                opened = new FileService(new JdbcRunStore(sc), p, identity);
            } catch (StoreException e) {
                if (!e.code.equals("FILE_BUSY")) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
        check(opened != null, "file root startup remained busy");
        // 有界批次反复扫描，消化积压；FILE_BUSY 同样退避。
        try (FileService f = opened) {
            for (int i = 0; i < 50; i++) {
                try {
                    f.scan();
                } catch (StoreException e) {
                    if (!e.code.equals("FILE_BUSY")) {
                        throw e;
                    }
                }
                Thread.sleep(10);
            }
        }
    }

    /**
     * 存储负载场景：4 轮 × 24 次大对象上传与扫描回收，穿插双 JVM 清扫、下载 pin、
     * unknown artifact 保持、配额拒绝与关闭重启校验。
     *
     * @param output 证据输出目录（清扫日志落盘位置）
     * @return 汇总 JSON（各轮字节/耗时、上传/持有/残留字节与未跑项标记）
     * @throws Exception 文件、存储、进程或断言失败
     */
    static JSONObject churn(Path output) throws Exception {
        Properties p = settings();
        Path disk = Files.createDirectory(root.resolve("n16-files"));
        p.setProperty("files.root", disk.toString());
        p.setProperty("files.root.id", "n16-files");
        p.setProperty("files.download.key", "n16-test-private-download-key-123456789");
        p.setProperty("files.max.bytes", "262144");
        p.setProperty("files.max.file.bytes", "8192");
        p.setProperty("cleanup.scan.batch.size", "7");
        p.setProperty("cleanup.temp.grace.seconds", "0");
        Path privateConfig = root.resolve("sweeper.local.properties");
        Files.write(privateConfig, bytes(ExecutionTest.properties(p)));
        // 无关 Runner 根哨兵：任何一轮都不得被删除。
        Path unrelated = Files.createDirectory(root.resolve("unrelated-runner"));
        Files.write(unrelated.resolve("keep.txt"), bytes("unrelated runner must survive cleanup"));
        JSONArray cycles = new JSONArray();
        long uploaded = 0;
        long maxSweep = 0;
        String held = null;
        long heldSize = 0;
        try (FileService f = new FileService(store, p, identity)) {
            ControlFileTest.files = f;
            for (int cycle = 0; cycle < 4; cycle++) {
                for (int i = 0; i < 24; i++) {
                    JSONObject row =
                            ControlFileTest.uploadText(
                                    String.join("", Collections.nCopies(4096, "x")));
                    uploaded += row.getLong("size");
                    // 首个对象打上长期下载 pin，整轮演练中不得被回收。
                    if (held == null) {
                        held = row.getString("inputId");
                        heldSize = row.getLong("size");
                        f.ledger.pin(T, S, held, "n16-long-download", null, "download");
                    }
                }
                long before = bytesOnDisk(disk);
                // 加速老化夹具：只改 expires_at，产品 TTL 策略不变；不是原生 30 天 retention 浸泡。
                ControlFileTest.sql(
                        "UPDATE harness_file SET expires_at=0 WHERE root_id='n16-files'");
                long started = System.nanoTime();
                if (cycle == 2) {
                    // 第三轮改用两个真实 JVM 清扫进程竞争同一文件根。
                    String cp = System.getProperty("java.class.path");
                    String executable =
                            Paths.get(System.getProperty("java.home"), "bin", "java").toString();
                    Process a =
                            new ProcessBuilder(
                                            executable,
                                            "-cp",
                                            cp,
                                            ResilienceTest.class.getName(),
                                            "sweep",
                                            privateConfig.toString())
                                    .redirectErrorStream(true)
                                    .redirectOutput(output.resolve("sweeper-a.log").toFile())
                                    .start();
                    Process b =
                            new ProcessBuilder(
                                            executable,
                                            "-cp",
                                            cp,
                                            ResilienceTest.class.getName(),
                                            "sweep",
                                            privateConfig.toString())
                                    .redirectErrorStream(true)
                                    .redirectOutput(output.resolve("sweeper-b.log").toFile())
                                    .start();
                    try {
                        check(
                                a.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                                        && b.waitFor(15, java.util.concurrent.TimeUnit.SECONDS),
                                "sweeper timeout");
                        check(a.exitValue() == 0 && b.exitValue() == 0, "multi-JVM sweep failed");
                    } finally {
                        if (a.isAlive()) {
                            a.destroyForcibly();
                        }
                        if (b.isAlive()) {
                            b.destroyForcibly();
                        }
                    }
                } else {
                    for (int n = 0; n < 8; n++) {
                        f.scan();
                    }
                }
                long elapsed = (System.nanoTime() - started) / 1000000;
                maxSweep = Math.max(maxSweep, elapsed);
                long after = bytesOnDisk(disk);
                check(Files.exists(unrelated.resolve("keep.txt")), "other root deleted");
                check(
                        !f.metadata(held, "input").getString("state").equals("deleted"),
                        "long download pin deleted");
                // 专用小配额目录，不填满宿主磁盘；每轮结束后残留必须回到基线以下。
                check(after < 8192, "disk grows after sweep: " + after);
                cycles.put(
                        new JSONObject()
                                .put("cycle", cycle)
                                .put("beforeBytes", before)
                                .put("afterBytes", after)
                                .put("reclaimedBytes", before - after)
                                .put("sweepMillis", elapsed));
            }
            f.ledger.unpin("n16-long-download");
            f.scan();
            // unknown artifact：执行未结算且到期也不得删除，结算确认后才回收。
            String r = ExecutionTest.create("unknown artifact retention");
            JdbcRunStore.Lease l = running(r, 60000);
            JSONObject artifact =
                    f.writeText(
                            l,
                            "n16-output",
                            new JSONObject()
                                    .put("fileName", "held.txt")
                                    .put("text", "unknown output"));
            store.finish(l, version(r), "failed", "unknown", new JSONObject(), new JSONArray());
            ControlFileTest.sql(
                    "UPDATE harness_file SET expires_at=0 WHERE file_id=?",
                    artifact.getString("artifactId"));
            f.scan();
            check(
                    !f.metadata(artifact.getString("artifactId"), "artifact")
                            .getString("state")
                            .equals("deleted"),
                    "unknown artifact reclaimed");
            ControlFileTest.sql(
                    "UPDATE harness_run SET execution_settlement='confirmed' WHERE run_id=?", r);
            f.scan();
            check(
                    f.metadata(artifact.getString("artifactId"), "artifact")
                            .getString("state")
                            .equals("deleted"),
                    "settled artifact retained");
            // 专用配额，不填满宿主卷。
            for (int i = 0; i < 30; i++) {
                ControlFileTest.uploadText(String.join("", Collections.nCopies(8192, "q")));
            }
            // 超出根配额后必须拒绝，不接受无界字节。
            boolean rejected = false;
            try {
                for (int i = 0; i < 8; i++) {
                    ControlFileTest.uploadText(String.join("", Collections.nCopies(8192, "q")));
                }
            } catch (StoreException e) {
                check(
                        e.code.equals("FILE_QUOTA_EXCEEDED")
                                || e.code.equals("ASSET_QUOTA_EXCEEDED"),
                        e.code);
                rejected = true;
            }
            check(rejected, "quota pressure accepted unbounded bytes");
            ControlFileTest.sql("UPDATE harness_file SET expires_at=0 WHERE root_id='n16-files'");
            for (int i = 0; i < 12; i++) {
                f.scan();
            }
        }
        sweep(p); // 真实关闭/重开：策略与文件根重新加载。
        long residual = bytesOnDisk(disk);
        check(residual < 8192, "restart left byte growth");
        pass(
                "96 uploads, bounded scan backlog, long download pin, unknown artifact, quota, restart, two JVM sweepers and independent root");
        return new JSONObject()
                .put("cycles", cycles)
                .put("uploadedBytes", uploaded)
                .put("heldBytes", heldSize)
                .put("maxSweepMillis", maxSweep)
                .put("residualBytes", residual)
                .put("aging", "accelerated expires_at only in disposable fixture")
                .put("nativeDiskFull", "NOT_RUN");
    }

    /**
     * 发布兼容性场景：active task release 切换与兼容回退不破坏在途 run 的不可变 pin。
     *
     * @throws Exception 目录、存储或断言失败
     */
    static void release() throws Exception {
        // 真实 catalog/profile/run pin 操作，不改动既有不可变发布。
        String old = release;
        String oldRun = run("1", "pinned before release switch");
        Map<String, byte[]> nextFiles = entries("N16 independent release candidate");
        String next = upload(zip(manifest(nextFiles), nextFiles)).getString("digest");
        catalog.lifecycle(T, next, 1, "ready");
        JSONObject task = catalog.profile(T, "task", "task", "1");
        task.put("version", "n16").put("skillReleases", new JSONArray().put(next));
        profiles.publish(T, task);
        // run() 执行任务绑定 CAS；新旧 run 各自保留独立的不可变配置。
        String newRun = run("n16", "candidate");
        check(
                profiles.pinned(identity, oldRun)
                        .getJSONArray("releases")
                        .getJSONObject(0)
                        .getString("digest")
                        .equals(old),
                "switch rewrote old pin");
        check(
                profiles.pinned(identity, newRun)
                        .getJSONArray("releases")
                        .getJSONObject(0)
                        .getString("digest")
                        .equals(next),
                "new run not switched");
        // 回退到旧版本任务：新 run 固定旧 pin，验证兼容回退路径。
        String rollback = run("1", "compatible rollback");
        check(
                profiles.pinned(identity, rollback)
                        .getJSONArray("releases")
                        .getJSONObject(0)
                        .getString("digest")
                        .equals(old),
                "rollback failed");
        pass(
                "active task release switch and compatible rollback preserve in-flight immutable pins");
    }

    /**
     * 测试入口：识别子进程模式（oom/sweep），否则驱动全部 N16 演练并落盘证据。
     *
     * @param args 为 "oom" 执行受限堆 OOM 子模式；为 "sweep" 时 args[1] 为配置文件路径
     * @throws Exception 环境准备、演练执行或证据落盘失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("oom")) {
            try {
                List<byte[]> heap = new ArrayList<>();
                while (true) {
                    heap.add(new byte[1048576]);
                }
            } catch (OutOfMemoryError expected) {
                System.out.println("N16_CONTROLLED_CHILD_HEAP_OOM");
                System.exit(73);
            }
        }
        if (args.length > 0 && args[0].equals("sweep")) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(args[1]))) {
                p.load(in);
            }
            sweep(p);
            return;
        }
        Path output = Paths.get(System.getProperty("resilience.test.output"));
        Files.createDirectories(output);
        ExecutionTest.prepare(Files.createTempDirectory("harness-n16-"), "");
        drain();
        JSONObject files = churn(output);
        release();
        Files.write(output.resolve("storage.json"), bytes(files.toString(2) + "\n"));
        Files.copy(root.resolve("harness_execution.db"), output.resolve("storage.db"));
        System.out.println("PASS: N16 deterministic drills at " + root);
    }
}
