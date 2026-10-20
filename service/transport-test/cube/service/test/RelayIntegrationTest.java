package cube.service.test;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.ModelConfig;
import cube.aigc.completion.*;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.*;
import cube.dispatcher.aigc.completion.*;
import cube.service.aigc.AIGCService;
import cube.service.aigc.resource.*;
import cube.service.aigc.unit.UnitScheduler;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cube.service.test.ChatCompletionServiceTest.check;
import static cube.service.test.ChatCompletionServiceTest.until;

/** Real HTTP at both ends, actual service queue and accounting, synthetic remote model hardware. */
public final class RelayIntegrationTest {
    static final String TOKEN = "remote-cube-test-token";
    static final JSONObject CAPS = new JSONObject().put("text", true).put("images", true).put("tools", true).put("stream", true)
            .put("parameters", new JSONArray(Arrays.asList("max_tokens", "max_completion_tokens", "temperature", "top_p", "top_k", "stop", "seed", "tool_choice", "parallel_tool_calls")));
    static final class Backend implements CompletionBackend {
        volatile boolean discoverable = true;
        volatile int rejection;
        volatile boolean truncate;
        volatile boolean stalled;
        final List<JSONObject> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
        @Override public JSONArray models(String token, long timeout) {
            if (!TOKEN.equals(token)) throw new CompletionError(401, "authentication_error", "Rejected", null);
            if (!discoverable) return new JSONArray();
            return new JSONArray().put(new JSONObject().put("id", "Baize").put("capabilities", CAPS))
                    .put(new JSONObject().put("id", "Baize2").put("capabilities", CAPS));
        }
        @Override public Subscription generate(String token, String id, JSONObject request, long timeout, Listener listener) {
            requests.add(new JSONObject(request.toString()));
            int running = active.incrementAndGet(); peak.accumulateAndGet(running, Math::max);
            clock.schedule(() -> {
                try {
                    JSONObject message = new JSONObject().put("role", "assistant").put("content", "answer");
                    JSONArray tools = request.optJSONArray("tools");
                    JSONObject last = request.getJSONArray("messages").getJSONObject(request.getJSONArray("messages").length() - 1);
                    boolean call = tools != null && tools.length() > 0 && !last.getString("role").equals("tool");
                    if (call) message.put("content", "").put("tool_calls", new JSONArray().put(new JSONObject()
                            .put("id", "call_relay").put("type", "function").put("function", new JSONObject()
                                    .put("name", "lookup").put("arguments", "{\"city\":\"Beijing\"}"))));
                    JSONObject result = new JSONObject().put("message", message).put("finish_reason", call ? "tool_calls" : "stop")
                            .put("usage", new JSONObject().put("prompt_tokens", 11).put("completion_tokens", 3).put("total_tokens", 14));
                    if (request.getJSONObject("options").has("stop")) result.put("stop_sequence", "STOP");
                    if (request.getBoolean("stream")) {
                        listener.onEvent(new JSONObject().put("requestId", id).put("sequence", 1).put("kind", "started").put("payload", new JSONObject()));
                        JSONObject delta = new JSONObject().put("content", "answer");
                        if (call) delta = new JSONObject().put("tool_calls", new JSONArray().put(new JSONObject()
                                .put("index", 0).put("id", "call_relay").put("type", "function")
                                .put("function", message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function"))));
                        listener.onEvent(new JSONObject().put("requestId", id).put("sequence", 2).put("kind", "delta")
                                .put("payload", new JSONObject().put("delta", delta)));
                    }
                    listener.onEvent(new JSONObject().put("requestId", id).put("sequence", request.getBoolean("stream") ? 3 : 1)
                            .put("kind", "completed").put("payload", new JSONObject().put("result", result)));
                } finally { active.decrementAndGet(); }
            }, 80, TimeUnit.MILLISECONDS);
            return () -> {};
        }
    }
    static final class Transport extends ChatCompletionServiceTest.FakeCellet {
        Fixture fixture;
        @Override public AIGCService getService() { return fixture; }
    }
    static final class Fixture extends ChatCompletionServiceTest.FakeService {
        final UnitScheduler scheduler = new UnitScheduler();
        Fixture(Transport transport, Relay relay) throws Exception {
            super(transport); transport.fixture = this; scheduler.fillFromRelay(relay);
        }
        @Override public List<AIGCUnit> getAllUnits() { return scheduler.getAll(); }
        @Override public AIGCUnit selectUnitByName(String name) { return scheduler.select(name); }
        @Override public List<ModelConfig> getModelConfigs() { return Arrays.asList(
                new ModelConfig("Baize", "Baize", "", "http://unused/", new JSONObject().put("unit", "Baize")),
                new ModelConfig("Baize2", "Baize2", "", "http://unused/", new JSONObject().put("unit", "Baize2"))); }
    }
    static Properties config(String url, String protocol) {
        Properties p = new Properties(); p.setProperty("relay", "true"); p.setProperty("relay.url", url);
        p.setProperty("relay.token", TOKEN); p.setProperty("relay.protocol", protocol); return p;
    }
    static JSONObject canonical(String model, boolean stream) {
        return new JSONObject().put("model", model).put("stream", stream).put("options", new JSONObject().put("max_tokens", 128))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
    }
    static JSONObject execute(Fixture service, JSONObject request) throws Exception {
        List<JSONObject> events = new CopyOnWriteArrayList<>();
        service.submitCompletion("token", UUID.randomUUID().toString(), request, 4000, events::add);
        until(() -> events.stream().anyMatch(e -> e.getString("kind").equals("completed") || e.getString("kind").equals("error")), "Relay terminal event");
        JSONObject last = events.get(events.size() - 1);
        check(last.getString("kind").equals("completed"), "Relay failed: " + last);
        check(last.getBoolean("actualFinished"), "Relay terminal evidence");
        until(() -> service.getCompletions().activeRequests() == 0, "Relay observer cleanup");
        until(() -> service.completionCapacity("token", request.getString("model"), false).getInt("available") == 1, "Relay slot cleanup");
        return last.getJSONObject("payload").getJSONObject("result");
    }
    static String http(String url, String path, boolean claude, boolean stream) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setReadTimeout(5000); c.setRequestProperty("Content-Type", "application/json");
        if (claude) { c.setRequestProperty("x-api-key", "token"); c.setRequestProperty("anthropic-version", "2023-06-01"); }
        else c.setRequestProperty("Authorization", "Bearer token");
        JSONObject body = new JSONObject().put("model", "Baize2").put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")))
                .put("max_tokens", 128).put("stream", stream);
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            check(c.getResponseCode() == 200, "local HTTP through Relay status");
            try (InputStream in = c.getInputStream()) { return read(in); }
        } finally { c.disconnect(); }
    }
    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n); return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    static void scenario(String url, String protocol, Backend hardware, AtomicReference<JSONObject> legacy) throws Exception {
        Relay relay = new Relay(config(url, protocol));
        Fixture fixture = new Fixture(new Transport(), relay);
        Server local = new Server(0);
        local.setHandler(new CompletionHandler(new CompletionBackend() {
            public JSONArray models(String token, long timeout) { return fixture.completionModels(token); }
            public Subscription generate(String token, String id, JSONObject request, long timeout, Listener listener) {
                fixture.getCompletions().generateInternal(token, id, request, timeout, listener::onEvent);
                return () -> fixture.getCompletions().cancel(token, id, null);
            }
        }, new CompletionConfig(new Properties())));
        local.start(); String localUrl = "http://127.0.0.1:" + ((ServerConnector) local.getConnectors()[0]).getLocalPort();
        try {
            relay.refreshModels();
            check(fixture.completionModels("token").length() == 2, "remote model capabilities");
            AIGCUnit unit = fixture.scheduler.select("Baize2");
            check(unit.getContact().getId() == 100000 && !unit.isRunning(), "select proxy without physical warmup");
            check(unit.getCapability().getChatCompletion().getBoolean("tools"), "native tools declaration");
            if (protocol.equals("claude")) check(!unit.getCapability().getChatCompletion().getJSONArray("parameters").toString().contains("seed"), "Claude capability intersection");
            for (boolean downstreamClaude : new boolean[]{false, true}) for (boolean stream : new boolean[]{false, true}) {
                String response = http(localUrl, downstreamClaude ? "/v1/messages" : "/v1/chat/completions", downstreamClaude, stream);
                check(response.contains("answer") && response.contains(stream ? downstreamClaude ? "message_stop" : "[DONE]" : "usage"), "both protocol ends and modes");
                until(() -> fixture.getCompletions().activeRequests() == 0 && !unit.isRunning(), "HTTP request released queue");
            }
            JSONObject tools = canonical("Baize2", true).put("tools", new JSONArray().put(new JSONObject().put("type", "function")
                    .put("function", new JSONObject().put("name", "lookup").put("parameters", new JSONObject().put("type", "object")))))
                    .put("options", new JSONObject().put("max_tokens", 128).put("tool_choice", "required").put("parallel_tool_calls", false));
            JSONObject result = execute(fixture, tools);
            check(result.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).getString("id").equals("call_relay"), "tool identity preserved");
            tools.getJSONArray("messages").put(result.getJSONObject("message")).put(new JSONObject().put("role", "tool").put("tool_call_id", "call_relay").put("content", "result"));
            check(execute(fixture, tools).getJSONObject("message").getString("content").equals("answer"), "tool result round trip");
            JSONObject image = canonical("Baize2", false);
            image.getJSONArray("messages").getJSONObject(0).put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "image"))
                    .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:image/png;base64,aGVsbG8="))));
            execute(fixture, image);
            JSONObject stopped = canonical("Baize2", true); stopped.getJSONObject("options").put("stop", new JSONArray().put("STOP"));
            check(execute(fixture, stopped).getString("stop_sequence").equals("STOP"), "real stop sequence preserved");
            check(fixture.storage.calls.get() == 8 && fixture.storage.totalInput == 88 && fixture.storage.totalOutput == 24, "usage exactly once per completed request");
            JSONObject text = new JSONObject().put("unit", "Baize2").put("content", "legacy").put("history", new JSONArray())
                    .put("option", new GeneratingOption(0.7, 0.6, 1.1, 123, 9).toJSON());
            ActionDialect response = fixture.getCellet().transmit(unit.getContext(), new Packet(AIGCAction.TextToText.name, text).toDialect(), 2000);
            check(Packet.extractDataPayload(new Packet(response)).getString("response").equals("legacy answer"), "legacy through normal transmit");
            check(legacy.get().getJSONObject("option").getInt("maxNewTokens") == 123 && legacy.get().getJSONObject("option").getInt("topK") == 9, "GeneratingOption preserved");
            List<JSONObject> concurrent = new CopyOnWriteArrayList<>();
            fixture.submitCompletion("token", "parallel-one", canonical("Baize", false), 2000, concurrent::add);
            fixture.submitCompletion("token", "parallel-two", canonical("Baize2", false), 2000, concurrent::add);
            until(() -> concurrent.size() == 2, "different model queues drained through shared budget");
            check(hardware.peak.get() == 1, "one shared HTTP budget across model aliases");
            hardware.discoverable = false; relay.refreshModels();
            check(fixture.completionModels("token").length() == 0 && fixture.completionCapacity("token", "Baize2", true).getInt("registered") == 0, "missing remote models are not ready");
            hardware.discoverable = true; relay.refreshModels();
            hardware.rejection = 401; relay.refreshModels();
            check(fixture.completionModels("token").length() == 0, "401 clears capability snapshot");
            hardware.rejection = 0; relay.refreshModels();
            AIGCUnit real = fixture.scheduler.setup(new Contact(100000, cube.auth.AuthConsts.DEFAULT_DOMAIN),
                    Collections.singletonList(new AICapability("Baize2", AICapability.NaturalLanguageProcessingTask, "2.0",
                            Collections.singletonList(AICapability.NaturalLanguageProcessing.Conversational), "")), new TalkContext(null, null)).get(0);
            check(Relay.owner(real) == null, "contact ID alone does not identify a Relay");
            relay.close(); check(fixture.scheduler.getAll().size() == 1 && fixture.scheduler.getAll().get(0) == real, "close removes only owned proxies");
        } finally { hardware.rejection = 0; hardware.discoverable = true; local.stop(); relay.close(); fixture.getCompletions().stop(); fixture.queue.shutdown(); }
    }
    static void failures(String url, String protocol, Backend hardware) throws Exception {
        for (String failure : Arrays.asList("rejected", "truncated", "timeout", "connect")) {
            Properties p = config(url, protocol);
            p.setProperty("relay.timeout.seconds", "1");
            Relay relay = new Relay(p);
            Fixture fixture = new Fixture(new Transport(), relay);
            try {
                relay.refreshModels();
                if (failure.equals("rejected")) hardware.rejection = 401;
                if (failure.equals("truncated")) hardware.truncate = true;
                if (failure.equals("timeout")) hardware.stalled = true;
                if (failure.equals("connect")) {
                    // A new provider with a discovered route can outlive the remote listener.
                    // Refused discovery itself must leave no advertised capability.
                    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { p.setProperty("relay.url", "http://127.0.0.1:" + socket.getLocalPort()); }
                    try (Relay disconnected = new Relay(p)) { disconnected.refreshModels(); check(!disconnected.selectUnit("Baize2").getCapability().getChatCompletion().optBoolean("text"), "unreachable endpoint advertised structured chat"); }
                    continue;
                }
                List<JSONObject> events = new CopyOnWriteArrayList<>();
                fixture.submitCompletion("token", UUID.randomUUID().toString(), canonical("Baize2", true), 3000, events::add);
                until(() -> events.stream().anyMatch(e -> e.getString("kind").equals("error")), "Relay failure event");
                JSONObject error = events.stream().filter(e -> e.getString("kind").equals("error")).findFirst().get();
                check(error.getBoolean("actualFinished") == failure.equals("rejected"), "logical failure falsely proves completion: " + failure);
                if (failure.equals("timeout")) check(error.getJSONObject("payload").getJSONObject("error").getInt("status") == 504, "deadline error status");
                if (failure.equals("rejected")) until(() -> fixture.completionCapacity("token", "Baize2", false).getInt("available") == 1, "definite rejection releases capacity");
                else {
                    check(fixture.completionCapacity("token", "Baize", false).getInt("available") == 0, "ambiguous remote execution released shared slot");
                    check(fixture.getCompletions().activeRequests() == 1, "unknown execution observer discarded");
                }
                check(fixture.storage.calls.get() == 0, "failed completion estimated usage");
            } finally {
                hardware.rejection = 0; hardware.truncate = false; hardware.stalled = false;
                fixture.getCompletions().stop(); relay.close(); fixture.queue.shutdown();
            }
        }
        Properties p = config(url, protocol); Relay first = Relay.configure(p);
        UnitScheduler scheduler = new UnitScheduler(); scheduler.fillFromRelay(first);
        check(first == Relay.configure(p), "unchanged configuration replaces active Relay");
        p.setProperty("relay.capacity", "2"); Relay second = Relay.configure(p); scheduler.fillFromRelay(second);
        check(first != second && scheduler.getAll().size() == 2 && scheduler.getAll().stream().allMatch(u -> Relay.owner(u) == second), "hot replacement left stale proxies");
        p.setProperty("relay", "false"); Relay.configure(p);
        check(scheduler.getAll().isEmpty(), "disable leaves selectable proxy Units");
    }
    static void codec() {
        JSONObject call = new JSONObject().put("type", "tool_use").put("id", "call_one").put("name", "lookup").put("input", new JSONObject());
        JSONObject history = new JSONObject().put("model", "Baize2").put("max_tokens", 128).put("messages", new JSONArray()
                .put(new JSONObject().put("role", "assistant").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "before"))
                        .put(call).put(new JSONObject().put("type", "text").put("text", "after"))))
                .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "tool_result")
                        .put("tool_use_id", "call_one").put("content", "result")))));
        JSONObject canonical = OpenAICompletionProtocol.normalize(history, true);
        JSONObject wire = RelayCodec.request(canonical, true, 2048);
        check(wire.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("id").equals("call_one"), "mixed Claude block order");
        check(wire.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("tool_use_id").equals("call_one"), "Claude tool result ID");
        try { RelayCodec.request(canonical, false, 2048); throw new AssertionError("lossy mixed history accepted"); }
        catch (CompletionError expected) { check(expected.status == 400, "unrepresentable OpenAI history must reject before sending"); }
        canonical = canonical("Baize2", false); canonical.getJSONArray("messages").getJSONObject(0).put("role", "developer");
        try { RelayCodec.request(canonical, true, 2048); throw new AssertionError("developer silently changed to system"); }
        catch (CompletionError expected) { check(expected.status == 400, "unrepresentable role rejected"); }
        RelayCodec.ClaudeStream stream = new RelayCodec.ClaudeStream();
        stream.add("message_start", new JSONObject().put("type", "message_start").put("message", new JSONObject().put("type", "message")
                .put("role", "assistant").put("content", new JSONArray()).put("usage", new JSONObject().put("input_tokens", 3).put("output_tokens", 0))));
        stream.add("content_block_start", new JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", call));
        JSONObject emptyArgs = stream.add("content_block_stop", new JSONObject().put("type", "content_block_stop").put("index", 0));
        check(emptyArgs.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments").equals("{}"), "empty tool arguments have a real delta");
        stream.add("message_delta", new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", "tool_use"))
                .put("usage", new JSONObject().put("output_tokens", 2)));
        stream.add("message_stop", new JSONObject().put("type", "message_stop"));
        check(stream.result().getJSONObject("usage").getInt("total_tokens") == 5, "Claude SSE actual usage");
        OpenAIResponseAccumulator accumulator = new OpenAIResponseAccumulator();
        accumulator.add(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject().put("content", "before")))));
        accumulator.add(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject().put("tool_calls", new JSONArray()
                .put(new JSONObject().put("index", 0).put("id", "call_one").put("type", "function").put("function", new JSONObject().put("name", "lookup").put("arguments", "{}"))))))));
        accumulator.add(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "tool_calls").put("delta", new JSONObject().put("content", "after"))))
                .put("usage", new JSONObject().put("prompt_tokens", 3).put("completion_tokens", 2).put("total_tokens", 5)));
        check(accumulator.result().getJSONArray("content_blocks").length() == 3, "OpenAI SSE keeps observed text/tool/text order");
    }
    public static void main(String[] args) throws Exception {
        codec();
        Backend hardware = new Backend(); AtomicReference<JSONObject> legacy = new AtomicReference<>();
        CompletionHandler compatibility = new CompletionHandler(hardware, new CompletionConfig(new Properties()));
        Server remote = new Server(0);
        remote.setHandler(new AbstractHandler() {
            @Override public void handle(String target, Request base, HttpServletRequest request, HttpServletResponse response) throws IOException {
                if (hardware.rejection != 0) { base.setHandled(true); response.setStatus(hardware.rejection); return; }
                if (target.contains("/chat/completions") || target.endsWith("/messages")) {
                    if (hardware.truncate || hardware.stalled) {
                        base.setHandled(true); response.setContentType("text/event-stream");
                        response.getWriter().write(": connected\n\n"); response.getWriter().flush();
                        if (hardware.stalled) try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return;
                    }
                }
                if (target.startsWith("/aigc/chat/")) {
                    legacy.set(new JSONObject(read(request.getInputStream()))); base.setHandled(true); response.setContentType("application/json");
                    response.getWriter().write(new JSONObject().put("content", "legacy answer").put("thought", "thought").toString()); return;
                }
                try { compatibility.handle(target, base, request, response); } catch (javax.servlet.ServletException e) { throw new IOException(e); }
            }
        });
        compatibility.setServer(remote); compatibility.start(); remote.start();
        String url = "http://127.0.0.1:" + ((ServerConnector) remote.getConnectors()[0]).getLocalPort();
        try {
            scenario(url, "openai", hardware, legacy); scenario(url, "claude", hardware, legacy);
            failures(url, "openai", hardware); failures(url, "claude", hardware);
            System.out.println("RelayIntegrationTest: " + ChatCompletionServiceTest.assertions + " assertions passed");
        } finally { remote.stop(); compatibility.stop(); hardware.clock.shutdownNow(); }
    }
}
