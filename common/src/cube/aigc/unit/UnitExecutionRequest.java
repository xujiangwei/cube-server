package cube.aigc.unit;

import org.json.JSONObject;

/** Frozen operation intent built by the host after schema/resource/approval checks.
 * Business schemas contain no physical target or deployment selector.
 */
public final class UnitExecutionRequest {
    public final UnitExecutionContext context;
    public final String idempotencyKey, operationId, descriptorDigest, argumentsDigest;
    public final long deadlineAt;
    private final JSONObject arguments;

    public UnitExecutionRequest(UnitExecutionContext context, String idempotencyKey,
            UnitOperationDescriptor descriptor, JSONObject arguments, long deadlineAt) {
        if (context == null || !context.allows(descriptor.permission))
            throw new IllegalArgumentException("UNIT_PERMISSION_DENIED");
        this.context = context;
        this.idempotencyKey = UnitJson.id(idempotencyKey);
        this.operationId = descriptor.operationId;
        this.descriptorDigest = descriptor.descriptorDigest;
        this.arguments = UnitJson.copy(arguments);
        this.argumentsDigest = UnitJson.digest(this.arguments);
        if (deadlineAt <= 0) throw new IllegalArgumentException("UNIT_DEADLINE_INVALID");
        this.deadlineAt = deadlineAt;
    }

    public JSONObject arguments() { return UnitJson.copy(arguments); }
}
