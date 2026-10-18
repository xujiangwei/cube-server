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

/** Dedicated disposable storage/drain/release drills; never uses an existing deployment. */
public final class N16Test {
    static Properties settings() throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) {
            p.load(in);
        }
        p.setProperty("execution.repository", root.resolve("repository").toString());
        p.setProperty("execution.repository.namespace", "runtime-test-repo");
        p.setProperty("execution.tenant", T);
        p.setProperty("execution.subject", S);
        p.setProperty("execution.accepting", "false");
        return p;
    }

    static void drain() throws Exception {
        Properties p = settings();
        try (ExecutionRuntime runtime = new ExecutionRuntime(store, p, 3000)) {
            fail("HARNESS_DRAINING", () -> runtime.create(key(), request("closed")));
            runtime.admission(true);
            String k = key();
            JSONObject receipt = runtime.create(k, request("in flight"));
            runtime.admission(false);
            check(
                    runtime.create(k, request("in flight")).toString().equals(receipt.toString()),
                    "closed replay lost");
            fail("IDEMPOTENCY_CONFLICT", () -> runtime.create(k, request("different")));
            check(!runtime.admission(null).getBoolean("drained"), "queued run ignored");
            String r = receipt.getString("runId");
            JdbcRunStore.Lease l = running(r, 60000);
            store.finish(l, version(r), "failed", "unknown", new JSONObject(), new JSONArray());
            check(
                    !runtime.admission(null).getBoolean("drained"),
                    "unknown effect reported drained");
            ControlFileTest.sql(
                    "UPDATE harness_run SET execution_settlement='confirmed' WHERE run_id=?", r);
            check(runtime.admission(null).getBoolean("drained"), "confirmed drain stuck");
        }
        Properties c = new Properties();
        c.setProperty("mode", "development");
        c.setProperty("worker.credential", "test-worker-credential-123456");
        c.setProperty("api.credential", "test-api-credential-123456789");
        for (String field : Arrays.asList("worker.credential", "api.credential")) {
            c.setProperty("admission.admin.credential", c.getProperty(field));
            try {
                new HarnessConfig(c);
                throw new AssertionError("shared administrator accepted");
            } catch (IOException expected) {
            }
        }
        pass("closed admission, replay/conflict, queued and unknown drain, independent admin");
    }

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

    static void sweep(Properties p) throws Exception {
        StoreConfig sc = new StoreConfig(p);
        FileService opened = null;
        long deadline = System.nanoTime() + 10000000000L;
        while (opened == null && System.nanoTime() < deadline) {
            try {
                opened = new FileService(new JdbcRunStore(sc), p, identity);
            } catch (StoreException e) {
                if (!e.code.equals("FILE_BUSY")) throw e;
                Thread.sleep(20);
            }
        }
        check(opened != null, "file root startup remained busy");
        try (FileService f = opened) {
            for (int i = 0; i < 50; i++) {
                try {
                    f.scan();
                } catch (StoreException e) {
                    if (!e.code.equals("FILE_BUSY")) throw e;
                }
                Thread.sleep(10);
            }
        }
    }

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
        Path unrelated = Files.createDirectory(root.resolve("unrelated-runner"));
        Files.write(unrelated.resolve("keep.txt"), bytes("unrelated runner must survive cleanup"));
        JSONArray cycles = new JSONArray();
        long uploaded = 0, maxSweep = 0;
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
                    if (held == null) {
                        held = row.getString("inputId");
                        heldSize = row.getLong("size");
                        f.ledger.pin(T, S, held, "n16-long-download", null, "download");
                    }
                }
                long before = bytesOnDisk(disk);
                // Accelerated age fixture: immutable TTL policy is unchanged. Not a native 30-day
                // soak.
                ControlFileTest.sql(
                        "UPDATE harness_file SET expires_at=0 WHERE root_id='n16-files'");
                long started = System.nanoTime();
                if (cycle == 2) {
                    String cp = System.getProperty("java.class.path");
                    String executable =
                            Paths.get(System.getProperty("java.home"), "bin", "java").toString();
                    Process a =
                            new ProcessBuilder(
                                            executable,
                                            "-cp",
                                            cp,
                                            N16Test.class.getName(),
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
                                            N16Test.class.getName(),
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
                        if (a.isAlive()) a.destroyForcibly();
                        if (b.isAlive()) b.destroyForcibly();
                    }
                } else for (int n = 0; n < 8; n++) f.scan();
                long elapsed = (System.nanoTime() - started) / 1000000;
                maxSweep = Math.max(maxSweep, elapsed);
                long after = bytesOnDisk(disk);
                check(Files.exists(unrelated.resolve("keep.txt")), "other root deleted");
                check(
                        !f.metadata(held, "input").getString("state").equals("deleted"),
                        "long download pin deleted");
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
            // Dedicated quota, not filling the host volume.
            for (int i = 0; i < 30; i++)
                ControlFileTest.uploadText(String.join("", Collections.nCopies(8192, "q")));
            boolean rejected = false;
            try {
                for (int i = 0; i < 8; i++)
                    ControlFileTest.uploadText(String.join("", Collections.nCopies(8192, "q")));
            } catch (StoreException e) {
                check(
                        e.code.equals("FILE_QUOTA_EXCEEDED")
                                || e.code.equals("ASSET_QUOTA_EXCEEDED"),
                        e.code);
                rejected = true;
            }
            check(rejected, "quota pressure accepted unbounded bytes");
            ControlFileTest.sql("UPDATE harness_file SET expires_at=0 WHERE root_id='n16-files'");
            for (int i = 0; i < 12; i++) f.scan();
        }
        sweep(p); // real close/reopen of policy and root
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

    static void release() throws Exception {
        // Actual catalog/profile/run pin operations, no mutation of existing immutable releases.
        String old = release;
        String oldRun = run("1", "pinned before release switch");
        Map<String, byte[]> nextFiles = entries("N16 independent release candidate");
        String next = upload(zip(manifest(nextFiles), nextFiles)).getString("digest");
        catalog.lifecycle(T, next, 1, "ready");
        JSONObject task = catalog.profile(T, "task", "task", "1");
        task.put("version", "n16").put("skillReleases", new JSONArray().put(next));
        profiles.publish(T, task);
        // run() performs the task-binding CAS; new and old runs retain separate immutable
        // configurations.
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

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("oom")) {
            try {
                List<byte[]> heap = new ArrayList<>();
                while (true) heap.add(new byte[1048576]);
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
        Path output = Paths.get(System.getProperty("n16.test.output"));
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
