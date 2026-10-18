/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Authoritative Harness ledger. Every mutator owns a short database transaction; no network send,
 * model call or trace dependency is allowed inside it. All run access requires a trusted tenant AND
 * subject supplied by the service.
 */
public final class JdbcRunStore {
  private final StoreConfig config;
  private final Faults faults;
  private volatile int admissionLimit = Integer.MAX_VALUE;

  public void admissionLimit(int limit) {
    if (limit < 1 || limit > 256) throw new StoreException("INVALID_ADMISSION_LIMIT");
    admissionLimit = limit;
  }

  public interface Faults {
    void at(String boundary);
  }

  interface Work<T> {
    T run(Connection c) throws Exception;
  }

  public interface InboxEffect {
    void apply(Connection transaction) throws Exception;
  }

  public static final class Lease {
    public final String tenant, subject, runId, owner;
    public final long fence;

    public Lease(String tenant, String subject, String runId, String owner, long fence) {
      this.tenant = id(tenant);
      this.subject = id(subject);
      this.runId = id(runId);
      this.owner = id(owner);
      this.fence = fence;
    }
  }

  public JdbcRunStore(StoreConfig config) throws Exception {
    this(config, boundary -> {});
  }

  /** Fault hook is for isolated tests; production uses the constructor without a hook. */
  public JdbcRunStore(StoreConfig config, Faults faults) throws Exception {
    this.config = config;
    this.faults = faults;
    try (Connection c = config.connect()) {
      StoreMigrations.verify(c, config);
    }
  }

  <T> T tx(Work<T> work) {
    for (int attempt = 0; ; attempt++) {
      boolean committed = false, commitAttempted = false;
      try (Connection c = config.connect()) {
        StoreConfig.begin(c, config.mysql());
        try {
          T result = work.run(c);
          faults.at("beforeCommit");
          commitAttempted = true;
          StoreConfig.end(c, config.mysql(), true);
          committed = true;
          faults.at("afterCommit");
          return result;
        } catch (Exception e) {
          if (!committed)
            try {
              StoreConfig.end(c, config.mysql(), false);
            } catch (SQLException rollbackFailure) {
              e.addSuppressed(rollbackFailure);
            }
          throw e;
        }
      } catch (Exception e) {
        if (!commitAttempted
            && attempt < 5
            && e instanceof SQLException
            && retryable((SQLException) e)) {
          try {
            Thread.sleep(10L << attempt);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StoreException("STORE_INTERRUPTED", interrupted);
          }
          continue;
        }
        if (e instanceof StoreException) throw (StoreException) e;
        throw new StoreException(
            commitAttempted ? "STORE_COMMIT_OUTCOME_UNKNOWN" : "STORE_UNAVAILABLE", e);
      }
    }
  }

  private static boolean retryable(SQLException e) {
    return e.getErrorCode() == 5
        || e.getErrorCode() == 6
        || e.getErrorCode() == 1213
        || e.getErrorCode() == 1205
        || (e.getSQLState() != null && e.getSQLState().startsWith("23"));
  }

  static String id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}"))
      throw new StoreException("INVALID_STORE_ID");
    return value;
  }

  /** Semantic JSON digest: object key order and numeric lexical spelling do not affect identity. */
  public static String canonical(Object value) {
    if (value == null || value == JSONObject.NULL) return "null";
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      List<String> keys = new ArrayList<>(object.keySet());
      Collections.sort(keys);
      StringJoiner out = new StringJoiner(",", "{", "}");
      for (String key : keys) out.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
      return out.toString();
    }
    if (value instanceof JSONArray) {
      StringJoiner out = new StringJoiner(",", "[", "]");
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) out.add(canonical(array.get(i)));
      return out.toString();
    }
    if (value instanceof Number)
      return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    if (value instanceof Boolean) return value.toString();
    if (value instanceof String) return JSONObject.quote((String) value);
    throw new StoreException("INVALID_STORE_JSON");
  }

  public static String hash(Object value) {
    try {
      StringBuilder out = new StringBuilder();
      for (byte b :
          MessageDigest.getInstance("SHA-256")
              .digest(canonical(value).getBytes(StandardCharsets.UTF_8)))
        out.append(String.format("%02x", b & 255));
      return out.toString();
    } catch (Exception e) {
      throw new StoreException("INVALID_STORE_JSON", e);
    }
  }

  private static String json(Object value) {
    String text = canonical(value);
    if (text.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new StoreException("STORE_PAYLOAD_TOO_LARGE");
    return text;
  }

  private static PreparedStatement statement(Connection c, String sql, Object... args)
      throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
    return s;
  }

  static int update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = statement(c, sql, args)) {
      return s.executeUpdate();
    }
  }

  static JSONArray rows(Connection c, String sql, Object... args) throws SQLException {
    JSONArray out = new JSONArray();
    try (PreparedStatement s = statement(c, sql, args);
        ResultSet r = s.executeQuery()) {
      ResultSetMetaData meta = r.getMetaData();
      while (r.next()) {
        JSONObject row = new JSONObject();
        for (int i = 1; i <= meta.getColumnCount(); i++)
          row.put(
              meta.getColumnLabel(i), r.getObject(i) == null ? JSONObject.NULL : r.getObject(i));
        out.put(row);
      }
    }
    return out;
  }

  static JSONObject first(JSONArray rows) {
    return rows.length() == 0 ? null : rows.getJSONObject(0);
  }

  long now(Connection c) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery(
                config.mysql()
                    ? "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)"
                    : "SELECT CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)")) {
      r.next();
      return r.getLong(1);
    }
  }

  JSONObject run(Connection c, String tenant, String subject, String runId, boolean lock)
      throws SQLException {
    JSONObject run =
        first(
            rows(
                c,
                "SELECT * FROM harness_run WHERE tenant=? AND subject_id=? AND run_id=?"
                    + (lock && config.mysql() ? " FOR UPDATE" : ""),
                id(tenant),
                id(subject),
                id(runId)));
    if (run == null) throw new StoreException("RUN_NOT_FOUND");
    return run;
  }

  private static boolean terminal(String state) {
    return Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(state);
  }

  void owner(Connection c, JSONObject r, Lease lease) throws SQLException {
    if (!lease.owner.equals(r.optString("owner_id"))
        || lease.fence != r.getLong("fence")
        || r.getLong("lease_until") <= now(c)) throw new StoreException("STALE_OWNER");
  }

  static void version(JSONObject r, long expected) {
    if (r.getLong("version") != expected) throw new StoreException("VERSION_CONFLICT");
  }

  public static JSONObject snapshot(JSONObject row) {
    return new JSONObject()
        .put("runId", row.getString("run_id"))
        .put("version", row.getLong("version"))
        .put("eventSeq", row.getLong("event_seq"))
        .put("state", row.getString("state"))
        .put("executionSettlement", row.getString("execution_settlement"))
        .put("taskProfileVersion", row.getString("profile_version"));
  }

  /** Atomically advances the version, appends the event and records its send intent. */
  JSONObject emit(Connection c, JSONObject r, String type, JSONObject data, String topic)
      throws SQLException {
    long v = r.getLong("version"), seq = r.getLong("event_seq") + 1, time = now(c);
    int changed =
        update(
            c,
            "UPDATE harness_run SET version=?,event_seq=?,updated_at=? WHERE run_id=? AND tenant=? AND version=?",
            v + 1,
            seq,
            time,
            r.getString("run_id"),
            r.getString("tenant"),
            v);
    if (changed != 1) throw new StoreException("VERSION_CONFLICT");
    r.put("version", v + 1).put("event_seq", seq).put("updated_at", time);
    String payload = json(data);
    // An additive, content-free state projection supports offline replay. It is part of the
    // authoritative event, not an external trace sink; old events remain explicitly incomplete.
    JSONObject persisted =
        run(c, r.getString("tenant"), r.getString("subject_id"), r.getString("run_id"), false);
    JSONObject projection =
        snapshot(persisted).put("usageSettlement", persisted.getString("usage_settlement"));
    update(
        c,
        "INSERT INTO harness_event (run_id,event_seq,tenant,run_version,event_type,occurred_at,schema_version,data_json,projection_json) VALUES (?,?,?,?,?,?,?,?,?)",
        r.getString("run_id"),
        seq,
        r.getString("tenant"),
        v + 1,
        id(type),
        time,
        1,
        payload,
        canonical(projection));
    // Explicit columns keep the schema's event representation unambiguous.
    JSONObject event =
        new JSONObject()
            .put("runId", r.getString("run_id"))
            .put("eventSeq", seq)
            .put("runVersion", v + 1)
            .put("type", type)
            .put("occurredAt", time)
            .put("schemaVersion", 1)
            .put("dataJson", payload);
    update(
        c,
        "INSERT INTO harness_outbox VALUES (?,?,?,?,?,?,0,?,NULL,0,0,0)",
        hash(new JSONArray().put(r.getString("run_id")).put(seq)),
        r.getString("run_id"),
        r.getString("tenant"),
        seq,
        id(topic),
        json(event),
        time);
    return snapshot(r);
  }

  private String requestKey(
      String tenant, String subject, String operation, String target, String key) {
    return hash(
        new JSONArray()
            .put(id(tenant))
            .put(id(subject))
            .put(id(operation))
            .put(id(target))
            .put(id(key)));
  }

  private JSONObject replay(Connection c, String key, String digest) throws SQLException {
    JSONObject old = first(rows(c, "SELECT * FROM harness_request WHERE request_hash=?", key));
    if (old == null) return null;
    if (!digest.equals(old.getString("payload_hash")))
      throw new StoreException("IDEMPOTENCY_CONFLICT");
    return new JSONObject(old.getString("response_json"));
  }

  private void receipt(
      Connection c,
      String key,
      String tenant,
      String subject,
      String op,
      String target,
      String digest,
      JSONObject response)
      throws SQLException {
    // No pruning in N03: receipts survive for the entire run lifetime and at least 24h.
    update(
        c,
        "INSERT INTO harness_request VALUES (?,?,?,?,?,?,?,?)",
        key,
        tenant,
        subject,
        op,
        target,
        digest,
        json(response),
        now(c) + 86400000L);
  }

  public JSONObject create(
      String tenant, String subject, String key, String profileVersion, JSONObject request) {
    return createResolved(tenant, subject, key, profileVersion, request, null);
  }

  public JSONObject findCreate(
      String tenant, String subject, String key, String profileVersion, JSONObject request) {
    String k = requestKey(tenant, subject, "create", "runs", key);
    String digest =
        hash(new JSONObject().put("profileVersion", profileVersion).put("request", request));
    return tx(c -> replay(c, k, digest));
  }

  interface ConfigResolver {
    JSONObject resolve(java.sql.Connection connection) throws Exception;
  }

  JSONObject createResolved(
      String tenant,
      String subject,
      String key,
      String profileVersion,
      JSONObject request,
      ConfigResolver resolver) {
    id(profileVersion);
    String k = requestKey(tenant, subject, "create", "runs", key);
    String digest =
        hash(new JSONObject().put("profileVersion", profileVersion).put("request", request));
    return tx(
        c -> {
          // A store identity row is the admission mutex across service replicas. Replay remains
          // legal when full.
          if (resolver != null && config.mysql())
            rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1 FOR UPDATE");
          JSONObject old = replay(c, k, digest);
          if (old != null) return old;
          if (resolver != null
              && first(
                          rows(
                              c,
                              "SELECT COUNT(*) AS n FROM harness_run WHERE tenant=? AND state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')",
                              tenant))
                      .getLong("n")
                  >= admissionLimit) throw new StoreException("HARNESS_BUSY");
          String runId = UUID.randomUUID().toString();
          long time = now(c);
          JSONObject pinned = resolver == null ? null : resolver.resolve(c);
          update(
              c,
              "INSERT INTO harness_run VALUES (?,?,?,?,?,0,0,NULL,0,0,?,?,?,?)",
              runId,
              tenant,
              subject,
              pinned == null ? profileVersion : pinned.getJSONObject("task").getString("version"),
              "queued",
              "pending",
              "settled",
              time,
              time);
          if (pinned != null) {
            update(
                c,
                "INSERT INTO harness_run_config VALUES (?,?,?,?,?)",
                runId,
                tenant,
                subject,
                hash(pinned),
                canonical(pinned));
            JSONArray releases = pinned.getJSONArray("releases");
            for (int i = 0; i < releases.length(); i++)
              update(
                  c,
                  "INSERT INTO harness_release_ref VALUES (?,?,?)",
                  tenant,
                  releases.getJSONObject(i).getString("digest"),
                  runId);
            JSONArray attachments = request.getJSONObject("input").getJSONArray("attachmentIds");
            for (int i = 0; i < attachments.length(); i++)
              update(
                  c,
                  "INSERT INTO harness_file_binding VALUES (?,?)",
                  runId,
                  attachments.getString(i));
          }
          JSONObject response =
              emit(c, run(c, tenant, subject, runId, true), "run.created", request, "coordinator");
          receipt(c, k, tenant, subject, "create", "runs", digest, response);
          return response;
        });
  }

  /** One transaction provides a consistent state/result/usage snapshot. */
  public JSONObject read(String tenant, String subject, String runId) {
    return tx(
        c -> {
          JSONObject r = run(c, tenant, subject, runId, true);
          JSONObject result =
              first(
                  rows(
                      c,
                      "SELECT result_json FROM harness_result WHERE run_id=? AND tenant=?",
                      runId,
                      tenant));
          JSONObject usage =
              first(
                  rows(
                      c,
                      "SELECT COALESCE(SUM(estimated_input),0) AS estimatedInput,"
                          + "COALESCE(SUM(estimated_output),0) AS estimatedOutput,COALESCE(SUM(actual_input),0) AS actualInput,"
                          + "COALESCE(SUM(actual_output),0) AS actualOutput, SUM(CASE WHEN actual_input IS NULL THEN 1 ELSE 0 END) AS pending"
                          + " FROM harness_usage WHERE run_id=? AND tenant=?",
                      runId,
                      tenant));
          return new JSONObject()
              .put("snapshot", snapshot(r))
              .put("owner", r.opt("owner_id"))
              .put("fence", r.getLong("fence"))
              .put("leaseUntil", r.getLong("lease_until"))
              .put("usageSettlement", r.getString("usage_settlement"))
              .put("usage", usage)
              .put(
                  "result",
                  result == null
                      ? JSONObject.NULL
                      : new JSONObject(result.getString("result_json")));
        });
  }

  public Lease acquire(
      String tenant,
      String subject,
      String runId,
      String owner,
      long expectedVersion,
      long duration) {
    id(owner);
    if (duration < 100 || duration > 60000) throw new StoreException("INVALID_LEASE_DURATION");
    return tx(
        c -> {
          JSONObject r = run(c, tenant, subject, runId, true);
          version(r, expectedVersion);
          long time = now(c);
          if (r.getLong("lease_until") > time) throw new StoreException("LEASE_BUSY");
          long fence = r.getLong("fence") + 1;
          if (update(
                  c,
                  "UPDATE harness_run SET owner_id=?,fence=?,lease_until=? WHERE run_id=? AND version=? AND lease_until<=?",
                  owner,
                  fence,
                  time + duration,
                  runId,
                  expectedVersion,
                  time)
              != 1) throw new StoreException("LEASE_BUSY");
          update(
              c,
              "INSERT INTO harness_lease VALUES (?,?,?,?,?)",
              runId,
              fence,
              owner,
              time,
              time + duration);
          r.put("owner_id", owner).put("fence", fence).put("lease_until", time + duration);
          emit(c, r, "lease.acquired", new JSONObject().put("fence", fence), "coordinator");
          return new Lease(tenant, subject, runId, owner, fence);
        });
  }

  public void renew(Lease lease, long duration) {
    if (duration < 100 || duration > 60000) throw new StoreException("INVALID_LEASE_DURATION");
    tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          long until = now(c) + duration;
          if (update(
                  c,
                  "UPDATE harness_run SET lease_until=? WHERE run_id=? AND owner_id=? AND fence=? AND lease_until>?",
                  until,
                  lease.runId,
                  lease.owner,
                  lease.fence,
                  now(c))
              != 1) throw new StoreException("STALE_OWNER");
          update(
              c,
              "UPDATE harness_lease SET expires_at=? WHERE run_id=? AND fence=?",
              until,
              lease.runId,
              lease.fence);
          return null;
        });
  }

  public JSONObject release(Lease lease, long expectedVersion) {
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          version(r, expectedVersion);
          update(
              c,
              "UPDATE harness_run SET lease_until=0 WHERE run_id=? AND fence=?",
              lease.runId,
              lease.fence);
          update(
              c,
              "UPDATE harness_lease SET expires_at=? WHERE run_id=? AND fence=?",
              now(c),
              lease.runId,
              lease.fence);
          return emit(c, r, "lease.released", new JSONObject(), "coordinator");
        });
  }

  public JSONObject transition(
      Lease lease, long expectedVersion, String state, JSONObject checkpoint) {
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          version(r, expectedVersion);
          String from = r.getString("state");
          boolean valid =
              (from.equals("queued") && Arrays.asList("running", "cancelling").contains(state))
                  || (from.equals("running")
                      && Arrays.asList(
                              "running",
                              "waiting_input",
                              "waiting_approval",
                              "reconciling",
                              "cancelling")
                          .contains(state))
                  || (Arrays.asList("waiting_input", "waiting_approval").contains(from)
                      && Arrays.asList("running", "cancelling").contains(state))
                  || (from.equals("reconciling")
                      && Arrays.asList("running", "reconciling", "cancelling").contains(state))
                  || (from.equals("cancelling")
                      && Arrays.asList("cancelling", "reconciling").contains(state));
          if (!valid) throw new StoreException("INVALID_STATE_TRANSITION");
          update(c, "UPDATE harness_run SET state=? WHERE run_id=?", state, lease.runId);
          r.put("state", state);
          return emit(c, r, "run." + state, checkpoint, "coordinator");
        });
  }

  /** Durable command receipt only; N11 supplies policy/wait/approval semantics and execution. */
  public JSONObject submitControl(
      String tenant,
      String subject,
      String runId,
      String key,
      String operation,
      long expectedVersion,
      JSONObject payload) {
    if (!Arrays.asList("cancel", "resume", "approval", "feedback").contains(operation))
      throw new StoreException("INVALID_CONTROL");
    String k = requestKey(tenant, subject, operation, runId, key);
    String digest =
        hash(new JSONObject().put("expectedVersion", expectedVersion).put("payload", payload));
    return tx(
        c -> {
          JSONObject r = run(c, tenant, subject, runId, true);
          JSONObject old = replay(c, k, digest);
          if (old != null) return old;
          version(r, expectedVersion);
          if (terminal(r.getString("state"))) throw new StoreException("RUN_TERMINAL");
          JSONObject response =
              emit(
                  c,
                  r,
                  "control.requested",
                  new JSONObject()
                      .put("operation", operation)
                      .put("commandId", k)
                      .put("payload", payload),
                  "control");
          receipt(c, k, tenant, subject, operation, runId, digest, response);
          return response;
        });
  }

  private static String table(String kind) {
    if (kind.equals("round")) return "harness_round";
    if (kind.equals("call")) return "harness_tool_call";
    throw new StoreException("INVALID_INTENT_KIND");
  }

  private static void attempt(int attempt) {
    if (attempt < 1 || attempt > 1000000) throw new StoreException("INVALID_ATTEMPT");
  }

  public JSONObject intent(
      Lease lease,
      long expectedVersion,
      String kind,
      String intentId,
      int attempt,
      JSONObject payload,
      long estimatedInput,
      long estimatedOutput) {
    String table = table(kind);
    id(intentId);
    attempt(attempt);
    nonnegative(estimatedInput, estimatedOutput);
    String digest =
        hash(
            new JSONObject()
                .put("payload", payload)
                .put("estimatedInput", estimatedInput)
                .put("estimatedOutput", estimatedOutput));
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          JSONObject old =
              first(
                  rows(
                      c,
                      "SELECT intent_hash FROM "
                          + table
                          + " WHERE run_id=? AND intent_id=? AND attempt=?",
                      lease.runId,
                      intentId,
                      attempt));
          if (old != null) {
            if (!old.getString("intent_hash").equals(digest))
              throw new StoreException("INTENT_CONFLICT");
            return snapshot(r);
          }
          version(r, expectedVersion);
          if (!r.getString("state").equals("running"))
            throw new StoreException("INVALID_STATE_TRANSITION");
          // An unknown earlier attempt must be reconciled before any retry is authorized.
          JSONObject pending =
              first(
                  rows(
                      c,
                      "SELECT attempt FROM "
                          + table
                          + " WHERE run_id=? AND intent_id=? AND status<>'confirmed'",
                      lease.runId,
                      intentId));
          if (pending != null) throw new StoreException("INTENT_RECONCILIATION_REQUIRED");
          update(
              c,
              "INSERT INTO " + table + " VALUES (?,?,?,?,?,?,?,'pending',NULL,NULL)",
              lease.runId,
              intentId,
              attempt,
              lease.tenant,
              digest,
              json(payload),
              lease.fence);
          if (kind.equals("round")) {
            update(
                c,
                "INSERT INTO harness_usage VALUES (?,?,?,?,?,?,NULL,NULL,0)",
                lease.runId,
                intentId,
                attempt,
                lease.tenant,
                estimatedInput,
                estimatedOutput);
            update(
                c,
                "UPDATE harness_run SET usage_settlement='provisional' WHERE run_id=?",
                lease.runId);
          }
          update(
              c,
              "UPDATE harness_run SET execution_settlement='pending' WHERE run_id=?",
              lease.runId);
          r.put("execution_settlement", "pending");
          return emit(
              c,
              r,
              kind + ".intent",
              new JSONObject()
                  .put("intentId", intentId)
                  .put("attempt", attempt)
                  .put("intentHash", digest)
                  .put("fencingToken", lease.fence)
                  .put("payload", payload),
              kind.equals("round") ? "model" : "runner");
        });
  }

  /**
   * Current owner verifies historical intent/attempt evidence; old callbacks never advance the run.
   */
  public JSONObject confirmIntent(
      Lease lease,
      long expectedVersion,
      String kind,
      String intentId,
      int attempt,
      long originFence,
      String intentHash,
      JSONObject result) {
    String table = table(kind);
    id(intentId);
    attempt(attempt);
    String resultHash = hash(result);
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          JSONObject intent =
              first(
                  rows(
                      c,
                      "SELECT * FROM " + table + " WHERE run_id=? AND intent_id=? AND attempt=?",
                      lease.runId,
                      intentId,
                      attempt));
          if (intent == null
              || intent.getLong("origin_fence") != originFence
              || !intent.getString("intent_hash").equals(intentHash))
            throw new StoreException("INTENT_EVIDENCE_MISMATCH");
          if (intent.getString("status").equals("confirmed")) {
            if (!resultHash.equals(intent.getString("result_hash")))
              throw new StoreException("RESULT_CONFLICT");
            return snapshot(r);
          }
          version(r, expectedVersion);
          update(
              c,
              "UPDATE "
                  + table
                  + " SET status='confirmed',result_json=?,result_hash=? WHERE run_id=? AND intent_id=? AND attempt=?",
              json(result),
              resultHash,
              lease.runId,
              intentId,
              attempt);
          String settlement = outstanding(c, lease.runId) ? "pending" : "confirmed";
          update(
              c,
              "UPDATE harness_run SET execution_settlement=? WHERE run_id=?",
              settlement,
              lease.runId);
          r.put("execution_settlement", settlement);
          return emit(
              c,
              r,
              kind + ".confirmed",
              new JSONObject()
                  .put("intentId", intentId)
                  .put("attempt", attempt)
                  .put("resultHash", resultHash)
                  .put("originFence", originFence),
              "coordinator");
        });
  }

  private boolean outstanding(Connection c, String runId) throws SQLException {
    return first(
                rows(
                    c,
                    "SELECT intent_id FROM harness_round WHERE run_id=? AND status<>'confirmed'",
                    runId))
            != null
        || first(
                rows(
                    c,
                    "SELECT intent_id FROM harness_tool_call WHERE run_id=? AND status<>'confirmed'",
                    runId))
            != null;
  }

  public JSONArray intents(String tenant, String subject, String runId, String kind) {
    String table = table(kind);
    return tx(
        c -> {
          run(c, tenant, subject, runId, true);
          return rows(
              c,
              "SELECT * FROM " + table + " WHERE run_id=? AND tenant=? ORDER BY intent_id,attempt",
              runId,
              tenant);
        });
  }

  private static void nonnegative(long... values) {
    for (long value : values)
      if (value < 0 || value > 1000000000000L) throw new StoreException("INVALID_USAGE");
  }

  private static long whole(JSONObject object, String key) {
    try {
      Object value = object.get(key);
      if (!(value instanceof Number)) throw new ArithmeticException();
      return new BigDecimal(value.toString()).longValueExact();
    } catch (RuntimeException e) {
      throw new StoreException("INVALID_USAGE");
    }
  }

  private boolean usage(Connection c, Lease lease, JSONObject evidence) throws SQLException {
    String round = id(evidence.getString("roundId")), key = id(evidence.getString("evidenceId"));
    long rawAttempt = whole(evidence, "attempt");
    if (rawAttempt < 1 || rawAttempt > 1000000) throw new StoreException("INVALID_ATTEMPT");
    int attempt = (int) rawAttempt;
    long input = whole(evidence, "input"),
        output = whole(evidence, "output"),
        revision = whole(evidence, "expectedRevision");
    nonnegative(input, output, revision);
    String digest = hash(evidence);
    JSONObject old =
        first(
            rows(
                c,
                "SELECT evidence_hash FROM harness_usage_adjustment WHERE run_id=? AND round_id=? AND attempt=? AND evidence_id=?",
                lease.runId,
                round,
                attempt,
                key));
    if (old != null) {
      if (!digest.equals(old.getString("evidence_hash")))
        throw new StoreException("USAGE_EVIDENCE_CONFLICT");
      return false;
    }
    JSONObject intent =
        first(
            rows(
                c,
                "SELECT status FROM harness_round WHERE run_id=? AND intent_id=? AND attempt=?",
                lease.runId,
                round,
                attempt));
    if (intent == null || !intent.getString("status").equals("confirmed"))
      throw new StoreException("USAGE_INTENT_UNCONFIRMED");
    if (update(
            c,
            "UPDATE harness_usage SET actual_input=?,actual_output=?,revision=revision+1 WHERE run_id=? AND tenant=? AND round_id=? AND attempt=? AND revision=?",
            input,
            output,
            lease.runId,
            lease.tenant,
            round,
            attempt,
            revision)
        != 1) throw new StoreException("USAGE_VERSION_CONFLICT");
    update(
        c,
        "INSERT INTO harness_usage_adjustment VALUES (?,?,?,?,?,?,?,?,?)",
        lease.runId,
        round,
        attempt,
        key,
        digest,
        revision,
        input,
        output,
        now(c));
    String settlement =
        first(
                    rows(
                        c,
                        "SELECT round_id FROM harness_usage WHERE run_id=? AND actual_input IS NULL",
                        lease.runId))
                == null
            ? "settled"
            : "provisional";
    update(c, "UPDATE harness_run SET usage_settlement=? WHERE run_id=?", settlement, lease.runId);
    return true;
  }

  /** Late actual usage replaces that attempt's total; a revision prevents stale corrections. */
  public JSONObject settleUsage(Lease lease, long expectedVersion, JSONObject evidence) {
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          boolean changed = usage(c, lease, evidence);
          if (!changed) return snapshot(r);
          version(r, expectedVersion);
          return emit(
              c,
              r,
              "usage.settled",
              new JSONObject()
                  .put("evidenceId", evidence.getString("evidenceId"))
                  .put("roundId", evidence.getString("roundId"))
                  .put("attempt", evidence.getInt("attempt")),
              "usage");
        });
  }

  /** Final result, optional actual usage, version, event and Outbox commit together. */
  public JSONObject finish(
      Lease lease,
      long expectedVersion,
      String state,
      String executionSettlement,
      JSONObject result,
      JSONArray actualUsage) {
    if (!terminal(state)
        || !Arrays.asList("confirmed", "unknown").contains(executionSettlement)
        || (state.equals("succeeded") && !executionSettlement.equals("confirmed")))
      throw new StoreException("INVALID_FINAL");
    JSONObject envelope =
        new JSONObject()
            .put("state", state)
            .put("executionSettlement", executionSettlement)
            .put("result", result)
            .put("usage", actualUsage);
    String digest = hash(envelope);
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          JSONObject old =
              first(rows(c, "SELECT * FROM harness_result WHERE run_id=?", lease.runId));
          if (old != null) {
            if (!digest.equals(old.getString("result_hash")))
              throw new StoreException("FINAL_CONFLICT");
            return new JSONObject(old.getString("result_json"));
          }
          version(r, expectedVersion);
          if (executionSettlement.equals("confirmed") && outstanding(c, lease.runId))
            throw new StoreException("EXECUTION_UNSETTLED");
          for (int i = 0; i < actualUsage.length(); i++)
            usage(c, lease, actualUsage.getJSONObject(i));
          update(
              c,
              "INSERT INTO harness_result VALUES (?,?,?,?,?,?)",
              lease.runId,
              lease.tenant,
              state,
              json(envelope),
              digest,
              now(c));
          update(
              c,
              "UPDATE harness_run SET state=?,execution_settlement=? WHERE run_id=?",
              state,
              executionSettlement,
              lease.runId);
          update(
              c,
              "UPDATE harness_wait SET status='expired' WHERE run_id=? AND status='pending'",
              lease.runId);
          r.put("state", state).put("execution_settlement", executionSettlement);
          emit(c, r, "run." + state, new JSONObject().put("resultHash", digest), "result");
          return new JSONObject(json(envelope));
        });
  }

  /** Records a compensation/reconciliation obligation, never repeats an external effect itself. */
  public JSONObject requestCompensation(
      Lease lease,
      long expectedVersion,
      String key,
      String kind,
      String intentId,
      int attempt,
      String intentHash,
      JSONObject obligation) {
    String table = table(kind);
    id(intentId);
    attempt(attempt);
    String k = requestKey(lease.tenant, lease.subject, "compensate", lease.runId, key);
    JSONObject payload =
        new JSONObject()
            .put("kind", kind)
            .put("intentId", intentId)
            .put("attempt", attempt)
            .put("intentHash", intentHash)
            .put("obligation", obligation);
    String digest = hash(payload);
    return tx(
        c -> {
          JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
          owner(c, r, lease);
          JSONObject old = replay(c, k, digest);
          if (old != null) return old;
          version(r, expectedVersion);
          JSONObject intent =
              first(
                  rows(
                      c,
                      "SELECT intent_hash FROM "
                          + table
                          + " WHERE run_id=? AND intent_id=? AND attempt=?",
                      lease.runId,
                      intentId,
                      attempt));
          if (intent == null || !intentHash.equals(intent.getString("intent_hash")))
            throw new StoreException("INTENT_EVIDENCE_MISMATCH");
          JSONObject response = emit(c, r, "compensation.requested", payload, "compensation");
          receipt(c, k, lease.tenant, lease.subject, "compensate", lease.runId, digest, response);
          return response;
        });
  }

  /** Checkpoint comes from durable run/intents/commands; trace is deliberately absent. */
  public JSONArray recover(String tenant, String afterRunId, int limit) {
    page(limit);
    id(tenant);
    if (!afterRunId.isEmpty()) id(afterRunId);
    return tx(
        c ->
            rows(
                c,
                "SELECT run_id,subject_id,version,state,fence,lease_until FROM harness_run WHERE tenant=? AND run_id>? AND lease_until<=?"
                    + " AND (state NOT IN ('succeeded','failed','cancelled','timed_out') OR execution_settlement<>'confirmed' OR usage_settlement<>'settled') ORDER BY run_id LIMIT ?",
                tenant,
                afterRunId,
                now(c),
                limit));
  }

  private static void page(int limit) {
    if (limit < 1 || limit > 500) throw new StoreException("INVALID_PAGE_SIZE");
  }

  public JSONArray events(String tenant, String subject, String runId, long after, int limit) {
    page(limit);
    if (after < 0) throw new StoreException("INVALID_EVENT_CURSOR");
    return tx(
        c -> {
          JSONObject r = run(c, tenant, subject, runId, true);
          if (after > r.getLong("event_seq")) throw new StoreException("INVALID_EVENT_CURSOR");
          JSONArray found =
              rows(
                  c,
                  "SELECT * FROM harness_event WHERE run_id=? AND tenant=? AND event_seq>? ORDER BY event_seq LIMIT ?",
                  runId,
                  tenant,
                  after,
                  limit);
          if (after < r.getLong("event_seq")
              && (found.length() == 0 || found.getJSONObject(0).getLong("event_seq") != after + 1))
            throw new StoreException("EVENT_GAP");
          JSONArray out = new JSONArray();
          for (int i = 0; i < found.length(); i++) {
            JSONObject e = found.getJSONObject(i);
            if (e.getLong("event_seq") != after + i + 1) throw new StoreException("EVENT_GAP");
            out.put(
                new JSONObject()
                    .put("runId", runId)
                    .put("eventSeq", e.getLong("event_seq"))
                    .put("runVersion", e.getLong("run_version"))
                    .put("type", e.getString("event_type"))
                    .put("occurredAt", e.getLong("occurred_at"))
                    .put("schemaVersion", e.getInt("schema_version"))
                    .put("dataJson", e.getString("data_json")));
          }
          return out;
        });
  }

  /** Global dispatch is for the trusted internal dispatcher; messages retain tenant boundaries. */
  public JSONArray claimOutbox(String dispatcher, int limit, long duration) {
    id(dispatcher);
    page(limit);
    if (duration < 100 || duration > 60000) throw new StoreException("INVALID_LEASE_DURATION");
    return tx(
        c -> {
          long time = now(c);
          JSONArray candidates =
              rows(
                  c,
                  "SELECT * FROM harness_outbox WHERE delivered=0 AND available_at<=? AND claim_until<=? ORDER BY available_at,message_id LIMIT ?"
                      + (config.mysql() ? " FOR UPDATE SKIP LOCKED" : ""),
                  time,
                  time,
                  limit);
          JSONArray claimed = new JSONArray();
          for (int i = 0; i < candidates.length(); i++) {
            JSONObject m = candidates.getJSONObject(i);
            long fence = m.getLong("claim_fence") + 1;
            if (update(
                    c,
                    "UPDATE harness_outbox SET claim_owner=?,claim_fence=?,claim_until=?,attempts=attempts+1 WHERE message_id=? AND delivered=0 AND claim_until<=? AND claim_fence=?",
                    dispatcher,
                    fence,
                    time + duration,
                    m.getString("message_id"),
                    time,
                    fence - 1)
                == 1)
              claimed.put(
                  m.put("claim_owner", dispatcher)
                      .put("claim_fence", fence)
                      .put("claim_until", time + duration)
                      .put("attempts", m.getInt("attempts") + 1));
          }
          return claimed;
        });
  }

  /**
   * Call only after the receiver committed its Inbox/effect. A lost ACK deliberately redelivers.
   */
  public void ackOutbox(String dispatcher, String messageId, long fence) {
    id(dispatcher);
    id(messageId);
    tx(
        c -> {
          if (update(
                  c,
                  "UPDATE harness_outbox SET delivered=1 WHERE message_id=? AND delivered=0 AND claim_owner=? AND claim_fence=? AND claim_until>?",
                  messageId,
                  dispatcher,
                  fence,
                  now(c))
              != 1) throw new StoreException("STALE_OUTBOX_CLAIM");
          return null;
        });
  }

  public void retryOutbox(String dispatcher, String messageId, long fence, long delayMillis) {
    id(dispatcher);
    id(messageId);
    if (delayMillis < 0 || delayMillis > 60000) throw new StoreException("INVALID_RETRY_DELAY");
    tx(
        c -> {
          long time = now(c);
          if (update(
                  c,
                  "UPDATE harness_outbox SET claim_until=0,available_at=? WHERE message_id=? AND delivered=0 AND claim_owner=? AND claim_fence=? AND claim_until>?",
                  time + delayMillis,
                  messageId,
                  dispatcher,
                  fence,
                  time)
              != 1) throw new StoreException("STALE_OUTBOX_CLAIM");
          return null;
        });
  }

  /**
   * Inbox and a same-database receiver effect share one transaction. External effects require a
   * journal.
   */
  public boolean consume(String tenant, String consumer, String messageId, InboxEffect effect) {
    id(tenant);
    id(consumer);
    id(messageId);
    return tx(
        c -> {
          JSONObject m =
              first(
                  rows(
                      c,
                      "SELECT payload_json FROM harness_outbox WHERE tenant=? AND message_id=?"
                          + (config.mysql() ? " FOR UPDATE" : ""),
                      tenant,
                      messageId));
          if (m == null) throw new StoreException("MESSAGE_NOT_FOUND");
          if (first(
                  rows(
                      c,
                      "SELECT message_id FROM harness_inbox WHERE consumer_id=? AND message_id=?",
                      consumer,
                      messageId))
              != null) return false;
          effect.apply(c);
          update(
              c,
              "INSERT INTO harness_inbox VALUES (?,?,?,?,?)",
              consumer,
              messageId,
              tenant,
              hash(m.getString("payload_json")),
              now(c));
          return true;
        });
  }

  /**
   * One bounded dispatch pass. The sender must ACK only a durable, deduplicated receiver result.
   */
  public interface Sender {
    void send(JSONObject message) throws Exception;
  }

  public int dispatch(String dispatcher, int limit, Sender sender) {
    JSONArray batch = claimOutbox(dispatcher, limit, 30000);
    int sent = 0;
    for (int i = 0; i < batch.length(); i++) {
      JSONObject m = batch.getJSONObject(i);
      try {
        sender.send(m);
        ackOutbox(dispatcher, m.getString("message_id"), m.getLong("claim_fence"));
        sent++;
      } catch (Exception failure) {
        try {
          retryOutbox(dispatcher, m.getString("message_id"), m.getLong("claim_fence"), 1000);
        } catch (StoreException stale) {
          /* Another dispatcher will recover an expired claim. */
        }
      }
    }
    return sent;
  }
}
