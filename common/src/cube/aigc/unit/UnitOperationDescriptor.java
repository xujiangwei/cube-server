package cube.aigc.unit;

import org.json.JSONObject;

/** Immutable versioned business contract. Private routing metadata is never projected. */
public final class UnitOperationDescriptor {
    private static final String[] FIELDS = {"operationId", "version", "unitName", "skill", "toolName",
            "brokerOperation", "permission", "description", "executionMode", "effect", "approval",
            "inputSchema", "outputSchema", "inputMediaTypes", "outputMediaTypes", "dependencies", "usageKind", "limits"};
    private final JSONObject definition;
    public final String operationId, descriptorDigest, unitName, toolName, brokerOperation, permission, effect, approval;

    public UnitOperationDescriptor(JSONObject installedDefinition) {
        definition = new JSONObject();
        for (String key : FIELDS) definition.put(key, installedDefinition.get(key));
        operationId = UnitJson.id(definition.getString("operationId"));
        unitName = UnitJson.id(definition.getString("unitName"));
        toolName = UnitJson.id(definition.getString("toolName"));
        brokerOperation = UnitJson.id(definition.getString("brokerOperation"));
        permission = UnitJson.id(definition.getString("permission"));
        effect = definition.getString("effect");
        approval = definition.getString("approval");
        if (!java.util.Arrays.asList("read-only", "write", "session-control").contains(effect)
                || !java.util.Arrays.asList("policy", "required").contains(approval)
                || !java.util.Arrays.asList("sync", "job", "session").contains(definition.getString("executionMode"))
                || !definition.getString("skill").matches("[a-z][a-z0-9_.-]*/[a-z][a-z0-9_.-]*")
                || !toolName.startsWith(definition.getString("skill").replace('/', '.') + "."))
            throw new IllegalArgumentException("UNIT_OPERATION_INVALID");
        descriptorDigest = UnitJson.digest(definition);
        if (!descriptorDigest.equals(installedDefinition.getString("descriptorDigest")))
            throw new IllegalArgumentException("UNIT_DESCRIPTOR_DIGEST_MISMATCH");
        // JSONObject values supplied by callers are mutable; isolate the entire object graph.
        JSONObject detached = UnitJson.copy(definition);
        for (String key : FIELDS) definition.put(key, detached.get(key));
    }

    public JSONObject toJSON() { return UnitJson.copy(definition).put("descriptorDigest", descriptorDigest); }
    public JSONObject inputSchema() { return UnitJson.copy(definition.getJSONObject("inputSchema")); }
    public JSONObject outputSchema() { return UnitJson.copy(definition.getJSONObject("outputSchema")); }
    public String executionMode() { return definition.getString("executionMode"); }
    public int timeoutMillis() { return definition.getJSONObject("limits").getInt("timeoutMillis"); }
}
