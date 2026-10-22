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

/**
 * 控制与文件集成测试：真实 JDBC、文件系统与协调器控制，不接入真实写入 provider。
 *
 * <p>controls() 覆盖控制面（wait/resume/approval/cancel）：持久化等待跨存储重启保留、
 * expectedVersion 前置校验与同键重放顺序、独立审批人与参数绑定、并发 resume/cancel
 * 单次推进、过期等待拒绝。filesystem() 覆盖文件面：持锁扫描返回 FILE_BUSY 且不阻塞
 * 控制事务、root 身份拒绝另一物理目录、上传/读取/产物 TTL、pin 与墓碑对账、删除提交
 * 边界恢复、Windows junction 别名、策略冲突与重启保持。全部场景跑在一次一测试库与
 * 临时文件根上，禁用真实模型与 Runner。
 */
public final class ControlFileTest {
  /** 文件服务实例；filesystem() 与 uploadText() 复用同一文件根与账本。 */
  static FileService files;

  /** 控制面存储（wait/resume/approval/cancel），与 files.ledger 绑定以验证控制与文件同库协作。 */
  static ControlStore controls;

  /** 从 store.local.properties 加载并按本测试覆写的运行配置。 */
  static Properties settings;

  /**
   * 在测试库上执行一条更新语句，用于制造 TTL 到期、墓碑、结算确认等夹具状态。
   *
   * @param query SQL 更新文本
   * @param args 预编译语句参数，按位置绑定
   * @throws Exception 连接或执行失败
   */
  static void sql(String query, Object... args) throws Exception {
    try (Connection c = config.connect();
        PreparedStatement p = c.prepareStatement(query)) {
      for (int i = 0; i < args.length; i++) {
        p.setObject(i + 1, args[i]);
      }
      p.executeUpdate();
    }
  }

  /**
   * 上传一段 UTF-8 文本并完成入库，返回 input 元数据。
   *
   * <p>同时验证同键重放返回同一 inputId、同键异文件名按幂等冲突拒绝。
   *
   * @param text 文本内容
   * @return complete 之后的 input JSON（含 inputId、sha256、expiresAt）
   * @throws Exception 上传或入库失败
   */
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

  /**
   * 以 task profile 创建一个带 instruction 与附件列表的 run。
   *
   * @param text instruction 文本
   * @param ids attachmentIds，只能绑定本主体 ready 且未到期的 input
   * @return 新建 run 的 runId
   */
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

  /**
   * 构造 resume 控制请求体，绑定 expectedVersion 与目标 waitId。
   *
   * @param r runId
   * @param wait 目标 waitId
   * @return resume 请求 JSON
   */
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

  /**
   * 控制面场景：持久化 wait 跨存储重启保留、版本前置校验与重放顺序、独立审批人与
   * 参数绑定、并发 resume/cancel 单次推进、过期等待拒绝。
   *
   * @throws Exception 存储或断言失败
   */
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
    // 用全新 ControlStore 重启控制面：等待问题必须从数据库恢复，重启不丢状态。
    ControlStore restarted =
        new ControlStore(new JdbcRunStore(config), config.mysql()).files(files.ledger);
    JSONObject wait = restarted.current(T, S, r);
    check(
        wait.getJSONObject("details").getString("question").equals("which input?"),
        "restart lost question");
    // waitId 错误时按等待不存在拒绝，不会误推进其它等待。
    fail(
        "WAIT_NOT_FOUND",
        () -> controls.apply(T, S, S, r, key(), "resume", "wrong", resume(r, "wrong"), false));
    // 同键同参重放返回等价回执：expectedVersion 在重放判定前已被校验。
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
    // 同键异参按幂等冲突拒绝，不能被当成重放放行。
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
    // resume 成功后等待按 binding 标记为已处理，且只产生一条 user 输入。
    check(
        controls.handled(r, "input", "round-1") && controls.inputs(T, S, r).length() == 1,
        "resume missing");
    pass(
        "persisted wait survives store restart; wait binding, expectedVersion and replay ordering enforced");

    // 审批场景：审批人必须独立（不能审批自己的任务），参数按 SHA256 绑定后不可篡改。
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
    // 同主体审批自己的任务被拒绝。
    fail(
        "APPROVER_FORBIDDEN",
        () -> controls.apply(T, S, S, approval, key(), "approval", w, approve, true));
    // 参数摘要与固定定义不一致时拒绝，防止审批后被篡改执行。
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
    // 独立审批人以正确摘要审批成功，并按 binding 标记已处理。
    controls.apply(T, S, "independent-reviewer", approval, key(), "approval", w, approve, true);
    check(controls.handled(approval, "approval", "fake-only"), "approval not bound");
    // 并发 resume 与 cancel 竞争同一版本：只允许一次推进，另一方按版本冲突拒绝。
    String race = create("control race", new JSONArray());
    JdbcRunStore.Lease rl = running(race, 60000);
    controls.waitFor(rl, version(race), "input", payload, controls.now() + 10000);
    store.release(rl, version(race));
    String rw = controls.current(T, S, race).getString("waitId");
    JSONObject rr = resume(race, rw);
    JSONObject cancel = new JSONObject().put("expectedVersion", version(race));
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
    // 过期等待：直接把 expires_at 置零，resume 必须被拒绝而不是放行。
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

  /**
   * 文件场景：持锁扫描返回 FILE_BUSY 且不阻塞控制事务、root 身份拒绝另一物理目录、
   * 上传/读取/产物 TTL、pin 与墓碑对账、删除提交边界恢复、Windows junction 别名、
   * 策略冲突与重启保持。
   *
   * @throws Exception 文件、存储或断言失败
   */
  static void filesystem() throws Exception {
    AssetFiles lockedRoot =
        new AssetFiles(
            root.resolve("files"),
            files.config.maxBytes,
            new String(
                Files.readAllBytes(root.resolve("files/.harness-assets")),
                java.nio.charset.StandardCharsets.UTF_8));
    // 持有文件根锁：期间扫描必须立即返回 FILE_BUSY，且不能阻塞控制事务推进。
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
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
      // 持锁期间 cancel 控制仍可受理，证明文件锁不会阻塞控制准入。
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
    // 同一 rootId 指向另一个物理目录时必须拒绝启动，且不读写原根数据。
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
    // 实际上传/完成/读取：默认 input TTL 为 7 天，重复 complete 不续期。
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
    // 已完成会话拒绝重复上传；跨租户账本查询按文件不存在拒绝。
    fail("FILE_NOT_FOUND", () -> files.ledger.get("other", S, id));
    fail(
        "FILE_UPLOAD_CLOSED",
        () -> files.uploadInput(id, new ByteArrayInputStream(bytes("hello 文件"))));
    // 绑定后可按原 run/call pin 恢复读取；到期文件禁止新 pin。
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
    // 消费确认后过期文件才被回收；过期 input 不能再绑定新 run。
    files.consumed(run, "read-1", id);
    files.scan();
    check(
        files.metadata(id, "input").getString("state").equals("deleted"),
        "expired input not reclaimed");
    fail("FILE_EXPIRED", () -> create("expired input", new JSONArray().put(id)));
    // artifact 写入：默认 TTL 30 天，同键重写返回同一 artifact，下载句柄仍需认证。
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
    // unknown 执行且无 pin 时到期 artifact 不得被删除；结算确认后才允许回收。
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
    // 同内容文件各自独立身份与 storageKey，不共享 blob；一方到期不影响另一方。
    JSONObject sameA = uploadText("same");
    JSONObject sameB = uploadText("same");
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
    // 多 owner claim/fence：旧 claim 过期后 fence 递增，失败退避内不得重复领取。
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
    // 失败记录退避后同一周期内不得立即再领。
    second.failed(next, "INJECTED_OCCUPIED");
    check(second.claim(cf) == null, "retry ignored backoff");
    second.close();
    // 删除提交边界：提交前失败回滚、提交后响应丢失按墓碑/缺失字节对账，均不重造数据。
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
                    && once.compareAndSet(true, false)) {
                  throw new RuntimeException("injected delete commit boundary");
                }
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
    // Windows 平台：真实 junction 别名必须被拒绝，且原文件根不受影响。
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
    // 策略冲突：与活跃多 JVM 策略不兼容的新策略拒绝加载；重启保持已存 expiresAt。
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
    // cleanup.enabled=false 只暂停物理删除，到期访问仍被拒绝。
    sql("UPDATE harness_file SET expires_at=0 WHERE file_id=?", sameB.getString("inputId"));
    files.scan();
    fail(
        "FILE_EXPIRED",
        () -> files.ledger.pin(T, S, sameB.getString("inputId"), "blocked", null, "download"));
    check(
        !files.metadata(sameB.getString("inputId"), "input").getString("state").equals("deleted"),
        "disabled cleanup deleted");
    // 配置校验：空配置与无法识别的键都拒绝构造 FileConfig。
    invalid(() -> new FileConfig(new Properties()));
    Properties typo = new Properties();
    typo.putAll(settings);
    typo.setProperty("cleanup.typo", "1");
    invalid(() -> new FileConfig(typo));
    pass(
        "multi-owner fence and retry; incompatible live policies refused; restart preserves expiresAt; cleanup-disabled expiry still rejects access");
  }

  /**
   * 测试入口：准备一次性运行根与 store 配置，装配 FileService、ControlStore 与
   * ProfileRegistry，依次执行 controls() 与 filesystem()，结束后关闭文件服务。
   *
   * @param args 未使用的命令行参数
   * @throws Exception 环境准备或场景执行失败
   */
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
    System.out.println("PASS: on " + config.dialect + " at " + root);
  }
}
