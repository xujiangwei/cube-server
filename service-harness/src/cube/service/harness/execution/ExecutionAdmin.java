/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Exports reviewable definitions; publication/binding remains an explicit RuntimeAdmin operation.
 */
public final class ExecutionAdmin {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("ExecutionAdmin <new-directory>");
        Path root = Files.createDirectory(Paths.get(args[0]));
        JSONObject runtime = new JSONObject().put("schemaVersion", 2).put("id", "resources").put("version", "1").put("kind", "runtime").put("mode", "resources-only")
                .put("capabilities", new JSONArray().put("resources")).put("maxWorkspaceBytes", 1048576).put("maxWorkspaceFiles", 128).put("certification", "offline-resources");
        Files.write(root.resolve("resources.json"), RuntimeSupport.bytes(runtime), StandardOpenOption.CREATE_NEW);
        for (String model : new String[]{"Baize", "Baize2"}) {
            Files.write(root.resolve(model + ".json"), RuntimeSupport.bytes(ModelProfiles.model(model)), StandardOpenOption.CREATE_NEW);
            JSONObject task = new JSONObject().put("schemaVersion", 2).put("id", "resources-" + model).put("version", "2").put("kind", "task")
                    .put("model", new JSONObject().put("id", model).put("version", "2")).put("runtime", new JSONObject().put("id", "resources").put("version", "1"))
                    .put("skillReleases", new JSONArray()).put("permissions", new JSONArray(ProfileRegistry.PLATFORM_PERMISSIONS)).put("requiredCapabilities", new JSONArray().put("resources").put("resource_decisions"))
                    .put("toolSetVersion", "resource-tools-v1").put("contextPolicy", new JSONObject().put("version", "context-v1").put("budgetVersion", "transport-bytes-v1").put("outputReserve", 2048).put("maxSourceChars", 4096).put("compaction", "extractive-v1").put("maxInputBytes", 48000))
                    .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 32).put("maxRepairs", 2).put("maxLifetimeMillis", 300000));
            Files.write(root.resolve("task-" + model + ".json"), RuntimeSupport.bytes(task), StandardOpenOption.CREATE_NEW);
        }
        System.out.println("Exported offline adapter profiles for Baize and Baize2; publish explicitly after review");
    }
}
