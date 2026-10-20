/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.WorkerRegistry;
import cube.service.harness.store.ModelJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Worker pull transport with instance/session fencing; only trusted configuration grants capacity.
 */
public final class ModelDispatcher {

    private final WorkerRegistry sessions;

    private final ModelJobStore jobs;

    private final Properties config;

    private final InferenceGateway inference;

    private final Map<String, JSONObject> descriptions = new HashMap<>();

    private final Map<String, Boolean> health = new HashMap<>();

    public ModelDispatcher(ModelJobStore jobs, Properties config, long lease) {
        this(jobs, config, lease, null);
    }

    public ModelDispatcher(ModelJobStore jobs, Properties config, long lease, InferenceGateway inference) {
        this.jobs = jobs;
        this.config = config;
        this.sessions = new WorkerRegistry(lease);
        this.inference = inference;
    }

    public synchronized JSONObject apply(JSONObject request, long transport, java.util.function.BooleanSupplier valid) {
        String action = request.getString("action"), worker = request.getString("workerId");
        int protocol = request.getInt("protocolVersion");
        JSONObject descriptor = descriptions.get(worker);
        if (action.equals("describe")) {
            descriptor = new JSONObject(request.getJSONObject("descriptor").toString());
            String prefix = "worker." + worker + ".";
            if (!"model".equals(config.getProperty(prefix + "role")) || !descriptor.getString("model").equals(config.getProperty(prefix + "model")))
                throw new IllegalArgumentException("FORBIDDEN");
            String model = descriptor.getString("model");
            String adapter = protocol == 3 ? (descriptor.getBoolean("nativeTools") ? "openai-tools-v2" : "openai-json-v2")
                    : model.equals("scripted") ? "scripted-v1" : model.equals("Baize") ? "baize-json-v1" : "baize2-tools-v1";
            if (!adapter.equals(descriptor.getString("adapter")) || !ModelProfiles.adapterDigest(adapter).equals(descriptor.getString("profileDigest")))
                throw new IllegalArgumentException("MODEL_PROFILE_MISMATCH");
            String backend = config.getProperty(prefix + "backend");
            if (backend == null) throw new IllegalArgumentException("BACKEND_REQUIRED");
            int capacity = Integer.parseInt(config.getProperty("backend." + backend + ".capacity", "0"));
            if (capacity < 1 || capacity > 32) throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
            if (protocol == 2) {
                if (!backend.equals(descriptor.getString("backendId")) || descriptor.getInt("capacity") != capacity
                        || descriptor.getBoolean("nativeTools") != model.equals("Baize2"))
                    throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
                jobs.backend(backend, descriptor, capacity);
            } else {
                if (inference == null) throw new IllegalArgumentException("INFERENCE_REQUIRED");
                jobs.backend(backend, new JSONObject().put("backendId", backend).put("type", config.getProperty("backend." + backend + ".type", "unit")), capacity);
                descriptor.put("backendId", backend).put("capacity", capacity).put("protocolVersion", 3);
            }
        }
        JSONObject normalized = new JSONObject(request.toString());
        normalized.remove("descriptor");
        normalized.remove("report");
        normalized.remove("healthy");
        for (String k : Arrays.asList("jobId", "jobInstanceId", "wireAttempt", "requestHash", "requestJson", "after"))
            normalized.remove(k);
        normalized.put("action", action.equals("describe") ? "register" : action.equals("drain") ? "drain" : action.equals("unregister") ? "unregister" : "heartbeat");
        JSONObject response = sessions.apply(normalized, transport, valid);
        if (action.equals("describe")) descriptions.put(worker, new JSONObject(descriptor.toString()));
        if (descriptor == null) throw new IllegalArgumentException("STALE_SESSION");
        if (descriptor.optInt("protocolVersion", 2) != protocol)
            throw new IllegalArgumentException("PROTOCOL_MISMATCH");
        health.put(worker, request.optBoolean("healthy", true));
        response.put("ready", response.getBoolean("ready") && health.get(worker)
                && (protocol == 3 ? inference.ready(descriptor) : descriptor.getString("model").equals("scripted")));
        if (action.equals("actual_finished")) {
            JSONObject report = new JSONObject(request.getJSONObject("report").toString());
            if (protocol == 3) {
                JSONObject bound = jobs.jobById(report.getString("jobId"));
                if (bound == null || !worker.equals(bound.optString("worker_id")) || !report.getString("instanceId").equals(bound.optString("instance_id")))
                    throw new IllegalArgumentException("JOB_EVIDENCE_MISMATCH");
                JSONObject outcome = ModelProtocol.outcome(report.getString("outcomeJson"), report.getBoolean("actualFinished"));
                outcome = inference.ledger.reconcile(report.getString("jobId"), outcome);
                report.put("outcomeJson", outcome.toString()).put("actualFinished", !outcome.getString("status").equals("unknown"));
            }
            jobs.report(worker, report);
        }
        if (action.startsWith("inference_")) response.put("inference", inference.apply(request));
        if (action.equals("poll")) {
            JSONObject job = jobs.poll(worker, request.getString("instanceId"), request.getString("sessionId"), descriptor, 1, response.getBoolean("ready"));
            if (job != null) response.put("job", job);
        }
        return response.put("protocolVersion", protocol).put("requestId", request.getString("requestId")).put("ok", true)
                .put("cancellations", jobs.cancellations(worker));
    }

    public synchronized JSONArray snapshot() {
        JSONArray out = sessions.snapshot();
        for (int i = 0; i < out.length(); i++) {
            JSONObject w = out.getJSONObject(i);
            boolean healthy = Boolean.TRUE.equals(health.get(w.getString("workerId")));
            JSONObject d = descriptions.get(w.getString("workerId"));
            boolean backendReady = d.optInt("protocolVersion", 2) == 3 ? inference.ready(d) : d.getString("model").equals("scripted");
            w.put("healthy", healthy).put("ready", w.getBoolean("ready") && healthy && backendReady).put("descriptor", d);
        }
        return out;
    }

    public synchronized void disconnect(long id) {
        sessions.disconnect(id);
    }

    public synchronized boolean ready() {
        JSONArray list = snapshot();
        for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).getBoolean("ready")) return true;
        return false;
    }

    public synchronized JSONObject capacity() {
        JSONObject view = jobs.capacity();
        JSONArray workers = snapshot(), backends = view.getJSONArray("backends"), occupied = view.getJSONArray("workers");
        Set<String> busy = new HashSet<>();
        for (int n = 0; n < occupied.length(); n++) busy.add(occupied.getJSONObject(n).getString("worker_id"));
        List<CapacityView> views = new ArrayList<>();
        for (int n = 0; n < backends.length(); n++) {
            JSONObject backend = backends.getJSONObject(n);
            String id = backend.getString("backend_id");
            CapacityView row = new CapacityView(backend);
            views.add(row);
            for (int j = 0; j < workers.length(); j++) {
                JSONObject worker = workers.getJSONObject(j), d = worker.getJSONObject("descriptor");
                if (!d.getString("backendId").equals(id) || !worker.getBoolean("ready")) continue;
                row.ready++;
                boolean idle = !busy.contains(worker.getString("workerId"));
                if (idle) row.idle++;
                String model = d.getString("model");
                int[] processing = row.processing.computeIfAbsent(model, key -> new int[2]);
                processing[0]++;
                if (idle) processing[1]++;
                if (d.optInt("protocolVersion", 2) == 3) {
                    if (!row.models.containsKey(model)) row.models.put(model, inference.capacity(d));
                } else row.legacy = true;
            }
        }
        // Assign shared groups to backends with executable work first, so a busy alias cannot
        // consume the entire reported concurrency while another alias reports available slots.
        views.sort((a, b) -> Integer.compare(b.executable(), a.executable()));
        int available = 0, total = 0;
        CapacityBudget budgets = new CapacityBudget();
        for (CapacityView row : views)
            for (JSONObject capacity : row.models.values()) {
                JSONArray groups = CapacityBudget.entries(capacity);
                if (groups != null) budgets.observe(groups);
            }
        for (CapacityView row : views) {
            JSONObject backend = row.backend;
            int physical = Math.max(0, backend.getInt("capacity") - backend.getInt("occupied"));
            int live = row.legacy ? physical : 0, registered = row.legacy ? row.ready : 0;
            List<String> modelOrder = new ArrayList<>(row.models.keySet());
            modelOrder.sort((a, b) -> Integer.compare(row.modelAvailable(b), row.modelAvailable(a)));
            for (String model : modelOrder) {
                JSONObject capacity = row.models.get(model);
                int[] processing = row.processing.get(model);
                JSONArray groups = CapacityBudget.entries(capacity);
                if (groups != null) {
                    int free = budgets.claim(groups, true, Math.max(0, Math.min(processing[1], physical - live)));
                    live += free;
                    registered += free;
                    row.assigned.put(model, free);
                } else if (config.getProperty("backend." + backend.getString("backend_id") + ".type", "unit").equals("unit")) {
                    live += Math.min(processing[1], capacity.getInt("available"));
                    registered += Math.min(processing[0], capacity.getInt("registered"));
                } else {
                    live = Math.max(live, capacity.getInt("available"));
                    registered = Math.max(registered, capacity.getInt("registered"));
                }
            }
            row.live = live;
            row.registered = registered;
        }
        // Allocate remaining registered capacity only after every backend's available routes
        // have reserved their share. One busy model must not hide another backend's free route.
        for (CapacityView row : views) {
            JSONObject backend = row.backend;
            int physical = Math.max(0, backend.getInt("capacity") - backend.getInt("occupied"));
            int live = row.live, registered = row.registered;
            for (String model : row.models.keySet()) {
                JSONArray groups = CapacityBudget.entries(row.models.get(model));
                if (groups != null) registered += budgets.claim(groups, false,
                        Math.max(0, Math.min(row.processing.get(model)[0] - row.assigned.getOrDefault(model, 0),
                                backend.getInt("capacity") - registered)));
            }
            int free = Math.min(row.idle, Math.min(physical, live));
            int concurrency = Math.min(row.ready, Math.min(backend.getInt("capacity"), registered));
            backend.put("processingSlots", row.ready).put("processingAvailable", row.idle).put("inferenceUnits", registered)
                    .put("inferenceAvailable", Math.min(physical, live)).put("readyUnits", row.ready).put("available", free).put("executableConcurrency", concurrency);
            available += free;
            total += concurrency;
        }
        view.remove("workers");
        return view.put("registeredUnits", workers.length()).put("available", available).put("executableConcurrency", total);
    }

    private static final class CapacityView {
        final JSONObject backend;
        final Map<String, JSONObject> models = new LinkedHashMap<>();
        final Map<String, int[]> processing = new HashMap<>();
        final Map<String, Integer> assigned = new HashMap<>();
        int ready, idle, live, registered;
        boolean legacy;

        CapacityView(JSONObject backend) {
            this.backend = backend;
        }

        int modelAvailable(String model) {
            return Math.min(processing.get(model)[1], models.get(model).getInt("available"));
        }

        int executable() {
            int free = legacy ? idle : 0;
            for (String model : models.keySet()) free += modelAvailable(model);
            return Math.min(free, Math.max(0, backend.getInt("capacity") - backend.getInt("occupied")));
        }
    }
}
