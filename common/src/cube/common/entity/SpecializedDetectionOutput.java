package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/** Structured detector results, including successful empty detections and stream snapshots. */
public final class SpecializedDetectionOutput implements JSONable {
    public final long sn;
    public final JSONObject resultPayload;
    private final JSONObject json;

    public SpecializedDetectionOutput(long sn, JSONObject payload) {
        if (!(payload.opt("response") instanceof String) || !(payload.opt("thought") instanceof String)
                || payload.optJSONObject("resultPayload") == null || payload.optJSONObject("performance") == null)
            throw new IllegalArgumentException("Invalid detection response shape");
        this.sn = sn;
        this.json = new JSONObject(payload.toString()).put("sn", sn).put("unit", SpecializedDetectionInput.UNIT)
                .put("timestamp", System.currentTimeMillis());
        this.resultPayload = this.json.getJSONObject("resultPayload");
    }
    @Override public JSONObject toJSON() { return new JSONObject(json.toString()); }
    @Override public JSONObject toCompactJSON() { return toJSON(); }
}
