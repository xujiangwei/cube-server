/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.*;

/**
 * Explicit loopback-only N08 offline fixture, never a business integration.
 */
public final class MockQueryProvider implements QueryProvider {
    private final URL endpoint;
    private final String token;
    private final Set<String> targets = new TreeSet<>(), fields = new TreeSet<>();

    public MockQueryProvider(Properties p) throws Exception {
        endpoint = new URL(p.getProperty("broker.mock.url", ""));
        if (!endpoint.getProtocol().equals("http")
                || !InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getRef() != null)
            throw new IllegalArgumentException("MOCK_BROKER_LOOPBACK_REQUIRED");
        token = p.getProperty("broker.mock.token", "");
        if (token.length() < 24) throw new IllegalArgumentException("BROKER_PRIVATE_TOKEN_REQUIRED");
        for (String t : p.getProperty("broker.mock.targets", "").split(","))
            if (!t.isEmpty()) targets.add(RuntimeSupport.id(t));
        for (String f : p.getProperty("broker.mock.fields", "cpuPercent,memoryMiB").split(","))
            if (!f.isEmpty()) fields.add(RuntimeSupport.id(f));
        if (targets.isEmpty()
                || fields.isEmpty()
                || !new HashSet<>(Arrays.asList("cpuPercent", "memoryMiB")).containsAll(fields))
            throw new IllegalArgumentException("BROKER_SCOPE_REQUIRED");
    }

    public String operation() {
        return "mockconsole.query_status";
    }

    public String permission() {
        return "console.status.read";
    }

    public JSONObject inputSchema() {
        return parameters();
    }

    public JSONObject resultSchema() {
        return outputSchema();
    }

    public static JSONObject parameters() {
        JSONObject id =
                new JSONObject()
                        .put("type", "string")
                        .put("pattern", "^[A-Za-z0-9_.:-]{1,128}$")
                        .put("maxLength", 128);
        return new JSONObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JSONObject()
                                .put(
                                        "targets",
                                        new JSONObject()
                                                .put("type", "array")
                                                .put("items", id)
                                                .put("minItems", 1)
                                                .put("maxItems", 32))
                                .put(
                                        "fields",
                                        new JSONObject()
                                                .put("type", "array")
                                                .put(
                                                        "items",
                                                        new JSONObject()
                                                                .put("type", "string")
                                                                .put("maxLength", 128)
                                                                .put("enum", new JSONArray().put("cpuPercent").put("memoryMiB")))
                                                .put("minItems", 1)
                                                .put("maxItems", 2)))
                .put("required", new JSONArray().put("targets").put("fields"))
                .put("additionalProperties", false);
    }

    public static JSONObject outputSchema() {
        JSONObject integer =
                new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 9007199254740991L);
        JSONObject row =
                new JSONObject()
                        .put("type", "object")
                        .put(
                                "properties",
                                new JSONObject()
                                        .put("target", new JSONObject().put("type", "string").put("maxLength", 128))
                                        .put("queriedAt", integer)
                                        .put(
                                                "cpuPercent",
                                                new JSONObject()
                                                        .put("type", "integer")
                                                        .put("minimum", 0)
                                                        .put("maximum", 100))
                                        .put("memoryMiB", integer))
                        .put("required", new JSONArray().put("target").put("queriedAt"))
                        .put("additionalProperties", false);
        return new JSONObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JSONObject()
                                .put(
                                        "nodes",
                                        new JSONObject()
                                                .put("type", "array")
                                                .put("items", row)
                                                .put("minItems", 1)
                                                .put("maxItems", 32)))
                .put("required", new JSONArray().put("nodes"))
                .put("additionalProperties", false);
    }

    public void validate(JSONObject args) {
        Contract.validate(args, inputSchema());
        if (!targets.containsAll(RuntimeSupport.strings(args.getJSONArray("targets")))
                || !fields.containsAll(RuntimeSupport.strings(args.getJSONArray("fields"))))
            throw new StoreException("BROKER_SCOPE_FORBIDDEN");
    }

    public JSONObject query(JSONObject args) throws Exception {
        validate(args);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        java.util.concurrent.ScheduledExecutorService deadlines =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ScheduledFuture<?> abort =
                deadlines.schedule(
                        connection::disconnect, 3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
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
            Set<String> seen = new HashSet<>(),
                    wanted = RuntimeSupport.strings(args.getJSONArray("targets"));
            JSONArray rows = raw.getJSONArray("nodes");
            if (rows.length() > 32) throw new IllegalArgumentException();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject source = rows.getJSONObject(i);
                String target = source.getString("target");
                if (!wanted.contains(target)) continue;
                if (!seen.add(target)) throw new IllegalArgumentException();
                JSONObject row =
                        new JSONObject().put("target", target).put("queriedAt", source.get("queriedAt"));
                for (String field : RuntimeSupport.strings(args.getJSONArray("fields")))
                    row.put(field, source.get(field));
                nodes.put(row);
            }
            if (!seen.equals(wanted)) throw new IllegalArgumentException();
            return new JSONObject().put("nodes", nodes);

        } finally {
            abort.cancel(false);
            deadlines.shutdownNow();
            connection.disconnect();
        }
    }

    public void validateResult(JSONObject args, JSONObject data) {
        Set<String> allowedFields = RuntimeSupport.strings(args.getJSONArray("fields")),
                allowedTargets = RuntimeSupport.strings(args.getJSONArray("targets"));
        allowedFields.add("target");
        allowedFields.add("queriedAt");
        Set<String> seen = new HashSet<>();
        JSONArray nodes = data.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject row = nodes.getJSONObject(i);
            if (!allowedFields.containsAll(row.keySet())
                    || !allowedTargets.contains(row.getString("target"))
                    || !seen.add(row.getString("target")))
                throw new IllegalArgumentException("TOOL_OUTPUT_INVALID");
        }
        if (!seen.equals(allowedTargets)) throw new IllegalArgumentException("TOOL_OUTPUT_INVALID");
    }

    public void close() {
    }
}
