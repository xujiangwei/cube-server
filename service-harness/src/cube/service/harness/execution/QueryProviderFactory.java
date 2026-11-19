/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import java.util.Collection;

/**
 * 受信任的部署扩展，用于批量创建由目录支撑的提供方。
 *
 * <p>每个创建的提供方所有权都移交给宿主，由宿主校验并关闭。工厂只持有共享资源，
 * 关闭时不得关闭已返回的提供方。若创建过程在返回前失败，工厂须清理未发布的实例。</p>
 */
public interface QueryProviderFactory extends AutoCloseable {
    /**
     * 批量创建提供方，所有权随返回值移交给宿主。
     *
     * @return 新创建的提供方集合。
     * @throws Exception 创建失败或依赖不可用时抛出。
     */
    Collection<QueryProvider> create() throws Exception;

    /**
     * 释放工厂持有的共享资源，不得关闭已返回的提供方。
     */
    @Override
    void close();
}
