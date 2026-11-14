package cube.aigc.unit;

import org.json.JSONArray;
import org.json.JSONObject;

/** Public result envelope. Adapters must project raw results and files before constructing it. */
public final class UnitExecutionReceipt {
    public final String operationId, executionRef, state;
    private final JSONObject result;

    public UnitExecutionReceipt(String operationId, String executionRef, String state, String answer,
            String dataJson, JSONArray artifactRefs, JSONArray warnings) {
        this.operationId = UnitJson.id(operationId);
        this.executionRef = UnitJson.id(executionRef);
        if (!java.util.Arrays.asList("succeeded", "running", "stopping", "failed", "unknown").contains(state))
            throw new IllegalArgumentException("UNIT_EXECUTION_STATE_INVALID");
        this.state = state;
        if (answer == null || answer.length() > 4096 || dataJson == null || dataJson.length() > 8192
                || artifactRefs == null || artifactRefs.length() > 32 || warnings == null || warnings.length() > 16)
            throw new IllegalArgumentException("UNIT_RESULT_LIMIT_EXCEEDED");
        for (int i = 0; i < artifactRefs.length(); i++) UnitJson.id(artifactRefs.getString(i));
        for (int i = 0; i < warnings.length(); i++) UnitJson.id(warnings.getString(i));
        result = new JSONObject().put("operationId", operationId).put("executionRef", executionRef).put("state", state)
                .put("answer", answer).put("dataJson", dataJson)
                .put("artifactRefs", new JSONArray(artifactRefs.toString())).put("warnings", new JSONArray(warnings.toString()));
    }

    public JSONObject toJSON() { return UnitJson.copy(result); }
    public boolean terminal() { return "succeeded".equals(state) || "failed".equals(state); }
}
