/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.cache;

import cell.adapter.extra.memory.LockFuture;
import cell.adapter.extra.memory.SharedMemory;
import cell.adapter.extra.memory.SharedMemoryConfig;
import cell.util.log.Logger;
import cube.core.*;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shared Memory 缓存。
 */
public class SharedMemoryCache extends AbstractCache {

    public final static String TYPE = "SMC";

    private String configFile;

    private volatile SharedMemory memory;

    /**
     * 构造函数。
     *
     * @param name 缓存器名称。
     */
    public SharedMemoryCache(String name) {
        super(name, TYPE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void start() {
        if (null != this.memory) {
            return;
        }

        SharedMemoryConfig config = new SharedMemoryConfig(this.configFile);
        this.memory = new SharedMemory(config);

        this.memory.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void stop() {
        if (null == this.memory) {
            return;
        }

        this.memory.stop();
        this.memory = null;
    }

    /**
     * 判断缓存是否已就绪。
     *
     * @return 已启动返回 {@code true} 。
     */
    public boolean isReady() {
        return null != this.memory;
    }

    /**
     * 未启动时明确失败。
     *
     * <p>原实现直接使用 {@code this.memory}，未 {@code start()} 或启动失败时表现为 NPE，
     * 堆栈指向缓存调用方，看不出根因。这里改为带缓存名的 {@link IllegalStateException}。</p>
     */
    private void ensureReady() {
        if (null == this.memory) {
            throw new IllegalStateException("SharedMemoryCache is not started: " + getName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(JSONObject config) {
        super.configure(config);

        try {
            this.configFile = config.getString("configFile");
        } catch (JSONException e) {
            Logger.e(SharedMemoryCache.class, "#configure - JSON error", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(CacheKey key, CacheValue value) {
        this.ensureReady();
        this.memory.applyPut(key.get(), value.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CacheValue get(CacheKey key) {
        this.ensureReady();

        JSONObject value = this.memory.applyGet(key.get());
        if (null == value) {
            return null;
        }
        return new CacheValue(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CacheValue get(CacheExpression expression) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(CacheKey key) {
        this.ensureReady();
        this.memory.applyRemove(key.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(CacheKey key, final CacheTransaction transaction) {
        this.ensureReady();

        Context context = new Context(key);

        LockFuture future = new LockFuture() {
            @Override
            public void acquired(String key) {
                transaction.perform(context);
            }
        };

        context.lockFuture = future;

        try {
            this.memory.apply(key.get(), future);
        } catch (Exception e) {
            Logger.e(this.getClass(), "#execute - Failed to acquire lock, key: " + key.get(), e);
        }
    }


    /**
     * 事务上下文实现。
     */
    protected class Context extends TransactionContext {

        protected LockFuture lockFuture;

        public Context(CacheKey key) {
            super(key);
        }

        @Override
        public CacheValue get() {
            JSONObject value = this.lockFuture.get();
            if (null != value) {
                return new CacheValue(value);
            }

            return null;
        }

        @Override
        public void put(CacheValue value) {
            this.lockFuture.put(value.get());
        }

        @Override
        public void remove() {
            this.lockFuture.remove();
        }
    }
}
