package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import static cube.service.harness.store.JdbcRunStore.*;

/** Java owns inference side effects and completion evidence, independently of Python processing. */
public final class InferenceStore {
    private final JdbcRunStore store;
    private final String lock;

    public InferenceStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.lock = mysql ? " FOR UPDATE" : "";
    }

    private JSONObject job(java.sql.Connection c, String job, String worker, String instance) throws Exception {
        JSONObject j = first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?" + lock, job));
        if (j == null || !worker.equals(j.optString("worker_id")) || !instance.equals(j.optString("instance_id"))
                || j.getInt("execution_protocol") != 3) throw new StoreException("JOB_EVIDENCE_MISMATCH");
        return j;
    }

    public JSONObject submit(String job, String worker, String originalInstance, String currentInstance,
                             int attempt, String requestHash, JSONObject request) {
        if (!hash(request).equals(requestHash) || canonical(request).getBytes(StandardCharsets.UTF_8).length > 131072)
            throw new StoreException("INFERENCE_REQUEST_INVALID");
        return store.tx(c -> {
            JSONObject j = job(c, job, worker, originalInstance);
            JSONObject old = first(rows(c, "SELECT * FROM harness_inference WHERE job_id=? AND wire_attempt=?", job, attempt));
            if (old != null) {
                if (!old.getString("request_hash").equals(requestHash)) throw new StoreException("INFERENCE_CONFLICT");
                return old;
            }
            if (!originalInstance.equals(currentInstance) || j.getInt("actual_finished") != 0
                    || j.getInt("cancel_requested") != 0) throw new StoreException("INFERENCE_NOT_ADMISSIBLE");
            JSONObject input = new JSONObject(j.getString("request_json"));
            int repairs = Math.min(1, Math.min(input.optInt("maxRepairs", 0),
                    input.optJSONObject("generation") == null ? 0 : input.getJSONObject("generation").optInt("maxRepairs", 0)));
            if (attempt < 1 || attempt > 1 + repairs || !j.getString("model_id").equals(request.optString("model"))
                    || request.optLong("max_tokens", request.optLong("max_completion_tokens", 0)) != input.getLong("outputReserve"))
                throw new StoreException("INFERENCE_PROFILE_MISMATCH");
            if (attempt > 1) {
                JSONObject previous = first(rows(c, "SELECT * FROM harness_inference WHERE job_id=? AND wire_attempt=?", job, attempt - 1));
                if (previous == null || previous.getInt("actual_finished") != 1 || previous.isNull("response_json"))
                    throw new StoreException("INFERENCE_PREVIOUS_UNRESOLVED");
            }
            String inference = hash(new JSONArray().put(job).put(attempt));
            update(c, "INSERT INTO harness_inference VALUES (?,?,?,?,?,?,'queued',0,0,NULL,NULL,NULL,NULL,NULL,?)",
                    inference, job, attempt, j.getString("backend_id"), requestHash, canonical(request), store.now(c));
            return first(rows(c, "SELECT * FROM harness_inference WHERE inference_id=?", inference));
        });
    }

    public JSONArray queued() {
        return store.tx(c -> rows(c, "SELECT * FROM harness_inference WHERE status='queued' ORDER BY created_at,inference_id LIMIT 32"));
    }

    /** Commit the reservation BEFORE dispatch. A crash in the send window can never cause a resend. */
    public boolean claim(String inference, String node, String owner) {
        return store.tx(c -> {
            JSONObject i = first(rows(c, "SELECT * FROM harness_inference WHERE inference_id=?" + lock, inference));
            if (i == null || !i.getString("status").equals("queued")) return false;
            String backend = i.getString("backend_id");
            JSONObject b = first(rows(c, "SELECT * FROM harness_model_backend WHERE backend_id=?" + lock, backend));
            int occupied = first(rows(c, "SELECT COUNT(*) AS n FROM harness_inference WHERE backend_id=? AND status<>'queued' AND actual_finished=0", backend)).getInt("n");
            occupied += first(rows(c, "SELECT COUNT(*) AS n FROM harness_model_job WHERE backend_id=? AND execution_protocol=2 AND status<>'queued' AND actual_finished=0", backend)).getInt("n");
            if (occupied >= b.getInt("capacity")) return false;
            JSONObject j = first(rows(c, "SELECT cancel_requested FROM harness_model_job WHERE job_id=?", i.getString("job_id")));
            if (j.getInt("cancel_requested") != 0 || i.getInt("cancel_requested") != 0) {
                update(c, "UPDATE harness_inference SET status='finished',actual_finished=1,error_code='MODEL_NOT_DISPATCHED',cancel_requested=1 WHERE inference_id=?", inference);
                return false;
            }
            return update(c, "UPDATE harness_inference SET status='dispatched',node_id=?,owner_id=? WHERE inference_id=? AND status='queued'", node, owner, inference) == 1;
        });
    }

    public void recover(String node, String owner) {
        store.tx(c -> {
            update(c, "UPDATE harness_inference SET status='unknown',error_code='INFERENCE_JVM_RESTARTED' WHERE node_id=? AND owner_id<>? AND status='dispatched' AND actual_finished=0", node, owner);
            return null;
        });
    }

    public void append(String inference, JSONObject event) {
        String raw = canonical(event);
        if (raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw new StoreException("MODEL_OUTPUT_TOO_LARGE");
        store.tx(c -> {
            JSONObject i = first(rows(c, "SELECT actual_finished FROM harness_inference WHERE inference_id=?" + lock, inference));
            if (i == null || i.getInt("actual_finished") == 1) return null;
            JSONObject totals = first(rows(c, "SELECT COUNT(*) AS n,COALESCE(SUM(LENGTH(event_json)),0) AS bytes FROM harness_inference_event WHERE inference_id=?", inference));
            if (totals.getInt("n") >= 4096 || totals.getLong("bytes") + raw.length() > 1048576)
                throw new StoreException("MODEL_OUTPUT_TOO_LARGE");
            update(c, "INSERT INTO harness_inference_event VALUES (?,?,?)", inference, totals.getLong("n") + 1, raw);
            return null;
        });
    }

    public void finish(String inference, JSONObject response, String error, boolean actualFinished, String unit) {
        String raw = response == null ? null : canonical(response);
        if (raw != null && raw.getBytes(StandardCharsets.UTF_8).length > 65536) {
            raw = null; error = "MODEL_OUTPUT_TOO_LARGE";
        }
        final String result = raw, code = error;
        store.tx(c -> {
            JSONObject old = first(rows(c, "SELECT * FROM harness_inference WHERE inference_id=?" + lock, inference));
            if (old == null) throw new StoreException("INFERENCE_NOT_FOUND");
            if (old.getInt("actual_finished") == 1) return null;
            update(c, "UPDATE harness_inference SET status=?,actual_finished=?,response_json=?,error_code=?,unit_id=? WHERE inference_id=?",
                    actualFinished ? "finished" : "unknown", actualFinished ? 1 : 0, result, code, unit, inference);
            return null;
        });
    }

    public JSONObject page(String job, String worker, String originalInstance, int attempt, long after, boolean cancel) {
        return store.tx(c -> {
            job(c, job, worker, originalInstance);
            JSONObject i = first(rows(c, "SELECT * FROM harness_inference WHERE job_id=? AND wire_attempt=?" + lock, job, attempt));
            if (i == null) throw new StoreException("INFERENCE_NOT_FOUND");
            String id = i.getString("inference_id");
            if (cancel) {
                update(c, "UPDATE harness_inference SET cancel_requested=1 WHERE inference_id=?", id);
                if (i.getString("status").equals("queued")) {
                    update(c, "UPDATE harness_inference SET status='finished',actual_finished=1,error_code='MODEL_NOT_DISPATCHED' WHERE inference_id=?", id);
                    i.put("status", "finished").put("actual_finished", 1).put("error_code", "MODEL_NOT_DISPATCHED");
                }
                i.put("cancel_requested", 1);
            }
            long end = first(rows(c, "SELECT COALESCE(MAX(event_seq),0) AS n FROM harness_inference_event WHERE inference_id=?", id)).getLong("n");
            if (after < 0 || after > end) throw new StoreException("INVALID_INFERENCE_CURSOR");
            JSONArray events = rows(c, "SELECT event_seq,event_json FROM harness_inference_event WHERE inference_id=? AND event_seq>? ORDER BY event_seq LIMIT 16", id, after);
            JSONArray page = new JSONArray(); long cursor = after; int bytes = 0;
            for (int n = 0; n < events.length(); n++) {
                JSONObject e = events.getJSONObject(n); String value = e.getString("event_json");
                bytes += value.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 131072) break;
                cursor = e.getLong("event_seq");
                page.put(new JSONObject().put("sequence", cursor).put("data", new JSONObject(value)));
            }
            JSONObject out = new JSONObject().put("inferenceId", id).put("status", i.getString("status"))
                    .put("actualFinished", i.getInt("actual_finished") == 1).put("cancelRequested", i.getInt("cancel_requested") == 1)
                    .put("events", page).put("nextCursor", cursor).put("hasMore", cursor < end);
            if (!i.isNull("error_code")) out.put("errorCode", i.getString("error_code"));
            if (!i.isNull("response_json") && cursor == end) out.put("response", new JSONObject(i.getString("response_json")));
            return out;
        });
    }

    /** Ignore worker claims about physical completion and usage; only Java backend evidence can settle them. */
    public JSONObject reconcile(String job, JSONObject outcome) {
        return store.tx(c -> {
            JSONArray attempts = rows(c, "SELECT * FROM harness_inference WHERE job_id=? ORDER BY wire_attempt", job);
            if (attempts.length() == 0 && outcome.getString("status").equals("success"))
                throw new StoreException("INFERENCE_EVIDENCE_REQUIRED");
            long input = 0, output = 0; boolean known = attempts.length() > 0;
            JSONArray audit = new JSONArray();
            for (int n = 0; n < attempts.length(); n++) {
                JSONObject i = attempts.getJSONObject(n);
                if (i.getInt("actual_finished") == 0) return new JSONObject().put("status", "unknown")
                        .put("errorCode", "MODEL_COMPLETION_UNKNOWN").put("audit", new JSONArray())
                        .put("cancellationObserved", outcome.getBoolean("cancellationObserved"));
                JSONObject record = new JSONObject().put("attempt", i.getInt("wire_attempt")).put("requestHash", i.getString("request_hash"));
                if (!i.isNull("response_json")) {
                    JSONObject response = new JSONObject(i.getString("response_json"));
                    record.put("responseHash", hash(response)).put("finishReason", response.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
                    JSONObject u = response.optJSONObject("usage");
                    if (u == null) known = false;
                    else {
                        input = Math.addExact(input, u.getLong("prompt_tokens")); output = Math.addExact(output, u.getLong("completion_tokens"));
                        record.put("usage", new JSONObject().put("input", u.getLong("prompt_tokens")).put("output", u.getLong("completion_tokens")));
                    }
                } else {
                    known = false;
                    if (outcome.getString("status").equals("success")) throw new StoreException("INFERENCE_EVIDENCE_REQUIRED");
                }
                audit.put(record);
            }
            JSONObject result = new JSONObject(outcome.toString()).put("audit", audit); result.remove("usage");
            if (known) result.put("usage", new JSONObject().put("input", input).put("output", output));
            return result;
        });
    }
}
