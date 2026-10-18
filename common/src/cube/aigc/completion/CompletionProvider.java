package cube.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

/** In-process structured inference. No dispatcher connection or HTTP session is required. */
public interface CompletionProvider {
    interface Listener {
        /** Canonical started/delta/completed/error event. actualFinished is execution evidence. */
        void onEvent(JSONObject event);
    }

    JSONArray completionModels(String token);

    /** Authorized healthy compatible Units and currently idle inference slots, independent of orchestration workers. */
    JSONObject completionCapacity(String token, String model, boolean nativeTools);

    /** Returns immediately; request uses CompletionRequest's canonical representation. */
    void submitCompletion(String token, String requestId, JSONObject request, long timeoutMillis, Listener listener);
}
