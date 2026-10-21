/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.carpet.Cell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 专用 Harness 容器的程序化入口：固定从进程工作目录读取 {@code cell.xml} 启动独立 Cell，
 * 并以调用方指定的实例私有停止文件作为停机信号，适合前台 supervisor/systemd 托管。
 * 该容器只加载 Harness 自身，不加载 Console、AIGCService 或旧业务模块；
 * 业务 REST 因缺少 Cube Auth 返回 AUTH_UNAVAILABLE。
 */
public final class HarnessCell {
    /**
     * 启动专用 Cell 并阻塞等待停止文件出现，随后优雅停机；同时注册 JVM 停机钩子。
     *
     * @param args 恰好两个参数：{@code <cell.xml>}（必须与工作目录下的 cell.xml 为同一文件）
     *             与 {@code <private-stop-file>}（本实例私有的停止文件）
     * @throws IllegalArgumentException 参数个数不为 2，或所选配置不是工作目录下的 cell.xml
     * @throws Exception                Cell 启动失败（IllegalStateException）或等待停止文件期间被中断
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("HarnessCell <cell.xml> <private-stop-file>");
        }
        // 该版本 Cell 的 start(boolean,String) 第二参数是日志文件名；程序化入口固定从工作目录读取 cell.xml。
        if (!Files.isSameFile(Paths.get(args[0]), Paths.get("cell.xml"))) {
            throw new IllegalArgumentException("Select config as cell.xml in a dedicated working directory");
        }
        Cell cell = new Cell();
        if (!cell.start(false, "harness.log")) {
            throw new IllegalStateException("Cell startup failed");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(cell::stop, "harness-cell-shutdown"));
        Path stop = Paths.get(args[1]);
        while (!Files.exists(stop)) {
            Thread.sleep(200);
        }
        cell.stop();
    }
}
