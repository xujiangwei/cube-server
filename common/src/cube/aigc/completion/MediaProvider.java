package cube.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

/** Trusted host bridge to existing image Units. No model code or public URL is accepted. */
public interface MediaProvider {
    /** Live, explicitly enabled routes: unit, instanceId, capacity, ready. */
    JSONArray mediaUnits();

    /**
     * Dispatch exactly once. The host persists its intent before calling this method.
     * A receipt is delivered only after a physical response (or a proven unsent failure).
     * Losing the connection is not physical completion. Implementations must not retry.
     */
    void invokeMedia(JSONObject execution, byte[] image, java.util.function.Consumer<JSONObject> receipt);
    /** Best effort: stop queued/preparation work; a sent request still needs a physical receipt. */
    default void cancelMedia(String requestId) { }
}
