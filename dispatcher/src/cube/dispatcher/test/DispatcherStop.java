/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.test;

import cell.carpet.CellBoot;

/**
 * 本地停止入口。
 *
 * <p>以 tag {@code dispatcher} 通知 Cell 停止，用于在 IDE 内验证优雅停机链路。</p>
 */
public class DispatcherStop {

    /**
     * 停止调度机。
     *
     * @param args 未使用。
     */
    public static void main(String[] args) {
        CellBoot boot = new CellBoot();
        boot.setTag("dispatcher");
        boot.stop();
    }
}
