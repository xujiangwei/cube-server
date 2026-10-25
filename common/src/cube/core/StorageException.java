/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

/**
 * 存储访问异常。
 *
 * <p>用于 {@link Storage#executeQueryOrThrow(String, StorageField[], Conditional[])} 等
 * "显式失败"入口。常规入口（{@code executeQuery}）在失败时返回 {@code null} 并记录日志，
 * 不抛出本异常——两种语义并存是刻意的：存量调用点按"拿到列表"编写，改为抛出会扩大冲击面。</p>
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
