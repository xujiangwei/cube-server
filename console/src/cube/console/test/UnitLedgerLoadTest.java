/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.test;

import cube.console.mgmt.StatisticDataManager;
import cube.console.mgmt.UnitOverviewOutcome;
import cube.console.storage.DataStorage;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 单元台账回源的降载回归。
 *
 * <p>覆盖两件事：</p>
 * <ul>
 *     <li>30 天回溯窗口的活跃情况由数据库聚合得出，结果与逐条归集一致（口径不变）；</li>
 *     <li>单元数超出上限时接口拒绝而不是静默截断，避免台账少单元却无人察觉。</li>
 * </ul>
 */
public final class UnitLedgerLoadTest {

    private static final String DOMAIN = "units.example";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        trailAggregation();
        trailAggregationMatchesEventScan();
        overflowIsRejectedNotTruncated();
        System.out.println("UnitLedgerLoadTest: " + assertions + " assertions passed");
    }

    /**
     * 回溯聚合：每个有活动的单元一行，给出最近活动时间与活跃天数。
     */
    private static void trailAggregation() throws Exception {
        FixtureStorage storage = new FixtureStorage();
        long now = System.currentTimeMillis();
        long begin = now - 30L * 24 * 60 * 60 * 1000;
        long end = now + 1000L;

        // 单元 A：两个不同的自然日各一次活动；单元 B：只在一个自然日有活动。
        // 时间锚点必须落在过去：今天正午在正午之前运行时是未来时刻，会被窗口
        // 右端（now + 1s）滤掉，测试结果随运行时刻变化。
        long day1 = daysAgoNoon(2);
        long day2 = daysAgoNoon(1);
        storage.events.add(event(100001L, "SignIn", day1));
        storage.events.add(event(100001L, "SignOut", day1 + 3600000L));
        storage.events.add(event(100001L, "SignIn", day2));
        storage.events.add(event(100002L, "SignIn", day2));

        List<DataStorage.UnitTrail> trails = storage.queryUnitTrail(DOMAIN, begin, end, zoneOffset());
        check(trails.size() == 2, "one row per active unit, got " + trails.size());

        DataStorage.UnitTrail a = find(trails, 100001L);
        DataStorage.UnitTrail b = find(trails, 100002L);
        check(a != null && b != null, "both active units present");
        check(a.lastActiveTime == day2, "latest activity time is the max across the window");
        check(a.activeDays == 2, "two distinct local days counted, got " + a.activeDays);
        check(b.activeDays == 1, "single local day counted, got " + b.activeDays);
    }

    /**
     * 下推聚合与「逐条拉回再归集」结果一致 —— 这是口径不变的直接证据。
     */
    private static void trailAggregationMatchesEventScan() throws Exception {
        FixtureStorage storage = new FixtureStorage();
        long now = System.currentTimeMillis();
        long begin = now - 30L * 24 * 60 * 60 * 1000;
        long end = now + 1000L;

        // 时间锚点必须落在过去：今天正午在正午之前运行时是未来时刻，会被窗口
        // 右端（now + 1s）滤掉，测试结果随运行时刻变化。
        long day1 = daysAgoNoon(2);
        long day2 = daysAgoNoon(1);
        storage.events.add(event(100001L, "SignIn", day1 + 1000L));
        storage.events.add(event(100001L, "SignOut", day1 + 2000L));
        storage.events.add(event(100001L, "SignIn", day2 + 3000L));
        storage.events.add(event(100003L, "SignIn", day1 + 4000L));
        storage.events.add(event(100003L, "SignIn", day1 + 5000L));

        Map<Long, long[]> pushed = new HashMap<>();
        for (DataStorage.UnitTrail trail : storage.queryUnitTrail(DOMAIN, begin, end, zoneOffset())) {
            pushed.put(trail.contactId, new long[]{trail.lastActiveTime, trail.activeDays});
        }

        // 旧口径：MAX(time) + 本地日历 DAY_OF_YEAR 去重
        Map<Long, Long> lastActive = new HashMap<>();
        Map<Long, Set<Integer>> days = new HashMap<>();
        for (DataStorage.ContactEvent event : storage.events) {
            if (event.time < begin || event.time >= end) {
                continue;
            }
            Long last = lastActive.get(event.contactId);
            if (last == null || event.time > last) {
                lastActive.put(event.contactId, event.time);
            }
            Set<Integer> set = days.get(event.contactId);
            if (set == null) {
                set = new HashSet<>();
                days.put(event.contactId, set);
            }
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(event.time);
            set.add(cal.get(Calendar.DAY_OF_YEAR));
        }

        check(pushed.size() == lastActive.size(), "same unit set on both paths");
        for (Map.Entry<Long, long[]> entry : pushed.entrySet()) {
            long id = entry.getKey();
            check(entry.getValue()[0] == lastActive.get(id), "same latest activity for unit " + id);
            check(entry.getValue()[1] == days.get(id).size(), "same active days for unit " + id);
        }
    }

    /**
     * 单元数超限：拒绝而不是静默截断。
     */
    private static void overflowIsRejectedNotTruncated() throws Exception {
        FixtureStorage storage = new FixtureStorage();
        StatisticDataManager manager = new StatisticDataManager();
        inject(manager, "storage", storage);
        inject(manager, "maxUnitContacts", 2);

        // 3 个单元 > 上限 2
        storage.contacts.add(contact(100001L));
        storage.contacts.add(contact(100002L));
        storage.contacts.add(contact(100003L));

        UnitOverviewOutcome outcome = manager.queryUnitOverviewOutcome(DOMAIN, yesterday(),
                Collections.<Long, List<JSONObject>>emptyMap(), Collections.<Long>emptySet());
        check(outcome.tooLarge, "overflow is reported as too large");
        check(outcome.data == null, "no partial ledger is returned");
        check("NONE".equals(outcome.cacheState()), "overflow carries no cache state");
    }

    private static DataStorage.UnitTrail find(List<DataStorage.UnitTrail> trails, long contactId) {
        for (DataStorage.UnitTrail trail : trails) {
            if (trail.contactId == contactId) {
                return trail;
            }
        }
        return null;
    }

    private static JSONObject contact(long id) {
        return new JSONObject().put("id", id).put("name", "worker-" + id)
                .put("device", "GPU").put("platform", "Linux");
    }

    private static DataStorage.ContactEvent event(long id, String name, long time) throws Exception {
        java.lang.reflect.Constructor<DataStorage.ContactEvent> constructor =
                DataStorage.ContactEvent.class.getDeclaredConstructor(long.class, String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, name, time);
    }

    private static long zoneOffset() {
        return java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis());
    }

    /**
     * 若干天前的正午时刻。
     *
     * @param days 天数。
     * @return 返回时刻戳。
     */
    private static long daysAgoNoon(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -days);
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

            private static Calendar yesterday() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 可控的假存储：单元列表与事件均可注入。
     */
    private static final class FixtureStorage extends DataStorage {
        final List<ContactEvent> events = new ArrayList<>();
        final List<JSONObject> contacts = new ArrayList<>();

        FixtureStorage() throws Exception {
            super(new Properties());
        }

        @Override
        public List<JSONObject> queryUnitContacts(String domain, int maxContacts) {
            List<JSONObject> result = new ArrayList<>(contacts);
            if (maxContacts > 0 && result.size() > maxContacts) {
                // 与真实实现一致：多取一行用于判断超限
                result = new ArrayList<>(result.subList(0, maxContacts + 1));
            }
            return result;
        }

        @Override
        public List<DataStorage.UnitTrail> queryUnitTrail(String domain, long from, long to,
                                                          long zoneOffsetMillis) {
            Map<Long, Long> lastActive = new HashMap<>();
            Map<Long, Set<Integer>> days = new HashMap<>();
            for (ContactEvent event : events) {
                if (event.time < from || event.time >= to) {
                    continue;
                }
                Long last = lastActive.get(event.contactId);
                if (last == null || event.time > last) {
                    lastActive.put(event.contactId, event.time);
                }
                Set<Integer> set = days.get(event.contactId);
                if (set == null) {
                    set = new HashSet<>();
                    days.put(event.contactId, set);
                }
                set.add((int) ((event.time + zoneOffsetMillis) / 86400000L));
            }
            List<DataStorage.UnitTrail> result = new ArrayList<>();
            for (Map.Entry<Long, Long> entry : lastActive.entrySet()) {
                result.add(new DataStorage.UnitTrail(entry.getKey(), entry.getValue(),
                        days.get(entry.getKey()).size()));
            }
            return result;
        }
    }
}
