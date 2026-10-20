/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.service.aigc.resource;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.Utils;
import cell.util.log.Logger;
import cube.aigc.Consts;
import cube.aigc.ModelConfig;
import cube.aigc.completion.*;
import cube.auth.AuthConsts;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.*;
import cube.common.state.AIGCStateCode;
import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Development-only HTTP Units. Their identity is owned here, never inferred from a contact ID. */
public final class Relay implements AutoCloseable {
    private static volatile Relay instance;
    private final String url, token, fingerprint;
    private final boolean claude;
    private final int capacity, maxTokens;
    private final long timeoutMillis;
    private final Semaphore slots;
    private final Map<String, AIGCUnit> units = new LinkedHashMap<>();
    private final Map<String, Call> calls = new ConcurrentHashMap<>();
    private final Set<HttpURLConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ScheduledExecutorService refresh = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "relay-models"));
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "relay-deadlines"));
    private final ThreadPoolExecutor executor;
    private final Set<Map<String, AIGCUnit>> registries = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean closed;
    private String lastRefreshError;
    private final String group = "relay:" + UUID.randomUUID();

    public static final class Context extends TalkContext {
        public final Relay owner;
        public final String unit;
        private Context(Relay owner, String unit) { super(null, null); this.owner = owner; this.unit = unit; }
        @Override public boolean isValid() { return !owner.closed; }
        @Override public String getSessionHost() { return "development-relay"; }
    }

    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }

    public static Relay getInstance() { return instance; }
    public static synchronized Relay configure(Properties properties) {
        if (!Boolean.parseBoolean(properties.getProperty("relay", "false"))) {
            if (instance != null) instance.close();
            instance = null;
            return null;
        }
        String fingerprint = configuration(properties);
        if (instance != null && !instance.closed && instance.fingerprint.equals(fingerprint)) return instance;
        Relay next = new Relay(properties);
        Relay previous = instance;
        instance = next;
        if (previous != null) previous.close();
        return next;
    }

    public static synchronized Relay createInstance(String url, String token) {
        Properties p = new Properties(); p.setProperty("relay", "true"); p.setProperty("relay.url", url); p.setProperty("relay.token", token);
        return configure(p);
    }

    private static String configuration(Properties p) {
        return new JSONObject().put("url", p.getProperty("relay.url", "http://127.0.0.1:7010"))
                .put("token", p.getProperty("relay.token", "")).put("protocol", p.getProperty("relay.protocol", "openai"))
                .put("capacity", p.getProperty("relay.capacity", "1")).put("timeout", p.getProperty("relay.timeout.seconds", "300"))
                .put("tokens", p.getProperty("relay.max.tokens", "2048")).toString();
    }

    public Relay(Properties p) {
        fingerprint = configuration(p);
        String base = p.getProperty("relay.url", "http://127.0.0.1:7010").replaceAll("/+$", "");
        try {
            URI uri = new URI(base);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new Exception();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid relay.url"); }
        url = base + "/";
        token = p.getProperty("relay.token", "");
        if (token.isEmpty() || token.contains("\r") || token.contains("\n") || !token.matches("[A-Za-z0-9_.~-]+"))
            throw new IllegalArgumentException("Invalid relay.token");
        String protocol = p.getProperty("relay.protocol", "openai");
        if (!protocol.equals("openai") && !protocol.equals("claude")) throw new IllegalArgumentException("Invalid relay.protocol");
        claude = protocol.equals("claude");
        capacity = positive(p, "relay.capacity", 1, 32);
        timeoutMillis = positive(p, "relay.timeout.seconds", 300, 600) * 1000L;
        maxTokens = positive(p, "relay.max.tokens", 2048, Integer.MAX_VALUE);
        slots = new Semaphore(capacity);
        executor = new ThreadPoolExecutor(capacity, capacity, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
                r -> daemon(r, "relay-http"), new ThreadPoolExecutor.AbortPolicy());
        Contact contact = new Contact(100000, AuthConsts.DEFAULT_DOMAIN);
        for (String name : Arrays.asList(ModelConfig.BAIZE_UNIT, ModelConfig.BAIZE_2_UNIT)) {
            AICapability caps = new AICapability(name, AICapability.NaturalLanguageProcessingTask, "2.0",
                    Collections.singletonList(AICapability.NaturalLanguageProcessing.Conversational), "");
            AIGCUnit unit = new AIGCUnit(contact, caps, new Context(this, name)) {
                @Override public String getQueryKey() { return super.getQueryKey() + "_" + group; }
            };
            unit.setTimestamp(0); // Proxy routes have no physical Unit registration warmup.
            units.put(name, unit);
        }
        refresh.scheduleWithFixedDelay(this::refreshModels, 0, 10, TimeUnit.SECONDS);
    }

    private static int positive(Properties p, String k, int fallback, int max) {
        int v;
        try { v = Integer.parseInt(p.getProperty(k, String.valueOf(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid " + k); }
        if (v < 1 || v > max) throw new IllegalArgumentException("Invalid " + k);
        return v;
    }

    public synchronized void fillUnits(Map<String, AIGCUnit> map) {
        if (closed) return;
        registries.add(map);
        for (AIGCUnit unit : units.values()) map.put(unit.getQueryKey(), unit);
    }

    public AIGCUnit selectUnit(String name) { return closed ? null : units.get(name); }
    public String getUrl() { return url; }
    public static Relay owner(AIGCUnit unit) { return unit.getContext() instanceof Context ? ((Context) unit.getContext()).owner : null; }

    public JSONObject capacity(AIGCUnit unit) {
        return new JSONObject().put("capacityGroup", group).put("groupCapacity", capacity)
                .put("groupAvailable", closed ? 0 : slots.availablePermits());
    }

    /** No generation, scheduling, or caller-specific token is used by this background capability probe. */
    public synchronized void refreshModels() {
        if (closed) return;
        Map<String, JSONObject> next = new HashMap<>();
        String failure = null;
        HttpURLConnection c = null;
        ScheduledFuture<?> deadline = null;
        try {
            c = open("v1/models", false, 3000);
            final HttpURLConnection probe = c;
            deadline = deadlines.schedule(probe::disconnect, 3000, TimeUnit.MILLISECONDS);
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("HTTP_" + status);
            JSONArray models;
            try (InputStream in = c.getInputStream()) { models = new JSONObject(read(in, 4 * 1024 * 1024)).getJSONArray("data"); }
            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.getJSONObject(i), remote = model.optJSONObject("capabilities");
                if (remote == null || !remote.optBoolean("text")) continue;
                JSONObject caps = new JSONObject().put("text", true).put("images", remote.optBoolean("images"))
                        .put("tools", remote.optBoolean("tools")).put("stream", remote.optBoolean("stream"));
                JSONArray params = new JSONArray(), offered = remote.optJSONArray("parameters");
                if (offered != null) for (int j = 0; j < offered.length(); j++) {
                    String k = offered.getString(j);
                    if (Arrays.asList("max_tokens", "max_completion_tokens", "temperature", "top_p", "top_k", "stop",
                            "tool_choice", "parallel_tool_calls").contains(k)
                            || (!claude && Arrays.asList("seed", "presence_penalty", "frequency_penalty").contains(k))) params.put(k);
                }
                caps.put("parameters", params);
                next.put(model.getString("id"), caps);
            }
        } catch (Exception e) {
            next.clear();
            failure = e instanceof IOException && e.getMessage() != null && e.getMessage().matches("HTTP_[0-9]{3}")
                    ? e.getMessage() : "MODEL_DISCOVERY_FAILED";
        } finally { if (deadline != null) deadline.cancel(false); disconnect(c); }
        for (Map.Entry<String, AIGCUnit> entry : units.entrySet()) entry.getValue().getCapability()
                .setChatCompletion(next.getOrDefault(entry.getKey(), new JSONObject()));
        if (!Objects.equals(failure, lastRefreshError)) {
            if (failure != null) Logger.w(Relay.class, "#refreshModels - Development relay unavailable: " + failure);
            else Logger.i(Relay.class, "#refreshModels - Development relay model capabilities ready");
        }
        lastRefreshError = failure;
    }

    private HttpURLConnection open(String path, boolean useClaude, long timeout) throws IOException {
        if (closed) throw new IOException("Relay stopped");
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        connections.add(c);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout((int) Math.min(timeout, 3000));
        c.setReadTimeout((int) timeout);
        c.setRequestProperty("Content-Type", "application/json");
        if (useClaude) { c.setRequestProperty("x-api-key", token); c.setRequestProperty("anthropic-version", "2023-06-01"); }
        else c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private void disconnect(HttpURLConnection c) { if (c != null) { connections.remove(c); c.disconnect(); } }

    private static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) { if (bytes.size() + n > limit) throw new IOException("Relay response too large"); bytes.write(buffer, 0, n); }
        return decode(bytes.toByteArray());
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static void post(HttpURLConnection c, JSONObject body) throws IOException {
        c.setRequestMethod("POST"); c.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }

    public ActionDialect transmit(Context context, ActionDialect dialect, long timeout) {
        Packet packet = new Packet(dialect);
        if (!AIGCAction.TextToText.name.equals(packet.name)) return legacyReply(packet, null);
        HttpURLConnection c = null; ScheduledFuture<?> deadline = null;
        boolean acquired = false, attempted = false, finished = false;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeout, timeoutMillis));
        try {
            acquired = slots.tryAcquire(Math.min(timeout, timeoutMillis), TimeUnit.MILLISECONDS);
            if (!acquired) return legacyReply(packet, null);
            long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
            c = open("aigc/chat/" + token + "/", false, remaining);
            final HttpURLConnection active = c;
            deadline = deadlines.schedule(active::disconnect, remaining, TimeUnit.MILLISECONDS);
            JSONObject data = packet.data;
            JSONObject wire = new JSONObject().put("channel", Utils.randomString(16)).put("content", data.getString("content"))
                    .put("unit", context.unit).put("option", data.optJSONObject("option") == null ? new GeneratingOption().toJSON() : data.getJSONObject("option"))
                    .put("histories", 0).put("pattern", Consts.PATTERN_CHAT).put("recordable", false)
                    .put("records", data.optJSONArray("history") == null ? new JSONArray() : data.getJSONArray("history"));
            attempted = true;
            post(c, wire);
            int status = c.getResponseCode();
            if (status != 200) {
                finished = Arrays.asList(400, 401, 403, 404, 413, 429).contains(status);
                return legacyReply(packet, null);
            }
            try (InputStream in = c.getInputStream()) {
                JSONObject result = new JSONObject(read(in, 32 * 1024 * 1024));
                finished = result.opt("content") instanceof String;
                return legacyReply(packet, result);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Logger.w(Relay.class, "#transmit - Development relay text request failed: " + e.getClass().getSimpleName());
            return legacyReply(packet, null);
        } finally { if (deadline != null) deadline.cancel(false); disconnect(c); if (acquired && (finished || !attempted)) slots.release(); }
    }

    private static ActionDialect legacyReply(Packet request, JSONObject result) {
        boolean ok = result != null && result.opt("content") instanceof String;
        return new Packet(request.sn, request.name, new JSONObject().put("code", ok ? AIGCStateCode.Ok.code : AIGCStateCode.UnitError.code)
                .put("data", ok ? new JSONObject().put("response", result.getString("content")).put("thought", result.optString("thought", "")) : new JSONObject())).toDialect();
    }

    public boolean send(Context context, ActionDialect dialect, CompletionProvider.Listener listener) {
        Packet p = new Packet(dialect);
        String id = p.data.optString("requestId");
        if (AIGCAction.CancelChatCompletion.name.equals(p.name)) {
            Call call = calls.get(id);
            if (call != null) { call.cancelled = true; if (call.connection != null) call.connection.disconnect(); }
            return true;
        }
        if (!AIGCAction.ChatCompletion.name.equals(p.name) || closed) return false;
        JSONObject request = p.data.getJSONObject("request");
        Call call = new Call(id, context.unit, request, Math.min(timeoutMillis, p.data.getLong("timeoutMs")), listener);
        if (calls.putIfAbsent(id, call) != null) return false;
        try { executor.execute(call); }
        catch (RejectedExecutionException e) { calls.remove(id, call); call.error(new CompletionError(429, "rate_limit_error", "Relay queue is full", null), true); }
        return true;
    }

    private final class Call implements Runnable {
        final String id, unit;
        final JSONObject request;
        final long end;
        final CompletionProvider.Listener listener;
        final AtomicLong sequence = new AtomicLong();
        volatile HttpURLConnection connection;
        volatile boolean cancelled;
        Call(String id, String unit, JSONObject request, long timeout, CompletionProvider.Listener listener) {
            this.id = id; this.unit = unit; this.request = new JSONObject(request.toString());
            this.end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout); this.listener = listener;
        }
        void emit(String kind, JSONObject payload, boolean finished) {
            listener.onEvent(new JSONObject().put("requestId", id).put("sequence", sequence.incrementAndGet())
                    .put("kind", kind).put("payload", payload).put("actualFinished", finished));
        }
        void error(CompletionError error, boolean finished) { emit("error", new JSONObject().put("error", error.toJSON()), finished); }
        long remaining() throws SocketTimeoutException {
            long ms = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (ms <= 0 || cancelled) throw new SocketTimeoutException();
            return ms;
        }
        @Override public void run() {
            boolean acquired = false, attempted = false, finished = false;
            ScheduledFuture<?> task = null;
            try {
                CompletionRequest.validate(request);
                AIGCUnit target = units.get(unit);
                CompletionRequest.checkCapabilities(request, target.getCapability().getChatCompletion());
                JSONObject wire = RelayCodec.request(request, claude, maxTokens);
                acquired = slots.tryAcquire(remaining(), TimeUnit.MILLISECONDS);
                if (!acquired) throw new SocketTimeoutException();
                connection = open(claude ? "v1/messages" : "v1/chat/completions", claude, remaining());
                HttpURLConnection active = connection;
                task = deadlines.schedule(active::disconnect, remaining(), TimeUnit.MILLISECONDS);
                connection.setRequestProperty("Accept", request.getBoolean("stream") ? "text/event-stream" : "application/json");
                attempted = true;
                post(connection, wire);
                int status = connection.getResponseCode();
                if (status != 200) {
                    finished = Arrays.asList(400, 401, 403, 404, 413, 429).contains(status);
                    throw new CompletionError(status >= 400 && status < 500 ? status : 502,
                            "relay_http_" + status, "Remote Cube rejected the Relay request", null);
                }
                JSONObject result;
                try (InputStream in = connection.getInputStream()) {
                    if (!request.getBoolean("stream")) {
                        JSONObject response = new JSONObject(read(in, 32 * 1024 * 1024));
                        finished = terminal(response, claude);
                        result = RelayCodec.result(response, claude);
                    } else {
                        if (connection.getContentType() == null || !connection.getContentType().toLowerCase(Locale.ROOT).startsWith("text/event-stream")) throw RelayCodec.invalid();
                        emit("started", new JSONObject(), false);
                        RelayCodec.ClaudeStream anthropic = new RelayCodec.ClaudeStream();
                        OpenAIResponseAccumulator openai = new OpenAIResponseAccumulator();
                        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT));
                        StringBuilder data = new StringBuilder(); String event = ""; int total = 0;
                        while (true) {
                            String line = line(reader);
                            if (line == null) break;
                            total += line.length(); if (total > 32 * 1024 * 1024) throw RelayCodec.invalid();
                            if (line.isEmpty()) {
                                if (data.length() == 0) { event = ""; continue; }
                                String raw = data.toString(); data.setLength(0);
                                if (!claude && raw.equals("[DONE]")) { finished = openai.terminal(); break; }
                                JSONObject parsed = new JSONObject(raw);
                                JSONObject delta = claude ? anthropic.add(event, parsed) : openai.add(parsed);
                                event = "";
                                if (delta.length() > 0) emit("delta", new JSONObject().put("delta", delta), false);
                                if (claude && anthropic.stopped()) { finished = true; break; }
                            } else if (line.startsWith("data:")) {
                                if (data.length() > 0) data.append('\n');
                                data.append(line.substring(5).replaceFirst("^ ", ""));
                                if (data.length() > 4 * 1024 * 1024) throw RelayCodec.invalid();
                            } else if (line.startsWith("event:")) event = line.substring(6).trim();
                        }
                        if (!finished) throw RelayCodec.invalid();
                        result = claude ? anthropic.result() : openai.result();
                    }
                }
                emit("completed", new JSONObject().put("result", result), true);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                boolean timedOut = e instanceof SocketTimeoutException || cancelled || System.nanoTime() >= end;
                CompletionError error = e instanceof CompletionError ? (CompletionError) e : new CompletionError(
                        timedOut ? 504 : 502, timedOut ? "request_timeout" : "upstream_error", "Relay completion failed", null);
                Logger.w(Relay.class, "#send - Development relay completion failed: status=" + error.status + " code=" + error.code);
                error(error, finished || !attempted);
            } finally {
                if (task != null) task.cancel(false);
                disconnect(connection);
                // An ambiguous HTTP failure does not prove the remote model stopped.
                if (acquired && (finished || !attempted)) slots.release();
                calls.remove(id, this);
            }
        }
        String line(Reader reader) throws IOException {
            StringBuilder s = new StringBuilder(); int ch;
            while ((ch = reader.read()) != -1 && ch != '\n') {
                remaining(); if (s.length() >= 4 * 1024 * 1024) throw new IOException("SSE line too large"); s.append((char) ch);
            }
            if (ch == -1 && s.length() == 0) return null;
            if (s.length() > 0 && s.charAt(s.length() - 1) == '\r') s.setLength(s.length() - 1);
            return s.toString();
        }
    }

    private static boolean terminal(JSONObject response, boolean claude) {
        if (claude) return "message".equals(response.optString("type")) && "assistant".equals(response.optString("role"))
                && Arrays.asList("end_turn", "stop_sequence", "max_tokens", "tool_use").contains(response.optString("stop_reason"));
        JSONArray choices = response.optJSONArray("choices");
        return choices != null && choices.length() == 1 && choices.optJSONObject(0) != null
                && Arrays.asList("stop", "length", "tool_calls", "content_filter").contains(choices.getJSONObject(0).optString("finish_reason"));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        refresh.shutdownNow(); executor.shutdownNow(); deadlines.shutdownNow();
        for (HttpURLConnection c : connections) c.disconnect();
        for (Map<String, AIGCUnit> registry : registries) for (AIGCUnit unit : units.values()) registry.remove(unit.getQueryKey(), unit);
        registries.clear();
        for (AIGCUnit unit : units.values()) unit.getCapability().setChatCompletion(new JSONObject());
    }
}
