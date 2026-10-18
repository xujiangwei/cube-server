package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.execution.*;
import cube.service.harness.files.*;
import cube.service.harness.protocol.Contract;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import cube.service.harness.store.FileStore;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real JDBC, filesystem and coordinator controls; no real write provider. */
public final class ControlFileTest {
  static FileService files;
  static ControlStore controls;
  static Properties settings;

  static void sql(String query, Object... args) throws Exception {
    try (Connection c = config.connect();
        PreparedStatement p = c.prepareStatement(query)) {
      for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
      p.executeUpdate();
    }
  }

  static JSONObject uploadText(String text) throws Exception {
    byte[] b = bytes(text);
    JSONObject request =
        new JSONObject()
            .put("fileName", "测试.txt")
            .put("mediaType", "text/plain")
            .put("size", b.length)
            .put("sha256", RuntimeSupport.sha(b));
    String k = key();
    JSONObject created = files.create(k, request);
    check(
        created.getString("inputId").equals(files.create(k, request).getString("inputId")),
        "create replay");
    fail(
        "IDEMPOTENCY_CONFLICT",
        () -> files.create(k, RuntimeSupport.copy(request).put("fileName", "other.txt")));
    files.uploadInput(created.getString("inputId"), new ByteArrayInputStream(b));
    return files.complete(created.getString("inputId"), request.getString("sha256"));
  }

  static String create(String text, JSONArray ids) {
    return profiles
        .createRun(
            identity,
            key(),
            "task",
            request(text)
                .put("input", new JSONObject().put("instruction", text).put("attachmentIds", ids)))
        .getString("runId");
  }

  static JSONObject resume(String r, String wait) {
    return new JSONObject()
        .put("expectedVersion", version(r))
        .put("waitId", wait)
        .put(
            "input",
            new JSONObject()
                .put("instruction", "resume now")
                .put("attachmentIds", new JSONArray()));
  }

  static void controls() throws Exception {
    String r = create("durable wait", new JSONArray());
    JdbcRunStore.Lease l = running(r, 60000);
    JSONObject payload =
        new JSONObject()
            .put("binding", "round-1")
            .put("question", "which input?")
            .put("checkpointDigest", RuntimeSupport.sha(bytes("checkpoint")));
    controls.waitFor(l, version(r), "input", payload, controls.now() + 10000);
    store.release(l, version(r));
    ControlStore restarted =
        new ControlStore(new JdbcRunStore(config), config.mysql()).files(files.ledger);
    JSONObject wait = restarted.current(T, S, r);
    check(
        wait.getJSONObject("details").getString("question").equals("which input?"),
        "restart lost question");
    fail(
        "WAIT_NOT_FOUND",
        () -> controls.apply(T, S, S, r, key(), "resume", "wrong", resume(r, "wrong"), false));
    String k = key();
    JSONObject req = resume(r, wait.getString("waitId"));
    JSONObject first =
        restarted.apply(T, S, S, r, k, "resume", wait.getString("waitId"), req, false);
    check(
        JdbcRunStore.canonical(first)
            .equals(
                JdbcRunStore.canonical(
                    restarted.apply(
                        T, S, S, r, k, "resume", wait.getString("waitId"), req, false))),
        "version checked before replay");
    fail(
        "IDEMPOTENCY_CONFLICT",
        () ->
            controls.apply(
                T,
                S,
                S,
                r,
                k,
                "resume",
                wait.getString("waitId"),
                RuntimeSupport.copy(req).put("expectedVersion", 0),
                false));
    check(
        controls.handled(r, "input", "round-1") && controls.inputs(T, S, r).length() == 1,
        "resume missing");
    pass(
        "persisted wait survives store restart; wait binding, expectedVersion and replay ordering enforced");

    String approval = create("fake effect", new JSONArray());
    JdbcRunStore.Lease a = running(approval, 60000);
    String ah = JdbcRunStore.hash(new JSONObject().put("target", "fake-only"));
    controls.waitFor(
        a,
        version(approval),
        "approval",
        new JSONObject()
            .put("binding", "fake-only")
            .put("argumentsHash", ah)
            .put("arguments", new JSONObject().put("target", "fake-only")),
        controls.now() + 10000);
    store.release(a, version(approval));
    String w = controls.current(T, S, approval).getString("waitId");
    JSONObject approve =
        new JSONObject()
            .put("expectedVersion", version(approval))
            .put("decision", "approve")
            .put("argumentsHash", ah);
    fail(
        "APPROVER_FORBIDDEN",
        () -> controls.apply(T, S, S, approval, key(), "approval", w, approve, true));
    fail(
        "APPROVAL_ARGUMENTS_CHANGED",
        () ->
            controls.apply(
                T,
                S,
                "independent-reviewer",
                approval,
                key(),
                "approval",
                w,
                RuntimeSupport.copy(approve)
                    .put("argumentsHash", RuntimeSupport.sha(bytes("changed"))),
                true));
    controls.apply(T, S, "independent-reviewer", approval, key(), "approval", w, approve, true);
    check(controls.handled(approval, "approval", "fake-only"), "approval not bound");
    String race = create("control race", new JSONArray());
    JdbcRunStore.Lease rl = running(race, 60000);
    controls.waitFor(rl, version(race), "input", payload, controls.now() + 10000);
    store.release(rl, version(race));
    String rw = controls.current(T, S, race).getString("waitId");
    JSONObject rr = resume(race, rw),
        cancel = new JSONObject().put("expectedVersion", version(race));
    AtomicInteger successes = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> f1 =
          pool.submit(
              () -> {
                try {
                  controls.apply(T, S, S, race, key(), "resume", rw, rr, false);
                  successes.incrementAndGet();
                } catch (StoreException e) {
                  check(e.code.equals("VERSION_CONFLICT"), e.code);
                }
              });
      Future<?> f2 =
          pool.submit(
              () -> {
                try {
                  controls.apply(T, S, S, race, key(), "cancel", null, cancel, false);
                  successes.incrementAndGet();
                } catch (StoreException e) {
                  check(e.code.equals("VERSION_CONFLICT"), e.code);
                }
              });
      f1.get();
      f2.get();
    } finally {
      pool.shutdownNow();
    }
    check(successes.get() == 1, "two controls advanced same version");
    String exp = create("expired", new JSONArray());
    JdbcRunStore.Lease el = running(exp, 60000);
    controls.waitFor(el, version(exp), "input", payload, controls.now() + 10000);
    store.release(el, version(exp));
    String ew = controls.current(T, S, exp).getString("waitId");
    sql("UPDATE harness_wait SET expires_at=0 WHERE wait_id=?", ew);
    fail(
        "WAIT_EXPIRED",
        () -> controls.apply(T, S, S, exp, key(), "resume", ew, resume(exp, ew), false));
    pass(
        "independent approver and immutable arguments; concurrent resume/cancel advances once; expired waits refused");
  }

  static void filesystem() throws Exception {
    AssetFiles lockedRoot =
        new AssetFiles(
            root.resolve("files"),
            files.config.maxBytes,
            new String(
                Files.readAllBytes(root.resolve("files/.harness-assets")),
                java.nio.charset.StandardCharsets.UTF_8));
    CountDownLatch held = new CountDownLatch(1), releaseLock = new CountDownLatch(1);
    ExecutorService holder = Executors.newSingleThreadExecutor();
    Future<?> blocking =
        holder.submit(
            () -> {
              try {
                lockedRoot.locked(
                    () -> {
                      held.countDown();
                      check(releaseLock.await(5, TimeUnit.SECONDS), "lock fixture timeout");
                      return null;
                    });
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    try {
      check(held.await(2, TimeUnit.SECONDS), "lock not held");
      long started = System.nanoTime();
      fail("FILE_BUSY", files::scan);
      String controlRun = create("control during held stream", new JSONArray());
      controls.apply(
          T,
          S,
          S,
          controlRun,
          key(),
          "cancel",
          null,
          new JSONObject().put("expectedVersion", version(controlRun)),
          false);
      check(
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000,
          "file lock blocked control admission");
    } finally {
      releaseLock.countDown();
      blocking.get();
      holder.shutdownNow();
    }
    pass(
        "held file-stream root lock returns FILE_BUSY immediately while control transaction progresses");
    Path otherRoot = Files.createDirectory(root.resolve("other-files"));
    Properties otherSettings = new Properties();
    otherSettings.putAll(settings);
    otherSettings.setProperty("files.root", otherRoot.toString());
    fail("FILE_ROOT_IDENTITY_MISMATCH", () -> new FileService(store, otherSettings, identity));
    try (java.util.stream.Stream<Path> entries = Files.list(otherRoot)) {
      check(
          entries.noneMatch(f -> f.getFileName().toString().equals("objects")),
          "different root accessed prior objects");
    }
    pass("persistent root identity rejects another physical directory with the same rootId");
    JSONObject input = uploadText("hello 文件");
    String id = input.getString("inputId");
    long expires = input.getLong("expiresAt");
    check(
        expires - files.ledger.get(T, S, id).getLong("ready_at") == 604800000L,
        "input default TTL");
    files.complete(id, input.getString("sha256"));
    check(
        files.metadata(id, "input").getLong("expiresAt") == expires,
        "repeat complete refreshed TTL");
    fail("FILE_NOT_FOUND", () -> files.ledger.get("other", S, id));
    fail(
        "FILE_UPLOAD_CLOSED",
        () -> files.uploadInput(id, new ByteArrayInputStream(bytes("hello 文件"))));
    String run = create("read input", new JSONArray().put(id));
    JdbcRunStore.Lease l = running(run, 60000);
    check(files.readText(l, "read-1", id).getString("text").equals("hello 文件"), "content mismatch");
    sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", id);
    files.scan();
    check(
        !files.metadata(id, "input").getString("state").equals("deleted"),
        "active consume pin deleted");
    fail("FILE_EXPIRED", () -> files.ledger.pin(T, S, id, "new-consumer", null, "download"));
    check(
        files.readText(l, "read-1", id).getString("text").equals("hello 文件"),
        "recovery pin lost expired bytes");
    files.consumed(run, "read-1", id);
    files.scan();
    check(
        files.metadata(id, "input").getString("state").equals("deleted"),
        "expired input not reclaimed");
    fail("FILE_EXPIRED", () -> create("expired input", new JSONArray().put(id)));
    JSONObject out =
        files.writeText(
            l,
            "write-1",
            new JSONObject().put("fileName", "result.txt").put("text", "verified artifact"));
    String artifact = out.getString("artifactId");
    Contract.parse("artifact", RuntimeSupport.bytes(files.reference(out)));
    check(
        artifact.equals(
            files
                .writeText(
                    l,
                    "write-1",
                    new JSONObject().put("fileName", "result.txt").put("text", "verified artifact"))
                .getString("artifactId")),
        "artifact regenerated");
    check(
        out.getLong("expiresAt") - files.ledger.get(T, S, artifact).getLong("ready_at")
            == 2592000000L,
        "artifact default TTL");
    check(
        files.handle(artifact).getBoolean("requiresAuthentication"),
        "handle bypassed authentication");
    files.ledger.pin(T, S, artifact, "download-1", null, "download");
    sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", artifact);
    store.finish(l, version(run), "failed", "unknown", new JSONObject(), new JSONArray());
    files.scan();
    check(
        !files.metadata(artifact, "artifact").getString("state").equals("deleted"),
        "unknown execution deleted artifact");
    files.ledger.unpin("download-1");
    files.scan();
    check(
        !files.metadata(artifact, "artifact").getString("state").equals("deleted"),
        "unknown without pin deleted artifact");
    sql("UPDATE harness_run SET execution_settlement='confirmed' WHERE run_id=?", run);
    files.scan();
    check(
        files.metadata(artifact, "artifact").getString("state").equals("deleted"),
        "terminal artifact not reclaimed");
    JSONObject sameA = uploadText("same"), sameB = uploadText("same");
    check(
        !files
            .ledger
            .get(T, S, sameA.getString("inputId"))
            .getString("storage_key")
            .equals(files.ledger.get(T, S, sameB.getString("inputId")).getString("storage_key")),
        "unexpected shared blob");
    sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", sameA.getString("inputId"));
    files.scan();
    check(
        files.metadata(sameB.getString("inputId"), "input").getString("state").equals("ready"),
        "same-content identity deleted");
    pass(
        "actual UTF-8 upload/complete/read/artifact; immutable ready TTL; binding/tenant checks; expiry pins, unknown execution and independent same-content objects protected");
    JSONObject claimFile = uploadText("claim");
    String cf = claimFile.getString("inputId");
    sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", cf);
    files.ledger.candidates();
    JSONObject old = files.ledger.claim(cf);
    FileStore second = new FileStore(store, config.mysql(), files.config);
    second.policy();
    check(second.claim(cf) == null, "live claim stolen");
    sql("UPDATE harness_file SET claim_until=0 WHERE file_id=?", cf);
    JSONObject next = second.claim(cf);
    check(next.getLong("claim_fence") > old.getLong("claim_fence"), "fence not advanced");
    fail("STALE_FILE_CLAIM", () -> files.ledger.verifyClaim(old));
    second.failed(next, "INJECTED_OCCUPIED");
    check(second.claim(cf) == null, "retry ignored backoff");
    second.close();
    for (String boundary : Arrays.asList("beforeCommit", "afterCommit")) {
      JSONObject victim = uploadText("delete-failure-" + boundary);
      String vid = victim.getString("inputId");
      sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", vid);
      java.util.concurrent.atomic.AtomicBoolean once =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      JdbcRunStore faultyStore =
          new JdbcRunStore(
              config,
              b -> {
                if (b.equals(boundary)
                    && Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(f -> f.getMethodName().equals("deleted"))
                    && once.compareAndSet(true, false))
                  throw new RuntimeException("injected delete commit boundary");
              });
      FileStore faulty = new FileStore(faultyStore, config.mysql(), files.config);
      faulty.candidates();
      JSONObject claim = faulty.claim(vid);
      Path content = root.resolve("files/objects/" + claim.getString("storage_key") + ".data");
      check(Files.deleteIfExists(content), "actual file missing before failure injection");
      fail(
          boundary.equals("beforeCommit") ? "STORE_UNAVAILABLE" : "STORE_COMMIT_OUTCOME_UNKNOWN",
          () -> faulty.deleted(claim));
      sql("UPDATE harness_file SET claim_until=0 WHERE file_id=?", vid);
      files.scan();
      check(
          files.metadata(vid, "input").getString("state").equals("deleted"),
          "missing bytes did not reconcile tombstone");
    }
    pass(
        "actual byte deletion before DB commit failure and after-commit response loss reconciles without regenerating data");
    if (System.getProperty("os.name").toLowerCase().contains("windows")) {
      Path alias = root.resolve("file-alias");
      Process command =
          new ProcessBuilder(
                  "cmd", "/c", "mklink", "/J", alias.toString(), root.resolve("files").toString())
              .redirectErrorStream(true)
              .start();
      cube.service.harness.runtime.RuntimeSupport.read(command.getInputStream(), 4096);
      check(command.waitFor() == 0, "junction fixture failed");
      try {
        invalid(() -> RuntimeSupport.safeRoot(alias));
      } finally {
        Files.delete(alias);
      }
      check(Files.exists(root.resolve("files/.harness-assets")), "junction test touched file root");
      pass("actual Windows junction alias is rejected, original root retained");
    }
    Properties changed = new Properties();
    changed.putAll(settings);
    changed.setProperty("retention.input.ttl.seconds", "60");
    FileStore different = new FileStore(store, config.mysql(), new FileConfig(changed));
    fail("FILE_POLICY_CONFLICT", different::policy);
    long stable = files.metadata(sameB.getString("inputId"), "input").getLong("expiresAt");
    files.close();
    changed.setProperty("cleanup.enabled", "false");
    files = new FileService(store, changed, identity);
    check(
        files.metadata(sameB.getString("inputId"), "input").getLong("expiresAt") == stable,
        "restart rewrote expiresAt");
    sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", sameB.getString("inputId"));
    files.scan();
    fail(
        "FILE_EXPIRED",
        () -> files.ledger.pin(T, S, sameB.getString("inputId"), "blocked", null, "download"));
    check(
        !files.metadata(sameB.getString("inputId"), "input").getString("state").equals("deleted"),
        "disabled cleanup deleted");
    invalid(() -> new FileConfig(new Properties()));
    Properties typo = new Properties();
    typo.putAll(settings);
    typo.setProperty("cleanup.typo", "1");
    invalid(() -> new FileConfig(typo));
    pass(
        "multi-owner fence and retry; incompatible live policies refused; restart preserves expiresAt; cleanup-disabled expiry still rejects access");
  }

  public static void main(String[] args) throws Exception {
    ExecutionTest.prepare(
        Files.createTempDirectory("harness-control-files-"),
        System.getProperty("control.test.config", ""));
    settings = new Properties();
    try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) {
      settings.load(in);
    }
    settings.setProperty("files.root", root.resolve("files").toString());
    settings.setProperty("files.root.id", "test-files-" + key());
    settings.setProperty("execution.repository", root.resolve("repository").toString());
    settings.setProperty("files.download.key", "test-only-download-key-01234567890123456789");
    settings.setProperty("cleanup.temp.grace.seconds", "0");
    Files.createDirectory(root.resolve("files"));
    files = new FileService(store, settings, identity);
    controls = new ControlStore(store, config.mysql()).files(files.ledger);
    profiles = new ProfileRegistry(catalog, files.ledger);
    try {
      controls();
      filesystem();
    } finally {
      files.close();
    }
    System.out.println("PASS: N11/N12 on " + config.dialect + " at " + root);
  }
}
