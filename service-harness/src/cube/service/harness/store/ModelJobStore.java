/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import static cube.service.harness.store.JdbcRunStore.*;

import org.json.JSONArray;
import org.json.JSONObject;

/** Durable admission and physical backend reservations. Transport expiry never releases a slot. */
public final class ModelJobStore {
  private final JdbcRunStore store;
  private final String lock;

  public ModelJobStore(JdbcRunStore store, boolean mysql) {
    this.store = store;
    this.lock = mysql ? " FOR UPDATE" : "";
  }

  public void backend(String backend, JSONObject descriptor, int capacity) {
    store.tx(
        c -> {
          JSONObject old =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_model_backend WHERE backend_id=?" + lock,
                      id(backend)));
          String digest = hash(descriptor);
          boolean gateway = descriptor.has("type");
          if (old == null)
            update(
                c,
                "INSERT INTO harness_model_backend (backend_id,descriptor_hash,capacity,routing_hash) VALUES (?,?,?,?)",
                backend,
                digest,
                capacity,
                gateway ? digest : null);
          else {
            if (old.getInt("capacity") != capacity)
              throw new StoreException("BACKEND_IDENTITY_CONFLICT");
            if (gateway) {
              // Preserve the frozen v2 worker identity for in-flight drain/reconciliation.
              if (old.isNull("routing_hash"))
                update(
                    c,
                    "UPDATE harness_model_backend SET routing_hash=? WHERE backend_id=?",
                    digest,
                    backend);
              else if (!old.getString("routing_hash").equals(digest))
                throw new StoreException("BACKEND_IDENTITY_CONFLICT");
            } else if (!old.getString("descriptor_hash").equals(digest))
              throw new StoreException("BACKEND_IDENTITY_CONFLICT");
          }
          return null;
        });
  }

  /** Reconstructible from the already committed intent after a crash at the enqueue boundary. */
  public JSONObject enqueue(Lease lease, JSONObject intent, JSONObject model) {
    return store.tx(
        c -> {
          JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
          store.owner(c, r, lease);
          String round = intent.getString("intent_id");
          int attempt = intent.getInt("attempt");
          JSONObject authoritative =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_round WHERE run_id=? AND intent_id=? AND attempt=?",
                      lease.runId,
                      round,
                      attempt));
          if (authoritative == null || !canonical(authoritative).equals(canonical(intent)))
            throw new StoreException("INTENT_EVIDENCE_MISMATCH");
          String job = hash(new JSONArray().put(lease.runId).put(round).put(attempt));
          JSONObject old = first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?", job));
          if (old != null) return old;
          JSONObject payload = new JSONObject(intent.getString("payload_json"));
          String request = canonical(payload.getJSONObject("request"));
          if (request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48000)
            throw new StoreException("MODEL_REQUEST_TOO_LARGE");
          update(
              c,
              "INSERT INTO harness_model_job (job_id,run_id,tenant,subject_id,round_id,attempt,origin_fence,intent_hash,model_id,profile_digest,request_hash,request_json,backend_id,worker_id,instance_id,session_id,status,actual_finished,cancel_requested,outcome_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,'queued',0,0,NULL,?)",
              job,
              lease.runId,
              lease.tenant,
              lease.subject,
              round,
              attempt,
              intent.getLong("origin_fence"),
              intent.getString("intent_hash"),
              model.getString("id"),
              model.optString(
                  "adapterDigest",
                  cube.service.harness.execution.ModelProfiles.adapterDigest("scripted-v1")),
              hash(payload.getJSONObject("request")),
              request,
              store.now(c));
          return first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?", job));
        });
  }

  /**
   * Backend-row locking serializes capacity checks across JVMs and all workers sharing this
   * backend.
   */
  public JSONObject poll(
      String worker,
      String instance,
      String session,
      JSONObject descriptor,
      int workerCapacity,
      boolean healthy) {
    return store.tx(
        c -> {
          String backend = descriptor.getString("backendId");
          JSONObject b =
              first(
                  rows(
                      c, "SELECT * FROM harness_model_backend WHERE backend_id=?" + lock, backend));
          if (b == null) throw new StoreException("BACKEND_UNKNOWN");
          JSONObject existing =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_model_job WHERE worker_id=? AND instance_id=? AND actual_finished=0 AND status='assigned' ORDER BY created_at LIMIT 1",
                      worker,
                      instance));
          if (existing != null) return wire(existing);
          if (!healthy) return null;
          int occupied =
              first(
                      rows(
                          c,
                          "SELECT COUNT(*) AS n FROM harness_model_job WHERE backend_id=? AND status<>'queued' AND actual_finished=0",
                          backend))
                  .getInt("n");
          int own =
              first(
                      rows(
                          c,
                          "SELECT COUNT(*) AS n FROM harness_model_job WHERE worker_id=? AND actual_finished=0 AND status<>'queued'",
                          worker))
                  .getInt("n");
          if ((descriptor.optInt("protocolVersion", 2) == 2 && occupied >= b.getInt("capacity"))
              || own >= workerCapacity) return null;
          JSONObject job =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_model_job WHERE status='queued' AND cancel_requested=0 AND model_id=? AND profile_digest=? AND run_id IN (SELECT run_id FROM harness_run WHERE state='running') ORDER BY created_at,job_id LIMIT 1"
                          + lock,
                      descriptor.getString("model"),
                      descriptor.getString("profileDigest")));
          if (job == null) return null;
          if (update(
                  c,
                  "UPDATE harness_model_job SET backend_id=?,worker_id=?,instance_id=?,session_id=?,execution_protocol=?,status='assigned' WHERE job_id=? AND status='queued'",
                  backend,
                  worker,
                  instance,
                  session,
                  descriptor.optInt("protocolVersion", 2),
                  job.getString("job_id"))
              != 1) throw new StoreException("JOB_CLAIM_CONFLICT");
          job.put("worker_id", worker)
              .put("instance_id", instance)
              .put("session_id", session)
              .put("execution_protocol", descriptor.optInt("protocolVersion", 2));
          update(
              c,
              "UPDATE harness_model_job SET dispatched_at=? WHERE job_id=?",
              store.now(c),
              job.getString("job_id"));
          return wire(job);
        });
  }

  private static JSONObject wire(JSONObject j) {
    JSONObject result =
        new JSONObject()
            .put("jobId", j.getString("job_id"))
            .put("runId", j.getString("run_id"))
            .put("roundId", j.getString("round_id"))
            .put("attempt", j.getInt("attempt"))
            .put("originFence", j.getLong("origin_fence"))
            .put("intentHash", j.getString("intent_hash"))
            .put("requestHash", j.getString("request_hash"))
            .put("requestJson", j.getString("request_json"))
            .put("model", j.getString("model_id"))
            .put("workerId", j.getString("worker_id"))
            .put("instanceId", j.getString("instance_id"))
            .put("sessionId", j.getString("session_id"));
    return result;
  }

  public void report(String worker, JSONObject report) {
    store.tx(
        c -> {
          JSONObject j =
              first(
                  rows(
                      c,
                      "SELECT * FROM harness_model_job WHERE job_id=?" + lock,
                      report.getString("jobId")));
          if (j == null
              || !worker.equals(j.optString("worker_id"))
              || !j.getString("instance_id").equals(report.getString("instanceId"))
              || !j.getString("request_hash").equals(report.getString("requestHash")))
            throw new StoreException("JOB_EVIDENCE_MISMATCH");
          String result =
              canonical(
                  cube.service.harness.execution.ModelProtocol.outcome(
                      report.getString("outcomeJson"), report.getBoolean("actualFinished")));
          if (j.getInt("actual_finished") == 1) {
            if (!j.getString("outcome_json").equals(result) || !report.getBoolean("actualFinished"))
              throw new StoreException("JOB_RESULT_CONFLICT");
            return null;
          }
          // Unknown is quarantined. A later durable completion may resolve it; repeated unknown
          // must agree.
          if (j.getString("status").equals("unknown")
              && !report.getBoolean("actualFinished")
              && !j.getString("outcome_json").equals(result)) {
            if (j.getInt("execution_protocol") == 3)
              return null; // Java has already certified this unresolved observation.
            throw new StoreException("JOB_RESULT_CONFLICT");
          }
          update(
              c,
              "UPDATE harness_model_job SET status=?,actual_finished=?,outcome_json=? WHERE job_id=?",
              report.getBoolean("actualFinished") ? "finished" : "unknown",
              report.getBoolean("actualFinished") ? 1 : 0,
              result,
              j.getString("job_id"));
          if (report.getBoolean("actualFinished"))
            update(
                c,
                "UPDATE harness_model_job SET finished_at=? WHERE job_id=?",
                store.now(c),
                j.getString("job_id"));
          return null;
        });
  }

  public JSONObject jobById(String id) {
    return store.tx(c -> first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?", id)));
  }

  public JSONObject job(String run, String round) {
    return store.tx(
        c ->
            first(
                rows(
                    c,
                    "SELECT * FROM harness_model_job WHERE run_id=? AND round_id=? AND attempt=1",
                    run,
                    round)));
  }

  public void cancel(Lease lease, String job) {
    store.tx(
        c -> {
          JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
          store.owner(c, r, lease);
          update(
              c,
              "UPDATE harness_model_job SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE job_id=? AND run_id=?",
              store.now(c),
              job,
              lease.runId);
          JSONObject outcome =
              new JSONObject()
                  .put("status", "error")
                  .put("errorCode", "MODEL_NOT_DISPATCHED")
                  .put("audit", new JSONArray())
                  .put("cancellationObserved", true)
                  .put("usage", new JSONObject().put("input", 0).put("output", 0));
          update(
              c,
              "UPDATE harness_model_job SET status='finished',actual_finished=1,outcome_json=? WHERE job_id=? AND run_id=? AND status='queued'",
              canonical(outcome),
              job,
              lease.runId);
          return null;
        });
  }

  public JSONArray cancellations(String worker) {
    return store.tx(
        c -> {
          JSONArray result = new JSONArray(),
              list =
                  rows(
                      c,
                      "SELECT job_id FROM harness_model_job WHERE worker_id=? AND cancel_requested=1 AND actual_finished=0",
                      worker);
          for (int i = 0; i < list.length(); i++)
            result.put(list.getJSONObject(i).getString("job_id"));
          return result;
        });
  }

  public JSONObject capacity() {
    return store.tx(
        c ->
            new JSONObject()
                .put(
                    "backends",
                    rows(
                        c,
                        "SELECT b.*, (SELECT COUNT(*) FROM harness_model_job j WHERE j.backend_id=b.backend_id AND j.status<>'queued' AND j.actual_finished=0 AND j.execution_protocol=2) + (SELECT COUNT(*) FROM harness_inference i WHERE i.backend_id=b.backend_id AND i.status<>'queued' AND i.actual_finished=0) AS occupied FROM harness_model_backend b"))
                .put(
                    "workers",
                    rows(
                        c,
                        "SELECT worker_id,COUNT(*) AS occupied FROM harness_model_job WHERE status<>'queued' AND actual_finished=0 GROUP BY worker_id")));
  }

  public JSONArray runnable(String tenant, int limit) {
    return store.tx(
        c ->
            rows(
                c,
                "SELECT r.run_id,r.subject_id FROM harness_run r WHERE r.tenant=? AND (r.state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling') OR EXISTS (SELECT j.job_id FROM harness_model_job j JOIN harness_round q ON q.run_id=j.run_id AND q.intent_id=j.round_id AND q.attempt=j.attempt JOIN harness_usage u ON u.run_id=q.run_id AND u.round_id=q.intent_id AND u.attempt=q.attempt WHERE j.run_id=r.run_id AND j.actual_finished=1 AND (q.status<>'confirmed' OR (u.actual_input IS NULL AND j.outcome_json LIKE '%\"usage\":%'))) OR EXISTS (SELECT j.job_id FROM harness_tool_job j JOIN harness_tool_call q ON q.run_id=j.run_id AND q.intent_id=j.call_id AND q.attempt=j.attempt WHERE j.run_id=r.run_id AND j.actual_finished=1 AND q.status<>'confirmed')) ORDER BY r.created_at,r.run_id LIMIT "
                    + Math.max(1, Math.min(256, limit)),
                id(tenant)));
  }

  public int active(String tenant) {
    return store.tx(
        c ->
            first(
                    rows(
                        c,
                        "SELECT COUNT(*) AS n FROM harness_run WHERE tenant=? AND state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')",
                        tenant))
                .getInt("n"));
  }

  public long created(Lease lease) {
    return store.tx(
        c -> store.run(c, lease.tenant, lease.subject, lease.runId, false).getLong("created_at"));
  }

  public long created(String tenant, String subject, String run) {
    return store.tx(c -> store.run(c, tenant, subject, run, false).getLong("created_at"));
  }

  public boolean expired(String tenant, String subject, String run, long lifetime) {
    return store.tx(
        c ->
            store.now(c) - store.run(c, tenant, subject, run, false).getLong("created_at")
                > lifetime);
  }
}
