package cube.dispatcher.test;

import cell.api.Nucleus;
import cell.api.Speakable;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import cube.dispatcher.Scope;
import cube.dispatcher.aigc.CapabilityUnavailableException;
import cube.dispatcher.aigc.completion.CompletionGateway;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real Performer dispatch and response correlation, with deterministic service connections. */
public final class CapabilityRoutingTest {
    static int assertions;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); assertions++; }
    static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), "background query completed");
    }
    static void unavailable(Runnable call) {
        try { call.run(); throw new AssertionError("unsupported capability was routed"); }
        catch (CapabilityUnavailableException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            Node a = f.add("asr", unit("Speech", "AutomaticSpeechRecognition", false, false));
            Node b = f.add("baize", unit("Baize", "Conversational", true, true));
            until(() -> f.performer.getCapabilityRouter().candidates().size() == 2);
            ActionDialect asr = action(AIGCAction.AutomaticSpeechRecognition, new JSONObject());
            check(f.select(asr) == a.director, "ASR capability selects A");
            check(new Packet(f.performer.syncTransmit("AIGC", asr)).data.getJSONObject("data").getString("node").equals("asr"), "REST sync uses A");
            check(f.performer.tryTransmit("AIGC", action(AIGCAction.AutomaticSpeechRecognition, new JSONObject())), "async ASR accepted");
            check(a.business.get() == 2 && b.business.get() == 0, "async ASR also uses A");
            f.performer.syncTransmit((TalkContext) null, "AIGC", action(AIGCAction.AutomaticSpeechRecognition, new JSONObject()));
            check(a.business.get() == 3, "Talk/WebSocket sync uses A");
            f.performer.transmit((TalkContext) null, "AIGC", action(AIGCAction.AutomaticSpeechRecognition, new JSONObject()));
            check(a.business.get() == 4, "Talk asynchronous forwarding uses A");
            ActionDialect create = action(AIGCAction.RequestChannel, new JSONObject().put("participant", "user"));
            ActionDialect created = f.performer.syncTransmit("AIGC", create);
            String channel = Packet.extractDataPayload(new Packet(created)).getString("code");
            check(b.business.get() == 1, "same token creates a Baize channel on B");
            f.performer.transmit((TalkContext) null, "AIGC", action(AIGCAction.RequestChannel, new JSONObject()));
            check(f.select(action(AIGCAction.Chat, new JSONObject().put("channel", "channel-baize"))) == b.director,
                    "asynchronous Talk channel creation registers ownership");
            f.performer.transmit("same-token", "AIGC", action(AIGCAction.RequestChannel, new JSONObject()));
            check(f.select(action(AIGCAction.Chat, new JSONObject().put("channel", "channel-baize"))) == b.director,
                    "asynchronous token channel creation registers ownership");
            AtomicInteger replies = new AtomicInteger();
            cube.dispatcher.aigc.AIGCCellet cellet = new cube.dispatcher.aigc.AIGCCellet() {
                @Override public boolean speak(TalkContext context, cell.core.talk.Primitive primitive) {
                    replies.incrementAndGet(); return true;
                }
            };
            f.performer.transmit((TalkContext) null, cellet, action(AIGCAction.RequestChannel, new JSONObject()));
            check(replies.get() == 1 && f.select(action(AIGCAction.Chat, new JSONObject().put("channel", "channel-baize"))) == b.director,
                    "asynchronous Cellet forwarding records ownership and returns reply");
            @SuppressWarnings("unchecked") Map<String, Object> owners = (Map<String, Object>) value(f.performer.getCapabilityRouter(), "channels");
            Object owner = owners.get(channel);
            for (int i = 0; i < 10000; i++) owners.put("limit-" + i, owner);
            unavailable(() -> f.performer.syncTransmit("AIGC", action(AIGCAction.RequestChannel, new JSONObject())));
            check(((Map<?, ?>) value(f.performer, "blockMap")).isEmpty(), "admission rejection releases synchronous block");
            unavailable(() -> f.performer.transmit((TalkContext) null, cellet, action(AIGCAction.RequestChannel, new JSONObject())));
            check(((Map<?, ?>) value(f.performer, "transmissionMap")).size() == 1, "admission rejection releases its forwarding record");
            owners.keySet().removeIf(key -> key.startsWith("limit-"));
            check(f.select(action(AIGCAction.Chat, new JSONObject().put("channel", channel))) == b.director, "chat pinned to B");
            check(f.select(action(AIGCAction.KeepAliveChannel, new JSONObject().put("code", channel))) == b.director, "channel keepalive pinned");
            check(f.select(action(AIGCAction.StopChannel, new JSONObject().put("code", channel))) == b.director, "channel stop pinned");
            unavailable(() -> f.select(action(AIGCAction.Chat, new JSONObject().put("channel", channel).put("unit", "Other"))));
            unavailable(() -> f.select(action(AIGCAction.Chat, new JSONObject().put("channel", "lost-channel"))));
            check(f.select(completion("one", false, false)) == b.director, "Completion model mapping selects B");
            f.performer.tryTransmit("AIGC", completion("cancel-owner", false, false));
            Node c = f.add("second-baize", unit("Baize", "Conversational", true, true));
            until(() -> f.performer.getCapabilityRouter().candidates().size() == 3);
            check(f.select(action(AIGCAction.CancelChatCompletion, new JSONObject().put("requestId", "cancel-owner"))) == b.director,
                    "Completion cancellation stays on original service after candidates change");
            check(f.select(action(AIGCAction.Chat, new JSONObject().put("channel", channel))) == b.director, "new node cannot move existing channel");
            CompletionGateway gateway = new CompletionGateway(); gateway.start(f.performer);
            check(gateway.models("same-token", 1000).length() == 1, "authorized model lists merge and deduplicate");
            int queries = a.queries.get() + b.queries.get() + c.queries.get();
            for (int i = 0; i < 100; i++) f.select(asr);
            check(queries == a.queries.get() + b.queries.get() + c.queries.get(), "requests never query metadata");
            a.snapshot = snapshot("asr", new JSONArray()); f.refresh(a);
            until(() -> a.queries.get() >= 2 && !f.hasAsr());
            unavailable(() -> f.select(asr));
            int sentBefore = a.business.get();
            unavailable(() -> f.performer.tryTransmit(a.director, "AIGC", action(AIGCAction.AutomaticSpeechRecognition, new JSONObject())));
            check(a.business.get() == sentBefore, "capability lost between selection and sending prevents transmission");
            a.snapshot = snapshot("asr", new JSONArray().put(unit("Speech", "AutomaticSpeechRecognition", false, false))); f.refresh(a);
            until(f::hasAsr);
            Object state = f.state(a); field(state, "received").setLong(state, System.currentTimeMillis() - 91000);
            unavailable(() -> f.select(asr));
            f.refresh(a); until(f::hasAsr);
            f.performer.getCapabilityRouter().disconnected(b.speaker);
            int disconnectedQueries = b.queries.get();
            f.performer.getCapabilityRouter().onTick(System.currentTimeMillis() + 100000);
            check(b.queries.get() == disconnectedQueries && !f.performer.getCapabilityRouter().candidates().contains(b.director),
                    "background scan does not revive an explicitly disconnected connection");
            unavailable(() -> f.select(action(AIGCAction.Chat, new JSONObject().put("channel", channel))));
            check(f.select(completion("two", false, false)) == c.director, "new requests exclude disconnected service");
            f.performer.getCapabilityRouter().connected(b.director, b.speaker);
            until(() -> f.performer.getCapabilityRouter().candidates().contains(b.director));
            unavailable(() -> f.select(action(AIGCAction.Chat, new JSONObject().put("channel", channel))));
            a.snapshot.put("ready", false); f.refresh(a); until(() -> !f.hasAsr());
            a.snapshot.put("ready", true); f.refresh(a); until(f::hasAsr);
            gateway.stop();
        }
        try (Fixture f = new Fixture()) {
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < 4; i++) nodes.add(f.add("asr-" + i, unit("Speech", "AutomaticSpeechRecognition", false, false)));
            until(() -> f.performer.getCapabilityRouter().candidates().size() == 4);
            Map<Director, AtomicInteger> counts = new ConcurrentHashMap<>();
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> work = new ArrayList<>();
                for (int i = 0; i < 400; i++) work.add(executor.submit(() -> counts.computeIfAbsent(f.select(action(
                        AIGCAction.AutomaticSpeechRecognition, new JSONObject())), k -> new AtomicInteger()).incrementAndGet()));
                for (Future<?> future : work) future.get();
            } finally { executor.shutdownNow(); }
            check(counts.size() == 4, "all nodes participate beyond the third node");
            for (AtomicInteger count : counts.values()) check(count.get() == 100, "concurrent round robin is even");
            nodes.get(1).ready = false;
            for (int i = 0; i < 20; i++) check(f.select(action(AIGCAction.AutomaticSpeechRecognition, new JSONObject())) != nodes.get(1).director, "unready node excluded");
            check(f.performer.selectDirectorForCellet("Harness") == null, "no unrelated Cellet fallback");
        }
        try (Fixture f = new Fixture()) {
            Node split = f.add("split", unit("Baize", "Conversational", true, false), unit("Baize", "Conversational", false, true));
            until(() -> f.performer.getCapabilityRouter().candidates().size() == 1);
            unavailable(() -> f.select(completion("split", true, true)));
            split.answer = false; f.refresh(split);
            until(() -> !(Boolean) value(f.stateUnchecked(split), "inflight"));
            check(f.select(completion("cached", false, false)) == split.director, "query timeout preserves unexpired snapshot");
        }
        try (Fixture f = new Fixture()) {
            Director director = f.performer.addDirector("127.0.0.1", 6300, "127.0.0.1", 6080, new TestScope());
            Node node = new Node(f, director, "startup", unit("Speech", "AutomaticSpeechRecognition", false, false));
            node.answer = false;
            director.speaker = node.speaker;
            f.performer.getCapabilityRouter().onTick(System.currentTimeMillis());
            until(() -> node.queries.get() == 1);
            f.performer.getCapabilityRouter().connected(director, node.speaker);
            f.performer.getCapabilityRouter().onTick(System.currentTimeMillis() + 1000);
            check(node.queries.get() == 1, "one query in flight per node");
            until(() -> !(Boolean) value(f.stateUnchecked(node), "inflight"));
            unavailable(() -> f.select(action(AIGCAction.AutomaticSpeechRecognition, new JSONObject())));
            node.answer = true;
            f.refresh(node); until(f::hasAsr);
            check(node.queries.get() == 2, "startup scan and retry discover service without contact callback");
        }
        try (Fixture f = new Fixture()) {
            class RecordingCellet extends cube.dispatcher.aigc.AIGCCellet {
                ActionDialect response;
                RecordingCellet() { this.nucleus = new Nucleus(); }
                @Override public boolean speak(TalkContext context, cell.core.talk.Primitive primitive) {
                    response = new ActionDialect(primitive); return true;
                }
            }
            RecordingCellet cellet = new RecordingCellet();
            new cube.dispatcher.aigc.PassThroughTask(cellet, null,
                    action(AIGCAction.AutomaticSpeechRecognition, new JSONObject()), f.performer).run();
            check(Packet.extractCode(new Packet(cellet.response)) == cube.common.state.AIGCStateCode.UnitNoReady.code,
                    "Cellet ASR failure preserves UnitNoReady");
            new cube.dispatcher.aigc.PassThroughTask(cellet, null, completion("rpc", false, false), f.performer).run();
            check(AIGCAction.ChatCompletionEvent.name.equals(cellet.response.getName())
                    && new Packet(cellet.response).data.getJSONObject("payload").getJSONObject("error").getInt("status") == 503,
                    "Cellet Completion failure preserves error event format");
            new cube.dispatcher.aigc.PassThroughTask(cellet, null, action(AIGCAction.ChatCompletion,
                    new JSONObject().put("requestId", "invalid").put("request", new JSONObject())), f.performer).run();
            check(new Packet(cellet.response).data.getJSONObject("payload").getJSONObject("error").getInt("status") == 400,
                    "malformed Completion receives an error instead of hanging");
        }
        testLateResponse();
        System.out.println("CapabilityRoutingTest: " + assertions + " assertions passed");
    }

    static void testLateResponse() throws Exception {
        try (Fixture f = new Fixture()) {
            Node old = f.add("old", unit("Speech", "AutomaticSpeechRecognition", false, false));
            until(f::hasAsr);
            old.returned = false; old.entered = new CountDownLatch(1); old.release = new CountDownLatch(1); f.refresh(old);
            check(old.entered.await(2, TimeUnit.SECONDS), "old query started");
            Node replacement = new Node(f, old.director, "new", unit("Baize", "Conversational", true, true));
            old.director.speaker = replacement.speaker;
            f.performer.getCapabilityRouter().connected(old.director, replacement.speaker);
            until(() -> f.selectableModel());
            old.release.countDown();
            until(() -> old.returned);
            unavailable(() -> f.select(action(AIGCAction.AutomaticSpeechRecognition, new JSONObject())));
            check(f.select(completion("new", false, false)) == old.director, "late old response cannot overwrite new snapshot");
        }
    }

    static ActionDialect action(AIGCAction action, JSONObject data) {
        ActionDialect result = new Packet(action.name, data).toDialect(); result.addParam("token", "same-token"); return result;
    }
    static ActionDialect completion(String id, boolean stream, boolean tools) {
        JSONObject request = new JSONObject().put("model", "model").put("stream", stream).put("options", new JSONObject())
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
        if (tools) request.put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", "lookup").put("parameters", new JSONObject().put("type", "object")))));
        return action(AIGCAction.ChatCompletion, new JSONObject().put("requestId", id).put("request", request));
    }
    static JSONObject unit(String name, String subtask, boolean stream, boolean tools) {
        return new JSONObject().put("capability", new JSONObject().put("name", name).put("task", "AudioProcessing")
                .put("subtask", subtask).put("description", "").put("chatCompletion", new JSONObject().put("text", true)
                        .put("stream", stream).put("tools", tools).put("parameters", new JSONArray())));
    }
    static JSONObject snapshot(String instance, JSONArray units) {
        return new JSONObject().put("version", 1).put("instanceId", instance).put("ready", true).put("units", units)
                .put("models", new JSONArray().put(new JSONObject().put("id", "model").put("unit", "Baize")));
    }
    static Field field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    static Object value(Object object, String name) {
        try { return field(object, name).get(object); } catch (Exception e) { throw new AssertionError(e); }
    }
    static final class TestScope extends Scope { TestScope() { cellets.add("AIGC"); } }
    static final class Fixture implements AutoCloseable {
        final Performer performer = new Performer(new Nucleus());
        int port;
        Fixture() { performer.getCapabilityRouter().start(); }
        Node add(String name, JSONObject... units) {
            Director director = performer.addDirector("127.0.0.1", 6200 + port++, "127.0.0.1", 6080, new TestScope());
            Node node = new Node(this, director, name, units); director.speaker = node.speaker;
            performer.getCapabilityRouter().connected(director, node.speaker); return node;
        }
        Director select(ActionDialect request) { return performer.selectDirectorForRequest("same-token", "AIGC", request); }
        boolean hasAsr() { try { select(action(AIGCAction.AutomaticSpeechRecognition, new JSONObject())); return true; } catch (CapabilityUnavailableException e) { return false; } }
        boolean selectableModel() { try { select(completion("probe", false, false)); return true; } catch (CapabilityUnavailableException e) { return false; } }
        Object state(Node node) throws Exception { return ((Map<?, ?>) field(performer.getCapabilityRouter(), "states").get(performer.getCapabilityRouter())).get(node.director); }
        Object stateUnchecked(Node node) { try { return state(node); } catch (Exception e) { throw new AssertionError(e); } }
        void refresh(Node node) throws Exception { synchronized (performer.getCapabilityRouter()) { field(state(node), "nextQuery").setLong(state(node), 0); } performer.getCapabilityRouter().onTick(System.currentTimeMillis()); }
        public void close() { performer.getCapabilityRouter().stop(); }
    }
    static final class Node {
        final Fixture fixture; final Director director; final String name; final Speakable speaker;
        final AtomicInteger queries = new AtomicInteger(), business = new AtomicInteger();
        volatile JSONObject snapshot; volatile boolean ready = true, answer = true, returned;
        volatile CountDownLatch entered, release;
        Node(Fixture fixture, Director director, String name, JSONObject... units) {
            this.fixture = fixture; this.director = director; this.name = name;
            snapshot = snapshot(name, new JSONArray(Arrays.asList(units)));
            speaker = (Speakable) Proxy.newProxyInstance(Speakable.class.getClassLoader(), new Class<?>[]{Speakable.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isReady": return ready;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "getRemoteAddress": return new InetSocketAddress("127.0.0.1", director.endpoint.getPort());
                    case "speak":
                        ActionDialect request = (ActionDialect) args[1]; JSONObject data;
                        if (request.getName().equals(AIGCAction.GetCapabilitySnapshot.name)) {
                            queries.incrementAndGet(); data = new JSONObject(snapshot.toString());
                            if (entered != null) entered.countDown();
                            if (release != null) release.await(5, TimeUnit.SECONDS);
                            if (!answer) return true;
                        } else {
                            business.incrementAndGet(); data = new JSONObject().put("node", name);
                            if (request.getName().equals(AIGCAction.RequestChannel.name)) data.put("code", "channel-" + name)
                                    .put("creationTime", System.currentTimeMillis()).put("participant", "user");
                            if (request.getName().equals(AIGCAction.ListChatModels.name)) data.put("models", new JSONArray().put(new JSONObject().put("id", "model")));
                        }
                        ActionDialect response = new Packet(request.getParamAsLong("sn"), request.getName(), new JSONObject().put("code", 0).put("data", data)).toDialect();
                        response.addParam("_performer", request.getParamAsJson("_performer"));
                        fixture.performer.onListened((Speakable) proxy, "AIGC", response); returned = true; return true;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }
}
