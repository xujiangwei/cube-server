/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.test;

import cell.carpet.CellBoot;

/**
 * 本地启动入口。
 *
 * <p>以 tag {@code dispatcher} 启动 Cell ，用于在 IDE 内直接调起调度机，等同于执行启动脚本。</p>
 */
public class DispatcherStart {

    /**
     * 启动调度机。
     *
     * @param args 未使用。
     */
    public static void main(String[] args) {
        // 使用 Boot 启动 Cell 线程
        CellBoot boot = new CellBoot();
        boot.setTag("dispatcher");
        boot.start();

        boot.join();

        System.out.println("Boot exit");
    }
}
