package cube.aigc.unit;

import cube.aigc.usage.UsageContext;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;

public final class UnitCatalogTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    private static void rejected(Runnable action, String code) {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (IllegalArgumentException failure) { check(code.equals(failure.getMessage())); }
    }

    public static void main(String[] ignored) {
        UnitCatalog catalog = UnitCatalog.installed(); // Also checks Python/Java canonical digest parity.
        check("41a0b7e8ade1eb99d22b7ddc893876e1a0c02d667f0ce1f664dca33ef223896c".equals(
                UnitJson.digest(new JSONObject().put("text", "</script>\u0085\u2028\ud83d\ude00\n").put("ratio", 1e-7))));
        check(catalog.operations().size() >= 51);
        for (UnitOperationDescriptor descriptor : catalog.operations()) {
            JSONObject publicValue = descriptor.toJSON();
            check(!publicValue.has("adapterId") && !publicValue.has("binding") && !publicValue.has("source"));
            check(descriptor == catalog.brokerOperation(descriptor.brokerOperation));
            JSONObject changed = descriptor.toJSON();
            changed.put("effect", "write".equals(descriptor.effect) ? "read-only" : "write");
            rejected(() -> new UnitOperationDescriptor(changed), "UNIT_DESCRIPTOR_DIGEST_MISMATCH");
            descriptor.inputSchema().getJSONObject("properties").put("injected", true);
            check(!descriptor.inputSchema().getJSONObject("properties").has("injected"));
        }
        UnitOperationDescriptor speech = catalog.operation("speech.recognize.v1");
        for (String dependency : new String[]{"missing.v1", speech.operationId}) {
            JSONObject publicSpeech = speech.toJSON();
            publicSpeech.remove("descriptorDigest");
            publicSpeech.put("dependencies", new JSONArray().put(dependency));
            JSONObject row = UnitJson.copy(publicSpeech).put("descriptorDigest", UnitJson.digest(publicSpeech)).put("unitKey", "unit.speech");
            JSONObject invalid = new JSONObject().put("schemaVersion", 1)
                    .put("units", new JSONArray().put(new JSONObject().put("key", "unit.speech").put("name", "Speech")))
                    .put("operations", new JSONArray().put(row));
            invalid.put("catalogDigest", UnitJson.digest(invalid));
            rejected(() -> new UnitCatalog(invalid), dependency.equals(speech.operationId) ? "UNIT_DEPENDENCY_CYCLE" : "UNIT_DEPENDENCY_MISSING");
        }
        UsageContext usage = UsageContext.run("tenant", 123, "run-1", "job-1");
        UnitExecutionContext owner = new UnitExecutionContext("tenant", "123", "run-1", Collections.singleton(speech.permission), usage);
        rejected(() -> new UnitExecutionContext("other", "123", "run-1", Collections.emptySet(), usage), "UNIT_USAGE_IDENTITY_MISMATCH");
        UnitExecutionContext denied = new UnitExecutionContext("tenant", "123", "run-1", Collections.emptySet(), usage);
        rejected(() -> new UnitExecutionRequest(denied, "job-1", speech, new JSONObject(), 100), "UNIT_PERMISSION_DENIED");
        JSONObject arguments = new JSONObject().put("file", new JSONObject().put("kind", "input").put("ref", "file-1"));
        UnitExecutionRequest request = new UnitExecutionRequest(owner, "job-1", speech, arguments, 100);
        arguments.getJSONObject("file").put("ref", "other");
        check("file-1".equals(request.arguments().getJSONObject("file").getString("ref")));
        JSONArray artifacts = new JSONArray().put("artifact-1");
        UnitExecutionReceipt receipt = new UnitExecutionReceipt(speech.operationId, "exec-1", "succeeded", "ok", "{}", artifacts, new JSONArray());
        artifacts.put("other");
        check(receipt.terminal() && receipt.toJSON().getJSONArray("artifactRefs").length() == 1);
        System.out.println("UnitCatalogTest passed: " + catalog.operations().size() + " descriptors");
    }
}
