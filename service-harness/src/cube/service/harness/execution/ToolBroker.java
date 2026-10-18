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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static cube.service.harness.store.JdbcRunStore.canonical;
import static cube.service.harness.store.JdbcRunStore.hash;

/**
 * Server-only read-only Broker. Child code receives neither grants nor backend URLs/credentials.
 */
public final class ToolBroker implements AutoCloseable {
    public final ToolJobStore jobs;
    private final ProfileRegistry.Identity identity;
    private final String key, node, token;
    private final URL endpoint;
    private final Set<String> targets, fields;
    private final ConsoleStatusAdapter console;
    private final ThreadPoolExecutor queries;

    private final ScheduledExecutorService reconciliation = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harness-broker-reconcile");
        t.setDaemon(true);
        return t;
    });

    private final Semaphore permits = new Semaphore(34);
    private final Map<String, JSONObject> pending = new ConcurrentHashMap<>();

    public ToolBroker(ToolJobStore jobs, ProfileRegistry.Identity identity, Properties p) throws Exception {
        ToolProtocol.verify();
        this.jobs = jobs;
        this.identity = identity;
        key = p.getProperty("runner.grant.key", "");
        node = RuntimeSupport.id(p.getProperty("broker.node.id", ""));
        if (key.length() < 32) throw new IllegalArgumentException("RUNNER_GRANT_KEY_REQUIRED");
        console="console".equals(p.getProperty("broker.type"))?new ConsoleStatusAdapter(p):null;
        if (console==null&&!"true".equals(p.getProperty("broker.mock.enabled")))
            throw new IllegalArgumentException("MOCK_BROKER_EXPLICIT_ENABLE_REQUIRED");
        endpoint = console==null?new URL(p.getProperty("broker.mock.url", "")):null;
        if (console==null&&(!endpoint.getProtocol().equals("http") || !InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getRef() != null))
            throw new IllegalArgumentException("MOCK_BROKER_LOOPBACK_REQUIRED");
        token = p.getProperty("broker.mock.token", "");
        if (console==null&&token.length() < 24) throw new IllegalArgumentException("BROKER_PRIVATE_TOKEN_REQUIRED");
        targets = console==null?set(p.getProperty("broker.mock.targets", "")):console.targets();
        fields = set(p.getProperty(console==null?"broker.mock.fields":"broker.console.fields", console==null?"cpuPercent,memoryMiB":"running,version,jvm,host"));
        if (targets.isEmpty() || fields.isEmpty() || !(console==null?new HashSet<>(Arrays.asList("cpuPercent", "memoryMiB")):new HashSet<>(Arrays.asList("running","version","jvm","host"))).containsAll(fields))
            throw new IllegalArgumentException("BROKER_SCOPE_REQUIRED");
        queries = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(32), r -> {
            Thread t = new Thread(r, "harness-broker-query");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        jobs.brokerRestart(node);
        reconciliation.scheduleWithFixedDelay(this::flush, 0, 200, TimeUnit.MILLISECONDS);
    }

    private static Set<String> set(String value) {
        Set<String> s = new TreeSet<>();
        for (String x : value.split(",")) if (!x.isEmpty()) s.add(RuntimeSupport.id(x));
        return s;
    }

    private String sign(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : m.doFinal(value.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JSONObject parameters() {
        JSONObject id = new JSONObject().put("type", "string").put("pattern", "^[A-Za-z0-9_.:-]{1,128}$").put("maxLength", 128);
        return new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("targets", new JSONObject().put("type", "array").put("items", id).put("minItems", 1).put("maxItems", 32))
                        .put("fields", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string").put("maxLength", 128).put("enum", new JSONArray().put("cpuPercent").put("memoryMiB"))).put("minItems", 1).put("maxItems", 2)))
                .put("required", new JSONArray().put("targets").put("fields")).put("additionalProperties", false);
    }

    public static JSONObject outputSchema() {
        JSONObject integer = new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 9007199254740991L);
        JSONObject row = new JSONObject().put("type", "object").put("properties", new JSONObject().put("target", new JSONObject().put("type", "string").put("maxLength", 128))
                        .put("queriedAt", integer).put("cpuPercent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)).put("memoryMiB", integer))
                .put("required", new JSONArray().put("target").put("queriedAt")).put("additionalProperties", false);
        return new JSONObject().put("type", "object").put("properties", new JSONObject().put("nodes", new JSONObject().put("type", "array").put("items", row).put("minItems", 1).put("maxItems", 32)))
                .put("required", new JSONArray().put("nodes")).put("additionalProperties", false);
    }

    private void arguments(JSONObject a) {
        if(console==null)Contract.validate(a, parameters());else console.validate(a);
        Set<String> t = RuntimeSupport.strings(a.getJSONArray("targets")), f = RuntimeSupport.strings(a.getJSONArray("fields"));
        if (!targets.containsAll(t) || !fields.containsAll(f)) throw new StoreException("BROKER_SCOPE_FORBIDDEN");
    }

    public JSONObject enqueue(JdbcRunStore.Lease l, JSONObject intent, JSONObject definition) {
        JSONObject tool = definition.getJSONObject("tool"), runtime = definition.getJSONObject("runtime");
        if (!runtime.getString("mode").equals("trusted-python-v1") || !runtime.getString("certification").equals("offline-trusted-runner")
                || !RuntimeSupport.strings(runtime.getJSONArray("capabilities")).contains("tool.execute") || !tool.getString("effect").equals("read-only")
                || !tool.getString("brokerOperation").equals(console==null?"mockconsole.query_status":"cube_console.query_status") || !tool.getString("permission").equals("console.status.read")
                || !identity.permissions.contains("console.status.read"))
            throw new StoreException("TOOL_PROFILE_FORBIDDEN");
        if (!canonical(console==null?parameters():ConsoleStatusAdapter.parameters()).equals(canonical(definition.getJSONObject("parameters"))) || !canonical(console==null?outputSchema():ConsoleStatusAdapter.outputSchema()).equals(canonical(definition.getJSONObject("resultSchema"))))
            throw new StoreException("TOOL_REGISTRY_SCHEMA_MISMATCH");
        JSONObject args = StrictJson.object(new JSONObject(intent.getString("payload_json")).getString("argumentsJson").getBytes(StandardCharsets.UTF_8));
        arguments(args);
        return jobs.enqueue(l, intent, definition);
    }

    public JSONObject wire(JSONObject j, String worker, String runtime, String instance) {
        JSONObject d = new JSONObject(j.getString("definition_json"));
        JSONObject w = new JSONObject().put("jobId", j.getString("job_id")).put("runId", j.getString("run_id")).put("callId", j.getString("call_id"))
                .put("attempt", j.getInt("attempt")).put("originFence", j.getLong("origin_fence")).put("intentHash", j.getString("intent_hash"))
                .put("tenantId", j.getString("tenant")).put("subjectId", j.getString("subject_id")).put("release", j.getString("release_digest"))
                .put("tool", j.getString("tool_name")).put("argumentsJson", j.getString("arguments_json")).put("argumentsHash", j.getString("arguments_hash"))
                .put("bundleJson", d.getString("bundleJson")).put("workerId", worker).put("instanceId", instance).put("runtimeId", runtime)
                .put("timeoutMillis", Math.min(30000, d.optInt("timeoutMillis", 10000))).put("maxOutputBytes", 32768)
                .put("maxWorkspaceBytes", Math.min(10485760, d.getJSONObject("runtime").getLong("maxWorkspaceBytes")))
                .put("maxWorkspaceFiles", Math.min(256, d.getJSONObject("runtime").getInt("maxWorkspaceFiles")));
        w.put("jobHash", hash(w));
        JSONObject a = new JSONObject(j.getString("arguments_json"));
        JSONObject claims = new JSONObject().put("jobId", j.getString("job_id")).put("jobHash", w.getString("jobHash")).put("runId", j.getString("run_id"))
                .put("callId", j.getString("call_id")).put("attempt", j.getInt("attempt")).put("release", j.getString("release_digest"))
                .put("tool", j.getString("tool_name")).put("argumentsHash", j.getString("arguments_hash")).put("tenantId", j.getString("tenant"))
                .put("subjectId", j.getString("subject_id")).put("originFence", j.getLong("origin_fence")).put("workerId", worker).put("runtimeId", runtime)
                .put("targets", a.getJSONArray("targets")).put("fields", a.getJSONArray("fields")).put("expiresAt", System.currentTimeMillis() + 60000);
        String body = canonical(claims);
        w.put("grant", new JSONObject().put("bodyJson", body).put("signature", sign(body)));
        ToolProtocol.parse("runner-job", RuntimeSupport.bytes(w));
        return w;
    }

    public void authorize(JSONObject j, JSONObject grant) {
        JSONObject stored = new JSONObject(j.getString("wire_json")).getJSONObject("grant");
        if (!canonical(stored).equals(canonical(grant)) || !java.security.MessageDigest.isEqual(sign(grant.getString("bodyJson")).getBytes(StandardCharsets.US_ASCII), grant.getString("signature").getBytes(StandardCharsets.US_ASCII)))
            throw new StoreException("GRANT_INVALID");
        JSONObject claims = StrictJson.object(grant.getString("bodyJson").getBytes(StandardCharsets.UTF_8));
        JSONObject run = jobs.currentRun(j);
        if (claims.getLong("expiresAt") < System.currentTimeMillis() || run.getLong("fence") != j.getLong("origin_fence")
                || !identity.tenant.equals(j.getString("tenant")) || !identity.subject.equals(j.getString("subject_id"))
                || !identity.permissions.contains("console.status.read") || j.getInt("cancel_requested") != 0 || jobs.revoked(j)
                || !run.getJSONObject("snapshot").getString("state").equals("running"))
            throw new StoreException("GRANT_REVOKED_OR_STALE");
        arguments(new JSONObject(j.getString("arguments_json")));
    }

    public JSONObject query(JSONObject j, JSONObject grant, JSONArray selectedTargets, JSONArray selectedFields) {
        authorize(j, grant);
        if (!j.getString("status").equals("accepted")) throw new StoreException("TOOL_NOT_ACCEPTED");
        JSONObject args = new JSONObject().put("targets", selectedTargets).put("fields", selectedFields);
        if (!hash(args).equals(j.getString("arguments_hash"))) throw new StoreException("BROKER_ARGUMENT_CONFLICT");
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
        if (old.getString("status").equals("unknown")) throw new StoreException("BROKER_OUTCOME_UNKNOWN");
        if (old.getString("status").equals("finished"))
            return new JSONObject().put("status", "finished").put("dataJson", old.getString("response_json"));
        return new JSONObject().put("status", "pending");
    }

    private void invoke(JSONObject j, JSONObject args) {
        JSONObject response;
        boolean unknown = false;
        HttpURLConnection connection = null;
        ScheduledFuture<?> abort = null;
        try {
            // Recheck revocation after queue wait, before touching the upstream service.
            authorize(jobs.job(j.getString("job_id")), new JSONObject(j.getString("wire_json")).getJSONObject("grant"));
            if(console!=null){response=console.query(args);validateData(j,response);pending.put(j.getString("job_id"),new JSONObject().put("response",response).put("unknown",false));flush();return;}
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            final HttpURLConnection owned = connection;
            abort = reconciliation.schedule(owned::disconnect, 3000, TimeUnit.MILLISECONDS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(RuntimeSupport.bytes(args));
            }
            if (connection.getResponseCode() != 200) throw new IOException("BROKER_UPSTREAM_REJECTED");
            JSONObject raw;
            try (InputStream in = connection.getInputStream()) {
                raw = StrictJson.object(RuntimeSupport.read(in, 65536));
            }
            JSONArray nodes = new JSONArray();
            Set<String> seen = new HashSet<>(), wanted = RuntimeSupport.strings(args.getJSONArray("targets"));
            JSONArray rows = raw.getJSONArray("nodes");
            if (rows.length() > 32) throw new IllegalArgumentException();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject source = rows.getJSONObject(i);
                String target = source.getString("target");
                if (!wanted.contains(target)) continue;
                if (!seen.add(target)) throw new IllegalArgumentException();
                JSONObject row = new JSONObject().put("target", target).put("queriedAt", source.get("queriedAt"));
                for (String field : RuntimeSupport.strings(args.getJSONArray("fields")))
                    row.put(field, source.get(field));
                nodes.put(row);
            }
            if (!seen.equals(wanted)) throw new IllegalArgumentException();
            response = new JSONObject().put("nodes", nodes);
            validateData(j, response);
        } catch (Exception e) {
            unknown = !(e instanceof StoreException);
            response = ToolJobStore.error(e instanceof StoreException ? ((StoreException) e).code : "BROKER_OUTCOME_UNKNOWN");
        } finally {
            if (abort != null) abort.cancel(false);
            if (connection != null) connection.disconnect();
        }
        pending.put(j.getString("job_id"), new JSONObject().put("response", response).put("unknown", unknown));
        flush();
    }

    private synchronized void flush() {
        for (Map.Entry<String, JSONObject> e : pending.entrySet())
            try {
                jobs.brokerFinish(e.getKey(), node, e.getValue().getJSONObject("response"), e.getValue().getBoolean("unknown"));
                if (pending.remove(e.getKey(), e.getValue())) permits.release();
            } catch (RuntimeException ignored) {/* Retry only the DB commit, never the upstream operation. */}
    }

    private void validateData(JSONObject j, JSONObject data) {
        JSONObject definition = new JSONObject(j.getString("definition_json")), args = new JSONObject(j.getString("arguments_json"));
        Contract.validate(data, definition.getJSONObject("resultSchema"));
        Set<String> allowedFields = RuntimeSupport.strings(args.getJSONArray("fields")), allowedTargets = RuntimeSupport.strings(args.getJSONArray("targets"));
        allowedFields.add("target");
        allowedFields.add("queriedAt");
        if(console!=null)allowedFields.addAll(Arrays.asList("source","reportTimestamp","reportState","errorCode"));
        Set<String> seen = new HashSet<>();
        JSONArray nodes = data.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject row = nodes.getJSONObject(i);
            if (!allowedFields.containsAll(row.keySet()) || !allowedTargets.contains(row.getString("target")) || !seen.add(row.getString("target")))
                throw new IllegalArgumentException("TOOL_OUTPUT_INVALID");
        }
        if (!seen.equals(allowedTargets)) throw new IllegalArgumentException("TOOL_OUTPUT_INVALID");
    }

    public JSONObject report(JSONObject j, JSONObject report) {
        ToolProtocol.parse("runner-report", RuntimeSupport.bytes(report));
        JSONObject normalized;
        try {
            normalized = Contract.parse("tool-result", report.getString("resultJson").getBytes(StandardCharsets.UTF_8));
            if (normalized.getJSONArray("artifacts").length() != 0) throw new IllegalArgumentException();
            if (!report.getBoolean("actualFinished") && (!normalized.getString("status").equals("unknown") || !report.getString("effectStatus").equals("unknown")))
                throw new IllegalArgumentException();
            if (normalized.getString("status").equals("success")) {
                if (!report.getBoolean("actualFinished") || !report.getString("effectStatus").equals("completed") || normalized.has("errorCode"))
                    throw new IllegalArgumentException();
                validateData(j, StrictJson.object(normalized.getString("dataJson").getBytes(StandardCharsets.UTF_8)));
            } else if (normalized.has("dataJson") || !normalized.optString("errorCode").matches("[A-Z0-9_]{1,128}"))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            normalized = ToolJobStore.error("TOOL_OUTPUT_INVALID");
            if (!report.getBoolean("actualFinished")) normalized.put("status", "unknown");
        }
        if (report.getBoolean("actualFinished") && jobs.revoked(j)) normalized = ToolJobStore.error("TOOL_REVOKED");
        return jobs.report(j, report, normalized);
    }

    @Override
    public void close() {
        if (console != null) console.close();
        queries.shutdownNow();
        reconciliation.shutdownNow();
        flush();
    }
}
