/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 离线导出可审核的 model/runtime/task 定义到新目录的 CLI 入口。
 *
 * <p>导出只生成文件；可放入统一租户模板目录用于首次初始化，也可通过 RuntimeAdmin 管理已有租户。
 * 目标目录必须不存在（{@link Files#createDirectory} 语义），已存在即失败，避免覆盖既有定义。
 */
public final class ExecutionAdmin {

    /**
     * 导出 Baize/Baize2 的 resources runtime、model 与 task profile 到新目录。
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
        JSONObject runtime = DefaultProfiles.runtime();
        Files.write(root.resolve("resources.json"), RuntimeSupport.bytes(runtime), StandardOpenOption.CREATE_NEW);
        for (String model : new String[]{"Baize", "Baize2"}) {
            Files.write(root.resolve(model + ".json"), RuntimeSupport.bytes(ModelProfiles.model(model)), StandardOpenOption.CREATE_NEW);
            JSONObject task = DefaultProfiles.task(model);
            Files.write(root.resolve("task-" + model + ".json"), RuntimeSupport.bytes(task), StandardOpenOption.CREATE_NEW);
        }
        System.out.println("Exported offline adapter profiles for Baize and Baize2; use as deployment templates or manage existing tenants with RuntimeAdmin");
    }
}
