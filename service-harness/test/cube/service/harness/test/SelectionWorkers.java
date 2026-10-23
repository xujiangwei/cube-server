package cube.service.harness.test;

import cube.aigc.completion.CompletionProvider;
import cube.service.harness.execution.*;
import org.json.*;
import java.util.*;

/** Real registrations with offline inference hardware; no production admission bypass. */
final class SelectionWorkers {
    static final Map<String, String> sessions = new HashMap<>();
    static CompletionProvider provider() {
        return new CompletionProvider() {
            public JSONArray completionModels(String token) {
                JSONArray out = new JSONArray();
                for (String id : Arrays.asList("Baize", "Baize2")) out.put(new JSONObject().put("id", id)
                        .put("capabilities", new JSONObject().put("text", true).put("tools", true)));
                return out;
            }
            public JSONObject completionCapacity(String token, String model, boolean tools) { return new JSONObject().put("registered", 3).put("available", 3); }
            public void submitCompletion(String token, String id, JSONObject body, long timeout, Listener listener) { throw new AssertionError("Unexpected physical inference in selection fixture"); }
        };
    }

    static void tools(Properties p) {
        p.setProperty("broker.node.id", "selection-fixture-broker");
        p.setProperty("runner.grant.key", "selection-fixture-private-grant-key-1234567890");
        p.setProperty("execution.tools.enabled", "true");
        p.setProperty("execution.permissions", String.join(",", RuntimeTest.TEST_PERMISSIONS));
        p.setProperty("broker.mock.enabled", "true");
        p.setProperty("broker.mock.url", "http://127.0.0.1:1/status");
        p.setProperty("broker.mock.token", "selection-fixture-token-1234567890");
        p.setProperty("broker.mock.targets", "node-a");
    }

    static void online(ExecutionRuntime runtime, Properties p) {
        String model = runtime.deployment.model().getString("id");
        if (model.equals("scripted")) {
            p.setProperty("worker.selection-model.role", "model");
            p.setProperty("worker.selection-model.model", "scripted");
            p.setProperty("worker.selection-model.backend", "selection-scripted");
            p.setProperty("backend.selection-scripted.capacity", "1");
            runtime.workers.apply(ExecutionTest.envelope("selection-model", "selection-instance", "describe")
                    .put("descriptor", ExecutionTest.descriptor("selection-scripted")), 101, () -> true);
        } else {
            p.setProperty("backend.selection-unit.capacity", "3");
            p.setProperty("backend.selection-unit.type", "unit");
            p.setProperty("backend.selection-unit.token", "selection-fixture-token-1234567890");
            int transport = 101;
            for (String worker : Arrays.asList("baize-a", "baize-b", "baize2-a")) {
                String id = worker.startsWith("baize2") ? "Baize2" : "Baize";
                p.setProperty("worker." + worker + ".role", "model");
                p.setProperty("worker." + worker + ".model", id);
                p.setProperty("worker." + worker + ".backend", "selection-unit");
                JSONObject descriptor = InferenceTest.descriptor().put("model", id).put("nativeTools", id.equals("Baize2"))
                        .put("adapter", id.equals("Baize2") ? "openai-tools-v2" : "openai-json-v2");
                sessions.put(worker, runtime.workers.apply(InferenceTest.envelope(worker, "describe", null).put("descriptor", descriptor), transport++, () -> true).getString("sessionId"));
            }
        }
        if (runtime.runners != null) {
            p.setProperty("worker.selection-runner.role", "runner"); p.setProperty("worker.selection-runner.capacity", "1");
            runtime.runners.apply(new JSONObject().put("protocolVersion", 1).put("runnerType", "tool")
                    .put("requestId", RuntimeTest.key()).put("credential", "selection-fixture-token-123456")
                    .put("workerId", "selection-runner").put("instanceId", "selection-runner-instance").put("role", "runner")
                    .put("capabilities", new JSONArray().put("tool.execute")).put("action", "describe")
                    .put("descriptor", new JSONObject().put("runtimeId", "selection-runner-runtime").put("capacity", 1).put("runtime", "trusted-python-v1")), 201, () -> true);
        }
    }
}
