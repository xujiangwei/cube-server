/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * Durable tool admission/evidence. Sessions, timeouts and observation ACKs never prove execution ended.
 */
public final class ToolJobStore {
    private final JdbcRunStore store;
    private final String lock;

    public interface WireFactory {
        JSONObject create(JSONObject job, String worker, String runtime, String instance);
    }

    public ToolJobStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        lock = mysql ? " FOR UPDATE" : "";
    }

    public JSONObject enqueue(Lease lease, JSONObject intent, JSONObject definition) {
        return store.tx(c -> {
            JSONObject run = store.run(c, lease.tenant, lease.subject, lease.runId, true);
            store.owner(c, run, lease);
            JSONObject authoritative = first(rows(c, "SELECT * FROM harness_tool_call WHERE run_id=? AND intent_id=? AND attempt=?", lease.runId, intent.getString("intent_id"), intent.getInt("attempt")));
            if (authoritative == null || !canonical(authoritative).equals(canonical(intent)))
                throw new StoreException("INTENT_EVIDENCE_MISMATCH");
            String job = hash(new JSONArray().put(lease.runId).put(intent.getString("intent_id")).put(intent.getInt("attempt")));
            JSONObject old = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", job));
            if (old != null) return old;
            // Serializes bounded admission across JVMs, including unknown reservations.
            if (!lock.isEmpty()) rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1 FOR UPDATE");
            if (first(rows(c, "SELECT COUNT(*) AS n FROM harness_tool_job WHERE actual_finished=0")).getInt("n") >= 256)
                throw new StoreException("TOOL_QUEUE_FULL");
            JSONObject call = new JSONObject(intent.getString("payload_json"));
            JSONObject arguments = cube.service.harness.protocol.StrictJson.object(call.getString("argumentsJson").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            update(c, "INSERT INTO harness_tool_job (job_id,run_id,tenant,subject_id,call_id,attempt,origin_fence,intent_hash,release_digest,tool_name,definition_json,arguments_json,arguments_hash,status,actual_finished,cancel_requested,effect_status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'queued',0,0,'none',?)",
                    job, lease.runId, lease.tenant, lease.subject, intent.getString("intent_id"), intent.getInt("attempt"), intent.getLong("origin_fence"), intent.getString("intent_hash"), definition.getString("release"), call.getString("tool"), canonical(definition), canonical(arguments), hash(arguments), store.now(c));
            return first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", job));
        });
    }

    public void identity(String worker, String runtime, int capacity) {
        store.tx(c -> {
            JSONObject old = first(rows(c, "SELECT * FROM harness_runner_identity WHERE worker_id=?" + lock, worker));
            if (old == null) update(c, "INSERT INTO harness_runner_identity VALUES (?,?,?)", worker, runtime, capacity);
            else if (!runtime.equals(old.getString("runtime_id")) || capacity != old.getInt("capacity"))
                throw new StoreException("RUNNER_IDENTITY_CONFLICT");
            return null;
        });
    }

    public JSONObject poll(String worker, String runtime, String instance, boolean healthy, WireFactory factory) {
        return store.tx(c -> {
            JSONObject identity = first(rows(c, "SELECT * FROM harness_runner_identity WHERE worker_id=?" + lock, worker));
            if (identity == null || !runtime.equals(identity.getString("runtime_id")))
                throw new StoreException("RUNNER_IDENTITY_CONFLICT");
            if (!healthy || first(rows(c, "SELECT job_id FROM harness_tool_job WHERE worker_id=? AND status='unknown' AND actual_finished=0 LIMIT 1", worker)) != null)
                return null;
            JSONObject existing = first(rows(c, "SELECT * FROM harness_tool_job WHERE worker_id=? AND runtime_id=? AND status='assigned' AND cancel_requested=0 ORDER BY created_at LIMIT 1", worker, runtime));
            if (existing != null) return new JSONObject(existing.getString("wire_json"));
            if (!healthy || first(rows(c, "SELECT COUNT(*) AS n FROM harness_tool_job WHERE worker_id=? AND actual_finished=0 AND status<>'queued'", worker)).getInt("n") >= identity.getInt("capacity"))
                return null;
            JSONObject job = first(rows(c, "SELECT * FROM harness_tool_job WHERE status='queued' AND cancel_requested=0 AND EXISTS (SELECT run_id FROM harness_run r WHERE r.run_id=harness_tool_job.run_id AND r.state='running') ORDER BY created_at,job_id LIMIT 1" + lock));
            if (job == null) return null;
            JSONObject wire = factory.create(job, worker, runtime, instance);
            if (update(c, "UPDATE harness_tool_job SET worker_id=?,runtime_id=?,instance_id=?,job_hash=?,wire_json=?,status='assigned' WHERE job_id=? AND status='queued'", worker, runtime, instance, wire.getString("jobHash"), canonical(wire), job.getString("job_id")) != 1)
                throw new StoreException("TOOL_CLAIM_CONFLICT");
            return wire;
        });
    }

    public JSONObject find(String run, String call, int attempt) {
        return store.tx(c -> first(rows(c, "SELECT * FROM harness_tool_job WHERE run_id=? AND call_id=? AND attempt=?", run, call, attempt)));
    }

    public JSONObject job(String id) {
        return store.tx(c -> {
            JSONObject j = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", id));
            if (j == null) throw new StoreException("TOOL_JOB_NOT_FOUND");
            return j;
        });
    }

    public JSONArray cleanups(String worker, String runtime, long grace) {
        return store.tx(c -> {
            long now = store.now(c);
            update(c, "UPDATE harness_tool_job SET cleanup_after=? WHERE worker_id=? AND runtime_id=? AND job_hash IS NOT NULL AND report_hash IS NOT NULL AND actual_finished=1 AND effect_status<>'unknown' AND cleanup_after=0 AND EXISTS (SELECT intent_id FROM harness_tool_call q WHERE q.run_id=harness_tool_job.run_id AND q.intent_id=harness_tool_job.call_id AND q.attempt=harness_tool_job.attempt AND q.status='confirmed')", now + grace, worker, runtime);
            JSONArray rows = rows(c, "SELECT * FROM harness_tool_job WHERE worker_id=? AND runtime_id=? AND actual_finished=1 AND effect_status<>'unknown' AND cleanup_after>0 AND cleanup_after<=? AND cleanup_done=0 LIMIT 32", worker, runtime, now), out = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject j = rows.getJSONObject(i);
                out.put(new JSONObject().put("jobId", j.getString("job_id")).put("jobHash", j.getString("job_hash")).put("runtimeId", runtime).put("resultHash", j.getString("report_hash")));
            }
            return out;
        });
    }

    public void cleaned(String worker, String runtime, String job, String hash, String reportHash) {
        store.tx(c -> {
            JSONObject j = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?" + lock, job));
            if (j == null || !worker.equals(j.optString("worker_id")) || !runtime.equals(j.optString("runtime_id")) || !hash.equals(j.optString("job_hash")) || !reportHash.equals(j.optString("report_hash")) || j.getInt("actual_finished") != 1 || j.getLong("cleanup_after") == 0 || j.getLong("cleanup_after") > store.now(c))
                throw new StoreException("CLEANUP_EVIDENCE_MISMATCH");
            update(c, "UPDATE harness_tool_job SET cleanup_done=1 WHERE job_id=?", job);
            return null;
        });
    }

    public JSONObject evidence(String worker, String runtime, String job, String digest) {
        JSONObject j = job(job);
        if (!worker.equals(j.optString("worker_id")) || !runtime.equals(j.optString("runtime_id")) || !digest.equals(j.optString("job_hash")))
            throw new StoreException("TOOL_EVIDENCE_MISMATCH");
        return j;
    }

    public JSONObject accepted(String job, String digest) {
        return store.tx(c -> {
            JSONObject j = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?" + lock, job));
            if (j == null || !digest.equals(j.optString("job_hash")))
                throw new StoreException("TOOL_EVIDENCE_MISMATCH");
            if (j.getInt("cancel_requested") != 0 || revoked(c, j)) throw new StoreException("TOOL_REVOKED");
            if (j.getString("status").equals("assigned"))
                update(c, "UPDATE harness_tool_job SET status='accepted' WHERE job_id=?", job);
            return j;
        });
    }

    public JSONObject report(JSONObject job, JSONObject report, JSONObject result) {
        return store.tx(c -> {
            JSONObject j = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?" + lock, job.getString("job_id")));
            String hash = hash(report);
            if (j.getInt("actual_finished") == 1) {
                if (!hash.equals(j.optString("report_hash"))) throw new StoreException("TOOL_RESULT_CONFLICT");
                return j;
            }
            if (j.getString("status").equals("unknown") && !report.getBoolean("actualFinished") && !hash.equals(j.optString("report_hash")))
                throw new StoreException("TOOL_RESULT_CONFLICT");
            boolean ended = report.getBoolean("actualFinished");
            update(c, "UPDATE harness_tool_job SET status=?,actual_finished=?,effect_status=?,result_json=?,report_hash=? WHERE job_id=?",
                    ended ? "finished" : "unknown", ended ? 1 : 0, report.getString("effectStatus"), canonical(result), hash, j.getString("job_id"));
            return first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", j.getString("job_id")));
        });
    }

    private boolean revoked(java.sql.Connection c, JSONObject j) throws Exception {
        return first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=? AND tenant=? AND subject_id=?", j.getString("run_id"), j.getString("tenant"), j.getString("subject_id"))) != null;
    }

    public boolean revoked(JSONObject j) {
        return store.tx(c -> revoked(c, j));
    }

    public JSONObject currentRun(JSONObject j) {
        return store.read(j.getString("tenant"), j.getString("subject_id"), j.getString("run_id"));
    }

    public void cancel(Lease l, String job) {
        store.tx(c -> {
            store.owner(c, store.run(c, l.tenant, l.subject, l.runId, true), l);
            JSONObject j = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=? AND run_id=?" + lock, job, l.runId));
            if (j == null) throw new StoreException("TOOL_JOB_NOT_FOUND");
            update(c, "UPDATE harness_tool_job SET cancel_requested=1 WHERE job_id=?", job);
            if (j.getString("status").equals("queued"))
                update(c, "UPDATE harness_tool_job SET status='finished',actual_finished=1,effect_status='none',result_json=? WHERE job_id=?", canonical(error("TOOL_CANCELLED")), job);
            return null;
        });
    }

    public void revoke(String tenant, String subject, String run, String reason) {
        store.tx(c -> {
            store.run(c, tenant, subject, run, true);
            if (first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run)) == null)
                update(c, "INSERT INTO harness_tool_revocation VALUES (?,?,?,?)", run, tenant, subject, reason);
            update(c, "UPDATE harness_tool_job SET cancel_requested=1 WHERE run_id=? AND actual_finished=0", run);
            // A queued job has not reached a worker and has no effect.
            update(c, "UPDATE harness_tool_job SET status='finished',actual_finished=1,effect_status='none',result_json=? WHERE run_id=? AND status='queued'", canonical(error("TOOL_REVOKED")), run);
            return null;
        });
    }

    public JSONArray cancellations(String worker) {
        return store.tx(c -> rows(c, "SELECT job_id FROM harness_tool_job WHERE worker_id=? AND cancel_requested=1 AND actual_finished=0 LIMIT 64", worker));
    }

    public int occupied(String worker) {
        return store.tx(c -> first(rows(c, "SELECT COUNT(*) AS n FROM harness_tool_job WHERE worker_id=? AND actual_finished=0 AND status<>'queued'", worker)).getInt("n"));
    }

    public boolean healthy(String worker) {
        return store.tx(c -> first(rows(c, "SELECT job_id FROM harness_tool_job WHERE worker_id=? AND status='unknown' AND actual_finished=0 LIMIT 1", worker)) == null);
    }

    public JSONObject brokerExchange(String job, String requestHash, String node, boolean create) {
        return store.tx(c -> {
            // Job row serializes first submission even when the exchange row does not yet exist.
            rows(c, "SELECT job_id FROM harness_tool_job WHERE job_id=?" + lock, job);
            JSONObject old = first(rows(c, "SELECT * FROM harness_broker_exchange WHERE job_id=?", job));
            if (old != null) {
                if (!requestHash.equals(old.getString("request_hash")))
                    throw new StoreException("BROKER_ARGUMENT_CONFLICT");
                return old.put("new", false);
            }
            if (!create) return null;
            update(c, "INSERT INTO harness_broker_exchange VALUES (?,?,?,'dispatched',NULL)", job, requestHash, node);
            return new JSONObject().put("status", "dispatched").put("new", true);
        });
    }

    public void brokerFinish(String job, String node, JSONObject response, boolean unknown) {
        store.tx(c -> {
            update(c, "UPDATE harness_broker_exchange SET status=?,response_json=? WHERE job_id=? AND owner_id=? AND status='dispatched'", unknown ? "unknown" : "finished", canonical(response), job, node);
            return null;
        });
    }

    public void brokerRestart(String node) {
        store.tx(c -> {
            update(c, "UPDATE harness_broker_exchange SET status='unknown',response_json=? WHERE owner_id=? AND status='dispatched'", canonical(error("BROKER_OUTCOME_UNKNOWN")), node);
            return null;
        });
    }

    public static JSONObject error(String code) {
        return new JSONObject().put("status", "error").put("artifacts", new JSONArray()).put("errorCode", code);
    }
}
