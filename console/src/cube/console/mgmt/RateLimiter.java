/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.mgmt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器。
 *
 * <p>按 key（用户令牌 / 客户端地址）独立计数：桶内令牌按固定速率补充，取到令牌即放行，
 * 桶空则拒绝。用于把「谁能打这个接口、能打多快」显式化 —— 统计类接口一旦被匿名高频调用，
 * 单次回源的全表扫描会直接压垮统计库。</p>
 *
 * <p>限流器只做配额，不做鉴权：没有令牌的人同样会被计数，只是被拒绝得更早。</p>
 */
public class RateLimiter {

    /**
     * 桶的最大令牌数，即允许的突发量。
     */
    private final int capacity;

    /**
     * 补充一个令牌所需的毫秒数。
     */
    private final long refillIntervalMillis;

    /**
     * 桶数量超过该阈值时触发一次闲置清理。
     */
    private final int cleanupThreshold = 1024;

    /**
     * 桶闲置超过该时长后被清理（毫秒）。
     */
    private final long idleMillis = 600000L;

    /**
     * 各 key 的令牌桶。
     */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 被拒绝的次数。
     */
    private final AtomicLong rejected = new AtomicLong();

    /**
     * 构造限流器。
     *
     * @param permitsPerMinute 每分钟允许的次数。
     * @param burst            允许的突发量，即桶容量。
     */
    public RateLimiter(int permitsPerMinute, int burst) {
        int permits = Math.max(1, permitsPerMinute);
        this.capacity = Math.max(1, burst);
        this.refillIntervalMillis = Math.max(1L, 60000L / permits);
    }

    /**
     * 尝试取一个令牌。
     *
     * @param key 限流维度，取值为空时不限制。
     * @return 放行返回 {@code true} 。
     */
    public boolean tryAcquire(String key) {
        if (null == key || key.isEmpty()) {
            return true;
        }

        long now = System.currentTimeMillis();
        Bucket bucket = this.buckets.computeIfAbsent(key, k -> new Bucket(this.capacity));

        synchronized (bucket) {
            bucket.refill(now, this.refillIntervalMillis, this.capacity);
            bucket.lastAccess = now;
            if (bucket.tokens >= 1.0d) {
                bucket.tokens -= 1.0d;
                return true;
            }
        }

        this.rejected.incrementAndGet();
        this.cleanup(now);
        return false;
    }

    /**
     * 计算还需等待多少秒可以再次放行。
     *
     * @param key 限流维度。
     * @return 等待秒数，最小为 1。
     */
    public long retryAfterSeconds(String key) {
        if (null == key || key.isEmpty()) {
            return 1L;
        }

        Bucket bucket = this.buckets.get(key);
        if (null == bucket) {
            return 1L;
        }

        synchronized (bucket) {
            if (bucket.tokens >= 1.0d) {
                return 1L;
            }
            double need = (1.0d - bucket.tokens) * this.refillIntervalMillis;
            return Math.max(1L, (long) Math.ceil(need / 1000.0d));
        }
    }

    /**
     * 清理长期闲置的桶，避免 key 维度无界增长。
     *
     * @param now 当前时刻。
     */
    private void cleanup(long now) {
        if (this.buckets.size() <= this.cleanupThreshold) {
            return;
        }

        for (Map.Entry<String, Bucket> entry : this.buckets.entrySet()) {
            if (now - entry.getValue().lastAccess > this.idleMillis) {
                this.buckets.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 被拒绝的累计次数。
     *
     * @return 拒绝次数。
     */
    public long getRejectedCount() {
        return this.rejected.get();
    }

    /**
     * 当前持有的桶数量。
     *
     * @return 桶数量。
     */
    public int getBucketCount() {
        return this.buckets.size();
    }

    /**
     * 单个 key 的令牌桶。
     */
    private static final class Bucket {

        /**
         * 剩余令牌数。
         */
        private double tokens;

        /**
         * 上次补充令牌的时刻。
         */
        private long lastRefill;

        /**
         * 上次被访问的时刻。
         */
        private long lastAccess;

        Bucket(double tokens) {
            this.tokens = tokens;
            this.lastRefill = System.currentTimeMillis();
            this.lastAccess = this.lastRefill;
        }

        /**
         * 按经过的时间补充令牌。
         *
         * @param now      当前时刻。
         * @param interval 补充一个令牌所需的毫秒数。
         * @param max      桶容量。
         */
        void refill(long now, long interval, int max) {
            long elapsed = now - this.lastRefill;
            if (elapsed <= 0L) {
                return;
            }
            this.tokens = Math.min(max, this.tokens + ((double) elapsed / (double) interval));
            this.lastRefill = now;
        }
    }
}
