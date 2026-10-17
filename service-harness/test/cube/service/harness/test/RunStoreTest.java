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

/** Runs identically on SQLite and a real, dedicated MySQL target; no database mocks. */
public final class RunStoreTest {
    private static final JSONArray checks = new JSONArray();
    private static StoreConfig config;
    private static Properties properties;
    private static Path configFile;
    private static JdbcRunStore a, b;
    private static final String T = "tenant-a", S = "subject-a";
    private static String key() { return UUID.randomUUID().toString(); }
    private static JSONObject object(String name) { return new JSONObject().put("value", name); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void pass(String name) { checks.put(name); System.out.println("PASS: " + name); }
    private static void expect(String code, Runnable body) {
        try { body.run(); throw new AssertionError("Expected " + code); }
        catch (StoreException e) { check(code.equals(e.code), code + " != " + e.code); }
    }
    private static long version(String run) { return a.read(T, S, run).getJSONObject("snapshot").getLong("version"); }
    private static String create() { return a.create(T, S, key(), "profile-v1", object("request")).getString("runId"); }
    private static JdbcRunStore.Lease lease(String run, String owner, long duration) {
        return a.acquire(T, S, run, owner, version(run), duration);
    }
    private static JdbcRunStore.Lease running(String run) {
        JdbcRunStore.Lease lease = lease(run, "coordinator-a", 60000);
        a.transition(lease, version(run), "running", object("checkpoint")); return lease;
    }
    private static long count(String table) throws Exception {
        try (Connection c = config.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next(); return r.getLong(1);
        }
    }
    private static JSONObject evidence(String round, String key, long revision, long in, long out) {
        return new JSONObject().put("roundId", round).put("attempt", 1).put("evidenceId", key)
                .put("expectedRevision", revision).put("input", in).put("output", out);
    }
    private static JSONObject addRound(JdbcRunStore.Lease lease, String round) {
        a.intent(lease, version(lease.runId), "round", round, 1, object("model-request"), 10, 20);
        JSONObject intent = a.intents(T, S, lease.runId, "round").getJSONObject(0);
        a.confirmIntent(lease, version(lease.runId), "round", round, 1, intent.getLong("origin_fence"),
                intent.getString("intent_hash"), object("model-result")); return intent;
    }

    private static void migrations() throws Exception {
        StoreMigrations.migrate(config, true);
        try (Connection c = config.connect()) { StoreMigrations.verify(c, config); }
        Properties wrong = new Properties(); wrong.putAll(properties); wrong.setProperty("store.identity", "wrong-identity-123456789");
        try { new JdbcRunStore(new StoreConfig(wrong)); throw new AssertionError("Wrong DB identity accepted"); }
        catch (StoreException e) { check(e.code.equals("STORE_IDENTITY_MISMATCH"), e.code); }
        wrong.setProperty("store.database", "legacy_business");
        try { new StoreConfig(wrong); throw new AssertionError("Legacy target accepted"); }
        catch (StoreException e) { check(e.code.equals("STORE_TARGET_REFUSED"), e.code); }
        if (!config.mysql()) {
            Properties foreign = new Properties(); foreign.putAll(properties);
            foreign.setProperty("store.database", "harness_foreign");
            foreign.setProperty("store.sqlite.file", configFile.getParent().resolve("harness_foreign.db").toString());
            StoreConfig target = new StoreConfig(foreign);
            try (Connection c = target.connect(); Statement statement = c.createStatement()) { statement.execute("CREATE TABLE legacy_table(id INT)"); }
            try { StoreMigrations.migrate(target, true); throw new AssertionError("Foreign schema initialized"); }
            catch (StoreException e) { check(e.code.equals("STORE_SCHEMA_MISMATCH"), e.code); }
        }
        pass("migration repeat, identity mismatch and legacy target refusal");
    }

    private static void serviceIntegration() throws Exception {
        Properties values = new Properties(); values.putAll(properties);
        values.setProperty("mode", "development"); values.setProperty("http.port", "0");
        values.setProperty("worker.credential", "private-worker-test-credential-12345");
        values.setProperty("api.credential", "private-api-test-credential-678901");
        values.setProperty("store.enabled", "true");
        HarnessService service = new HarnessService(new HarnessConfig(values));
        try { service.start(); check(service.isStarted() && service.runStore() != null, "Store not installed in service"); }
        finally { service.stop(); }
        values.setProperty("store.identity", "incorrect-identity-123456");
        HarnessService refused = new HarnessService(new HarnessConfig(values));
        try { refused.start(); check(!refused.isStarted() && refused.runStore() == null, "Wrong store did not fail closed"); }
        finally { refused.stop(); }
        values.setProperty("store.enabled", "false");
        HarnessService disabled = new HarnessService(new HarnessConfig(values));
        try { disabled.start(); check(disabled.isStarted() && disabled.runStore() == null, "Disabled ledger changed G0 startup"); }
        finally { disabled.stop(); }
        pass("service installs verified store, refuses wrong identity, disabled store preserves G0 startup");
    }

    private static void idempotency() throws Exception {
        String key = key(); ExecutorService pool = Executors.newFixedThreadPool(6); Set<String> runs = new HashSet<>();
        try {
            List<Future<JSONObject>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> new JdbcRunStore(config).create(T, S, key, "profile-v1", object("same"))));
            for (Future<JSONObject> f : futures) runs.add(f.get(20, TimeUnit.SECONDS).getString("runId"));
        } finally { pool.shutdownNow(); }
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

    private static void atomicFailures() throws Exception {
        long runCount = count("harness_run"), eventCount = count("harness_event"), outboxCount = count("harness_outbox"), requestCount = count("harness_request");
        JdbcRunStore broken = new JdbcRunStore(config, stage -> { if (stage.equals("beforeCommit")) throw new StoreException("INJECTED_CRASH"); });
        expect("INJECTED_CRASH", () -> broken.create(T, S, key(), "profile-v1", object("rollback")));
        check(count("harness_run") == runCount && count("harness_event") == eventCount
                && count("harness_outbox") == outboxCount && count("harness_request") == requestCount, "Partial transaction survived");
        String key = key(); JdbcRunStore lost = new JdbcRunStore(config, stage -> { if (stage.equals("afterCommit")) throw new StoreException("LOST_REPLY"); });
        expect("LOST_REPLY", () -> lost.create(T, S, key, "profile-v1", object("lost")));
        JSONObject retried = a.create(T, S, key, "profile-v1", object("lost"));
        check(a.events(T, S, retried.getString("runId"), 0, 10).length() == 1, "Lost reply duplicated commit");
        pass("rollback leaves no run/event/outbox/receipt, lost commit reply is idempotent");
    }

    private static Process child(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(); cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp"); cmd.add(System.getProperty("java.class.path")); cmd.add(ProcessWorker.class.getName());
        cmd.add(configFile.toString()); Collections.addAll(cmd, args);
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private static void processCrashes() throws Exception {
        long before = count("harness_run");
        for (String boundary : Arrays.asList("beforeCommit", "afterCommit")) {
            String key = key(); Process process = child("crash", key, boundary);
            check(process.waitFor(25, TimeUnit.SECONDS), "Crash writer timeout");
            check(process.exitValue() == 73, "Crash hook did not halt process");
            long expected = boundary.equals("beforeCommit") ? before : before + 2;
            check(count("harness_run") == expected, "Crash persistence mismatch at " + boundary);
            JSONObject saved = a.create(T, S, key, "profile-v1", object("process-crash"));
            check(a.events(T, S, saved.getString("runId"), 0, 10).length() == 1, "Crash retry duplicated event");
        }
        check(count("harness_run") == before + 2, "Crash retry duplicated run");
        pass("real JVM halt immediately before and after commit, restart/retry recovers");
    }

    private static void concurrentCas() throws Exception {
        String run = create(); JdbcRunStore.Lease lease = running(run); long v = version(run);
        ExecutorService pool = Executors.newFixedThreadPool(8); AtomicInteger winners = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 8; i++) all.add(pool.submit(() -> {
                go.await(); try { new JdbcRunStore(config).transition(lease, v, "running", object(key())); winners.incrementAndGet(); }
                catch (StoreException e) { check(e.code.equals("VERSION_CONFLICT"), e.code); } return null;
            }));
            go.countDown(); for (Future<?> f : all) f.get(25, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        check(winners.get() == 1 && version(run) == v + 1, "CAS had multiple winners");
        pass("eight independent connections race one version: exactly one CAS advances");

        String fresh = create(); long initial = version(fresh);
        Process one = child("acquire", fresh, Long.toString(initial), "owner-process-a");
        Process two = child("acquire", fresh, Long.toString(initial), "owner-process-b");
        try {
            check(new BufferedReader(new InputStreamReader(one.getInputStream())).readLine().equals("READY"), "Owner one not ready");
            check(new BufferedReader(new InputStreamReader(two.getInputStream())).readLine().equals("READY"), "Owner two not ready");
            one.getOutputStream().write(10); one.getOutputStream().flush(); two.getOutputStream().write(10); two.getOutputStream().flush();
            check(one.waitFor(25, TimeUnit.SECONDS) && two.waitFor(25, TimeUnit.SECONDS), "Owner processes timeout");
            check((one.exitValue() == 0 && two.exitValue() == 74) || (two.exitValue() == 0 && one.exitValue() == 74), "Two owner processes won");
        } finally { if (one.isAlive()) one.destroyForcibly(); if (two.isAlive()) two.destroyForcibly(); }
        pass("two real JVM owner processes contend: exactly one lease acquired");
    }

    private static void fencingAndRecovery() throws Exception {
        String run = create(); JdbcRunStore.Lease old = lease(run, "old-owner", 200);
        a.transition(old, version(run), "running", object("checkpoint"));
        a.intent(old, version(run), "call", "call-1", 1, object("fixed-arguments"), 0, 0);
        JSONObject intent = a.intents(T, S, run, "call").getJSONObject(0);
        expect("LEASE_BUSY", () -> b.acquire(T, S, run, "new-owner", version(run), 1000));
        Thread.sleep(350);
        check(a.recover(T, "", 500).toString().contains(run), "Expired run absent from recovery");
        JdbcRunStore.Lease current = b.acquire(T, S, run, "new-owner", version(run), 60000);
        check(current.fence > old.fence, "Fence did not advance");
        expect("STALE_OWNER", () -> a.transition(old, version(run), "running", object("late")));
        expect("STALE_OWNER", () -> a.renew(old, 1000));
        expect("STALE_OWNER", () -> a.confirmIntent(old, version(run), "call", "call-1", 1, old.fence, intent.getString("intent_hash"), object("late")));
        expect("STALE_OWNER", () -> a.finish(old, version(run), "failed", "unknown", object("late"), new JSONArray()));
        expect("INTENT_RECONCILIATION_REQUIRED", () -> a.intent(current, version(run), "call", "call-1", 2, object("fixed-arguments"), 0, 0));
        expect("INTENT_EVIDENCE_MISMATCH", () -> a.confirmIntent(current, version(run), "call", "call-1", 1, old.fence + 1, intent.getString("intent_hash"), object("historical")));
        String compensationKey = key(); long compensationVersion = version(run);
        JSONObject compensation = b.requestCompensation(current, compensationVersion, compensationKey, "call", "call-1", 1, intent.getString("intent_hash"), object("query-status-first"));
        check(JdbcRunStore.canonical(compensation).equals(JdbcRunStore.canonical(a.requestCompensation(current, compensationVersion, compensationKey,
                "call", "call-1", 1, intent.getString("intent_hash"), object("query-status-first")))), "Compensation duplicated");
        b.confirmIntent(current, version(run), "call", "call-1", 1, old.fence, intent.getString("intent_hash"), object("historical"));
        b.renew(current, 60000); b.release(current, version(run));
        expect("STALE_OWNER", () -> a.renew(current, 1000));
        pass("lease expiry/recovery, stale-owner rejection, no blind replay, historical evidence reconciliation");
    }

    private static void controlsAndEvents() throws Exception {
        String run = create(), key = key(); long v = version(run);
        JSONObject response = a.submitControl(T, S, run, key, "cancel", v, object("cancel"));
        JSONObject replay = b.submitControl(T, S, run, key, "cancel", v, object("cancel"));
        check(JdbcRunStore.canonical(response).equals(JdbcRunStore.canonical(replay)), "Control receipt changed");
        expect("IDEMPOTENCY_CONFLICT", () -> a.submitControl(T, S, run, key, "cancel", v, object("changed")));
        expect("VERSION_CONFLICT", () -> a.submitControl(T, S, run, key(), "cancel", v, object("cancel")));
        JSONArray page = a.events(T, S, run, 0, 1); check(page.length() == 1, "Event limit ignored");
        Contract.validate(page.getJSONObject(0), new JSONObject(new String(Contract.resource("schemas/event.json"), StandardCharsets.UTF_8)));
        long seq = page.getJSONObject(0).getLong("eventSeq");
        check(b.events(T, S, run, seq, 500).getJSONObject(0).getLong("eventSeq") == seq + 1, "Event cursor gap");
        Contract.validate(a.read(T, S, run).getJSONObject("snapshot"), new JSONObject(new String(Contract.resource("schemas/run-snapshot.json"), StandardCharsets.UTF_8)));
        expect("INVALID_EVENT_CURSOR", () -> a.events(T, S, run, 999999, 5));
        expect("INVALID_PAGE_SIZE", () -> a.events(T, S, run, 0, 501));
        String gap = create();
        try (Connection c = config.connect(); PreparedStatement out = c.prepareStatement("DELETE FROM harness_outbox WHERE run_id=?");
             PreparedStatement event = c.prepareStatement("DELETE FROM harness_event WHERE run_id=?")) {
            out.setString(1, gap); out.executeUpdate(); event.setString(1, gap); event.executeUpdate();
        }
        expect("EVENT_GAP", () -> a.events(T, S, gap, 0, 10));
        pass("durable control receipts, expectedVersion conflicts, schema-valid snapshots and cursor pages");
    }

    private static void finalAndUsage() throws Exception {
        String run = create(); JdbcRunStore.Lease lease = running(run); addRound(lease, "round-1");
        long v = version(run); long finals = count("harness_result"), adjustments = count("harness_usage_adjustment");
        JSONObject actual = evidence("round-1", "provider-receipt", 0, 12, 19);
        JdbcRunStore broken = new JdbcRunStore(config, stage -> { if (stage.equals("beforeCommit")) throw new StoreException("FINAL_CRASH"); });
        expect("FINAL_CRASH", () -> broken.finish(lease, v, "succeeded", "confirmed", object("final"), new JSONArray().put(actual)));
        check(count("harness_result") == finals && count("harness_usage_adjustment") == adjustments && version(run) == v, "Final rollback incomplete");
        JSONObject committed = a.finish(lease, v, "succeeded", "confirmed", object("final"), new JSONArray().put(actual));
        check(JdbcRunStore.canonical(committed).equals(JdbcRunStore.canonical(b.finish(lease, v, "succeeded", "confirmed", object("final"), new JSONArray().put(actual)))), "Final receipt changed");
        check(count("harness_result") == finals + 1 && count("harness_usage_adjustment") == adjustments + 1, "Duplicate final or usage row");
        expect("FINAL_CONFLICT", () -> a.finish(lease, v, "succeeded", "confirmed", object("different"), new JSONArray().put(actual)));
        JSONObject read = a.read(T, S, run); check(read.getString("usageSettlement").equals("settled"), "Actual usage not settled");
        check(read.getJSONObject("usage").getLong("estimatedInput") == 10 && read.getJSONObject("usage").getLong("actualInput") == 12, "Estimate/actual merged");
        expect("INVALID_STATE_TRANSITION", () -> a.transition(lease, version(run), "running", object("reopen")));
        pass("final/result/usage/event atomic rollback, duplicate final idempotent, estimates separate");

        String late = create(); JdbcRunStore.Lease lateLease = running(late); addRound(lateLease, "late-round");
        a.finish(lateLease, version(late), "succeeded", "confirmed", object("immutable"), new JSONArray());
        check(a.read(T, S, late).getString("usageSettlement").equals("provisional"), "Missing usage marked actual");
        a.release(lateLease, version(late));
        check(a.recover(T, "", 500).toString().contains(late), "Late usage lost from recovery scan");
        JdbcRunStore.Lease current = b.acquire(T, S, late, "settlement-owner", version(late), 60000);
        JSONObject first = evidence("late-round", "evidence-1", 0, 15, 25);
        a.settleUsage(current, version(late), first); long once = version(late);
        a.settleUsage(current, once - 1, first); check(version(late) == once, "Duplicate usage advanced version");
        expect("USAGE_EVIDENCE_CONFLICT", () -> a.settleUsage(current, version(late), evidence("late-round", "evidence-1", 0, 99, 99)));
        expect("USAGE_VERSION_CONFLICT", () -> a.settleUsage(current, version(late), evidence("late-round", "evidence-stale", 0, 99, 99)));
        expect("INVALID_USAGE", () -> a.settleUsage(current, version(late), evidence("late-round", "fraction", 1, 99, 99).put("input", 1.5)));
        a.settleUsage(current, version(late), evidence("late-round", "correction-2", 1, 16, 26));
        JSONObject readLate = a.read(T, S, late);
        check(readLate.getJSONObject("usage").getLong("actualInput") == 16 && readLate.getJSONObject("usage").getLong("actualOutput") == 26, "Correction accumulated twice");
        check(readLate.getJSONObject("snapshot").getString("state").equals("succeeded") && readLate.getJSONObject("result").getJSONObject("result").getString("value").equals("immutable"), "Late settlement reopened final");
        pass("provisional terminal usage recovered by new owner, deduped actuals and revisioned corrections");
    }

    private static void outbox() throws Exception {
        // Earlier tests intentionally dropped every notification. Polling still discovers all sends.
        AtomicInteger effects = new AtomicInteger(); int sent = a.dispatch("dispatcher-drain", 500, message -> {
            a.consume(message.getString("tenant"), "receiver-main", message.getString("message_id"), c -> effects.incrementAndGet());
        });
        check(sent > 0 && effects.get() == sent, "Outbox polling did not recover lost notifications");
        String run = create(); JSONArray messages = a.claimOutbox("dispatcher-a", 10, 200);
        check(messages.length() == 1, "Unexpected outbox pending count"); JSONObject m = messages.getJSONObject(0);
        String msg = m.getString("message_id"); long fence = m.getLong("claim_fence");
        expect("MESSAGE_NOT_FOUND", () -> a.consume("tenant-b", "receiver-main", msg, c -> { throw new AssertionError("Cross-tenant effect"); }));
        check(a.consume(T, "receiver-main", msg, c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE harness_run SET updated_at=updated_at+1 WHERE run_id=?")) { s.setString(1, run); check(s.executeUpdate() == 1, "Inbox effect missing"); }
        }), "First consume rejected");
        // Simulate send success + ACK loss. Claim expiry transfers ownership and redelivers.
        Thread.sleep(350); JSONObject again = b.claimOutbox("dispatcher-b", 10, 60000).getJSONObject(0);
        check(again.getString("message_id").equals(msg) && again.getLong("claim_fence") > fence, "Message identity changed on retry");
        expect("STALE_OUTBOX_CLAIM", () -> a.ackOutbox("dispatcher-a", msg, fence));
        check(!b.consume(T, "receiver-main", msg, c -> { throw new AssertionError("Duplicate business effect"); }), "Duplicate consumed");
        b.retryOutbox("dispatcher-b", msg, again.getLong("claim_fence"), 0);
        JSONObject retried = a.claimOutbox("dispatcher-c", 10, 60000).getJSONObject(0);
        a.ackOutbox("dispatcher-c", msg, retried.getLong("claim_fence"));
        check(a.claimOutbox("dispatcher-d", 10, 1000).length() == 0, "Delivered message redelivered");

        String failRun = create(); JSONObject failMessage = a.claimOutbox("dispatcher-fail", 10, 60000).getJSONObject(0);
        String failId = failMessage.getString("message_id");
        expect("EFFECT_ROLLBACK", () -> a.consume(T, "receiver-rollback", failId, c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE harness_run SET state='failed' WHERE run_id=?")) { s.setString(1, failRun); s.executeUpdate(); }
            throw new StoreException("EFFECT_ROLLBACK");
        }));
        check(a.read(T, S, failRun).getJSONObject("snapshot").getString("state").equals("queued"), "Receiver effect not rolled back");
        check(a.consume(T, "receiver-rollback", failId, c -> {}), "Rolled-back inbox survived");
        a.ackOutbox("dispatcher-fail", failId, failMessage.getLong("claim_fence"));
        create();
        check(a.dispatch("dispatcher-network-fail", 10, message -> { throw new IOException("Injected network failure"); }) == 0, "Failed send ACKed");
        Thread.sleep(1100);
        check(b.dispatch("dispatcher-network-retry", 10, message -> b.consume(message.getString("tenant"),
                "receiver-main", message.getString("message_id"), c -> {})) == 1, "Failed network send was lost");
        pass("lost notifications, failed sends, ACK loss, claim fencing, duplicate Inbox and transactional effects");
    }

    public static void main(String[] args) throws Exception {
        String configured = System.getProperty("store.test.config", ""); properties = new Properties();
        if (configured.isEmpty() || configured.startsWith("${")) {
            Path dir = Files.createTempDirectory("cube-harness-store-");
            properties.setProperty("store.dialect", "sqlite"); properties.setProperty("store.database", "harness_test");
            properties.setProperty("store.identity", "test-identity-" + key());
            properties.setProperty("store.sqlite.root", dir.toString()); properties.setProperty("store.sqlite.file", dir.resolve("harness_test.db").toString());
            configFile = dir.resolve("private.properties"); try (OutputStream out = Files.newOutputStream(configFile)) { properties.store(out, "Disposable Harness test only"); }
        } else {
            configFile = Paths.get(configured).toAbsolutePath();
            try (InputStream in = Files.newInputStream(configFile)) { properties.load(in); }
            if (!"true".equals(properties.getProperty("store.test.disposable")))
                throw new StoreException("DISPOSABLE_TEST_DATABASE_REQUIRED");
        }
        config = new StoreConfig(properties); StoreMigrations.migrate(config, true); a = new JdbcRunStore(config); b = new JdbcRunStore(config);
        migrations(); serviceIntegration(); idempotency(); atomicFailures(); processCrashes(); concurrentCas(); fencingAndRecovery(); controlsAndEvents(); finalAndUsage(); outbox();
        JSONObject summary = new JSONObject().put("dialect", config.dialect).put("checks", checks).put("result", "PASS")
                .put("databaseProduct", databaseProduct()).put("migrationHash", StoreMigrations.digest(config));
        String output = System.getProperty("store.test.output", "");
        if (!output.isEmpty() && !output.startsWith("${")) Files.write(Paths.get(output), summary.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS: " + checks.length() + " RunStore scenarios on " + config.dialect);
    }

    private static String databaseProduct() throws Exception {
        try (Connection c = config.connect()) { return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion(); }
    }

    public static final class ProcessWorker {
        public static void main(String[] args) throws Exception {
            Properties p = new Properties(); try (InputStream in = Files.newInputStream(Paths.get(args[0]))) { p.load(in); }
            StoreConfig config = new StoreConfig(p);
            if (args[1].equals("crash")) {
                JdbcRunStore store = new JdbcRunStore(config, boundary -> { if (boundary.equals(args[3])) Runtime.getRuntime().halt(73); });
                store.create(T, S, args[2], "profile-v1", object("process-crash")); throw new AssertionError("Crash hook missed");
            }
            JdbcRunStore store = new JdbcRunStore(config); System.out.println("READY"); System.out.flush(); System.in.read();
            try { store.acquire(T, S, args[2], args[4], Long.parseLong(args[3]), 60000); }
            catch (StoreException e) {
                if (!(e.code.equals("VERSION_CONFLICT") || e.code.equals("LEASE_BUSY"))) throw e;
                System.exit(74);
            }
        }
    }
}
