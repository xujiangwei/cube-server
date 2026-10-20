/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.completion;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.aigc.ContactPreference;
import cube.aigc.ModelConfig;
import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionProvider;
import cube.aigc.completion.CompletionRequest;
import cube.aigc.completion.CompletionResult;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AIGCUnit;
import cube.service.Director;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;
import cube.service.aigc.resource.Relay;
import cube.service.aigc.unit.UnitMeta;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Structured generation has no channel, hidden history, SKILL or tool executor.
 */
public class ChatCompletionService {
    private final AIGCService service;

    private final AIGCCellet cellet;

    private final Map<String, Pending> requests = new ConcurrentHashMap<>();

    private final Map<String, Cancelled> earlyCancellations = new java.util.LinkedHashMap<>();

    private final Semaphore admission = new Semaphore(128);

    private volatile boolean stopped;

    private boolean retentionStarted;

    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "chat-completion-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ChatCompletionService(AIGCService service, AIGCCellet cellet) {
        this.service = service;
        this.cellet = cellet;
        timer.setRemoveOnCancelPolicy(true);
    }

    private AuthToken authenticate(String code) {
        AuthToken token = service.getToken(code);
        if (token == null || token.getExpiry() <= System.currentTimeMillis()) {
            throw new CompletionError(401, "authentication_error", "Invalid or expired Cube token", null);
        }
        if (stopped || !service.isStarted())
            throw new CompletionError(503, "service_unavailable", "AI service is not ready", null);
        return token;
    }

    private List<ModelConfig> allowedModels(AuthToken token) {
        ContactPreference preference = service.getPreference(token.getContactId());
        List<ModelConfig> models = preference == null || preference.getModels().length() == 0
                ? service.getModelConfigs() : service.getModelConfigs(preference.getModels());
        if (models == null)
            throw new CompletionError(503, "service_unavailable", "Model configuration is unavailable", null);
        return models;
    }

    private AIGCUnit select(String name) {
        AIGCUnit selected = service.selectUnitByName(name);
        if (selected != null && !quarantined(selected) && selected.getCapability().getChatCompletion().optBoolean("text", false))
            return selected;
        for (AIGCUnit unit : service.getAllUnits()) {
            if (name.equals(unit.getCapability().getName()) && unit.getContext() != null && unit.getContext().isValid()
                    && !quarantined(unit) && unit.getCapability().getChatCompletion().optBoolean("text", false))
                return unit;
        }
        return null;
    }

    private AIGCUnit select(String name, JSONObject request) {
        AIGCUnit initial = select(name);
        if (initial == null) return null;
        CompletionError unsupported;
        try {
            CompletionRequest.checkCapabilities(request, initial.getCapability().getChatCompletion());
            return initial;
        } catch (CompletionError error) {
            unsupported = error;
        }
        for (AIGCUnit candidate : service.getAllUnits()) {
            if (!name.equals(candidate.getCapability().getName()) || candidate == initial
                    || candidate.getContext() == null || !candidate.getContext().isValid() || quarantined(candidate))
                continue;
            try {
                CompletionRequest.checkCapabilities(request, candidate.getCapability().getChatCompletion());
                return candidate;
            } catch (CompletionError ignored) {
            }
        }
        throw unsupported;
    }

    public JSONObject capacity(String code, String modelId, boolean nativeTools) {
        AuthToken token = authenticate(code);
        int registered = 0, available = 0;
        JSONArray groups = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ModelConfig model : allowedModels(token)) {
            if (!model.getModel().equals(modelId)) continue;
            for (AIGCUnit unit : service.getAllUnits()) {
                JSONObject caps = unit.getCapability().getChatCompletion();
                if (!model.getUnitName().equals(unit.getCapability().getName()) || unit.getContext() == null
                        || !unit.getContext().isValid() || !caps.optBoolean("text")
                        || (nativeTools && !caps.optBoolean("tools"))) continue;
                if (!seen.add(unit.getQueryKey())) continue;
                registered++;
                int free = !unit.isRunning() && !reserved(unit) ? 1 : 0;
                Relay relay = Relay.owner(unit);
                if (relay != null) {
                    JSONObject group = relay.capacity(unit);
                    free = Math.min(free, group.getInt("groupAvailable"));
                    group.put("registered", 1).put("available", free).put("route", unit.getQueryKey());
                    groups.put(group);
                } else groups.put(new JSONObject().put("capacityGroup", unit.getQueryKey())
                        .put("route", unit.getQueryKey()).put("registered", 1).put("available", free)
                        .put("groupCapacity", 1).put("groupAvailable", free));
                available += free;
            }
        }
        JSONObject result = new JSONObject().put("registered", registered).put("available", available).put("capacityGroups", groups);
        if (groups.length() == 1) result.put("capacityGroup", groups.getJSONObject(0).getString("capacityGroup"));
        return result;
    }

    private boolean reserved(AIGCUnit unit) {
        for (Pending pending : requests.values())
            if (pending.unitKey().equals(unit.getQueryKey()) && !pending.actualFinished) return true;
        return false;
    }

    private boolean quarantined(AIGCUnit unit) {
        for (Pending pending : requests.values())
            if (pending.listener != null && pending.terminal && !pending.actualFinished
                    && pending.unitKey().equals(unit.getQueryKey())) return true;
        return false;
    }

    private AIGCUnit selectInternal(String name, JSONObject request) {
        for (AIGCUnit unit : service.getAllUnits()) {
            if (!name.equals(unit.getCapability().getName()) || unit.getContext() == null
                    || !unit.getContext().isValid() || unit.isRunning() || reserved(unit)) continue;
            try {
                CompletionRequest.checkCapabilities(request, unit.getCapability().getChatCompletion());
                return unit;
            } catch (CompletionError ignored) {
            }
        }
        return null;
    }

    public JSONObject models(String code) {
        AuthToken token = authenticate(code);
        JSONArray data = new JSONArray();
        for (ModelConfig model : allowedModels(token)) {
            // A model lookup is a capability snapshot, never a scheduling operation.
            AIGCUnit unit = null;
            for (AIGCUnit candidate : service.getAllUnits()) {
                if (model.getUnitName().equals(candidate.getCapability().getName()) && candidate.getContext() != null
                        && candidate.getContext().isValid() && !quarantined(candidate)
                        && candidate.getCapability().getChatCompletion().optBoolean("text")) { unit = candidate; break; }
            }
            if (unit != null) data.put(new JSONObject().put("id", model.getModel())
                    .put("name", model.getName()).put("created", 0)
                    .put("capabilities", unit.getCapability().getChatCompletion()));
        }
        return new JSONObject().put("models", data);
    }

    public void generate(String code, TalkContext origin, ActionDialect dialect) {
        generate(code, origin, dialect, null);
    }

    /**
     * Same authorization, queue, capability checks and accounting as REST, without a fake TalkContext.
     */
    public void generateInternal(String code, String id, JSONObject request, long timeout, CompletionProvider.Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener required");
        ActionDialect dialect = new Packet(AIGCAction.ChatCompletion.name, new JSONObject()
                .put("requestId", id).put("request", request).put("timeoutMs", timeout)).toDialect();
        generate(code, null, dialect, listener);
    }

    private void generate(String code, TalkContext origin, ActionDialect dialect, CompletionProvider.Listener listener) {
        String id = new Packet(dialect).data.optString("requestId", "");
        try {
            AuthToken token = authenticate(code);
            JSONObject data = new Packet(dialect).data;
            JSONObject request = data.getJSONObject("request");
            CompletionRequest.validate(request);
            ModelConfig model = null;
            for (ModelConfig candidate : allowedModels(token)) {
                if (candidate.getModel().equals(request.getString("model"))) {
                    model = candidate;
                    break;
                }
            }
            if (model == null)
                throw new CompletionError(404, "model_not_found", "Unknown or unavailable model", "model");
            AIGCUnit unit = listener == null ? select(model.getUnitName(), request) : selectInternal(model.getUnitName(), request);
            if (unit == null)
                throw new CompletionError(503, "service_unavailable", "Requested model has no compatible unit", "model");
            if (id.isEmpty() || id.length() > 128) throw CompletionError.invalid("Invalid request ID", null);
            long timeout = data.getLong("timeoutMs");
            if (timeout <= 0) throw CompletionError.invalid("Invalid request timeout", null);
            if (!admission.tryAcquire())
                throw new CompletionError(429, "rate_limit_error", "Generation queue is full", null);
            Pending pending = new Pending(id, token, model, unit, origin, dialect, request, timeout, listener);
            synchronized (this) {
                if (stopped) {
                    admission.release();
                    throw new CompletionError(503, "service_unavailable", "AI service stopped", null);
                }
                Cancelled cancelled = earlyCancellations.remove(id);
                if (cancelled != null && cancelled.matches(code, origin)) {
                    admission.release();
                    throw new CompletionError(504, "request_timeout", "Generation cancelled before admission", null);
                }
                if (requests.putIfAbsent(id, pending) != null) {
                    admission.release();
                    throw CompletionError.invalid("Duplicate request ID", null);
                }
                pending.timeoutTask = timer.schedule(() -> pending.fail(new CompletionError(504,
                        "request_timeout", "Generation timed out", null)), timeout, TimeUnit.MILLISECONDS);
            }
            try {
                pending.enqueue();
            } catch (RuntimeException e) {
                pending.fail(new CompletionError(503, "service_unavailable", "Generation queue is unavailable", null));
            }
        } catch (CompletionError error) {
            reject(origin, dialect, id, error, listener);
        } catch (RuntimeException error) {
            reject(origin, dialect, id, CompletionError.invalid("Invalid structured request", null), listener);
        }
    }

    public void cancel(String code, String id, TalkContext origin) {
        Pending pending;
        synchronized (this) {
            pending = requests.get(id);
            if (pending == null && !stopped && id != null && !id.isEmpty() && id.length() <= 128) {
                if (!retentionStarted) {
                    retentionStarted = true;
                    timer.scheduleWithFixedDelay(() -> {
                        synchronized (ChatCompletionService.this) {
                            earlyCancellations.entrySet().removeIf(entry -> System.nanoTime() >= entry.getValue().expires);
                        }
                    }, 30, 30, TimeUnit.SECONDS);
                }
                // ServiceTask dispatch uses a shared pool; a cancel can overtake admission.
                earlyCancellations.put(id, new Cancelled(code, origin));
                while (earlyCancellations.size() > 1024)
                    earlyCancellations.remove(earlyCancellations.keySet().iterator().next());
            }
        }
        // Bind control operations to both the token and original dispatcher connection.
        if (pending != null && pending.token.getCode().equals(code) && java.util.Objects.equals(pending.origin, origin)) {
            pending.fail(new CompletionError(504, "request_timeout", "Generation cancelled", null));
        }
    }

    public void onUnitEvent(TalkContext context, ActionDialect dialect) {
        Pending pending = null;
        try {
            JSONObject event = new Packet(dialect).data;
            pending = requests.get(event.getString("requestId"));
            if (pending != null && pending.unitContext.equals(context)) pending.accept(event);
        } catch (RuntimeException e) {
            if (pending != null && pending.unitContext.equals(context)) pending.fail(new CompletionError(502,
                    "upstream_error", "Malformed generation event", null));
            Logger.w(ChatCompletionService.class, "Invalid completion event", e);
        }
    }

    public synchronized void stop() {
        stopped = true;
        for (Pending pending : requests.values()) {
            pending.fail(new CompletionError(503, "service_unavailable", "AI service stopped", null));
            // Stop observing in this JVM; the Harness ledger still retains unknown physical reservations.
            pending.done.countDown();
        }
        timer.shutdownNow();
        earlyCancellations.clear();
    }

    public int activeRequests() {
        return requests.size();
    }

    private static class Cancelled {
        final String token;
        final TalkContext context;
        final long expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);

        Cancelled(String token, TalkContext context) {
            this.token = token;
            this.context = context;
        }

        boolean matches(String token, TalkContext context) {
            return System.nanoTime() < expires && java.util.Objects.equals(this.token, token) && this.context.equals(context);
        }
    }

    private void reject(TalkContext origin, ActionDialect dialect, String id, CompletionError error, CompletionProvider.Listener listener) {
        if (listener == null) error(origin, dialect, id, error);
        else listener.onEvent(new JSONObject().put("requestId", id).put("sequence", 1).put("kind", "error")
                .put("actualFinished", true).put("payload", new JSONObject().put("error", error.toJSON())));
    }

    private void error(TalkContext origin, ActionDialect dialect, String id, CompletionError error) {
        send(origin, dialect, new JSONObject().put("requestId", id).put("sequence", 1)
                .put("kind", "error").put("payload", new JSONObject().put("error", error.toJSON())));
    }

    private boolean send(TalkContext origin, ActionDialect dialect, JSONObject event) {
        ActionDialect reply = new Packet(AIGCAction.ChatCompletionEvent.name, event).toDialect();
        Director.copyPerformer(dialect, reply);
        return cellet.speak(origin, reply);
    }

    private class Pending extends UnitMeta {
        final String id;
        final AuthToken token;
        final ModelConfig model;
        final TalkContext origin;
        final TalkContext unitContext;
        final ActionDialect dialect;
        final JSONObject request;
        final long deadline;
        final CountDownLatch done = new CountDownLatch(1);
        long sequence;
        volatile boolean terminal;
        boolean sent, attempted;
        volatile boolean actualFinished;
        final CompletionProvider.Listener listener;
        ScheduledFuture<?> timeoutTask;

        Pending(String id, AuthToken token, ModelConfig model, AIGCUnit unit, TalkContext origin,
                ActionDialect dialect, JSONObject request, long timeout, CompletionProvider.Listener listener) {
            super(ChatCompletionService.this.service, unit);
            this.id = id;
            this.token = token;
            this.model = model;
            this.origin = origin;
            this.dialect = dialect;
            this.request = request;
            this.listener = listener;
            this.unitContext = unit.getContext();
            this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }

        @Override
        public void process() {
            AtomicInteger count = service.increaseUnitCounter(unit.getCapability().getName());
            unit.setRunning(true);
            try {
                synchronized (this) {
                    if (terminal) return;
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        fail(new CompletionError(504, "request_timeout", "Generation timed out in queue", null));
                        return;
                    }
                    JSONObject data = new JSONObject().put("requestId", id).put("unit", unit.getCapability().getName())
                            .put("request", request).put("timeoutMs", remaining);
                    attempted = true;
                    sent = cellet.speak(unitContext, new Packet(AIGCAction.ChatCompletion.name, data).toDialect());
                    if (!sent) {
                        fail(new CompletionError(503, "service_unavailable", "Unit transport unavailable", null));
                        return;
                    }
                }
                if (!done.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    fail(new CompletionError(504, "request_timeout", "Generation timed out", null));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(new CompletionError(503, "service_unavailable", "Generation interrupted", null));
            } catch (RuntimeException e) {
                fail(new CompletionError(502, "upstream_error", "Unit generation failed", null));
            } finally {
                // Every exit path, including a failed send/callback, preserves the Unit's serial queue.
                boolean interrupted = false;
                while (listener != null && attempted && !actualFinished && !stopped) {
                    try {
                        done.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                unit.setRunning(false);
                count.decrementAndGet();
            }
        }

        synchronized void enqueue() {
            if (!terminal) service.submitChatCompletion(this);
        }

        String unitKey() {
            return unit.getQueryKey();
        }

        synchronized void accept(JSONObject event) {
            boolean observing = terminal && listener != null && !actualFinished;
            if (terminal && !observing) return;
            if (observing && !"completed".equals(event.optString("kind"))
                    && !("error".equals(event.optString("kind")) && event.optBoolean("actualFinished"))) return;
            long next = CompletionResult.tokens(event, "sequence");
            if (next == 0) throw new IllegalArgumentException("Invalid event sequence");
            if (next <= sequence) return;
            if (!observing && next != sequence + 1) {
                fail(new CompletionError(502, "upstream_error", "Generation event sequence interrupted", null));
                return;
            }
            String kind = event.getString("kind");
            if (kind.equals("completed")) {
                try {
                    JSONObject result = event.getJSONObject("payload").getJSONObject("result");
                    CompletionResult.validate(result);
                    actualFinished = true;
                    JSONObject usage = result.getJSONObject("usage");
                    long input = CompletionResult.tokens(usage, "prompt_tokens"), output = CompletionResult.tokens(usage, "completion_tokens");
                    service.getStorage().updateUsage(token.getContactId(), model.getModel(), output, input);
                } catch (CompletionError e) {
                    fail(e);
                    return;
                } catch (RuntimeException e) {
                    fail(new CompletionError(500, "server_error", "Failed to record generation usage", null));
                    return;
                }
                terminal = true;
            } else if (kind.equals("error")) {
                actualFinished = actualFinished || event.optBoolean("actualFinished", false);
                terminal = true;
            }
            else if (!kind.equals("started") && !kind.equals("delta")) {
                fail(new CompletionError(502, "upstream_error", "Unknown generation event", null));
                return;
            }
            sequence = next;
            try {
                if (!deliver(event) && !terminal)
                    fail(new CompletionError(503, "service_unavailable", "Dispatcher disconnected", null));
            } finally {
                if (terminal) release();
            }
        }

        synchronized void fail(CompletionError error) {
            if (terminal) return;
            terminal = true;
            if (sent && listener == null) {
                try {
                    cellet.speak(unitContext, new Packet(AIGCAction.CancelChatCompletion.name,
                            new JSONObject().put("requestId", id)).toDialect());
                } catch (RuntimeException ignored) {
                }
            }
            try {
                deliver(new JSONObject().put("requestId", id).put("sequence", listener == null ? ++sequence : sequence)
                        .put("kind", "error").put("payload", new JSONObject().put("error", error.toJSON())));
            } catch (RuntimeException e) {
                Logger.w(ChatCompletionService.class, "Unable to send terminal event", e);
            } finally {
                release();
            }
        }

        boolean deliver(JSONObject event) {
            if (listener == null) return send(origin, dialect, event);
            listener.onEvent(new JSONObject(event.toString()).put("actualFinished", actualFinished || !attempted)
                    .put("unitId", unit.getQueryKey()));
            return true;
        }

        void release() {
            // Internal callers keep observing late completion. Logical failure is not physical completion.
            if ((listener == null || actualFinished || !attempted) && requests.remove(id, this)) admission.release();
            if (timeoutTask != null) timeoutTask.cancel(false);
            service.removeQueuedChatCompletion(this);
            if (listener == null || actualFinished || !attempted) done.countDown();
        }
    }

}
