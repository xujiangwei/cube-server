/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 离线导出可审核的服务器执行配置和租户业务策略到新目录的 CLI 入口。
 *
 * <p>导出只生成文件：harness-execution 用于服务器部署，harness-tenant 用于租户业务策略初始化。
 * 目标目录必须不存在（{@link Files#createDirectory} 语义），已存在即失败，避免覆盖既有定义。
 */
public final class ExecutionAdmin {

    /**
     * 导出 Baize/Baize2 的模型、运行时、execution 和 policy 到新目录。
     *
     * @param args 恰需一个参数：目标新目录路径（必须不存在）
     * @throws Exception                目录创建或文件写入失败（含目录已存在）
     * @throws IllegalArgumentException 参数个数不为 1
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("ExecutionAdmin <new-directory>");
        }
        Path root = Files.createDirectory(Paths.get(args[0]));
        Path execution = Files.createDirectory(root.resolve("harness-execution"));
        Path tenant = Files.createDirectory(root.resolve("harness-tenant"));
        JSONObject runtime = DefaultProfiles.runtime();
        Files.write(execution.resolve("resources.json"), RuntimeSupport.bytes(runtime), StandardOpenOption.CREATE_NEW);
        JSONObject trusted = DefaultProfiles.runtime().put("id", "trusted-python").put("mode", "trusted-python-v1")
                .put("capabilities", new JSONArray().put("resources").put("tool.execute"))
                .put("certification", "offline-trusted-runner").put("toolSetVersion", "broker-tools");
        Files.write(execution.resolve("trusted-python.json"), RuntimeSupport.bytes(trusted), StandardOpenOption.CREATE_NEW);
        for (String model : new String[]{"Baize", "Baize2"}) {
            JSONObject definition = ModelProfiles.model(model).put("version", DefaultProfiles.execution(model).getJSONObject("model").getString("version"));
            Files.write(execution.resolve(model + ".json"), RuntimeSupport.bytes(definition), StandardOpenOption.CREATE_NEW);
        }
        Files.write(execution.resolve("execution.json"), RuntimeSupport.bytes(DefaultProfiles.execution("Baize")), StandardOpenOption.CREATE_NEW);
        Files.write(tenant.resolve("default.json"), RuntimeSupport.bytes(DefaultProfiles.tenantPolicy()), StandardOpenOption.CREATE_NEW);
        System.out.println("Exported server execution configuration and independent tenant business policy defaults");
    }
}
