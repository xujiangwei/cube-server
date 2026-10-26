package cube.service.test;

import cube.common.entity.*;
import cube.service.aigc.AIGCService;
import cube.service.aigc.unit.PluginUnitInvoker;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static cube.service.test.PluginUnitInvokerTest.*;

/** Readiness, connection ownership, atomic snapshots, expiry and capacity admission. */
public final class PluginUnitStatusTest {
    public static void main(String[] args) {
        Cellet cellet = new Cellet();
        AIGCService service = new AIGCService(cellet);
        Context ca = new Context(), cb = new Context();
        Contact a = new Contact(100L, "test", "a"), b = new Contact(200L, "test", "b");
        AICapability capability = new AICapability("forest-detection", "ComputerVision", "1.0",
                Collections.singletonList("ObjectDetection"), "OmniForest");
        service.setupUnit(a, Collections.singletonList(capability), ca);
        service.setupUnit(b, Collections.singletonList(capability), cb);
        AtomicLong now = new AtomicLong(1000);
        PluginUnitInvoker invoker = new PluginUnitInvoker(service, now::get);
        check(invoker.status("forest-detection").getInt("providers") == 2, "connected providers counted");
        failure(503, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        check(!invoker.report(new Context(), status(1, "ready", 1, 0)), "unregistered connection cannot report");
        check(invoker.report(ca, status(1, "initializing", 1, 0)), "initializing report accepted");
        check(invoker.report(cb, status(1, "failed", 1, 0)), "failed report accepted");
        check(invoker.status("forest-detection").getInt("availableCapacity") == 0, "unready capacity is zero");
        failure(503, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        check(invoker.report(cb, status(2, "ready", 1, 0)), "ready transition accepted");
        invoker.invoke("forest-detection", new JSONObject(), 100);
        check(cellet.sent.containsKey(cb) && !cellet.sent.containsKey(ca), "only ready provider receives work");
        check(!invoker.report(cb, status(2, "failed", 1, 0)), "duplicate version rejected");
        check(!invoker.report(cb, status(1, "failed", 1, 0)), "older version rejected");
        JSONObject malformed = status(3, "ready", 1, 0);
        malformed.getJSONArray("units").put(new JSONObject(malformed.getJSONArray("units").getJSONObject(0).toString())
                .put("unit", "unregistered-unit"));
        check(!invoker.report(cb, malformed), "malformed full snapshot rejected atomically");
        check(invoker.status("forest-detection").getInt("readyProviders") == 1, "failed snapshot cannot change readiness");
        check(invoker.report(cb, status(3, "ready", 1, 1)), "Python busy status accepted");
        failure(429, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        check(invoker.report(cb, status(4, "ready", 1, 0)), "Python idle status accepted");
        cellet.timeout = true;
        failure(504, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        check(invoker.status("forest-detection").getInt("availableCapacity") == 0, "timed out reservation consumes capacity");
        JSONObject busy = status(5, "ready", 1, 1);
        busy.getJSONArray("units").getJSONObject(0).put("activeRequests", new JSONArray().put(cellet.sent.get(cb).sn));
        check(invoker.report(cb, busy), "busy status alongside reservation");
        check(invoker.status("forest-detection").getInt("availableCapacity") == 0, "same work is not double counted");
        invoker.complete(cb, response(cellet.sent.get(cb)).toDialect());
        check(invoker.status("forest-detection").getInt("availableCapacity") == 0, "reported work still occupies capacity");
        check(invoker.report(cb, status(6, "ready", 1, 0)), "idle update releases reported work");
        check(invoker.status("forest-detection").getInt("availableCapacity") == 1, "capacity restored");
        now.addAndGet(30000);
        check(!invoker.status("forest-detection").getBoolean("ready"), "30 second stale status is unknown");
        failure(503, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        check(invoker.report(cb, status(7, "ready", 1, 0)), "periodic snapshot restores readiness");
        Context replacement = new Context();
        service.setupUnit(b, Collections.singletonList(capability), replacement);
        check(!invoker.report(cb, status(8, "ready", 1, 0)), "old connection rejected after reconnect");
        check(!invoker.status("forest-detection").getBoolean("ready"), "new connection starts unknown");
        check(invoker.report(replacement, status(1, "ready", 1, 0)), "new session may restart sequence");
        check(invoker.report(replacement, new JSONObject().put("sequence", 2).put("units", new JSONArray())), "empty full snapshot accepted");
        check(!invoker.report(replacement, status(1, "ready", 1, 0)), "empty snapshot cannot allow stale readiness replay");
        check(!invoker.status("forest-detection").getBoolean("ready"), "omitted units become unknown");
        check(invoker.report(replacement, status(3, "ready", 2, 1)), "concurrency capacity accepted");
        cellet.timeout = true;
        failure(504, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        check(invoker.status("forest-detection").getInt("availableCapacity") == 0, "distinct reported and reserved work both consume capacity");
        failure(429, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        JSONObject overlap = status(4, "ready", 2, 1);
        overlap.getJSONArray("units").getJSONObject(0).put("activeRequests", new JSONArray().put(cellet.sent.get(replacement).sn));
        check(invoker.report(replacement, overlap), "matching request identity accepted");
        check(invoker.status("forest-detection").getInt("availableCapacity") == 1, "same request counted once across both sides");
        failure(504, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        failure(429, () -> invoker.invoke("forest-detection", new JSONObject(), 100));
        replacement.valid = false;
        check(invoker.status("forest-detection").getInt("providers") == 1, "disconnection removes provider capacity");
        PluginUnitInvoker standard = service.getPluginUnits();
        check(standard.report(ca, status(1, "ready", 1, 0)), "standard invoker ready");
        service.setupUnit(a, Collections.singletonList(capability), ca);
        check(!standard.status("forest-detection").getBoolean("ready"), "repeat setup requires a new status");
        testStatusTransport(capability, a);
        System.out.println("PluginUnitStatusTest: " + assertions + " assertions passed");
    }

    private static void testStatusTransport(AICapability capability, Contact contact) {
        class StatusCellet extends cube.service.aigc.AIGCCellet {
            final cell.api.Nucleus nucleus = new cell.api.Nucleus();
            final AIGCService owner = new AIGCService(this);
            cell.core.talk.dialect.ActionDialect response;
            @Override public cell.api.Nucleus getNucleus() { return nucleus; }
            @Override public AIGCService getService() { return owner; }
            @Override public boolean speak(cell.core.talk.TalkContext context, cell.core.talk.Primitive primitive) {
                response = new cell.core.talk.dialect.ActionDialect(primitive); return true;
            }
        }
        StatusCellet cellet = new StatusCellet();
        Context context = new Context();
        cellet.owner.setupUnit(contact, Collections.singletonList(capability), context);
        cube.common.Packet request = new cube.common.Packet("pluginStatus", status(1, "ready", 1, 0));
        cell.core.talk.dialect.ActionDialect dialect = request.toDialect();
        dialect.addParam("_performer", new JSONObject().put("sn", request.sn).put("ts", 1));
        new cube.service.aigc.task.PluginStatusTask(cellet, context, dialect, null).run();
        cube.common.Packet response = new cube.common.Packet(cellet.response);
        check(request.sn.equals(response.sn) && request.name.equals(response.name), "status acknowledgement correlation");
        check(cube.common.Packet.extractCode(response) == 0, "registered status acknowledged successfully");
        check(cellet.response.getParamAsJson("_performer").getLong("sn") == request.sn, "Python synchronous routing metadata preserved");
        new cube.service.aigc.task.PluginStatusTask(cellet, new Context(), dialect, null).run();
        check(cube.common.Packet.extractCode(new cube.common.Packet(cellet.response)) == 1, "foreign connection receives explicit rejection");
    }
}
