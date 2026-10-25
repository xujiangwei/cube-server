package cube.dispatcher.test;

import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import org.json.*;
import static cube.dispatcher.test.CapabilityRoutingTest.*;

/** Module routing uses the production Performer and cached service discovery. */
public final class ModuleRoutingTest {
    static ActionDialect request(String action, String unit) {
        ActionDialect value = new Packet(action, new JSONObject()).toDialect();
        value.addParam("moduleName", "forest");
        value.addParam("moduleUnit", unit);
        value.addParam("token", "same-token");
        return value;
    }
    static void advertise(Node node) {
        node.snapshot.put("moduleRoutingVersion", 1).put("modules", new JSONArray().put(
                new JSONObject().put("name", "forest").put("actions", new JSONArray().put("forestDetect").put("forestGetCapability"))));
    }
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            Node legacy = f.add("legacy", unit("forest-detection", "ObjectDetection", false, false));
            Node noModel = f.add("no-model");
            Node a = f.add("forest-a", unit("forest-detection", "ObjectDetection", false, false));
            Node b = f.add("forest-b", unit("forest-detection", "ObjectDetection", false, false));
            until(() -> f.performer.getCapabilityRouter().candidates().size() == 4);
            advertise(noModel); advertise(a); advertise(b);
            f.refresh(noModel); f.refresh(a); f.refresh(b);
            until(() -> {
                synchronized (f.performer.getCapabilityRouter()) {
                    for (Node node : new Node[]{noModel, a, b}) {
                        Object state = f.stateUnchecked(node);
                        try { if (field(state, "inflight").getBoolean(state)) return false; }
                        catch (Exception error) { throw new AssertionError(error); }
                    }
                    return true;
                }
            });
            int aCalls = 0, bCalls = 0;
            for (int i = 0; i < 20; i++) {
                cube.dispatcher.Director selected = f.select(request("forestDetect", "forest-detection"));
                check(selected == a.director || selected == b.director, "only loaded forest providers selected");
                if (selected == a.director) aCalls++; else bCalls++;
            }
            check(aCalls == 10 && bCalls == 10, "same-capability services share traffic");
            unavailable(() -> f.select(request("forestDetect", "Forest-Detection")));
            unavailable(() -> f.select(request("unknownAction", "forest-detection")));
            f.performer.getCapabilityRouter().disconnected(a.speaker);
            f.performer.getCapabilityRouter().disconnected(b.speaker);
            check(f.select(request("forestGetCapability", "")) == noModel.director, "capability query works without a model");
            unavailable(() -> f.select(request("forestDetect", "forest-detection")));
            int queries = noModel.queries.get();
            Packet reply = new Packet(f.performer.syncTransmit("AIGC", request("forestGetCapability", ""), 2000));
            check("no-model".equals(Packet.extractDataPayload(reply).getString("node")), "actual forwarding follows module routing");
            check(queries == noModel.queries.get(), "request does not query discovery metadata");
        }
        System.out.println("ModuleRoutingTest: module and legacy snapshot compatibility passed");
    }
}
