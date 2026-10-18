/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * Read-only, bounded diagnostic projection. Never used by recovery or authoritative commits.
 */
public final class TraceStore {
    private final JdbcRunStore store;
    private final long retentionMillis;
    private final int maxBytes;
    public final boolean enabled;
    private final boolean feedbackEnabled;

    public TraceStore(JdbcRunStore store, Properties p) {
        this.store = store;
        enabled = Boolean.parseBoolean(p.getProperty("trace.enabled", "false"));
        feedbackEnabled = Boolean.parseBoolean(p.getProperty("feedback.enabled", "false"));
        long seconds = Long.parseLong(p.getProperty("trace.retention.seconds", "604800"));
        maxBytes = Integer.parseInt(p.getProperty("trace.export.max.bytes", "262144"));
        if (seconds < 60 || seconds > 31536000 || maxBytes < 4096 || maxBytes > 262144)
            throw new IllegalArgumentException("INVALID_TRACE_CONFIG");
        retentionMillis = seconds * 1000;
    }

    public JSONObject feedback(
            String tenant, String subject, String run, String key, JSONObject body) {
        if (!feedbackEnabled) throw new StoreException("FEEDBACK_DISABLED");
        String rating = body.getString("rating");
        if (!(rating.equals("positive") || rating.equals("negative"))
                || body.optString("reason").length() > 4096) throw new StoreException("INVALID_FEEDBACK");
        String digest = hash(body),
                fid = hash(new JSONArray().put(id(tenant)).put(id(subject)).put(id(run)).put(id(key)));
        return store.tx(
                c -> {
                    store.run(c, tenant, subject, run, true);
                    JSONObject old =
                            first(rows(c, "SELECT payload_hash FROM harness_feedback WHERE feedback_id=?", fid));
                    if (old != null && !old.getString("payload_hash").equals(digest))
                        throw new StoreException("IDEMPOTENCY_CONFLICT");
                    if (old == null) {
                        if (first(rows(c, "SELECT COUNT(*) AS n FROM harness_feedback WHERE run_id=?", run))
                                .getInt("n")
                                >= 256) throw new StoreException("FEEDBACK_QUOTA_EXCEEDED");
                        update(
                                c,
                                "INSERT INTO harness_feedback VALUES (?,?,?,?,?,?,?,?)",
                                fid,
                                run,
                                tenant,
                                subject,
                                digest,
                                rating,
                                canonical(new JSONObject().put("reason", body.optString("reason"))),
                                store.now(c));
                    }
                    return new JSONObject().put("feedbackId", fid).put("accepted", true);
                });
    }

    /**
     * Content never leaves by default: retain JSON shape and digest commitments for mock outputs.
     */
    private static Object redact(Object v) {
        if (v instanceof JSONObject) {
            JSONObject out = new JSONObject(), o = (JSONObject) v;
            for (String k : o.keySet()) out.put("keySha256:" + hash(k), redact(o.get(k)));
            return out;
        }
        if (v instanceof JSONArray) {
            JSONArray out = new JSONArray(), a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) out.put(redact(a.get(i)));
            return out;
        }
        if (v instanceof String) return new JSONObject().put("redactedSha256", hash(v));
        return v;
    }

    private static JSONArray contentRows(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            for (String k : new String[]{"payload_json", "result_json"}) {
                if (!row.has(k) || row.isNull(k)) continue;
                String raw = row.getString(k);
                if (k.equals("payload_json")) {
                    JSONObject payload = new JSONObject(raw);
                    if (payload.has("argumentsJson"))
                        row.put(
                                "argumentsHash",
                                hash(
                                        cube.service.harness.protocol.StrictJson.object(
                                                payload.getString("argumentsJson").getBytes(StandardCharsets.UTF_8))));
                    if (payload.has("tool")) row.put("tool", payload.getString("tool"));
                }
                row.put(k + "_sha256", hash(new JSONObject(raw))).put(k, redact(new JSONObject(raw)));
            }
        }
        return rows;
    }

    public JSONObject export(String tenant, String subject, String run) {
        if (!enabled) throw new StoreException("TRACE_DISABLED");
        return store.tx(
                c -> {
                    // Run lock orders this consistent read against coordinator/file/control transactions.
                    JSONObject r = store.run(c, tenant, subject, run, true);
                    if (store.now(c) - r.getLong("updated_at") >= retentionMillis)
                        throw new StoreException("TRACE_EXPIRED");
                    JSONArray events =
                            rows(
                                    c,
                                    "SELECT event_seq,run_version,event_type,occurred_at,projection_json FROM harness_event WHERE run_id=? ORDER BY event_seq LIMIT 1025",
                                    run);
                    if (events.length() > 1024) throw new StoreException("TRACE_EXPORT_TOO_LARGE");
                    boolean complete = true;
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject e = events.getJSONObject(i);
                        if (e.isNull("projection_json")) complete = false;
                        else e.put("projection", new JSONObject(e.getString("projection_json")));
                        e.remove("projection_json");
                    }
                    JSONArray rounds =
                            contentRows(
                                    rows(
                                            c,
                                            "SELECT intent_id,attempt,intent_hash,origin_fence,status,payload_json,result_json,result_hash FROM harness_round WHERE run_id=? ORDER BY intent_id,attempt LIMIT 257",
                                            run));
                    JSONArray calls =
                            contentRows(
                                    rows(
                                            c,
                                            "SELECT intent_id,attempt,intent_hash,origin_fence,status,payload_json,result_json,result_hash FROM harness_tool_call WHERE run_id=? ORDER BY intent_id,attempt LIMIT 257",
                                            run));
                    if (rounds.length() > 256 || calls.length() > 256)
                        throw new StoreException("TRACE_EXPORT_TOO_LARGE");
                    JSONObject pinned =
                            first(
                                    rows(c, "SELECT digest,config_json FROM harness_run_config WHERE run_id=?", run));
                    if (pinned != null) {
                        JSONObject fixed = new JSONObject(pinned.getString("config_json"));
                        pinned.remove("config_json");
                        for (String kind : new String[]{"task", "model", "runtime"}) {
                            JSONObject definition = fixed.getJSONObject(kind);
                            pinned.put(
                                    kind,
                                    new JSONObject()
                                            .put("id", definition.getString("id"))
                                            .put("version", definition.getString("version"))
                                            .put("digest", hash(definition)));
                        }
                    }
                    JSONObject result =
                            first(
                                    rows(
                                            c,
                                            "SELECT result_hash,committed_at FROM harness_result WHERE run_id=?",
                                            run));
                    JSONObject out =
                            new JSONObject()
                                    .put("schemaVersion", 1)
                                    .put("kind", "harness-redacted-state-trace")
                                    .put("runId", run)
                                    .put("redaction", "all-content-strings-sha256")
                                    .put("replayComplete", complete)
                                    .put(
                                            "snapshot",
                                            snapshot(r).put("usageSettlement", r.getString("usage_settlement")))
                                    .put("config", pinned == null ? JSONObject.NULL : pinned)
                                    .put("events", events)
                                    .put("rounds", rounds)
                                    .put("calls", calls)
                                    .put(
                                            "usage",
                                            rows(
                                                    c,
                                                    "SELECT round_id,attempt,estimated_input,estimated_output,actual_input,actual_output,revision FROM harness_usage WHERE run_id=? ORDER BY round_id,attempt",
                                                    run))
                                    .put(
                                            "modelJobs",
                                            rows(
                                                    c,
                                                    "SELECT job_id,round_id,attempt,model_id,profile_digest,backend_id,worker_id,status,actual_finished,cancel_requested,created_at,dispatched_at,finished_at,cancelled_at FROM harness_model_job WHERE run_id=? ORDER BY created_at,job_id",
                                                    run))
                                    .put(
                                            "toolJobs",
                                            rows(
                                                    c,
                                                    "SELECT job_id,call_id,attempt,release_digest,tool_name,worker_id,status,actual_finished,cancel_requested,effect_status,created_at,dispatched_at,finished_at,cancelled_at,cleanup_after,cleanup_done FROM harness_tool_job WHERE run_id=? ORDER BY created_at,job_id",
                                                    run))
                                    .put(
                                            "inferences",
                                            rows(
                                                    c,
                                                    "SELECT inference_id,job_id,wire_attempt,backend_id,status,actual_finished,cancel_requested,error_code,created_at,dispatched_at,finished_at,cancelled_at FROM harness_inference WHERE job_id IN (SELECT job_id FROM harness_model_job WHERE run_id=?) ORDER BY created_at,inference_id",
                                                    run))
                                    .put(
                                            "files",
                                            rows(
                                                    c,
                                                    "SELECT file_id,kind,size_bytes,sha256,status,created_at,ready_at,expires_at,deleted_at,attempts FROM harness_file WHERE run_id=? ORDER BY created_at,file_id",
                                                    run))
                                    .put(
                                            "feedback",
                                            rows(
                                                    c,
                                                    "SELECT feedback_id,payload_hash,rating,created_at FROM harness_feedback WHERE run_id=? ORDER BY created_at,feedback_id",
                                                    run))
                                    .put("resultCommitment", result == null ? JSONObject.NULL : result)
                                    .put(
                                            "timingCoverage",
                                            "event timestamps, queue creation, file ready/delete, commit; unavailable stage timestamps are not inferred");
                    JSONObject metrics =
                            new JSONObject().put("rounds", rounds.length()).put("calls", calls.length());
                    for (String kind : new String[]{"modelJobs", "toolJobs", "inferences"}) {
                        JSONArray list = out.getJSONArray(kind), timings = new JSONArray();
                        int active = 0, queued = 0;
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject j = list.getJSONObject(i);
                            if (j.getInt("actual_finished") == 0) active++;
                            if (j.getString("status").equals("queued")) queued++;
                            JSONObject time =
                                    new JSONObject()
                                            .put(
                                                    "id", j.getString(kind.equals("inferences") ? "inference_id" : "job_id"));
                            for (String field : new String[]{"queue", "execution", "cancelToPhysical"}) {
                                String a =
                                        field.equals("queue")
                                                ? "created_at"
                                                : field.equals("execution") ? "dispatched_at" : "cancelled_at";
                                String b = field.equals("queue") ? "dispatched_at" : "finished_at";
                                time.put(
                                        field + "Millis",
                                        j.isNull(a) || j.isNull(b)
                                                ? JSONObject.NULL
                                                : Math.max(0, j.getLong(b) - j.getLong(a)));
                            }
                            timings.put(time);
                        }
                        metrics.put(
                                kind,
                                new JSONObject()
                                        .put("total", list.length())
                                        .put("unsettledPhysical", active)
                                        .put("queued", queued)
                                        .put("timings", timings));
                    }
                    out.put("metrics", metrics);
                    out.put("digest", hash(out));
                    if (canonical(out).getBytes(StandardCharsets.UTF_8).length > maxBytes)
                        throw new StoreException("TRACE_EXPORT_TOO_LARGE");
                    return out;
                });
    }
}
