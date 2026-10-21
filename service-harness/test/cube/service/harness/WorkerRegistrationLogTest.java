package cube.service.harness;

import cell.util.log.*;
import cube.service.harness.protocol.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Checks real registration responses and the public Logger output, including redaction. */
public final class WorkerRegistrationLogTest {
    static final class Capture implements LogHandle {
        final List<String> messages = new ArrayList<>();
        public String getName() { return "harness-registration-test"; }
        public void logDebug(String tag, String text) {}
        public void logInfo(String tag, String text) { if (tag.equals(HarnessService.class.getName())) messages.add("INFO " + text); }
        public void logWarning(String tag, String text) { if (tag.equals(HarnessService.class.getName())) messages.add("WARN " + text); }
        public void logError(String tag, String text) { if (tag.equals(HarnessService.class.getName())) messages.add("ERROR " + text); }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static JSONObject send(HarnessService service, JSONObject request, long transport) {
        return service.process(request.toString().getBytes(StandardCharsets.UTF_8), transport);
    }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties(); p.setProperty("worker.credential", "private-registration-test-credential");
        p.setProperty("http.port", "0"); p.setProperty("worker.model-1.role", "model");
        HarnessConfig config = new HarnessConfig(p);
        Capture capture = new Capture(); LogManager.getInstance().addHandle(capture);
        HarnessService service = new HarnessService(config);
        try {
            service.start(); capture.messages.clear();
            JSONObject request = new JSONObject().put("protocolVersion", 1).put("action", "register").put("workerId", "model-1")
                    .put("instanceId", "instance-1").put("role", "model").put("requestId", "register-1")
                    .put("capabilities", new JSONArray()).put("credential", p.getProperty("worker.credential"));
            JSONObject registered = send(service, request, 1);
            check(registered.getBoolean("registered") && !registered.getBoolean("ready"), "registration and readiness conflated");
            check(capture.messages.size() == 1 && capture.messages.get(0).startsWith("INFO ")
                    && capture.messages.get(0).contains("registered=true ready=false"), "not-ready success log");
            JSONObject duplicate = send(service, new JSONObject(request.toString()).put("instanceId", "other"), 2);
            check(duplicate.getString("errorCode").equals("DUPLICATE_WORKER") && capture.messages.size() == 2
                    && capture.messages.get(1).contains("WARN ") && capture.messages.get(1).contains("DUPLICATE_WORKER"), "duplicate rejection log");
            send(service, new JSONObject(request.toString()).put("credential", "never-log-this-private-credential"), 1);
            check(capture.messages.get(2).contains("UNAUTHORIZED"), "authentication rejection missing");
            send(service, new JSONObject(request.toString()).put("workerId", "forbidden"), 3);
            check(capture.messages.get(3).contains("FORBIDDEN"), "authorization rejection missing");
            int before = capture.messages.size();
            send(service, new JSONObject(request.toString()).put("action", "heartbeat").put("sessionId", registered.getString("sessionId")), 1);
            check(capture.messages.size() == before, "heartbeat repeated registration log");
            for (int protocol = 2; protocol <= 3; protocol++) {
                JSONObject model = new JSONObject(request.toString()).put("protocolVersion", protocol).put("action", "describe")
                        .put("descriptor", new JSONObject().put("model", "Baize2"));
                before = capture.messages.size(); send(service, model, protocol);
                check(capture.messages.size() == before + 1 && capture.messages.get(before).contains("registration rejected"), "versioned failure must log once");
                WorkerRegistrationLog.outcome(model, new JSONObject().put("ok", true).put("registered", true).put("ready", true), config, protocol, null);
                check(capture.messages.get(capture.messages.size() - 1).contains("registered=true ready=true"), "versioned success format");
            }
            for (String type : new String[]{"tool", "sandbox"}) {
                JSONObject runner = new JSONObject(request.toString());
                runner.remove("protocolVersion");
                runner.put("runnerType", type).put("role", "runner").put("action", "describe")
                        .put("capabilities", new JSONArray().put("tool.execute"))
                        .put("descriptor", new JSONObject().put("runtimeId", "runtime-test").put("capacity", 1)
                                .put("runtime", "trusted-python-v1")
                                .put("sourceDigest", cube.service.harness.execution.ToolProtocol.runtime(type).getString("digest")));
                if (type.equals("sandbox")) runner.getJSONObject("descriptor")
                        .put("executionProfile", "linux-firecracker-python-v1")
                        .put("sandboxProfileDigest", String.join("", Collections.nCopies(64, "a")))
                        .put("certificateDigest", String.join("", Collections.nCopies(64, "b")));
                JSONObject rejected = send(service, runner, 7);
                check(rejected.getString("errorCode").equals("RUNNER_DISABLED")
                        && rejected.getString("runnerType").equals(type) && !rejected.has("protocolVersion"), "named Runner error envelope");
                check(capture.messages.get(capture.messages.size() - 1).contains("runnerType=" + type), "Runner type missing from log");
                check(send(service, new JSONObject(runner.toString()).put("protocolVersion", 5), 7)
                        .getString("errorCode").equals("SCHEMA_INVALID"), "mixed numeric/named protocol accepted");
                check(send(service, new JSONObject(runner.toString()).put("runnerType", "unknown"), 7)
                        .getString("errorCode").equals("RUNNER_TYPE_INVALID"), "unknown Runner type accepted");
            }
            for (int legacy : new int[]{4, 5, 6})
                check(send(service, new JSONObject(request.toString()).put("protocolVersion", legacy), 8)
                        .getString("errorCode").equals("PROTOCOL_MISMATCH"), "legacy numeric Runner selector accepted");
            service.process("{invalid".getBytes(StandardCharsets.UTF_8), 1);
            check(capture.messages.get(capture.messages.size() - 1).contains("worker request rejected"), "invalid JSON rejection missing");
            WorkerRegistrationLog.outcome(request, HarnessService.error("register-1", "INTERNAL_ERROR"), config, 1,
                    new IllegalStateException("never-log-this-private-credential"));
            check(capture.messages.get(capture.messages.size() - 1).startsWith("ERROR "), "internal exception log severity");
            HarnessService.logWorkerRejection("HARNESS_BUSY", 1);
            check(capture.messages.get(capture.messages.size() - 1).contains("HARNESS_BUSY"), "queue rejection missing");
            String all = capture.messages.toString();
            check(!all.contains("never-log-this-private-credential") && !all.contains(p.getProperty("worker.credential"))
                    && !all.contains(registered.getString("sessionId")), "private fields leaked into logs");
            System.out.println("PASS: registration success/not-ready, probe/model versions, named Runner types, legacy rejection, heartbeat silence and private-field redaction");
        } finally { service.stop(); LogManager.getInstance().removeHandle(capture); }
    }
}
