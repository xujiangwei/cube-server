/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONObject;

/**
 * Additive CPU Runner protocol; model v1/v2/v3 contracts remain frozen.
 */
public final class ToolProtocol {
    public static JSONObject parse(String schema, byte[] raw) {
        try {
            JSONObject value = StrictJson.object(raw);
            Contract.validate(value, StrictJson.object(Contract.resource("runner/schemas/" + schema + ".json")));
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }

    public static JSONObject runtime() {
        try {
            return StrictJson.object(Contract.resource("runner/runtime.json"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }

    public static void verify() {
        try {
            JSONObject manifest = StrictJson.object(Contract.resource("runner/manifest.json"));
            for (String p : manifest.keySet())
                if (!RuntimeSupport.sha(Contract.resource("runner/" + p)).equals(manifest.getString(p)))
                    throw new IllegalArgumentException("RUNNER_CONTRACT_DIGEST_MISMATCH");
            if (!cube.service.harness.store.JdbcRunStore.hash(runtime().getJSONObject("sources")).equals(runtime().getString("digest")))
                throw new IllegalArgumentException("RUNNER_SOURCE_DIGEST_MISMATCH");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }
}
