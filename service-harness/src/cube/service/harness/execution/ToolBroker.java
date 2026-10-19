/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import cube.service.harness.store.ToolJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;

import static cube.service.harness.store.JdbcRunStore.canonical;
import static cube.service.harness.store.JdbcRunStore.hash;

/**
 * Server-only read-only Broker. Child code receives neither grants nor backend URLs/credentials.
 */
public final class ToolBroker implements AutoCloseable {
    public final ToolJobStore jobs;
    private final ProfileRegistry.Identity identity;
    private final Set<String> permissions;
    private final String key, node;
    private final QueryProviders providers;
    private cube.service.harness.files.FileService files;

    public ToolBroker files(cube.service.harness.files.FileService value) {
        files = value;
        return this;
    }

    private final ThreadPoolExecutor queries;

    private final ScheduledExecutorService reconciliation =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "harness-broker-reconcile");
                        t.setDaemon(true);
                        return t;
                    });

    private final Semaphore permits = new Semaphore(34);
    private final ExecutorService recoveryQueries =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    r -> {
                        Thread t = new Thread(r, "harness-provider-recovery");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private long lastRecovery;
    private final Set<String> recovering = ConcurrentHashMap.newKeySet();
    private final Map<String, JSONObject> pending = new ConcurrentHashMap<>();

    public ToolBroker(ToolJobStore jobs, ProfileRegistry.Identity identity, Properties p)
            throws Exception {
        this(jobs, identity, identity.permissions, p);
    }

    public ToolBroker(ToolJobStore jobs, Set<String> permissions, Properties p) throws Exception {
        this(jobs, null, permissions, p);
    }

    private ToolBroker(ToolJobStore jobs, ProfileRegistry.Identity identity, Set<String> permissions,
                       Properties p) throws Exception {
        ToolProtocol.verify();
        this.jobs = jobs;
        this.identity = identity;
        this.permissions = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(permissions));
        key = p.getProperty("runner.grant.key", "");
        node = RuntimeSupport.id(p.getProperty("broker.node.id", ""));
        if (key.length() < 32) throw new IllegalArgumentException("RUNNER_GRANT_KEY_REQUIRED");
        providers = new QueryProviders(p);
        queries =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<Runnable>(32),
                        r -> {
                            Thread t = new Thread(r, "harness-broker-query");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        jobs.brokerRestart(node);
        reconciliation.scheduleWithFixedDelay(this::flush, 0, 200, TimeUnit.MILLISECONDS);
    }

    private String sign(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : m.doFinal(value.getBytes(StandardCharsets.UTF_8)))
                out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Compatibility helpers for the offline N08 fixture only.
    public static JSONObject parameters() {
        return MockQueryProvider.parameters();
    }

    public static JSONObject outputSchema() {
        return MockQueryProvider.outputSchema();
    }

    private QueryProvider provider(JSONObject job) {
        return providers.get(
                new JSONObject(job.getString("definition_json"))
                        .getJSONObject("tool")
                        .getString("brokerOperation"));
    }

    private void arguments(JSONObject args, QueryProvider provider) {
        Contract.validate(args, provider.inputSchema());
        // Cell v4 query grants remain bounded targets/fields; schemas belong to each provider.
        if (provider.protocolVersion() == 4) {
            RuntimeSupport.strings(args.getJSONArray("targets"));
            RuntimeSupport.strings(args.getJSONArray("fields"));
        }
        provider.validate(args);
    }

    public JSONObject enqueue(JdbcRunStore.Lease l, JSONObject intent, JSONObject definition) {
        JSONObject tool = definition.getJSONObject("tool"),
                runtime = definition.getJSONObject("runtime");
        QueryProvider provider = providers.get(tool.getString("brokerOperation"));
        if ((provider.protocolVersion() == 6) != (provider instanceof WorkerExecutionProvider))
            throw new StoreException("PROVIDER_EXECUTION_OWNER_INVALID");
        if (!runtime.getString("mode").equals("trusted-python-v1")
                || !runtime.getString("certification").equals("offline-trusted-runner")
                || !RuntimeSupport.strings(runtime.getJSONArray("capabilities")).contains("tool.execute")
                || !tool.getString("effect").equals("read-only")
                || !tool.getString("permission").equals(provider.permission())
                || !permissions.contains(provider.permission()))
            throw new StoreException("TOOL_PROFILE_FORBIDDEN");
        if (tool.optInt("brokerProtocol", 4) != provider.protocolVersion())
            throw new StoreException("PROVIDER_PROTOCOL_MISMATCH");
        if (!canonical(provider.inputSchema()).equals(canonical(definition.getJSONObject("parameters")))
                || !canonical(provider.resultSchema())
                .equals(canonical(definition.getJSONObject("resultSchema"))))
            throw new StoreException("TOOL_REGISTRY_SCHEMA_MISMATCH");
        JSONObject args =
                StrictJson.object(
                        new JSONObject(intent.getString("payload_json"))
                                .getString("argumentsJson")
                                .getBytes(StandardCharsets.UTF_8));
        arguments(args, provider);
        return jobs.enqueue(l, intent, definition);
    }

    public JSONObject wire(JSONObject j, String worker, String runtime, String instance) {
        JSONObject d = new JSONObject(j.getString("definition_json"));
        JSONObject w =
                new JSONObject()
                        .put("jobId", j.getString("job_id"))
                        .put("runId", j.getString("run_id"))
                        .put("callId", j.getString("call_id"))
                        .put("attempt", j.getInt("attempt"))
                        .put("originFence", j.getLong("origin_fence"))
                        .put("intentHash", j.getString("intent_hash"))
                        .put("tenantId", j.getString("tenant"))
                        .put("subjectId", j.getString("subject_id"))
                        .put("release", j.getString("release_digest"))
                        .put("tool", j.getString("tool_name"))
                        .put("argumentsJson", j.getString("arguments_json"))
                        .put("argumentsHash", j.getString("arguments_hash"))
                        .put("bundleJson", d.getString("bundleJson"))
                        .put("workerId", worker)
                        .put("instanceId", instance)
                        .put("runtimeId", runtime)
                        .put(
                                "timeoutMillis",
                                Math.min(
                                        30000,
                                        d.optInt("timeoutMillis", provider(j).independentExecution() ? 30000 : 10000)))
                        .put("maxOutputBytes", 32768)
                        .put(
                                "maxWorkspaceBytes",
                                Math.min(10485760, d.getJSONObject("runtime").getLong("maxWorkspaceBytes")))
                        .put(
                                "maxWorkspaceFiles",
                                Math.min(256, d.getJSONObject("runtime").getInt("maxWorkspaceFiles")));
        if (provider(j) instanceof WorkerExecutionProvider)
            w.put("protocolVersion", 6)
                    .put("executionProfile", ((WorkerExecutionProvider) provider(j)).executionProfile());
        w.put("jobHash", hash(w));
        JSONObject a = new JSONObject(j.getString("arguments_json"));
        JSONObject claims =
                new JSONObject()
                        .put("jobId", j.getString("job_id"))
                        .put("jobHash", w.getString("jobHash"))
                        .put("runId", j.getString("run_id"))
                        .put("callId", j.getString("call_id"))
                        .put("attempt", j.getInt("attempt"))
                        .put("release", j.getString("release_digest"))
                        .put("tool", j.getString("tool_name"))
                        .put("argumentsHash", j.getString("arguments_hash"))
                        .put("tenantId", j.getString("tenant"))
                        .put("subjectId", j.getString("subject_id"))
                        .put("originFence", j.getLong("origin_fence"))
                        .put("workerId", worker)
                        .put("runtimeId", runtime)
                        .put("expiresAt", System.currentTimeMillis() + 60000);
        if (provider(j).protocolVersion() == 4)
            claims.put("targets", a.getJSONArray("targets")).put("fields", a.getJSONArray("fields"));
        String body = canonical(claims);
        w.put("grant", new JSONObject().put("bodyJson", body).put("signature", sign(body)));
        ToolProtocol.parse("runner-job", RuntimeSupport.bytes(w));
        return w;
    }

    public void authorize(JSONObject j, JSONObject grant) {
        JSONObject stored = new JSONObject(j.getString("wire_json")).getJSONObject("grant");
        if (!canonical(stored).equals(canonical(grant))
                || !java.security.MessageDigest.isEqual(
                sign(grant.getString("bodyJson")).getBytes(StandardCharsets.US_ASCII),
                grant.getString("signature").getBytes(StandardCharsets.US_ASCII)))
            throw new StoreException("GRANT_INVALID");
        JSONObject claims =
                StrictJson.object(grant.getString("bodyJson").getBytes(StandardCharsets.UTF_8));
        JSONObject run = jobs.currentRun(j);
        if (claims.getLong("expiresAt") < System.currentTimeMillis()
                || run.getLong("fence") != j.getLong("origin_fence")
                || (identity != null && !identity.tenant.equals(j.getString("tenant")))
                || (identity != null && !identity.subject.equals(j.getString("subject_id")))
                || !permissions.contains(provider(j).permission())
                || j.getInt("cancel_requested") != 0
                || jobs.revoked(j)
                || !run.getJSONObject("snapshot").getString("state").equals("running"))
            throw new StoreException("GRANT_REVOKED_OR_STALE");
        arguments(new JSONObject(j.getString("arguments_json")), provider(j));
    }

    public JSONObject query(
            JSONObject j, JSONObject grant, JSONArray selectedTargets, JSONArray selectedFields) {
        return invokeArguments(
                j, grant, new JSONObject().put("targets", selectedTargets).put("fields", selectedFields));
    }

    public JSONObject invokeArguments(JSONObject j, JSONObject grant, JSONObject args) {
        if (provider(j) instanceof WorkerExecutionProvider)
            throw new StoreException("PYTHON_WORKER_EXECUTION_REQUIRED");
        authorize(j, grant);
        if (!j.getString("status").equals("accepted")) throw new StoreException("TOOL_NOT_ACCEPTED");
        if (!hash(args).equals(j.getString("arguments_hash")))
            throw new StoreException("BROKER_ARGUMENT_CONFLICT");
        JSONObject old = jobs.brokerExchange(j.getString("job_id"), hash(args), node, false);
        if (old == null) {
            if (!permits.tryAcquire()) throw new StoreException("BROKER_BUSY");
            try {
                old = jobs.brokerExchange(j.getString("job_id"), hash(args), node, true);
                if (old.getBoolean("new")) queries.execute(() -> invoke(j, args));
                else permits.release();
            } catch (RuntimeException e) {
                permits.release();
                throw e;
            }
        }
        if (old.getString("status").equals("unknown"))
            throw new StoreException("BROKER_OUTCOME_UNKNOWN");
        if (old.getString("status").equals("finished"))
            return new JSONObject()
                    .put("status", "finished")
                    .put("dataJson", old.getString("response_json"));
        return new JSONObject().put("status", "pending");
    }

    private void invoke(JSONObject j, JSONObject args) {
        JSONObject response;
        boolean unknown = false;
        try {
            // Recheck revocation after queue wait, before touching the upstream service.
            authorize(
                    jobs.job(j.getString("job_id")),
                    new JSONObject(j.getString("wire_json")).getJSONObject("grant"));
            response = provider(j).query(new ProviderInvocation(j, jobs, files), args);
            validateData(j, response);
        } catch (Exception e) {
            unknown = !(e instanceof StoreException);
            response =
                    ToolJobStore.error(
                            e instanceof StoreException ? ((StoreException) e).code : "BROKER_OUTCOME_UNKNOWN");
        }
        pending.put(
                j.getString("job_id"), new JSONObject().put("response", response).put("unknown", unknown));
        flush();
    }

    private WorkerExecutionProvider workerProvider(JSONObject j) {
        QueryProvider p = provider(j);
        if (!(p instanceof WorkerExecutionProvider) || p.protocolVersion() != 6)
            throw new StoreException("PROVIDER_EXECUTION_OWNER_INVALID");
        return (WorkerExecutionProvider) p;
    }

    private String workerOwner(JSONObject j) {
        return "python:" + j.getString("runtime_id");
    }

    /**
     * Durable permission to start once. The Python journal owns replay prevention.
     */
    public JSONObject prepareWorker(JSONObject j, JSONObject grant) {
        workerProvider(j);
        authorize(j, grant);
        if (!j.getString("status").equals("accepted")) throw new StoreException("TOOL_NOT_ACCEPTED");
        JSONObject saved =
                jobs.brokerExchange(
                        j.getString("job_id"), j.getString("arguments_hash"), workerOwner(j), true);
        return new JSONObject()
                .put("status", saved.getString("status").equals("dispatched") ? "execute" : "finished");
    }

    /**
     * Cleanup/result receipts remain admissible after cancellation, expiry or JVM restart.
     */
    public synchronized JSONObject completeWorker(JSONObject j, JSONObject receipt) throws Exception {
        WorkerExecutionProvider p = workerProvider(j);
        ToolProtocol.parse("sandbox-receipt", RuntimeSupport.bytes(receipt));
        String receiptHash = hash(receipt);
        JSONObject saved =
                jobs.brokerExchange(
                        j.getString("job_id"), j.getString("arguments_hash"), workerOwner(j), false);
        if (saved != null && saved.getString("status").equals("finished")) {
            JSONObject wrapper = new JSONObject(saved.getString("response_json"));
            if (!receiptHash.equals(wrapper.getString("receiptHash")))
                throw new StoreException("TOOL_RESULT_CONFLICT");
            return new JSONObject()
                    .put("status", "finished")
                    .put("dataJson", canonical(wrapper.getJSONObject("data")));
        }
        if (receipt.has("output") && saved == null) throw new StoreException("SANDBOX_NOT_DISPATCHED");
        if (saved == null)
            jobs.brokerExchange(
                    j.getString("job_id"), j.getString("arguments_hash"), workerOwner(j), true);
        JSONObject data;
        if (receipt.has("errorCode")) data = ToolJobStore.error(receipt.getString("errorCode"));
        else {
            try {
                data = p.complete(new ProviderInvocation(j, jobs, files), receipt.getJSONObject("output"));
                validateData(j, data);
            } catch (StoreException e) {
                data = ToolJobStore.error(e.code);
            }
        }
        jobs.workerFinish(
                j.getString("job_id"),
                workerOwner(j),
                new JSONObject().put("receiptHash", receiptHash).put("data", data));
        return new JSONObject().put("status", "finished").put("dataJson", canonical(data));
    }

    private synchronized void flush() {
        try {
            recoverExternal();
        } catch (RuntimeException ignored) {
        }
        for (Map.Entry<String, JSONObject> e : pending.entrySet())
            try {
                jobs.brokerFinish(
                        e.getKey(),
                        node,
                        e.getValue().getJSONObject("response"),
                        e.getValue().getBoolean("unknown"));
                if (pending.remove(e.getKey(), e.getValue())) permits.release();
            } catch (RuntimeException ignored) {
                /* Retry only the DB commit, never the upstream operation. */
            }
    }

    private void recoverExternal() {
        long now = System.nanoTime() / 1000000;
        if (now - lastRecovery < 5000) return;
        lastRecovery = now;
        JSONArray rows = jobs.unresolvedBroker(node);
        for (int i = 0; i < 8; i++) {
            if (i >= rows.length()) break;
            JSONObject job = rows.getJSONObject(i);
            String id = job.getString("job_id");
            try {
                QueryProvider provider = provider(job);
                if (provider instanceof WorkerExecutionProvider
                        || !provider.independentExecution()
                        || !recovering.add(id)) continue;
                try {
                    recoveryQueries.execute(
                            () -> {
                                try {
                                    if (provider.reconcile(new ProviderInvocation(job, jobs, files)))
                                        jobs.brokerReconciled(id, node);
                                } catch (Exception ignored) {
                                    /* Retain unknown and retry cleanup only. */
                                } finally {
                                    recovering.remove(id);
                                }
                            });
                } catch (RuntimeException e) {
                    recovering.remove(id);
                }
            } catch (RuntimeException ignored) {
                /* Missing/revoked extension cannot prove cleanup. */
            }
        }
    }

    private void validateData(JSONObject j, JSONObject data) {
        JSONObject definition = new JSONObject(j.getString("definition_json")),
                args = new JSONObject(j.getString("arguments_json"));
        Contract.validate(data, definition.getJSONObject("resultSchema"));
        provider(j).validateResult(args, data);
    }

    public JSONObject report(JSONObject j, JSONObject report) {
        ToolProtocol.parse("runner-report", RuntimeSupport.bytes(report));
        JSONObject normalized;
        try {
            normalized =
                    Contract.parse(
                            "tool-result", report.getString("resultJson").getBytes(StandardCharsets.UTF_8));
            if (normalized.getJSONArray("artifacts").length() != 0) throw new IllegalArgumentException();
            if (!report.getBoolean("actualFinished")
                    && (!normalized.getString("status").equals("unknown")
                    || !report.getString("effectStatus").equals("unknown")))
                throw new IllegalArgumentException();
            if (normalized.getString("status").equals("success")) {
                if (!report.getBoolean("actualFinished")
                        || !report.getString("effectStatus").equals("completed")
                        || normalized.has("errorCode")) throw new IllegalArgumentException();
                validateData(
                        j,
                        StrictJson.object(normalized.getString("dataJson").getBytes(StandardCharsets.UTF_8)));
                if (provider(j).protocolVersion() >= 5) {
                    JSONObject evidence =
                            jobs.brokerExchange(
                                    j.getString("job_id"),
                                    j.getString("arguments_hash"),
                                    provider(j) instanceof WorkerExecutionProvider ? workerOwner(j) : node,
                                    false);
                    JSONObject fact =
                            evidence != null && evidence.getString("status").equals("finished")
                                    ? new JSONObject(evidence.getString("response_json"))
                                    : null;
                    if (fact != null && provider(j) instanceof WorkerExecutionProvider)
                        fact = fact.getJSONObject("data");
                    if (evidence == null
                            || !evidence.getString("status").equals("finished")
                            || !canonical(fact)
                            .equals(canonical(new JSONObject(normalized.getString("dataJson")))))
                        throw new IllegalArgumentException("TOOL_FACT_NOT_PROVEN");
                }
            } else if (normalized.has("dataJson")
                    || !normalized.optString("errorCode").matches("[A-Z0-9_]{1,128}"))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            normalized = ToolJobStore.error("TOOL_OUTPUT_INVALID");
            if (!report.getBoolean("actualFinished")) normalized.put("status", "unknown");
        }
        if (report.getBoolean("actualFinished") && jobs.revoked(j))
            normalized = ToolJobStore.error("TOOL_REVOKED");
        return jobs.report(j, report, normalized, provider(j).independentExecution());
    }

    @Override
    public void close() {
        providers.close();
        queries.shutdownNow();
        recoveryQueries.shutdownNow();
        reconciliation.shutdownNow();
        flush();
    }
}
