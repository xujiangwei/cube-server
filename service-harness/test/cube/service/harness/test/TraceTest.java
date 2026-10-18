package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.store.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class TraceTest {
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("harness-trace-test-");
    Properties p = new Properties();
    String file = System.getProperty("trace.test.config", "");
    if (file.isEmpty()) {
      p.setProperty("store.dialect", "sqlite");
      p.setProperty("store.database", "harness_trace");
      p.setProperty("store.identity", "trace-test-independent-identity");
      p.setProperty("store.sqlite.root", dir.toString());
      p.setProperty("store.sqlite.file", dir.resolve("harness_trace.db").toString());
    } else
      try (java.io.InputStream in = Files.newInputStream(Paths.get(file))) {
        p.load(in);
      }
    config = new StoreConfig(p);
    StoreMigrations.migrate(config, true);
    store = new JdbcRunStore(config);
    String r =
        store
            .create(T, S, key(), "test", new JSONObject().put("private", "secret-content-token"))
            .getString("runId");
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
    fail("RUN_NOT_FOUND", () -> traces.export("other", S, r));
    JSONObject trace = traces.export(T, S, r);
    check(trace.getBoolean("replayComplete"), "projection missing");
    check(
        !trace.toString().contains("secret-content-token")
            && !trace.toString().contains("private-model-output")
            && !trace.toString().contains("secret-feedback"),
        "trace leaked content");
    String output = System.getProperty("trace.test.output", "");
    if (output.isEmpty()) output = dir.resolve("trace.json").toString();
    Files.write(Paths.get(output), trace.toString().getBytes("UTF-8"));
    p.setProperty("trace.enabled", "false");
    fail("TRACE_DISABLED", () -> new TraceStore(store, p).export(T, S, r));
    check(
        store.read(T, S, r).getJSONObject("snapshot").getString("state").equals("succeeded"),
        "trace disable broke recovery");
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
