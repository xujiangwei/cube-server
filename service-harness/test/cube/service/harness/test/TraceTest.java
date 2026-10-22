package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.store.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/**
 * 诊断导出集成测试：验证 trace 导出的内容脱敏、身份隔离、回放投影完整性与追加反馈语义。
 *
 * <p>默认在本进程临时目录创建独立 SQLite 库；{@code trace.test.config} 可指向显式
 * disposable 测试库，与其余存储测试使用同一凭据守护约定。run、intent、result 与
 * feedback 全部灌入含敏感标记的内容，导出必须只保留结构、身份/版本摘要与状态，
 * 敏感原文一律替换为摘要；trace 关闭或 TTL 过期只拒绝导出，不破坏权威账本恢复。
 */
public final class TraceTest {
  /**
   * 测试入口：建库、迁移并造一个终态 run，随后逐项验证反馈回放/冲突、导出脱敏与
   * replayComplete、跨租户拒绝、trace 关闭与 TTL 过期行为，最后写出导出文件。
   *
   * @param args 未使用的命令行参数
   * @throws Exception 存储、迁移或文件读写失败
   */
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("harness-trace-test-");
    Properties p = new Properties();
    String file = System.getProperty("trace.test.config", "");
    if (file.isEmpty()) {
      // 未显式指定配置时使用本测试独占的临时 SQLite 库，不触碰任何既有部署库。
      p.setProperty("store.dialect", "sqlite");
      p.setProperty("store.database", "harness_trace");
      p.setProperty("store.identity", "trace-test-independent-identity");
      p.setProperty("store.sqlite.root", dir.toString());
      p.setProperty("store.sqlite.file", dir.resolve("harness_trace.db").toString());
    } else {
      try (java.io.InputStream in = Files.newInputStream(Paths.get(file))) {
        p.load(in);
      }
    }
    config = new StoreConfig(p);
    StoreMigrations.migrate(config, true);
    store = new JdbcRunStore(config);
    String r =
        store
            .create(T, S, key(), "test", new JSONObject().put("private", "secret-content-token"))
            .getString("runId");
    // 打开 trace 与 feedback 开关后造数：round intent/result 与终态 result 均带敏感标记。
    p.setProperty("trace.enabled", "true");
    p.setProperty("feedback.enabled", "true");
    TraceStore traces = new TraceStore(store, p);
    JdbcRunStore.Lease l = running(r, 60000);
    intent(l, "round", "round-1", new JSONObject().put("secret", "secret-content-token"));
    confirm(l, "round", "round-1", new JSONObject().put("text", "private-model-output"));
    store.finish(
        l,
        version(r),
        "succeeded",
        "confirmed",
        new JSONObject().put("answer", "private-answer"),
        new JSONArray());
    // 追加式反馈：同键同内容回放返回等价结果，同键异内容按幂等冲突拒绝。
    String k = key();
    JSONObject body = new JSONObject().put("rating", "negative").put("reason", "secret-feedback");
    check(
        traces
            .feedback(T, S, r, k, body)
            .toString()
            .equals(traces.feedback(T, S, r, k, body).toString()),
        "feedback replay");
    fail(
        "IDEMPOTENCY_CONFLICT",
        () -> traces.feedback(T, S, r, k, new JSONObject().put("rating", "positive")));
    // 导出限定 token 对应的租户与主体：跨租户查询按 run 不存在拒绝。
    fail("RUN_NOT_FOUND", () -> traces.export("other", S, r));
    JSONObject trace = traces.export(T, S, r);
    // 事件状态投影必须完整（replayComplete），且任何敏感原文都不得出现在导出中。
    check(trace.getBoolean("replayComplete"), "projection missing");
    check(
        !trace.toString().contains("secret-content-token")
            && !trace.toString().contains("private-model-output")
            && !trace.toString().contains("secret-feedback"),
        "trace leaked content");
    String output = System.getProperty("trace.test.output", "");
    if (output.isEmpty()) {
      output = dir.resolve("trace.json").toString();
    }
    Files.write(Paths.get(output), trace.toString().getBytes("UTF-8"));
    // 关闭 trace 只拒绝新导出，权威账本快照与恢复不受影响。
    p.setProperty("trace.enabled", "false");
    fail("TRACE_DISABLED", () -> new TraceStore(store, p).export(T, S, r));
    check(
        store.read(T, S, r).getJSONObject("snapshot").getString("state").equals("succeeded"),
        "trace disable broke recovery");
    // 直接推进 run 的 updated_at 使导出超过保留 TTL：过期只拒绝导出，不清除恢复事件。
    try (java.sql.Connection c = config.connect();
        java.sql.PreparedStatement s =
            c.prepareStatement("UPDATE harness_run SET updated_at=1 WHERE run_id=?")) {
      s.setString(1, r);
      s.executeUpdate();
    }
    fail("TRACE_EXPIRED", () -> traces.export(T, S, r));
    check(store.events(T, S, r, 0, 100).length() > 0, "expiry removed recovery events");
    System.out.println(
        "PASS: trace redaction, identity, append-only feedback/idempotency, disable/TTL preserve recovery; output="
            + output);
  }
}
