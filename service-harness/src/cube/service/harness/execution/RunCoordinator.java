/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.ContextEngine;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.ResourceTools;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.ModelJobStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A short, fenced durable step per run. No inference or polling sleeps on API/Cell threads.
 */
public final class RunCoordinator implements AutoCloseable {
    private final JdbcRunStore store;
    private final ModelJobStore jobs;
    private final CatalogStore catalog;
    private final ProfileRegistry.Identity identity;
    private final ResourceTools resources;
    private final ToolBroker broker;
    private final ContextEngine contexts;
    private final String owner = "coordinator-" + UUID.randomUUID();
    private final Map<String, JdbcRunStore.Lease> leases = new HashMap<>();
    private final ScheduledExecutorService executor;
    private volatile String lastError;

    public RunCoordinator(JdbcRunStore store, ModelJobStore jobs, CatalogStore catalog, ProfileRegistry.Identity identity, ResourceTools resources) {
        this(store, jobs, catalog, identity, resources, null);
    }

    public RunCoordinator(JdbcRunStore store, ModelJobStore jobs, CatalogStore catalog, ProfileRegistry.Identity identity, ResourceTools resources, ToolBroker broker) {
        this.broker = broker;
        this.store = store;
        this.jobs = jobs;
        this.catalog = catalog;
        this.identity = identity;
        this.resources = resources;
        this.contexts = new ContextEngine(catalog, resources);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harness-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::scan, 0, 100, TimeUnit.MILLISECONDS);
    }

    public String lastError() {
        return lastError;
    }

    private void scan() {
        try {
            JSONArray batch = jobs.runnable(identity.tenant, 256);
            for (int i = 0; i < batch.length(); i++) {
                JSONObject row = batch.getJSONObject(i);
                if (!identity.subject.equals(row.getString("subject_id"))) continue;
                try {
                    step(row.getString("run_id"));
                } catch (StoreException e) {
                    lastError = e.code;
                    if (Arrays.asList("STALE_OWNER", "LEASE_BUSY", "VERSION_CONFLICT").contains(e.code))
                        leases.remove(row.getString("run_id"));
                } catch (Exception e) {
                    lastError = "COORDINATOR_STEP_FAILED";
                }
            }
        } catch (Exception e) {
            lastError = "COORDINATOR_STORE_UNAVAILABLE";
        }
    }

    private long version(String run) {
        return store.read(identity.tenant, identity.subject, run).getJSONObject("snapshot").getLong("version");
    }

    private JSONObject state(String run) {
        return store.read(identity.tenant, identity.subject, run).getJSONObject("snapshot");
    }

    private JSONArray intents(String run, String kind) {
        return store.intents(identity.tenant, identity.subject, run, kind);
    }

    private void confirm(JdbcRunStore.Lease l, String kind, JSONObject row, JSONObject result) {
        store.confirmIntent(l, version(l.runId), kind, row.getString("intent_id"), row.getInt("attempt"), row.getLong("origin_fence"), row.getString("intent_hash"), result);
    }

    private void finish(JdbcRunStore.Lease l, String state, String settlement, JSONObject result) {
        store.finish(l, version(l.runId), state, settlement, result, new JSONArray());
        leases.remove(l.runId);
    }

    private void fail(JdbcRunStore.Lease l, String code) {
        finish(l, "failed", "confirmed", new JSONObject().put("errorCode", code));
    }

    private void tool(JdbcRunStore.Lease l, JSONObject row, boolean expired, boolean terminal) throws Exception {
        JSONObject call = new JSONObject(row.getString("payload_json")), result = new JSONObject().put("artifacts", new JSONArray());
        try {
            JSONObject job = broker == null ? null : broker.jobs.find(l.runId, row.getString("intent_id"), row.getInt("attempt"));
            if (job == null && !terminal && !expired && broker != null) {
                JSONObject definition = resources.executable(identity, l.runId, call.getString("tool"));
                if(definition != null) job = broker.enqueue(l, row, definition);
            }
            if(job != null) {
                if(job.getInt("actual_finished") == 0) {
                    if(expired || terminal) broker.jobs.cancel(l, job.getString("job_id"));
                    if(!terminal && (expired || job.getString("status").equals("unknown")))
                        finish(l, expired ? "timed_out" : "failed", "unknown", new JSONObject().put("errorCode", expired ? "RUN_LIFETIME_EXCEEDED" : "TOOL_EXECUTION_UNKNOWN"));
                    return;
                }
                if(job.getString("effect_status").equals("unknown")) {
                    if(!terminal) finish(l, "failed", "unknown", new JSONObject().put("errorCode", "TOOL_EFFECT_UNKNOWN"));
                    return;
                }
                confirm(l, "call", row, new JSONObject(job.getString("result_json")));
                return;
            }
            if(terminal || expired) {confirm(l, "call", row, cube.service.harness.store.ToolJobStore.error("TOOL_CANCELLED"));return;}
            Object value = resources.invoke(identity, l.runId, call.getString("tool"), StrictJson.object(call.getString("argumentsJson").getBytes(java.nio.charset.StandardCharsets.UTF_8)), l);
            result.put("status", "success").put("dataJson", JdbcRunStore.canonical(value));
            Contract.parse("tool-result", RuntimeSupport.bytes(result));
        } catch (Exception e) {
            if(e instanceof StoreException && Arrays.asList("STORE_UNAVAILABLE", "STORE_COMMIT_OUTCOME_UNKNOWN", "STALE_OWNER", "VERSION_CONFLICT", "TOOL_QUEUE_FULL").contains(((StoreException)e).code)) throw e;
            result = new JSONObject().put("status", "error").put("artifacts", new JSONArray()).put("errorCode", e instanceof StoreException ? ((StoreException) e).code : "TOOL_OUTPUT_INVALID");
        }
        confirm(l, "call", row, result);
    }

    public synchronized void step(String run) throws Exception {
        JSONObject read = store.read(identity.tenant, identity.subject, run), s = read.getJSONObject("snapshot");
        String state = s.getString("state");
        boolean terminal = Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(state);
        if (terminal && s.getString("executionSettlement").equals("confirmed") && read.getString("usageSettlement").equals("settled"))
            return;
        JSONObject pin = catalog.pinned(identity.tenant, identity.subject, run), task = pin.getJSONObject("task"), model = pin.getJSONObject("model");
        JSONObject limits = task.optJSONObject("executionLimits");
        if (limits == null)
            limits = new JSONObject().put("maxRounds", 8).put("maxTools", 32).put("maxRepairs", 2).put("maxLifetimeMillis", 300000);
        boolean expired = jobs.expired(identity.tenant, identity.subject, run, limits.getLong("maxLifetimeMillis"));
        if (state.equals("waiting_input") && !expired) return;
        JdbcRunStore.Lease l = leases.get(run);
        if (l == null) {
            l = store.acquire(identity.tenant, identity.subject, run, owner, s.getLong("version"), 10000);
            leases.put(run, l);
        } else store.renew(l, 10000);
        if (state.equals("queued")) store.transition(l, version(run), "running", new JSONObject());
        if (state.equals("waiting_input")) {
            if (expired)
                finish(l, "timed_out", "confirmed", new JSONObject().put("errorCode", "RUN_LIFETIME_EXCEEDED"));
            else {
                store.release(l, version(run));
                leases.remove(run);
            }
            return;
        }
        JSONArray rounds = intents(run, "round"), calls = intents(run, "call");
        int repairs = 0;
        // Reconcile every historical round before any new intent. Callbacks only wrote evidence, never state.
        for (int i = 0; i < rounds.length(); i++) {
            JSONObject r = rounds.getJSONObject(i), j = jobs.enqueue(l, r, model);
            if (!j.isNull("outcome_json")) {
                JSONObject o = new JSONObject(j.getString("outcome_json"));
                repairs += Math.max(0, o.getJSONArray("audit").length() - 1);
            }
            if (!r.getString("status").equals("confirmed")) {
                if (j.getInt("actual_finished") == 0) {
                    if (expired) {
                        jobs.cancel(l, j.getString("job_id"));
                        finish(l, "timed_out", "unknown", new JSONObject().put("errorCode", "RUN_LIFETIME_EXCEEDED"));
                    }
                    return;
                }
                JSONObject o = new JSONObject(j.getString("outcome_json"));
                if (!o.getString("status").equals("success")) {
                    // Store the validated failure evidence. No next model context is built for a failed round.
                    confirm(l, "round", r, new JSONObject().put("errorCode", o.getString("errorCode")));
                    if (o.has("usage")) settle(l, r, j, o);
                    if (!terminal)
                        finish(l, "failed", "confirmed", new JSONObject().put("errorCode", o.getString("errorCode")));
                    return;
                }
                confirm(l, "round", r, o.getJSONObject("decision"));
                return;
            }
            JSONObject o = new JSONObject(j.getString("outcome_json"));
            if (o.has("usage")) {
                settle(l, r, j, o);
            }
        }
        for (int i = 0; i < calls.length(); i++)
            if (!calls.getJSONObject(i).getString("status").equals("confirmed")) {
                tool(l, calls.getJSONObject(i), expired, terminal);
                return;
            }
        if (terminal) {store.release(l, version(run)); leases.remove(run); return;}
        if (expired) {
            finish(l, "timed_out", "confirmed", new JSONObject().put("errorCode", "RUN_LIFETIME_EXCEEDED"));
            return;
        }
        if (repairs > limits.getInt("maxRepairs")) {
            fail(l, "REPAIR_LIMIT_EXCEEDED");
            return;
        }
        JSONObject snapshot;
        try {
            snapshot = contexts.assemble(identity, run, new JSONArray());
        } catch (StoreException e) {
            fail(l, e.code.split(":")[0]);
            return;
        }
        contexts.checkpoint(l, version(run), snapshot);
        JSONArray pending = snapshot.getJSONArray("pending");
        if (pending.length() > 0) {
            for (int i = 0; i < pending.length(); i++) {
                JSONObject p = pending.getJSONObject(i);
                if (!p.getString("kind").equals("proposal")) throw new StoreException("CONTEXT_PENDING_EXECUTION");
                if (calls.length() + pending.length() > limits.getInt("maxTools")) {
                    fail(l, "TOOL_LIMIT_EXCEEDED");
                    return;
                }
                JSONObject call = p.getJSONObject("call");
                store.intent(l, version(run), "call", call.getString("proposalId"), 1, call, 0, 0);
            }
            return;
        }
        if (rounds.length() > 0) {
            // Round IDs are fixed-width, so the durable order and lexical order agree.
            JSONObject last = new JSONObject(rounds.getJSONObject(rounds.length() - 1).getString("result_json"));
            if (last.getString("kind").equals("final")) {
                finish(l, "succeeded", "confirmed", last);
                return;
            }
            if (last.getString("kind").equals("need_input")) {
                store.transition(l, version(run), "waiting_input", last);
                store.release(l, version(run));
                leases.remove(run);
                return;
            }
        }
        if (rounds.length() >= limits.getInt("maxRounds")) {
            fail(l, "ROUND_LIMIT_EXCEEDED");
            return;
        }
        JSONObject request = ContextEngine.modelInput(snapshot);
        request.put("maxRepairs", Math.max(0, Math.min(1, limits.getInt("maxRepairs") - repairs)));
        String round = String.format(java.util.Locale.ROOT, "round-%04d", rounds.length() + 1);
        store.intent(l, version(run), "round", round, 1, new JSONObject().put("contextDigest", JdbcRunStore.hash(snapshot)).put("request", request), snapshot.getLong("inputTokens"), snapshot.getLong("outputReserve"));
    }

    private void settle(JdbcRunStore.Lease l, JSONObject r, JSONObject j, JSONObject o) {
        JSONObject usage = o.getJSONObject("usage");
        store.settleUsage(l, version(l.runId), new JSONObject().put("roundId", r.getString("intent_id")).put("attempt", r.getInt("attempt")).put("evidenceId", j.getString("job_id")).put("expectedRevision", 0).put("input", usage.getLong("input")).put("output", usage.getLong("output")));
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
