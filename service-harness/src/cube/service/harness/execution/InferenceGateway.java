/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionProvider;
import cube.aigc.completion.OpenAICompletionProtocol;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.InferenceStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cell submission is short. A separate dispatcher commits each inference before invoking a backend.
 */
public final class InferenceGateway implements AutoCloseable {
    public final InferenceStore ledger;
    private final Properties config;
    private final Supplier<CompletionProvider> unitProvider;
    private final Map<String, OpenAIHttpProvider> external = new ConcurrentHashMap<>();
    private final ScheduledExecutorService dispatcher;
    private final String node, owner = UUID.randomUUID().toString();
    private volatile String lastError;

    public InferenceGateway(InferenceStore ledger, Properties config, Supplier<CompletionProvider> unitProvider) {
        this.ledger = ledger;
        this.config = config;
        this.unitProvider = unitProvider;
        node = config.getProperty("inference.node.id", "harness-" + config.getProperty("http.port", "7088"));
        if (!node.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("INVALID_INFERENCE_NODE");
        dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harness-inference-dispatch");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        ledger.recover(node, owner);
        dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 50, TimeUnit.MILLISECONDS);
    }

    public String lastError() {
        return lastError;
    }

    private String property(String backend, String key, String fallback) {
        return config.getProperty("backend." + backend + "." + key, fallback);
    }

    private CompletionProvider provider(String backend) throws Exception {
        String type = property(backend, "type", "unit");
        if (type.equals("unit")) return unitProvider.get();
        if (!type.equals("openai")) throw new IllegalArgumentException("INVALID_BACKEND_TYPE");
        OpenAIHttpProvider p = external.get(backend);
        if (p == null) {
            synchronized (external) {
                p = external.get(backend);
                if (p == null) {
                    int capacity = Integer.parseInt(property(backend, "capacity", "0"));
                    if (capacity < 1 || capacity > 32) throw new IllegalArgumentException("INVALID_BACKEND_CAPACITY");
                    p = new OpenAIHttpProvider(property(backend, "baseUrl", ""), property(backend, "model", ""),
                            property(backend, "token", ""), capacity);
                    external.put(backend, p);
                }
            }
        }
        return p;
    }

    public boolean ready(JSONObject descriptor) {
        String backend = descriptor.getString("backendId");
        try {
            CompletionProvider p = provider(backend);
            if (p == null) return false;
            if (property(backend, "type", "unit").equals("openai")) return true;
            JSONArray models = p.completionModels(property(backend, "token", ""));
            for (int n = 0; n < models.length(); n++) {
                JSONObject model = models.getJSONObject(n);
                if (model.getString("id").equals(descriptor.getString("model"))) {
                    JSONObject caps = model.optJSONObject("capabilities");
                    return caps != null && caps.optBoolean("text") && (!descriptor.getBoolean("nativeTools") || caps.optBoolean("tools"));
                }
            }
        } catch (Exception e) {
            lastError = "INFERENCE_BACKEND_NOT_READY";
        }
        return false;
    }

    public JSONObject capacity(JSONObject descriptor) {
        String backend = descriptor.getString("backendId");
        try {
            CompletionProvider p = provider(backend);
            if (p != null) return p.completionCapacity(property(backend, "token", ""),
                    descriptor.getString("model"), descriptor.getBoolean("nativeTools"));
        } catch (Exception e) {
            lastError = "INFERENCE_BACKEND_NOT_READY";
        }
        return new JSONObject().put("registered", 0).put("available", 0);
    }

    public JSONObject apply(JSONObject envelope) {
        String action = envelope.getString("action"), job = envelope.getString("jobId"), worker = envelope.getString("workerId");
        String instance = envelope.getString("jobInstanceId");
        int attempt = envelope.getInt("wireAttempt");
        if (action.equals("inference_submit")) {
            JSONObject body = StrictJson.object(envelope.getString("requestJson").getBytes(StandardCharsets.UTF_8));
            try {
                OpenAICompletionProtocol.normalize(body, false);
            } catch (CompletionError e) {
                String param = e.param == null ? "BODY" : e.param.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
                throw new IllegalArgumentException("INVALID_INFERENCE_PARAMETER_" + param.substring(0, Math.min(80, param.length())));
            }
            ledger.submit(job, worker, instance, envelope.getString("instanceId"), attempt, envelope.getString("requestHash"), body);
        }
        return ledger.page(job, worker, instance, attempt, envelope.optLong("after", 0), action.equals("inference_cancel"));
    }

    private void dispatch() {
        try {
            JSONArray queued = ledger.queued();
            for (int n = 0; n < queued.length(); n++) {
                JSONObject i = queued.getJSONObject(n);
                String backend = i.getString("backend_id");
                CompletionProvider p;
                try {
                    p = provider(backend);
                } catch (Exception e) {
                    lastError = "INFERENCE_BACKEND_CONFIGURATION_ERROR";
                    continue;
                }
                if (p == null) continue;
                JSONObject wire = StrictJson.object(i.getString("request_json").getBytes(StandardCharsets.UTF_8));
                JSONObject canonical = OpenAICompletionProtocol.normalize(wire, false);
                String id = i.getString("inference_id");
                int seconds;
                try {
                    seconds = Integer.parseInt(property(backend, "timeoutSeconds", "180"));
                    if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("INVALID_BACKEND_TIMEOUT");
                    if (p.completionCapacity(property(backend, "token", ""), wire.getString("model"),
                            wire.optJSONArray("tools") != null).getInt("available") < 1) continue;
                } catch (Exception e) {
                    lastError = "INFERENCE_BACKEND_CONFIGURATION_ERROR";
                    continue;
                }
                if (!ledger.claim(id, node, owner)) continue;
                try {
                    p.submitCompletion(property(backend, "token", ""), id, canonical, seconds * 1000L,
                            event -> accept(id, wire.getString("model"), event));
                } catch (Exception e) {
                    // Invocation may have reached the provider. Retain the reservation.
                    ledger.finish(id, null, "MODEL_COMPLETION_UNKNOWN", false, null);
                }
            }
        } catch (Exception e) {
            lastError = "INFERENCE_DISPATCH_FAILED";
        }
    }

    private void accept(String id, String model, JSONObject event) {
        try {
            String kind = event.getString("kind");
            if (kind.equals("completed")) {
                JSONObject response = event.optJSONObject("rawResponse");
                if (response == null)
                    response = OpenAICompletionProtocol.result(event.getJSONObject("payload").getJSONObject("result"),
                            false, id, model, System.currentTimeMillis() / 1000);
                ledger.finish(id, response, null, true, event.optString("unitId", null));
            } else if (kind.equals("error")) {
                String code = event.getJSONObject("payload").getJSONObject("error").optString("code", "MODEL_BACKEND_ERROR").toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z0-9_]{1,128}")) code = "MODEL_BACKEND_ERROR";
                ledger.finish(id, null, code, event.optBoolean("actualFinished", false), event.optString("unitId", null));
            } else if (kind.equals("delta")) {
                JSONObject chunk = new JSONObject().put("id", id).put("model", model).put("object", "chat.completion.chunk")
                        .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                                .put("delta", event.getJSONObject("payload").getJSONObject("delta")).put("finish_reason", JSONObject.NULL)));
                ledger.append(id, chunk);
            }
        } catch (Exception e) {
            ledger.finish(id, null, "MODEL_OUTPUT_INVALID", event.optBoolean("actualFinished", false), event.optString("unitId", null));
        }
    }

    @Override
    public void close() {
        dispatcher.shutdownNow();
        try {
            dispatcher.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (OpenAIHttpProvider p : external.values()) p.close();
    }
}
