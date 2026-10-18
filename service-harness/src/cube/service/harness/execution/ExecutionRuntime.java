/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import com.sun.net.httpserver.HttpExchange;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Opt-in development execution runtime, with a trusted fixed identity and private resource repository.
 */
public final class ExecutionRuntime implements AutoCloseable {
    public final ModelJobStore jobs;
    public final ModelDispatcher workers;
    public final RunCoordinator coordinator;
    public final InferenceGateway inference;
    public final ToolBroker broker;
    public final RunnerDispatcher runners;
    public final ProfileRegistry profiles;
    public final ProfileRegistry.Identity identity;
    private final CatalogStore catalog;
    private final JdbcRunStore store;
    private final int maxActive;
    private final Semaphore streams = new Semaphore(2);

    public ExecutionRuntime(JdbcRunStore store, Properties p, long lease) throws Exception {
        this(store, p, lease, () -> null);
    }

    public ExecutionRuntime(JdbcRunStore store, Properties p, long lease, java.util.function.Supplier<cube.aigc.completion.CompletionProvider> provider) throws Exception {
        ModelProtocol.verify();
        this.store = store;
        StoreConfig sc = new StoreConfig(p);
        catalog = new CatalogStore(store, sc.mysql());
        profiles = new ProfileRegistry(catalog);
        Set<String> permissions = new TreeSet<>(Arrays.asList(p.getProperty("execution.permissions", "skill.list,skill.activate,resource.list,resource.read,resource.search").split(",")));
        if (!ProfileRegistry.PLATFORM_PERMISSIONS.containsAll(permissions))
            throw new IllegalArgumentException("RESOURCE_PERMISSIONS_REQUIRED");
        identity = new ProfileRegistry.Identity(p.getProperty("execution.tenant", "development"), p.getProperty("execution.subject", "developer"), permissions);
        maxActive = Integer.parseInt(p.getProperty("execution.max.active", "128"));
        if (maxActive < 1 || maxActive > 256) throw new IllegalArgumentException("INVALID_ADMISSION_LIMIT");
        store.admissionLimit(maxActive);
        Path path = Paths.get(Objects.requireNonNull(p.getProperty("execution.repository"), "execution.repository"));
        PackageRepository repo = new PackageRepository(catalog, new AssetFiles(path, Long.parseLong(p.getProperty("execution.repository.max.bytes", "67108864")), p.getProperty("execution.repository.namespace", "harness-resources")), Collections.emptyMap(), true, Boolean.parseBoolean(p.getProperty("execution.tools.enabled", "false")));
        jobs = new ModelJobStore(store, sc.mysql());
        inference = new InferenceGateway(new InferenceStore(store, sc.mysql()), p, provider);
        workers = new ModelDispatcher(jobs, p, lease, inference);
        broker = Boolean.parseBoolean(p.getProperty("execution.tools.enabled", "false")) ? new ToolBroker(new ToolJobStore(store, sc.mysql()), identity, p) : null;
        runners = broker == null ? null : new RunnerDispatcher(broker, p, lease);
        coordinator = new RunCoordinator(store, jobs, catalog, identity, new ResourceTools(catalog, repo), broker);
    }

    public void start() {
        inference.start();
        coordinator.start();
    }

    public synchronized JSONObject create(String key, JSONObject request) {
        // Durable create replay precedes admission inside the catalog/store (including when full).
        if (jobs.active(identity.tenant) >= maxActive) {
            JSONObject prior = store.findCreate(identity.tenant, identity.subject, key, request.getString("taskProfileId"), request);
            if (prior != null) return prior;
            throw new StoreException("HARNESS_BUSY");
        }
        return profiles.createRun(identity, key, request.getString("taskProfileId"), request);
    }

    public JSONObject read(String run) {
        JSONObject row = store.read(identity.tenant, identity.subject, RuntimeSupport.id(run));
        return new JSONObject().put("snapshot", row.getJSONObject("snapshot")).put("usageSettlement", row.getString("usageSettlement")).put("usage", row.getJSONObject("usage")).put("result", row.get("result"));
    }

    public JSONObject taskProfiles() {
        JSONArray list = catalog.taskProfiles(identity.tenant), out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = new JSONObject(list.getJSONObject(i).getString("definition_json"));
            out.put(new JSONObject().put("profileId", p.getString("id")).put("version", p.getString("version"))
                    .put("modelProfile", p.getJSONObject("model").getString("id") + "@" + p.getJSONObject("model").getString("version"))
                    .put("runtimeProfile", p.getJSONObject("runtime").getString("id") + "@" + p.getJSONObject("runtime").getString("version"))
                    .put("skillReleases", p.getJSONArray("skillReleases")).put("toolSetVersion", p.getString("toolSetVersion")).put("requiredCapabilities", p.getJSONArray("requiredCapabilities")));
        }
        return new JSONObject().put("profiles", out);
    }

    public JSONObject events(String run, long after, int limit) {
        JSONArray events = store.events(identity.tenant, identity.subject, run, after, limit);
        long cursor = after;
        if (events.length() > 0) cursor = events.getJSONObject(events.length() - 1).getLong("eventSeq");
        return new JSONObject().put("events", events).put("nextCursor", cursor);
    }

    /**
     * Finite replay page: bounded memory and lifetime; clients reconnect with the last delivered event ID.
     */
    public void stream(HttpExchange x, JSONObject page) throws IOException {
        if (!streams.tryAcquire()) throw new StoreException("HARNESS_BUSY");
        try {
            if (!(x instanceof JettyExchange)) throw new StoreException("SSE_REQUIRES_NONBLOCKING_HTTP");
            ((JettyExchange) x).stream(page, streams::release);
        } catch (IOException | RuntimeException e) {
            streams.release();
            throw e;
        }
    }

    @Override
    public void close() {
        coordinator.close();
        inference.close();
        if (broker != null) broker.close();
    }
}
