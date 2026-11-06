/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.mgmt;

import org.json.JSONObject;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单元台账的结果缓存。
 *
 * <p>缓存的是「由统计库算出、且不随节点上报变化」的那一部分（见 {@link UnitLedger}），
 * 在线状态与承载能力在响应时实时叠加，因此缓存命中不会让刚上报的单元停留在离线态。</p>
 *
 * <p>缓存同时承担两件事：</p>
 * <ul>
 *     <li><b>削峰</b>：同一键的并发未命中只放一个线程回源（单飞），其余请求复用同一结果；</li>
 *     <li><b>兜底</b>：回源被并发闸或全局回源配额拒绝时，返回仍然可用的旧快照（stale），
 *         让页面在数据库压力大时继续显示上一次的结果，而不是直接报错。</li>
 * </ul>
 */
public class UnitOverviewCache {

    /**
     * 回源加载器。
     */
    public interface Loader {

        /**
         * 从统计库加载台账快照。
         *
         * @return 台账快照；数据不可用时返回 {@code null} 。
         */
        UnitLedger load();
    }

    /**
     * 缓存查询结果。
     */
    public static final class Result {

        /**
         * 台账快照，可能为 {@code null} 。
         */
        public final UnitLedger ledger;

        /**
         * 是否为过期快照（回源被拒绝或失败时的兜底结果）。
         */
        public final boolean stale;

        /**
         * 是否被限流拒绝（并发闸或回源配额耗尽，且没有可用的旧快照）。
         */
        public final boolean rejected;

        /**
         * 回源是否失败（数据库不可用）。
         */
        public final boolean failed;

        /**
         * 是否复用了同一键上其它请求正在进行的回源结果。
         */
        public final boolean merged;

        Result(UnitLedger ledger, boolean stale, boolean rejected, boolean failed, boolean merged) {
            this.ledger = ledger;
            this.stale = stale;
            this.rejected = rejected;
            this.failed = failed;
            this.merged = merged;
        }
    }

    /**
     * 缓存条目。
     */
    private static final class Entry {

        /**
         * 台账快照。
         */
        private final UnitLedger ledger;

        /**
         * 写入时刻。
         */
        private final long timestamp;

        Entry(UnitLedger ledger, long timestamp) {
            this.ledger = ledger;
            this.timestamp = timestamp;
        }
    }

    /**
     * 缓存有效期，单位：毫秒；不大于 0 表示不缓存。
     */
    private final long ttlMillis;

    /**
     * 过期快照仍可作为兜底结果返回的时长，单位：毫秒。
     */
    private final long staleMillis;

    /**
     * 最多缓存的条目数。
     */
    private final int maxEntries;

    /**
     * 单个条目允许缓存的单元数上限，超过则不缓存。
     */
    private final int maxUnits;

    /**
     * 允许同时进行的回源数量。
     */
    private final Semaphore gate;

    /**
     * 回源等待其它请求完成的超时，单位：毫秒。
     */
    private final long mergeTimeoutMillis;

    /**
     * 全局回源配额（每分钟允许真正打库的次数）。
     */
    private final RateLimiter originLimiter;

    /**
     * 缓存条目。
     */
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 正在进行的回源，同一键只保留一个。
     */
    private final ConcurrentHashMap<String, CompletableFuture<UnitLedger>> inflight = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong staleServed = new AtomicLong();
    private final AtomicLong mergedServed = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong loadCount = new AtomicLong();
    private final AtomicLong loadFailures = new AtomicLong();
    private final AtomicLong loadMillis = new AtomicLong();

    /**
     * 构造缓存。
     *
     * @param ttlMillis          缓存有效期，单位：毫秒；不大于 0 表示不缓存。
     * @param staleMillis        过期快照的兜底时长，单位：毫秒。
     * @param maxEntries         最多缓存的条目数。
     * @param maxUnits           单条目最多缓存的单元数。
     * @param concurrency        允许同时进行的回源数量。
     * @param originPerMinute    全局回源配额，每分钟允许真正打库的次数。
     * @param mergeTimeoutMillis 等待其它请求回源完成的超时，单位：毫秒。
     */
    public UnitOverviewCache(long ttlMillis, long staleMillis, int maxEntries, int maxUnits,
                             int concurrency, int originPerMinute, long mergeTimeoutMillis) {
        this.ttlMillis = ttlMillis;
        this.staleMillis = staleMillis;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxUnits = Math.max(1, maxUnits);
        this.gate = new Semaphore(Math.max(1, concurrency));
        this.mergeTimeoutMillis = mergeTimeoutMillis;
        this.originLimiter = new RateLimiter(originPerMinute, Math.max(1, originPerMinute));
    }

    /**
     * 取缓存。
     *
     * @param key    缓存键。
     * @param loader 回源加载器。
     * @return 查询结果；被拒绝且无旧快照时 {@link Result#ledger} 为 {@code null} 。
     */
    public Result get(String key, Loader loader) {
        long now = System.currentTimeMillis();

        if (this.ttlMillis <= 0L) {
            // 关闭缓存：每次都回源，但仍受并发闸与回源配额约束
            return this.load(key, loader, null, now, false, false);
        }

        Entry cached = this.entries.get(key);
        if (null != cached && now - cached.timestamp < this.ttlMillis) {
            this.hits.incrementAndGet();
            return new Result(cached.ledger, false, false, false, false);
        }

        CompletableFuture<UnitLedger> mine = new CompletableFuture<>();
        CompletableFuture<UnitLedger> existing = this.inflight.putIfAbsent(key, mine);
        if (null != existing) {
            // 同一键已有回源在进行，复用它的结果，避免并发击穿
            this.mergedServed.incrementAndGet();
            try {
                UnitLedger ledger = existing.get(this.mergeTimeoutMillis, TimeUnit.MILLISECONDS);
                if (null == ledger) {
                    return this.fallback(cached, now, true, false);
                }
                this.hits.incrementAndGet();
                return new Result(ledger, false, false, false, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return this.fallback(cached, now, true, false);
            } catch (TimeoutException e) {
                return this.fallback(cached, now, true, false);
            } catch (ExecutionException e) {
                return this.fallback(cached, now, true, false);
            }
        }

        // 本次请求是回源的主导者：负责回源、写缓存，并唤醒等待同一键的其它请求
        Result result;
        try {
            result = this.load(key, loader, cached, now, false, true);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            this.inflight.remove(key, mine);
            throw e;
        }
        // 只有真正回源成功时才把结果共享出去；降级结果由各自的兜底路径决定
        boolean loaded = !result.stale && !result.rejected && !result.failed;
        mine.complete(loaded ? result.ledger : null);
        this.inflight.remove(key, mine);
        return result;
    }

    /**
     * 执行回源并写入缓存。
     *
     * @param key    缓存键。
     * @param loader 回源加载器。
     * @param cached 已有条目，可能为 {@code null} 。
     * @param now    当前时刻。
     * @param merged 是否为复用结果。
     * @param store  是否写入缓存。
     * @return 查询结果。
     */
    private Result load(String key, Loader loader, Entry cached, long now, boolean merged, boolean store) {
        if (!this.originLimiter.tryAcquire("origin")) {
            this.rejectedCount.incrementAndGet();
            return this.fallback(cached, now, merged, false);
        }

        boolean acquired = false;
        try {
            acquired = this.gate.tryAcquire();
            if (!acquired) {
                this.rejectedCount.incrementAndGet();
                return this.fallback(cached, now, merged, false);
            }

            long begin = System.currentTimeMillis();
            UnitLedger ledger = loader.load();
            this.loadMillis.addAndGet(System.currentTimeMillis() - begin);
            this.loadCount.incrementAndGet();

            if (null == ledger) {
                this.loadFailures.incrementAndGet();
                return this.fallback(cached, now, merged, true);
            }

            this.misses.incrementAndGet();
            if (store) {
                this.store(key, ledger, now);
            }
            return new Result(ledger, false, false, false, merged);
        } catch (RuntimeException e) {
            this.loadFailures.incrementAndGet();
            return this.fallback(cached, now, merged, true);
        } finally {
            if (acquired) {
                this.gate.release();
            }
        }
    }

    /**
     * 回源失败或被拒绝时的兜底。
     *
     * @param cached 已有条目，可能为 {@code null} 。
     * @param now    当前时刻。
     * @param merged 是否为复用结果。
     * @param failed 是否为回源失败。
     * @return 查询结果。
     */
    private Result fallback(Entry cached, long now, boolean merged, boolean failed) {
        if (null != cached && now - cached.timestamp < this.staleMillis) {
            this.staleServed.incrementAndGet();
            return new Result(cached.ledger, true, false, failed, merged);
        }
        return new Result(null, false, !failed, failed, merged);
    }

    /**
     * 写入缓存。
     *
     * @param key    缓存键。
     * @param ledger 台账快照。
     * @param now    当前时刻。
     */
    private void store(String key, UnitLedger ledger, long now) {
        if (ledger.units.size() > this.maxUnits) {
            // 条目过大时不缓存：宁可每次回源，也不要把堆撑爆
            this.entries.remove(key);
            return;
        }

        this.entries.put(key, new Entry(ledger, now));
        this.evict(now);
    }

    /**
     * 清理过期条目与超出上限的条目。
     *
     * @param now 当前时刻。
     */
    private void evict(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> item = iterator.next();
            if (now - item.getValue().timestamp >= this.staleMillis) {
                iterator.remove();
            }
        }

        while (this.entries.size() > this.maxEntries) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> item : this.entries.entrySet()) {
                if (item.getValue().timestamp < oldest) {
                    oldest = item.getValue().timestamp;
                    oldestKey = item.getKey();
                }
            }
            if (null == oldestKey) {
                break;
            }
            this.entries.remove(oldestKey);
        }
    }

    /**
     * 采集缓存指标。
     *
     * @return 指标快照。
     */
    public JSONObject metrics() {
        JSONObject json = new JSONObject();
        json.put("hits", this.hits.get());
        json.put("misses", this.misses.get());
        json.put("staleServed", this.staleServed.get());
        json.put("mergedServed", this.mergedServed.get());
        json.put("rejected", this.rejectedCount.get());
        json.put("loads", this.loadCount.get());
        json.put("loadFailures", this.loadFailures.get());
        json.put("loadMillis", this.loadMillis.get());
        json.put("entries", this.entries.size());
        json.put("originRejected", this.originLimiter.getRejectedCount());
        json.put("ttlMillis", this.ttlMillis);
        json.put("staleMillis", this.staleMillis);
        json.put("maxEntries", this.maxEntries);
        json.put("maxUnits", this.maxUnits);
        return json;
    }

    /**
     * 清空缓存。
     */
    public void clear() {
        this.entries.clear();
    }

    /**
     * 排序器：按「最近活动时间倒序、ID 升序」排列台账实体。
     *
     * <p>该顺序是最终展示顺序在「在线优先」之外的剩余部分：在线分组内、离线分组内都按此序，
     * 未激活分组内最近活动时间全为零，等价于 ID 升序。</p>
     */
    public static final Comparator<UnitLedger.Unit> LEDGER_ORDER = new Comparator<UnitLedger.Unit>() {
        @Override
        public int compare(UnitLedger.Unit a, UnitLedger.Unit b) {
            if (a.lastActiveTime != b.lastActiveTime) {
                return (a.lastActiveTime > b.lastActiveTime) ? -1 : 1;
            }
            return (a.id < b.id) ? -1 : ((a.id == b.id) ? 0 : 1);
        }
    };
}
