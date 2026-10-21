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
 * Current general-tool and dedicated sandbox contracts.
 */
public final class ToolProtocol {
    public static final String TOOL = "tool";
    public static final String SANDBOX = "sandbox";

    private static String family(String runnerType) {
        if (!TOOL.equals(runnerType) && !SANDBOX.equals(runnerType))
            throw new IllegalArgumentException("RUNNER_TYPE_INVALID");
        return "runner-" + runnerType;
    }

    public static JSONObject parse(String schema, byte[] raw) {
        try {
            JSONObject value = StrictJson.object(raw);
            String root = family(schema.equals("sandbox-receipt") ? SANDBOX
                    : value.optString("runnerType", TOOL));
            Contract.validate(
                    value, StrictJson.object(Contract.resource(root + "/schemas/" + schema + ".json")));
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }

    public static JSONObject runtime() {
        return runtime(TOOL);
    }

    public static JSONObject runtime(String runnerType) {
        String root = family(runnerType);
        try {
            return StrictJson.object(
                    Contract.resource(root + "/runtime.json"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }

    public static void verify() {
        try {
            for (String root : new String[]{"runner-tool", "runner-sandbox"}) {
                JSONObject manifest = StrictJson.object(Contract.resource(root + "/manifest.json"));
                for (String p : manifest.keySet())
                    if (!RuntimeSupport.sha(Contract.resource(root + "/" + p)).equals(manifest.getString(p)))
                        throw new IllegalArgumentException("RUNNER_CONTRACT_DIGEST_MISMATCH");
            }
            for (String type : new String[]{TOOL, SANDBOX}) {
                JSONObject runtime = runtime(type);
                if (!cube.service.harness.store.JdbcRunStore.hash(runtime.getJSONObject("sources"))
                        .equals(runtime.getString("digest")))
                    throw new IllegalArgumentException("RUNNER_SOURCE_DIGEST_MISMATCH");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("RUNNER_CONTRACT_MISSING", e);
        }
    }
}
