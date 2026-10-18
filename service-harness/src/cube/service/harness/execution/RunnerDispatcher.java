/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.store.StoreException;
import cube.service.harness.store.ToolJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Bounded per-transport runner leases; physical reservations are in the shared durable store.
 */
public final class RunnerDispatcher {
    private static final class Worker {
        String id, instance, nonce, runtime;
        long transport, deadline;
        int capacity;
        boolean draining, healthy;
    }

    private final Map<String, Worker> workers = new HashMap<>();
    private final ToolBroker broker;
    private final ToolJobStore jobs;
    private final Properties config;
    private final long lease;

    public RunnerDispatcher(ToolBroker broker, Properties config, long lease) {
        this.broker = broker;
        this.jobs = broker.jobs;
        this.config = config;
        this.lease = lease;
    }

    private long now() {
        return System.nanoTime() / 1000000;
    }

    private void expire() {
        workers.values().removeIf(w -> w.deadline <= now());
    }

    public synchronized JSONObject apply(JSONObject r, long transport, java.util.function.BooleanSupplier valid) {
        if (!valid.getAsBoolean()) throw new StoreException("STALE_SESSION");
        expire();
        String id = r.getString("workerId"), action = r.getString("action");
        if (!"runner".equals(config.getProperty("worker." + id + ".role"))) throw new StoreException("FORBIDDEN");
        Worker w = workers.get(id);
        if (action.equals("describe")) {
            if (r.has("sessionId")) throw new StoreException("SCHEMA_INVALID");
            JSONObject d = r.getJSONObject("descriptor");
            if (!ToolProtocol.runtime().getString("digest").equals(d.getString("sourceDigest")) || !d.getString("runtime").equals("trusted-python-v1"))
                throw new StoreException("RUNNER_SOURCE_DIGEST_MISMATCH");
            int capacity = Integer.parseInt(config.getProperty("worker." + id + ".capacity", "1"));
            if (capacity != d.getInt("capacity")) throw new StoreException("RUNNER_CAPACITY_MISMATCH");
            if (w != null && (w.transport != transport || !w.instance.equals(r.getString("instanceId"))))
                throw new StoreException("DUPLICATE_WORKER");
            jobs.identity(id, d.getString("runtimeId"), capacity);
            if (w == null) {
                if (workers.size() >= 64) throw new StoreException("REGISTRY_FULL");
                w = new Worker();
                w.id = id;
                w.transport = transport;
                w.instance = r.getString("instanceId");
                w.runtime = d.getString("runtimeId");
                w.capacity = capacity;
                w.nonce = UUID.randomUUID().toString();
                workers.put(id, w);
            }
        } else if (w == null || w.transport != transport || !w.instance.equals(r.getString("instanceId")) || !w.nonce.equals(r.optString("sessionId")))
            throw new StoreException("STALE_SESSION");
        w.deadline = now() + lease;
        w.healthy = r.optBoolean("healthy", true) && jobs.healthy(id);
        if (action.equals("drain")) w.draining = true;
        JSONObject out = new JSONObject().put("protocolVersion", 4).put("requestId", r.getString("requestId")).put("ok", true)
                .put("sessionId", w.nonce).put("leaseMillis", lease).put("registered", !action.equals("unregister")).put("ready", !w.draining && w.healthy);
        JSONArray cancel = new JSONArray(), rows = jobs.cancellations(id);
        for (int i = 0; i < rows.length(); i++) cancel.put(rows.getJSONObject(i).getString("job_id"));
        out.put("cancellations", cancel);
        if(Boolean.parseBoolean(config.getProperty("files.enabled","false"))&&Boolean.parseBoolean(config.getProperty("cleanup.enabled","true")))
            out.put("cleanup",jobs.cleanups(id,w.runtime,Long.parseLong(config.getProperty("cleanup.temp.grace.seconds","60"))*1000));
        if(action.equals("cleanup_ack")) jobs.cleaned(id,w.runtime,r.getString("jobId"),r.getString("jobHash"),r.getString("resultHash"));
        if (action.equals("poll") && !w.draining) {
            JSONObject job = jobs.poll(id, w.runtime, w.instance, w.healthy, broker::wire);
            if (job != null) out.put("job", job);
        }
        if (action.equals("accepted") || action.equals("status") || action.equals("broker_query")) {
            JSONObject j = jobs.evidence(id, w.runtime, r.getString("jobId"), r.getString("jobHash"));
            if (action.equals("accepted")) {
                broker.authorize(j, new JSONObject(j.getString("wire_json")).getJSONObject("grant"));
                jobs.accepted(j.getString("job_id"), j.getString("job_hash"));
                out.put("status", "accepted");
            } else if (action.equals("status")) {
                out.put("status", j.getString("status")).put("actualFinished", j.getInt("actual_finished") == 1);
            } else {
                JSONObject result = broker.query(j, r.getJSONObject("grant"), r.getJSONArray("targets"), r.getJSONArray("fields"));
                for (String k : result.keySet()) out.put(k, result.get(k));
            }
        }
        if (action.equals("result")) {
            JSONObject report = r.getJSONObject("report"), j = jobs.evidence(id, w.runtime, report.getString("jobId"), report.getString("jobHash"));
            JSONObject saved = broker.report(j, report);
            out.put("resultHash", saved.getString("report_hash")).put("actualFinished", saved.getInt("actual_finished") == 1);
        }
        w.healthy = w.healthy && jobs.healthy(id);
        out.put("ready", !w.draining && w.healthy);
        if (action.equals("unregister")) workers.remove(id);
        ToolProtocol.parse("runner-response", cube.service.harness.runtime.RuntimeSupport.bytes(out));
        return out;
    }

    public synchronized void disconnect(long transport) {
        workers.values().removeIf(w -> w.transport == transport);
    }

    public synchronized JSONArray snapshot() {
        expire();
        JSONArray out = new JSONArray();
        for (Worker w : workers.values()) {
            int used = jobs.occupied(w.id);
            boolean healthy = w.healthy && jobs.healthy(w.id);
            out.put(new JSONObject().put("workerId", w.id).put("instanceId", w.instance).put("runtimeId", w.runtime).put("role", "runner").put("ready", !w.draining && healthy).put("capacity", w.capacity).put("occupied", used).put("available", healthy && !w.draining ? Math.max(0, w.capacity - used) : 0));
        }
        return out;
    }
}
