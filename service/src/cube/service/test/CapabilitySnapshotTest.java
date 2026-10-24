package cube.service.test;

import cube.common.entity.AIGCUnit;
import org.json.JSONObject;

/** Verifies the production snapshot against the existing service/worker fixture. */
public final class CapabilitySnapshotTest {
    public static void main(String[] args) throws Exception {
        ChatCompletionServiceTest.FakeCellet cellet = new ChatCompletionServiceTest.FakeCellet();
        ChatCompletionServiceTest.FakeService service = new ChatCompletionServiceTest.FakeService(cellet);
        try {
            AIGCUnit unit = service.selected;
            unit.setRunning(true);
            JSONObject snapshot = service.getCapabilitySnapshot();
            check(snapshot.getBoolean("ready"), "started service ready");
            check(snapshot.getJSONArray("units").length() == 1, "busy worker remains advertised");
            check(snapshot.getJSONArray("models").getJSONObject(0).getString("unit").equals("Baize2"), "model alias maps to unit");
            check(snapshot.getJSONArray("units").getJSONObject(0).getJSONObject("capability").has("chatCompletion"), "protocol features preserved");
            check(!snapshot.toString().contains("weight") && !snapshot.toString().contains("token"), "snapshot has no weight or token");
            check(snapshot.getString("instanceId").equals(service.getCapabilitySnapshot().getString("instanceId")), "instance identity stable");
            unit.setContext(null);
            check(service.getCapabilitySnapshot().getJSONArray("units").length() == 0, "disconnected worker removed from snapshot");
            java.lang.reflect.Field started = cube.core.AbstractModule.class.getDeclaredField("started");
            started.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) started.get(service)).set(false);
            JSONObject stopped = service.getCapabilitySnapshot();
            check(!stopped.getBoolean("ready") && stopped.getJSONArray("units").length() == 0, "starting/stopped service not ready");
            System.out.println("CapabilitySnapshotTest: 8 assertions passed");
        } finally { service.queue.shutdown(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
