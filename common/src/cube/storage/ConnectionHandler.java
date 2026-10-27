/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.storage;

import java.sql.Connection;

/**
 * 连接回调。
 *
 * <p>由调用方实现，在持有底层连接期间执行自定义 JDBC 操作。
 * 回调抛出的异常会继续向上传递，连接仍会被归还。</p>
 */
public interface ConnectionHandler {

    /**
     * 处理一个已借出的连接。
     *
     * @param connection 可用连接，回调返回后即被归还，不应缓存引用。
     */
    void handle(Connection connection);
}
