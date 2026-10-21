/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.api.Nucleus;
import cell.carpet.CellListener;
import cube.core.Kernel;

/**
 * 仅承载 Harness 的专用容器监听器：按 Cell 生命周期创建、启动、销毁独立 Kernel。
 * 合部署场景不使用此类，而是复用宿主现有 ServiceCarpet，由宿主 Kernel 统一管理模块启停。
 */
public final class HarnessCarpet implements CellListener {
    /**
     * 本容器专属 Kernel，仅安装 HarnessService，不加载 Console、AIGCService 或旧业务模块。
     */
    private Kernel kernel;

    /**
     * Cell 预初始化：创建专用 Kernel 并挂到 Nucleus 参数上，供 install 阶段取用。
     *
     * @param nucleus 本 Cell 的 Nucleus，承载容器级共享参数
     */
    @Override
    public void cellPreinitialize(Nucleus nucleus) {
        kernel = new Kernel(nucleus);
        nucleus.setParameter("kernel", kernel);
    }

    /**
     * Cell 初始化完成：启动 Kernel，开始接受模块安装与业务请求。
     *
     * @param nucleus 本 Cell 的 Nucleus
     */
    @Override
    public void cellInitialized(Nucleus nucleus) {
        kernel.startup();
    }

    /**
     * Cell 预销毁：先 dispose Kernel，有序停止容器内模块活动。
     *
     * @param nucleus 本 Cell 的 Nucleus
     */
    @Override
    public void cellPredestroy(Nucleus nucleus) {
        kernel.dispose();
    }

    /**
     * Cell 销毁完成：关闭 Kernel，释放容器内全部资源。
     *
     * @param nucleus 本 Cell 的 Nucleus
     */
    @Override
    public void cellDestroyed(Nucleus nucleus) {
        kernel.shutdown();
    }
}
