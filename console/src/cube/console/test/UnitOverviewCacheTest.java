/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.test;

import cube.console.Console;
import cube.console.container.handler.StatisticDataHandler;
import cube.console.mgmt.StatisticDataManager;
import cube.console.mgmt.UnitAccessGuard;
import cube.console.mgmt.UnitLedger;
import cube.console.mgmt.UnitOverviewCache;
import cube.console.mgmt.UnitOverviewOutcome;
import cube.console.storage.DataStorage;
import cube.report.UnitReport;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单元概览接口的缓存、限流与降级回归。
 *
 * <p>覆盖四件事：</p>
 * <ul>
 *     <li>缓存只保存数据库派生部分，在线状态与能力仍按请求实时叠加；</li>
 *     <li>同一键的并发未命中只回源一次（单飞），其余请求复用同一结果；</li>
 *     <li>限流先于鉴权生效，匿名请求也会被挡住；</li>
 *     <li>回源被拒绝或失败时返回过期快照，不让页面直接报错。</li>
 * </ul>
 */
public final class UnitOverviewCacheTest {

    private static final String DOMAIN = "units.example";
    private static final long HOUR = 60L * 60 * 1000;

    /** 昨日正午（本地时区）的时刻戳，作为事件时间的固定锚点。 */
    private static final long YESTERDAY_NOON = yesterdayNoon();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        ledgerAndOverlay();
        cacheHitDoesNotFreezeOnlineState();
        singleFlightMergesConcurrentMisses();
        staleSnapshotServedWhenLoadFails();
        rejectedWhenOriginQuotaExhausted();
        rateLimitBlocksBeforeAuth();
        httpRejectsAnonymous();
        System.out.println("UnitOverviewCacheTest: " + assertions + " assertions passed");
    }

    /**
     * 台账快照与实时状态分离：快照不含状态与能力。
     */
    private static void ledgerAndOverlay() throws Exception {
        FixtureStorage storage = new FixtureStorage();
        StatisticDataManager manager = new StatisticDataManager();
        inject(manager, "storage", storage);

        Calendar day = yesterday();
        UnitLedger ledger = manager.queryUnitLedger(DOMAIN, day);
        check(ledger != null, "ledger loaded from statistic storage");
        check(ledger.total == 3, "ledger carries every unit in the domain");
        check(ledger.activeCount == 3, "ledger counts units with daily activity");
        check(ledger.timelineUnits.length == 24, "ledger carries one slice per hour");
        check(ledger.peakSlice >= 0, "ledger records the peak hour");

        Set<Long> online = new HashSet<>(Collections.singletonList(100001L));
        JSONObject data = manager.composeUnitOverview(DOMAIN, ledger, Collections.emptyMap(), online);
        check(data.getInt("onlineCount") == 1, "online count reflects the live report");
        check(data.getInt("offlineCount") == 2, "offline count reflects the ledger history");
        check(data.getInt("inactiveCount") == 0, "units with history are not inactive");
        check(data.getJSONArray("units").getJSONObject(0).getLong("id") == 100001L,
                "online unit is listed first");

        JSONObject fresh = manager.composeUnitOverview(DOMAIN, ledger, Collections.emptyMap(),
                new HashSet<Long>());
        check(fresh.getInt("onlineCount") == 0, "same ledger renders a different live state");
        check(fresh.getJSONArray("units").getJSONObject(0).getLong("id") == 100001L,
                "without live report the order falls back to the ledger order");
    }

    /**
     * 缓存命中不会冻结在线状态：同一份台账快照应渲染出不同的在线态。
     */
    private static void cacheHitDoesNotFreezeOnlineState() throws Exception {
        FixtureStorage storage = new FixtureStorage();
        StatisticDataManager manager = new StatisticDataManager();
        inject(manager, "storage", storage);
        inject(manager, "unitCache", new UnitOverviewCache(600000L, 600000L, 32, 50000, 4, 300, 10000L));

        Calendar day = yesterday();
        UnitOverviewOutcome first = manager.queryUnitOverviewOutcome(DOMAIN, day,
                Collections.emptyMap(), new HashSet<Long>(Collections.singletonList(100001L)));
        check(first.data != null, "first query returns data");
        check(first.data.getInt("onlineCount") == 1, "first query sees the online unit");

        // 台账没变，但在线实体变了：第二次必须反映新的在线态
        UnitOverviewOutcome second = manager.queryUnitOverviewOutcome(DOMAIN, day,
                Collections.emptyMap(), new HashSet<Long>(Collections.singletonList(100003L)));
        check(second.data != null, "second query returns data");
        check(second.data.getInt("onlineCount") == 1, "cached query still reports the online count");
        check(second.data.getJSONArray("units").getJSONObject(0).getLong("id") == 100003L,
                "cached query promotes the newly online unit to the front");

        JSONObject meta = manager.getUnitCache().metrics();
        check(meta.getLong("hits") >= 1, "cache hit is counted");
        check(meta.getLong("loads") == 1, "the ledger is loaded from the database only once");
    }

    /**
     * 同一键的并发未命中只回源一次。
     */
    private static void singleFlightMergesConcurrentMisses() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        UnitOverviewCache cache = new UnitOverviewCache(600000L, 600000L, 32, 50000, 4, 300, 10000L);

        UnitOverviewCache.Loader loader = new UnitOverviewCache.Loader() {
            @Override
            public UnitLedger load() {
                loads.incrementAndGet();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ledger();
            }
        };

        final UnitOverviewCache cacheRef = cache;
        final int threads = 6;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Boolean> merged = Collections.synchronizedList(new ArrayList<Boolean>());

        for (int i = 0; i < threads; ++i) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        UnitOverviewCache.Result result = cacheRef.get("same-key", loader);
                        merged.add(result.merged);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }

        start.countDown();
        Thread.sleep(200L);
        release.countDown();
        check(done.await(10, TimeUnit.SECONDS), "all concurrent requests finished");
        check(loads.get() == 1, "concurrent misses trigger a single database load, got " + loads.get());

        int mergedCount = 0;
        for (Boolean flag : merged) {
            if (flag) {
                ++mergedCount;
            }
        }
        check(mergedCount == threads - 1, "all but the leader reuse the in-flight result, got " + mergedCount);
    }

    /**
     * 回源失败时返回过期快照，不让页面直接报错。
     */
    private static void staleSnapshotServedWhenLoadFails() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        UnitOverviewCache cache = new UnitOverviewCache(1L, 600000L, 32, 50000, 4, 300, 10000L);

        UnitOverviewCache.Loader loader = new UnitOverviewCache.Loader() {
            @Override
            public UnitLedger load() {
                if (loads.incrementAndGet() == 1) {
                    return ledger();
                }
                return null;
            }
        };

        UnitOverviewCache.Result first = cache.get("key", loader);
        check(first.ledger != null && !first.stale, "first load returns a fresh snapshot");

        Thread.sleep(20L);
        UnitOverviewCache.Result second = cache.get("key", loader);
        check(second.ledger != null, "failed reload still serves the previous snapshot");
        check(second.stale, "the served snapshot is marked stale");
        check(!second.rejected, "a stale snapshot is not a rejection");

        UnitOverviewCache.Result third = cache.get("key", loader);
        check(third.ledger != null && third.stale, "stale snapshot keeps being served");
    }

    /**
     * 回源配额耗尽且没有旧快照时拒绝，而不是把数据库打穿。
     */
    private static void rejectedWhenOriginQuotaExhausted() {
        UnitOverviewCache cache = new UnitOverviewCache(600000L, 0L, 32, 50000, 4, 1, 10000L);

        UnitOverviewCache.Loader loader = new UnitOverviewCache.Loader() {
            @Override
            public UnitLedger load() {
                return ledger();
            }
        };

        UnitOverviewCache.Result first = cache.get("key", loader);
        check(first.ledger != null && !first.rejected, "the first request is served");

        UnitOverviewCache.Result second = cache.get("other", loader);
        check(second.ledger == null, "no data is returned once the origin quota is exhausted");
        check(second.rejected, "the request is reported as rejected");
    }

    /**
     * 限流先于鉴权：匿名请求也会被挡住。
     */
    private static void rateLimitBlocksBeforeAuth() {
        UnitAccessGuard guard = new UnitAccessGuard(true, 2, 2, 100, 100);
        check(guard.allow("token", "1.2.3.4"), "the first request is allowed");
        check(guard.allow("token", "1.2.3.4"), "the second request is allowed");
        check(!guard.allow("token", "1.2.3.4"), "the third request of the same user is rejected");
        check(guard.allow("other-token", "1.2.3.4"), "another user is not affected by the user quota");
        check(guard.retryAfterSeconds("token", "1.2.3.4") >= 1L, "the client is told how long to wait");

        // IP 维独立计数：换令牌不能绕过 IP 配额
        UnitAccessGuard ipGuard = new UnitAccessGuard(true, 100, 100, 2, 2);
        check(ipGuard.allow("token-a", "5.6.7.8"), "first request from the address is allowed");
        check(ipGuard.allow("token-b", "5.6.7.8"), "second request from the address is allowed");
        check(!ipGuard.allow("token-c", "5.6.7.8"), "rotating the token does not bypass the address quota");
    }

    /**
     * HTTP 层：匿名请求被拒绝，且不产生任何数据库访问。
     */
    private static void httpRejectsAnonymous() throws Exception {
        try (Harness harness = new Harness()) {
            String anonymous = harness.request(null);
            check(anonymous.startsWith("HTTP/1.1 401"), "anonymous request is rejected");
            check(harness.storage.calls == 0, "the rejected request never touches the database");

            String authenticated = harness.request("valid");
            check(authenticated.startsWith("HTTP/1.1 200"), "authenticated request is served");
            check(harness.storage.calls > 0, "the authenticated request reads the ledger");
        }
    }

    /**
     * 构造一份最小的台账快照。
     */
    private static UnitLedger ledger() {
        List<UnitLedger.Unit> units = new ArrayList<>();
        units.add(new UnitLedger.Unit(100001L, "worker-a", "GPU", "Linux", 2, 3600000L,
                1000L, 900L, 1));
        units.add(new UnitLedger.Unit(100002L, "worker-b", "GPU", "Linux", 2, 3600000L,
                900L, 800L, 1));
        units.add(new UnitLedger.Unit(100003L, "worker-c", "GPU", "Linux", 2, 3600000L,
                800L, 700L, 1));
        units.sort(UnitOverviewCache.LEDGER_ORDER);

        int[] timeline = new int[24];
        timeline[9] = 3;
        return new UnitLedger(Collections.unmodifiableList(units), 3, 3, 10800000L, timeline, 9,
                86400000L, 86400000L + 24L * 60 * 60 * 1000L);
    }

    /**
     * 昨日正午的时刻戳。
     *
     * @return 昨日正午的时刻戳。
     */
    private static long yesterdayNoon() {
        Calendar calendar = yesterday();
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        return calendar.getTimeInMillis();
    }

    /**
     * 昨日零点。
     */
    private static Calendar yesterday() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    /**
     * 反射写入私有字段。
     */
    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 构造一条联系人活动事件。
     *
     * @param id    联系人 ID。
     * @param name  事件名。
     * @param time  事件时间。
     * @return 活动事件。
     */
    private static DataStorage.ContactEvent event(long id, String name, long time) throws Exception {
        Constructor<DataStorage.ContactEvent> constructor = DataStorage.ContactEvent.class
                .getDeclaredConstructor(long.class, String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, name, time);
    }

    private static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 记录数据库访问次数的假存储。
     */
    private static final class FixtureStorage extends DataStorage {
        final List<ContactEvent> events = new ArrayList<>();
        int calls;

        FixtureStorage() throws Exception {
            super(new Properties());
            // 固定落在昨日正午：既在统计日窗口内，也在 30 天回溯窗口内。
            // 时间戳必须确定，否则「最近活动时间」相同与否取决于构造时的毫秒边界，
            // 排序断言会随机翻转。
            for (long id = 100001L; id <= 100003L; ++id) {
                events.add(event(id, "SignIn", YESTERDAY_NOON));
            }
        }

        @Override
        public List<JSONObject> queryUnitContacts(String domain, int maxContacts) {
            ++calls;
            List<JSONObject> contacts = new ArrayList<>();
            for (long id = 100001L; id <= 100003L; ++id) {
                contacts.add(new JSONObject().put("id", id).put("name", "worker-" + id)
                        .put("device", "GPU").put("platform", "Linux"));
            }
            return contacts;
        }

        @Override
        public List<ContactEvent> queryContactEvents(String domain, long from, long to, boolean unitOnly) {
            ++calls;
            List<ContactEvent> result = new ArrayList<>();
            for (ContactEvent event : events) {
                if (event.time >= from && event.time < to) {
                    result.add(event);
                }
            }
            return result;
        }
        /** 与真实数据库的聚合等价：按单元给出最近活动时间与活跃天数。 */
        @Override
        public List<UnitTrail> queryUnitTrail(String domain, long from, long to, long zoneOffsetMillis) {
            Map<Long, Long> lastActive = new HashMap<>();
            Map<Long, Set<Integer>> days = new HashMap<>();
            for (ContactEvent event : events) {
                if (event.time < from || event.time >= to) continue;
                Long last = lastActive.get(event.contactId);
                if (last == null || event.time > last) lastActive.put(event.contactId, event.time);
                Set<Integer> set = days.get(event.contactId);
                if (set == null) { set = new HashSet<>(); days.put(event.contactId, set); }
                set.add((int) ((event.time + zoneOffsetMillis) / 86400000L));
            }
            List<UnitTrail> result = new ArrayList<>();
            for (Map.Entry<Long, Long> entry : lastActive.entrySet()) {
                result.add(new UnitTrail(entry.getKey(), entry.getValue(),
                        days.get(entry.getKey()).size()));
            }
            return result;
        }
    }

    /**
     * HTTP 回归环境。
     */
    private static final class Harness implements AutoCloseable {
        final FixtureStorage storage = new FixtureStorage();
        final Console console = new Console();
        final Server server = new Server();
        final LocalConnector connector = new LocalConnector(server);

        Harness() throws Exception {
            StatisticDataManager manager = new StatisticDataManager();
            inject(manager, "storage", storage);
            inject(manager, "unitCache", new UnitOverviewCache(600000L, 600000L, 32, 50000, 4, 300, 10000L));
            inject(manager, "unitGuard", new UnitAccessGuard(true, 60, 60, 600, 600));
            inject(console, "statisticDataManager", manager);
            inject(console, "userManager", new cube.console.mgmt.UserManager() {
                @Override
                public boolean checkToken(String token) {
                    return "valid".equals(token);
                }
            });

            server.addConnector(connector);
            HandlerList handlers = new HandlerList();
            handlers.addHandler(new StatisticDataHandler(console));
            server.setHandler(handlers);
            server.start();
        }

        String request(String token) throws Exception {
            String response = connector.getResponse("GET /statistic/units?domain=" + DOMAIN
                    + " HTTP/1.1\r\nHost: localhost\r\n"
                    + (token == null ? "" : "Cookie: CubeConsoleToken=" + token + "\r\n")
                    + "Connection: close\r\n\r\n");
            return response;
        }

        @Override
        public void close() throws Exception {
            server.stop();
            server.join();
        }
    }
}
