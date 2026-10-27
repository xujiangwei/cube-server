/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.core;

/**
 * 缓存事务上下文。
 */
public abstract class TransactionContext {

    private CacheKey key;

    public TransactionContext(CacheKey key) {
        this.key = key;
    }

    /**
     * 获取缓存键。
     *
     * @return 缓存键。
     */
    public CacheKey getKey() {
        return this.key;
    }

    /**
     * 读取缓存值。
     *
     * @return 缓存值；不存在返回 {@code null} 。
     */
    public abstract CacheValue get();

    /**
     * 写入缓存值。
     *
     * @param value 缓存值。
     */
    public abstract void put(CacheValue value);

    /**
     * 移除缓存值。
     */
    public abstract void remove();
}
