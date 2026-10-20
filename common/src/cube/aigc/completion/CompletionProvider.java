/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * In-process structured inference. No dispatcher connection or HTTP session is required.
 */
public interface CompletionProvider {
    interface Listener {
        /**
         * Canonical started/delta/completed/error event. actualFinished is execution evidence.
         */
        void onEvent(JSONObject event);
    }

    JSONArray completionModels(String token);

    /**
     * Authorized healthy compatible Units and currently idle inference slots, independent of orchestration workers.
     * Optional capacityGroup identifies one aggregate shared pool; its counts describe that pool.
     * Optional capacityGroups entries identify shared budgets (capacityGroup, route, registered,
     * available, groupCapacity, groupAvailable). Count a route once and cap its group across models/backends.
     */
    JSONObject completionCapacity(String token, String model, boolean nativeTools);

    /**
     * Returns immediately; request uses CompletionRequest's canonical representation.
     */
    void submitCompletion(String token, String requestId, JSONObject request, long timeoutMillis, Listener listener);
}
