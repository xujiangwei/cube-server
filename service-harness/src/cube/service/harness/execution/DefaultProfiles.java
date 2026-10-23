/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.ProfileRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared definitions for deployment templates and the optional profile export CLI. */
public final class DefaultProfiles {
    private DefaultProfiles() { }

    public static JSONObject runtime() {
        return new JSONObject().put("schemaVersion", 2).put("id", "resources").put("version", "1")
                .put("kind", "runtime").put("mode", "resources-only").put("toolSetVersion", "resource-tools-v1")
                .put("capabilities", new JSONArray().put("resources"))
                .put("maxWorkspaceBytes", 1048576).put("maxWorkspaceFiles", 128)
                .put("certification", "offline-resources");
    }

    public static JSONObject tenantPolicy() {
        return new JSONObject().put("schemaVersion", 1).put("kind", "policy").put("id", "default").put("version", "1")
                .put("skillReleases", new JSONArray()).put("permissions", new JSONArray(ProfileRegistry.PLATFORM_PERMISSIONS))
                .put("businessLimits", new JSONObject().put("maxActiveRuns", 128).put("maxLifetimeMillis", 300000));
    }

    public static JSONObject execution(String model) {
        return new JSONObject().put("schemaVersion", 1).put("id", "server-default").put("version", "1").put("kind", "execution")
                .put("model", new JSONObject().put("id", model).put("version", model.equals("Baize") ? "1" : "2"))
                .put("runtime", new JSONObject().put("id", "resources").put("version", "1"))
                .put("requiredCapabilities", new JSONArray().put("resources").put("resource_decisions"))
                .put("contextPolicy", new JSONObject().put("version", "context-v1")
                        .put("budgetVersion", "transport-bytes-v1").put("outputReserve", 2048)
                        .put("maxSourceChars", 4096).put("compaction", "extractive-v1").put("maxInputBytes", 48000))
                .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 32)
                        .put("maxRepairs", 2).put("maxLifetimeMillis", 300000));
    }
}
