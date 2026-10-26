package cube.service.test;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.spi.PluginCallException;
import cube.common.Packet;
import cube.common.entity.*;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** No live service or database. Tests real scheduler and plugin invoker admission/correlation. */
public final class PluginUnitInvokerTest {
    static int assertions;
    static void check(boolean value, String text) { if (!value) throw new AssertionError(text); assertions++; }
    static void failure(int http, Runnable call) {
        try { call.run(); throw new AssertionError("Expected failure " + http); }
        catch (PluginCallException e) { check(e.httpStatus == http, "expected HTTP status " + http); }
    }
    static final class Context extends TalkContext {
        volatile boolean valid = true;
        Context() { super(null, null); }
        @Override public boolean isValid() { return valid; }
    }
    static final class Cellet extends AIGCCellet {
        final Map<TalkContext, Packet> sent = new ConcurrentHashMap<>();
        volatile boolean timeout;
        @Override public ActionDialect transmit(TalkContext context, ActionDialect dialect, long milliseconds) {
            Packet request = new Packet(dialect);
            sent.put(context, request);
            check("pluginInvoke".equals(request.name), "fixed plugin channel");
            check("OmniForest".equals(request.data.getString("unit")), "exact unit in request");
            if (timeout) return null;
            return response(request).toDialect();
        }
    }
    static Packet response(Packet request) {
        JSONObject result = new JSONObject().put("response", "").put("thought", "")
                .put("resultPayload", new JSONObject()).put("performance", new JSONObject());
        return new Packet(request.sn, "pluginInvoke", new JSONObject().put("code", 0).put("data", result));
    }
    static JSONObject status(long sequence, String state, int capacity, int inFlight) {
        JSONArray requests = new JSONArray();
        for (int i = 0; i < inFlight; i++) requests.put(i + 1);
        return new JSONObject().put("sequence", sequence).put("units", new JSONArray().put(new JSONObject()
                .put("unit", "OmniForest").put("state", state).put("stage", state)
                .put("ready", "ready".equals(state)).put("error", "failed".equals(state) ? "warmup failed" : "")
                .put("maxConcurrency", capacity).put("inFlight", inFlight).put("activeRequests", requests)
                .put("actions", new JSONArray().put("detection"))));
    }
    public static void main(String[] args) throws Exception {
        Cellet cellet = new Cellet();
        AIGCService service = new AIGCService(cellet);
        cube.service.aigc.unit.PluginUnitInvoker invoker = service.getPluginUnits();
        Contact a = new Contact(100L, "test", "a"), b = new Contact(200L, "test", "b");
        AICapability capability = new AICapability("OmniForest", "ComputerVision", "1.0",
                Collections.singletonList("ObjectDetection"), "OmniForest");
        Context ca = new Context(), cb = new Context();
        service.setupUnit(a, Collections.singletonList(capability), ca);
        service.setupUnit(b, Collections.singletonList(capability), cb);
        check(invoker.count("OmniForest") == 2, "two providers registered");
        check(!invoker.status("OmniForest").getBoolean("ready"), "registration alone is not readiness");
        failure(503, () -> invoker.invoke("OmniForest", new JSONObject(), 100));
        check(invoker.report(ca, status(1, "ready", 1, 0)), "first provider readiness");
        check(invoker.report(cb, status(1, "ready", 1, 0)), "second provider readiness");
        for (String name : Arrays.asList(" OmniForest ", "Omni_Forest", "../OmniForest", ""))
            failure(400, () -> invoker.invoke(name, new JSONObject(), 100));
        for (String name : Arrays.asList("unknown-unit", "forest-detection", "omniforest", "OMNIFOREST", "Omniforest"))
            failure(404, () -> invoker.invoke(name, new JSONObject(), 100));
        invoker.invoke("OmniForest", new JSONObject(), 100);
        cellet.timeout = true;
        failure(504, () -> invoker.invoke("OmniForest", new JSONObject(), 100));
        failure(504, () -> invoker.invoke("OmniForest", new JSONObject(), 100));
        failure(429, () -> invoker.invoke("OmniForest", new JSONObject(), 100));
        for (AIGCUnit unit : service.getAllUnits()) unit.resetRunning();
        failure(429, () -> invoker.invoke("OmniForest", new JSONObject(), 100));
        invoker.complete(new Context(), response(cellet.sent.get(ca)).toDialect());
        failure(429, () -> invoker.invoke("OmniForest", new JSONObject(), 100));
        invoker.complete(ca, response(cellet.sent.get(ca)).toDialect());
        cellet.timeout = false;
        invoker.invoke("OmniForest", new JSONObject(), 100);
        cb.valid = false;
        check(invoker.count("OmniForest") == 1, "disconnected provider excluded");
        service.setupUnit(a, Collections.emptyList(), ca);
        check(service.getAllUnits().size() == 1, "repeat setup removes old capabilities for that node only");
        Contact otherDomain = new Contact(200L, "other-domain", "other");
        service.setupUnit(otherDomain, Collections.singletonList(capability), new Context());
        service.teardownUnit(b);
        check(service.getAllUnits().size() == 1, "teardown preserves the same contact id in another domain");
        service.teardownUnit(otherDomain);
        check(service.getAllUnits().isEmpty(), "teardown removes providers");
        System.out.println("PluginUnitInvokerTest: " + assertions + " assertions passed");
    }
}
