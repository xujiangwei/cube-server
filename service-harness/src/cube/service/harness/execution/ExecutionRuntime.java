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
 * 按可信请求身份隔离数据，后台执行从持久账本恢复任务归属。
 */
public final class ExecutionRuntime implements AutoCloseable {

    public final ModelJobStore jobs;

    public final ModelDispatcher workers;

    public final RunCoordinator coordinator;

    public final InferenceGateway inference;

    public final ToolBroker broker;

    public final RunnerDispatcher runners;

    public final ProfileRegistry profiles;

    public final Set<String> permissions;
    public final ControlStore controls;
    public final TraceStore traces;
    public final cube.service.harness.files.FileService files;

    private final CatalogStore catalog;

    private final JdbcRunStore store;

    private final int maxActive;
    private volatile boolean accepting;

    private final Semaphore streams = new Semaphore(2);

    public ExecutionRuntime(JdbcRunStore store, Properties p, long lease) throws Exception {
        this(store, p, lease, () -> null);
    }

    public ExecutionRuntime(
            JdbcRunStore store,
            Properties p,
            long lease,
            java.util.function.Supplier<cube.aigc.completion.CompletionProvider> provider)
            throws Exception {
        ModelProtocol.verify();
        this.store = store;
        traces = new TraceStore(store, p);
        StoreConfig sc = new StoreConfig(p);
        catalog = new CatalogStore(store, sc.mysql());
        Set<String> configured = new TreeSet<>();
        for (String value : p.getProperty("execution.permissions",
                "skill.list,skill.activate,resource.list,resource.read,resource.search").split(","))
            if (!value.trim().isEmpty()) configured.add(RuntimeSupport.id(value.trim()));
        permissions = Collections.unmodifiableSet(configured);
        files =
                Boolean.parseBoolean(p.getProperty("files.enabled", "false"))
                        ? new cube.service.harness.files.FileService(store, p, null)
                        : null;
        profiles = new ProfileRegistry(catalog, files == null ? null : files.ledger);
        controls = new ControlStore(store, sc.mysql()).files(files == null ? null : files.ledger);
        maxActive = Integer.parseInt(p.getProperty("execution.max.active", "128"));
        if (maxActive < 1 || maxActive > 256)
            throw new IllegalArgumentException("INVALID_ADMISSION_LIMIT");
        store.admissionLimit(maxActive);
        accepting = Boolean.parseBoolean(p.getProperty("execution.accepting", "true"));
        Path path =
                Paths.get(
                        Objects.requireNonNull(
                                p.getProperty("execution.repository"), "execution.repository"));
        PackageRepository repo =
                new PackageRepository(
                        catalog,
                        new AssetFiles(
                                path,
                                Long.parseLong(
                                        p.getProperty(
                                                "execution.repository.max.bytes", "67108864")),
                                p.getProperty(
                                        "execution.repository.namespace", "harness-resources")),
                        Collections.emptyMap(),
                        true,
                        Boolean.parseBoolean(p.getProperty("execution.tools.enabled", "false")));
        jobs = new ModelJobStore(store, sc.mysql());
        inference = new InferenceGateway(new InferenceStore(store, sc.mysql()), p, provider);
        workers = new ModelDispatcher(jobs, p, lease, inference);
        broker =
                Boolean.parseBoolean(p.getProperty("execution.tools.enabled", "false"))
                        ? new ToolBroker(new ToolJobStore(store, sc.mysql()), permissions, p)
                        .files(files)
                        : null;
        runners = broker == null ? null : new RunnerDispatcher(broker, p, lease);
        coordinator =
                new RunCoordinator(
                        store,
                        jobs,
                        catalog,
                        permissions,
                        new ResourceTools(catalog, repo).files(files),
                        broker)
                        .controls(controls, files, p);
    }

    public void start() {
        if (files != null) files.start();
        inference.start();
        coordinator.start();
    }

    public synchronized JSONObject create(ProfileRegistry.Identity identity, String key, JSONObject request) {
        if (!accepting) {
            JSONObject prior =
                    store.findCreate(
                            identity.tenant,
                            identity.subject,
                            key,
                            request.getString("taskProfileId"),
                            request);
            if (prior != null) return prior;
            throw new StoreException("HARNESS_DRAINING");
        }
        // Durable create replay precedes admission inside the catalog/store (including when full).
        if (jobs.active(identity.tenant) >= maxActive) {
            JSONObject prior =
                    store.findCreate(
                            identity.tenant,
                            identity.subject,
                            key,
                            request.getString("taskProfileId"),
                            request);
            if (prior != null) return prior;
            throw new StoreException("HARNESS_BUSY");
        }
        return profiles.createRun(identity, key, request.getString("taskProfileId"), request);
    }

    public boolean accepting() {
        return accepting;
    }

    public synchronized JSONObject admission(Boolean enabled) {
        if (enabled != null) accepting = enabled;
        return jobs.drainStatus()
                .put("accepting", accepting)
                .put("scope", "this-jvm; ledger counts cover all tenants");
    }

    /** 仅供已获授权的同域审批人或 trace 读取者解析任务所有者。 */
    public ProfileRegistry.Identity owner(ProfileRegistry.Identity caller, String run) {
        return new ProfileRegistry.Identity(caller.tenant, store.subject(caller.tenant, run), permissions);
    }

    public JSONObject read(ProfileRegistry.Identity identity, String run) {
        JSONObject row = store.read(identity.tenant, identity.subject, RuntimeSupport.id(run));
        JSONObject result =
                new JSONObject()
                        .put("snapshot", row.getJSONObject("snapshot"))
                        .put("usageSettlement", row.getString("usageSettlement"))
                        .put("usage", row.getJSONObject("usage"))
                        .put("result", row.get("result"));
        JSONObject wait = controls.current(identity.tenant, identity.subject, run);
        if (wait != null) result.put("interaction", wait);
        return result;
    }

    public JSONObject control(
            ProfileRegistry.Identity identity,
            String run,
            String key,
            String operation,
            String wait,
            JSONObject payload,
            String actor,
            boolean approver) {
        return controls.apply(
                identity.tenant,
                identity.subject,
                actor,
                RuntimeSupport.id(run),
                key,
                operation,
                wait,
                payload,
                approver);
    }

    public JSONObject taskProfiles(ProfileRegistry.Identity identity) {
        JSONArray list = catalog.taskProfiles(identity.tenant), out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = new JSONObject(list.getJSONObject(i).getString("definition_json"));
            out.put(
                    new JSONObject()
                            .put("profileId", p.getString("id"))
                            .put("version", p.getString("version"))
                            .put(
                                    "modelProfile",
                                    p.getJSONObject("model").getString("id")
                                            + "@"
                                            + p.getJSONObject("model").getString("version"))
                            .put(
                                    "runtimeProfile",
                                    p.getJSONObject("runtime").getString("id")
                                            + "@"
                                            + p.getJSONObject("runtime").getString("version"))
                            .put("skillReleases", p.getJSONArray("skillReleases"))
                            .put("toolSetVersion", p.getString("toolSetVersion"))
                            .put("requiredCapabilities", p.getJSONArray("requiredCapabilities")));
        }
        return new JSONObject().put("profiles", out);
    }

    public JSONObject events(ProfileRegistry.Identity identity, String run, long after, int limit) {
        JSONArray events = store.events(identity.tenant, identity.subject, run, after, limit);
        long cursor = after;
        if (events.length() > 0)
            cursor = events.getJSONObject(events.length() - 1).getLong("eventSeq");
        return new JSONObject().put("events", events).put("nextCursor", cursor);
    }

    /**
     * Finite replay page: bounded memory and lifetime; clients reconnect with the last delivered
     * event ID.
     */
    public void stream(HttpExchange x, JSONObject page) throws IOException {
        if (!streams.tryAcquire()) throw new StoreException("HARNESS_BUSY");
        try {
            if (!(x instanceof JettyExchange))
                throw new StoreException("SSE_REQUIRES_NONBLOCKING_HTTP");
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
        if (files != null) files.close();
    }
}
