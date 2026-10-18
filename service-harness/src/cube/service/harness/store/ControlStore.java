/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * N11 controls are short authoritative transactions; no worker/network work in the transaction.
 */
public final class ControlStore {
    private final JdbcRunStore store;
    private final String lock;
    private FileStore files;

    public ControlStore files(FileStore files) {
        this.files = files;
        return this;
    }

    public ControlStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        lock = mysql ? " FOR UPDATE" : "";
    }

    private boolean terminal(String s) {
        return Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(s);
    }

    public JSONObject current(String tenant, String subject, String run) {
        return store.tx(
                c -> {
                    store.run(c, tenant, subject, run, false);
                    JSONObject w =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_wait WHERE run_id=? AND status='pending' ORDER BY created_at DESC LIMIT 1",
                                            run));
                    return w == null ? null : view(w);
                });
    }

    private JSONObject view(JSONObject w) {
        return new JSONObject()
                .put("waitId", w.getString("wait_id"))
                .put("kind", w.getString("kind"))
                .put("expiresAt", w.getLong("expires_at"))
                .put("argumentsHash", w.getString("arguments_hash"))
                .put("details", new JSONObject(w.getString("payload_json")));
    }

    public void waitFor(Lease l, long expected, String kind, JSONObject payload, long expires) {
        if (!Arrays.asList("input", "approval").contains(kind))
            throw new StoreException("INVALID_WAIT_KIND");
        store.tx(
                c -> {
                    JSONObject r = store.run(c, l.tenant, l.subject, l.runId, true);
                    store.owner(c, r, l);
                    version(r, expected);
                    if (!r.getString("state").equals("running") || expires <= store.now(c))
                        throw new StoreException("WAIT_EXPIRED");
                    String wait = hash(new JSONArray().put(l.runId).put(kind).put(payload));
                    JSONObject prior = first(rows(c, "SELECT * FROM harness_wait WHERE wait_id=?", wait));
                    if (prior != null) throw new StoreException("WAIT_ALREADY_CONSUMED");
                    String ah = kind.equals("approval") ? payload.getString("argumentsHash") : hash(payload);
                    update(
                            c,
                            "INSERT INTO harness_wait VALUES (?,?,?,'pending',?,?,?,?)",
                            wait,
                            l.runId,
                            kind,
                            canonical(payload),
                            ah,
                            expires,
                            store.now(c));
                    String state = kind.equals("input") ? "waiting_input" : "waiting_approval";
                    update(c, "UPDATE harness_run SET state=? WHERE run_id=?", state, l.runId);
                    r.put("state", state);
                    store.emit(
                            c,
                            r,
                            "run." + state,
                            new JSONObject()
                                    .put("waitId", wait)
                                    .put("expiresAt", expires)
                                    .put("details", payload),
                            "control");
                    return null;
                });
    }

    public boolean handled(String run, String kind, String binding) {
        return store.tx(
                c -> {
                    JSONArray all =
                            rows(
                                    c,
                                    "SELECT payload_json FROM harness_wait WHERE run_id=? AND kind=? AND status IN ('resumed','approved')",
                                    run,
                                    kind);
                    for (int i = 0; i < all.length(); i++)
                        if (binding.equals(
                                new JSONObject(all.getJSONObject(i).getString("payload_json"))
                                        .optString("binding"))) return true;
                    return false;
                });
    }

    public JSONObject apply(
            String tenant,
            String subject,
            String actor,
            String run,
            String key,
            String operation,
            String waitId,
            JSONObject payload,
            boolean approver) {
        if (!Arrays.asList("resume", "approval", "cancel").contains(operation))
            throw new StoreException("INVALID_CONTROL");
        String
                receipt = hash(new JSONArray().put(tenant).put(actor).put(run).put(operation).put(id(key))),
                digest = hash(new JSONObject().put("waitId", waitId).put("payload", payload));
        return store.tx(
                c -> {
                    JSONObject r = store.run(c, tenant, subject, run, true);
                    // Authentication is outside, replay precedes expectedVersion and terminal checks.
                    JSONObject old =
                            first(rows(c, "SELECT * FROM harness_control_receipt WHERE receipt_id=?", receipt));
                    if (old != null) {
                        if (!digest.equals(old.getString("payload_hash")))
                            throw new StoreException("IDEMPOTENCY_CONFLICT");
                        return new JSONObject(old.getString("response_json"));
                    }
                    version(r, payload.getLong("expectedVersion"));
                    if (terminal(r.getString("state"))) throw new StoreException("RUN_TERMINAL");
                    if (!operation.equals("cancel")
                            && first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                            != null) throw new StoreException("CONTROL_FORBIDDEN");
                    if (operation.equals("cancel")) {
                        update(c, "UPDATE harness_run SET state='cancelling' WHERE run_id=?", run);
                        r.put("state", "cancelling");
                        update(
                                c,
                                "UPDATE harness_wait SET status='cancelled' WHERE run_id=? AND status='pending'",
                                run);
                        // Revocation is atomic with the cancel receipt, so no new broker dispatch can slip in.
                        update(
                                c,
                                "UPDATE harness_model_job SET cancel_requested=1 WHERE run_id=? AND actual_finished=0",
                                run);
                        update(
                                c,
                                "UPDATE harness_tool_job SET cancel_requested=1 WHERE run_id=? AND actual_finished=0",
                                run);
                    } else {
                        JSONObject w =
                                first(
                                        rows(
                                                c,
                                                "SELECT * FROM harness_wait WHERE wait_id=? AND run_id=?" + lock,
                                                id(waitId),
                                                run));
                        if (w == null) throw new StoreException("WAIT_NOT_FOUND");
                        String kind = operation.equals("resume") ? "input" : "approval";
                        if (!w.getString("kind").equals(kind)
                                || !w.getString("status").equals("pending")
                                || !r.getString("state").equals("waiting_" + kind))
                            throw new StoreException("WAIT_STATE_CONFLICT");
                        if (w.getLong("expires_at") <= store.now(c)) throw new StoreException("WAIT_EXPIRED");
                        if (operation.equals("approval")) {
                            if (!approver || actor.equals(subject))
                                throw new StoreException("APPROVER_FORBIDDEN");
                            if (!w.getString("arguments_hash").equals(payload.getString("argumentsHash")))
                                throw new StoreException("APPROVAL_ARGUMENTS_CHANGED");
                        }
                        if (operation.equals("resume")) {
                            JSONArray attachments = payload.getJSONObject("input").getJSONArray("attachmentIds");
                            if (files == null && attachments.length() > 0)
                                throw new StoreException("FILES_DISABLED");
                            if (files != null) files.bind(c, tenant, subject, run, attachments);
                        }
                        String status =
                                operation.equals("resume")
                                        ? "resumed"
                                        : payload.getString("decision").equals("approve") ? "approved" : "rejected";
                        update(
                                c,
                                "UPDATE harness_wait SET status=? WHERE wait_id=? AND status='pending'",
                                status,
                                waitId);
                        String state = status.equals("rejected") ? "cancelling" : "running";
                        update(c, "UPDATE harness_run SET state=? WHERE run_id=?", state, run);
                        r.put("state", state);
                    }
                    JSONObject response =
                            store.emit(
                                    c,
                                    r,
                                    "control." + operation,
                                    new JSONObject()
                                            .put("actor", actor)
                                            .put("waitId", waitId)
                                            .put("payload", payload),
                                    "control");
                    update(
                            c,
                            "INSERT INTO harness_control_receipt VALUES (?,?,?,?)",
                            receipt,
                            digest,
                            canonical(response),
                            store.now(c));
                    return response;
                });
    }

    public JSONArray inputs(String tenant, String subject, String run) {
        return store.tx(
                c -> {
                    store.run(c, tenant, subject, run, false);
                    JSONArray out = new JSONArray();
                    JSONArray events =
                            rows(
                                    c,
                                    "SELECT data_json FROM harness_event WHERE run_id=? AND event_type='control.resume' ORDER BY event_seq",
                                    run);
                    for (int i = 0; i < events.length(); i++)
                        out.put(
                                new JSONObject(events.getJSONObject(i).getString("data_json"))
                                        .getJSONObject("payload")
                                        .getJSONObject("input"));
                    return out;
                });
    }

    public long now() {
        return store.tx(store::now);
    }

    public long cancelTime(String run) {
        return store.tx(
                c -> {
                    JSONObject r =
                            first(
                                    rows(
                                            c,
                                            "SELECT occurred_at FROM harness_event WHERE run_id=? AND event_type IN ('control.cancel','control.approval') ORDER BY event_seq DESC LIMIT 1",
                                            run));
                    return r == null ? store.now(c) : r.getLong("occurred_at");
                });
    }
}
