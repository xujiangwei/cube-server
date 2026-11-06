/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.test;

import cube.console.mgmt.StatisticDataManager;
import cube.console.mgmt.UnitLedger;
import cube.console.storage.DataStorage;
import cube.util.UnitDayAggregator;
import cube.console.storage.DataStorage.UnitDailySummary;
import cube.util.UnitDayRecord;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 单元台账的「日汇总优先」路径验证。
 *
 * <p>覆盖四种情形：汇总可用、汇总缺失、口径版本不匹配、日界口径不匹配。
 * 前者必须给出与明细路径完全相同的结果，后三者必须回落到明细路径，
 * 保证「汇总不可用时结果依然正确」。</p>
 */
public class UnitDailySummaryTest {

    private static int assertions = 0;

    /**
     * 一小时的毫秒数。
     */
    private static final long HOUR = 60L * 60 * 1000L;

    public static void main(String[] args) throws Exception {
        summaryMatchesDetail();
        summaryMissingFallsBack();
        versionMismatchFallsBack();
        dayBoundaryMismatchFallsBack();
        timelineMatchesDetail();
        incompleteSummaryFallsBack();
        summaryMetricsAreCounted();

        System.out.println("UnitDailySummaryTest: " + assertions + " assertions passed");
    }

    /**
     * 汇总可用时，结果与明细路径逐字段相同。
     */
    private static void summaryMatchesDetail() throws Exception {
        Fixture fixture = new Fixture();
        fixture.writeSummary(true, true);

        UnitLedger ledger = fixture.ledger();
        check(3 == ledger.activeCount, "summary path: active count must match the detail path");
        check(3 == ledger.total, "summary path: total unit count is unchanged");
        check(fixture.expectedTotalDuration() == ledger.totalDurationMillis, "summary path: total duration must match the detail path");
        check(3 == ledger.units.size(), "summary path: every unit is listed");

        UnitLedger.Unit unit = findUnit(ledger, 100001L);
        check(null != unit, "summary path: unit 100001 is present");
        check(2 == unit.eventCount, "summary path: event count comes from the summary");
        check(fixture.expectedDuration(100001L) == unit.durationMillis, "summary path: duration comes from the summary");
        check(fixture.firstSignInTime == unit.firstActiveTime, "summary path: first active time comes from the summary");
    }

    /**
     * 汇总缺失时回落到明细路径。
     */
    private static void summaryMissingFallsBack() throws Exception {
        Fixture fixture = new Fixture();
        fixture.writeSummary(false, true);

        UnitLedger ledger = fixture.ledger();
        check(3 == ledger.activeCount, "fallback: active count comes from the detail path");
        check(fixture.expectedTotalDuration() == ledger.totalDurationMillis, "fallback: total duration comes from the detail path");

        UnitLedger.Unit unit = findUnit(ledger, 100001L);
        check(null != unit, "fallback: unit 100001 is present");
        check(2 == unit.eventCount, "fallback: event count comes from the detail path");
    }

    /**
     * 汇总口径版本不匹配时回落到明细路径。
     */
    private static void versionMismatchFallsBack() throws Exception {
        Fixture fixture = new Fixture();
        fixture.writeSummary(true, false);

        UnitLedger ledger = fixture.ledger();
        check(3 == ledger.activeCount, "version mismatch: active count comes from the detail path");
        check(fixture.expectedTotalDuration() == ledger.totalDurationMillis, "version mismatch: total duration comes from the detail path");

        UnitLedger.Unit unit = findUnit(ledger, 100001L);
        check(null != unit, "version mismatch: unit 100001 is present");
        check(2 == unit.eventCount, "version mismatch: event count comes from the detail path");
    }

    /**
     * 日界口径不匹配时回落到明细路径。
     */
    private static void dayBoundaryMismatchFallsBack() throws Exception {
        Fixture fixture = new Fixture();
        fixture.writeSummary(true, true);
        fixture.summaryDayBeginOffset = 3600000L;

        UnitLedger ledger = fixture.ledger();
        check(3 == ledger.activeCount, "day boundary mismatch: active count comes from the detail path");
        check(fixture.expectedTotalDuration() == ledger.totalDurationMillis, "day boundary mismatch: total duration comes from the detail path");
    }

    /**
     * timeline 在两条路径下相同。
     */
    private static void timelineMatchesDetail() throws Exception {
        Fixture withSummary = new Fixture();
        withSummary.writeSummary(true, true);
        UnitLedger summarized = withSummary.ledger();

        Fixture withoutSummary = new Fixture();
        withoutSummary.writeSummary(false, true);
        UnitLedger detailed = withoutSummary.ledger();

        check(summarized.timelineUnits.length == detailed.timelineUnits.length,
                "timeline: both paths give 24 buckets");
        for (int i = 0; i < 24; ++i) {
            check(summarized.timelineUnits[i] == detailed.timelineUnits[i],
                    "timeline: bucket " + i + " must match the detail path");
        }
        check(summarized.peakSlice == detailed.peakSlice, "timeline: peak slice must match the detail path");
    }

    /**
     * 数据行数与标记不符时必须回落到明细路径。
     *
     * <p>多节点交错采集时，别的节点的「先删后插」可能清掉部分数据行，
     * 此时汇总是不完整的，不能当作完整结果使用。</p>
     */
    private static void incompleteSummaryFallsBack() throws Exception {
        Fixture fixture = new Fixture();
        fixture.writeSummary(true, true);
        // 标记行声明的行数比实际多一行：模拟数据被部分清掉
        fixture.summaryUnitCount = fixture.summary.size() + 1;

        UnitLedger ledger = fixture.ledger();
        check(fixture.expectedTotalDuration() == ledger.totalDurationMillis,
                "incomplete summary: total duration must come from the detail path");
        check(fixture.expectedDuration(100001L)
                        == findUnit(ledger, 100001L).durationMillis,
                "incomplete summary: duration must come from the detail path");
    }

    /**
     * 汇总路径的命中与回落必须被计数，且能被指标接口读出。
     */
    private static void summaryMetricsAreCounted() throws Exception {
        // 命中一次
        Fixture hit = new Fixture();
        hit.writeSummary(true, true);
        StatisticDataManager manager = newManager(hit);
        manager.queryUnitLedger("units.example", today());

        JSONObject metrics = manager.getUnitSummaryMetrics();
        check(1L == metrics.getLong("hits"), "metrics: a summary hit must be counted");
        check(0L == metrics.getLong("fallbacks"), "metrics: no fallback on the summary path");

        // 制造一次回落：汇总不可用
        Fixture miss = new Fixture();
        miss.writeSummary(false, true);
        setStorage(manager, miss);
        manager.queryUnitLedger("units.example", today());

        JSONObject metrics2 = manager.getUnitSummaryMetrics();
        check(1L == metrics2.getLong("hits"), "metrics: hit count must be preserved");
        check(1L == metrics2.getLong("fallbacks"), "metrics: a fallback must be counted");
        check(1L == metrics2.getLong("fallbackNotCollected"),
                "metrics: a not-collected fallback must be counted as normal");
        check(0L == metrics2.getLong("fallbackAbnormal"),
                "metrics: a not-collected fallback must not count as abnormal");
    }

    /**
     * 构造一个注入了给定存储的统计管理器。
     *
     * @param storage 存储。
     * @return 返回管理器。
     * @throws Exception 反射注入失败时抛出。
     */
    private static StatisticDataManager newManager(DataStorage storage) throws Exception {
        StatisticDataManager manager = new StatisticDataManager();
        Field field = StatisticDataManager.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(manager, storage);
        return manager;
    }

    /**
     * 替换统计管理器的存储。
     *
     * @param manager 统计管理器。
     * @param storage 存储。
     * @throws Exception 反射注入失败时抛出。
     */
    private static void setStorage(StatisticDataManager manager, DataStorage storage) throws Exception {
        Field field = StatisticDataManager.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(manager, storage);
    }

    /**
     * 今日零点。
     *
     * @return 返回今日零点的日历。
     */
    private static Calendar today() {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day;
    }

    /**
     * 在台账中找出指定单元。
     *
     * @param ledger 台账快照。
     * @param id     单元 ID 。
     * @return 返回该单元；不存在时返回 {@code null} 。
     */
    private static UnitLedger.Unit findUnit(UnitLedger ledger, long id) {
        for (UnitLedger.Unit unit : ledger.units) {
            if (unit.id == id) {
                return unit;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 构造一条活动事件。
     *
     * @param id   单元 ID 。
     * @param name 事件名。
     * @param time 事件时间戳。
     * @return 返回事件。
     * @throws Exception 反射构造失败时抛出。
     */
    private static DataStorage.ContactEvent event(long id, String name, long time) throws Exception {
        Constructor<DataStorage.ContactEvent> constructor = DataStorage.ContactEvent.class
                .getDeclaredConstructor(long.class, String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, name, time);
    }

    /**
     * 夹具：同时提供明细数据与日汇总数据，并记录两条路径各自被调用的次数。
     */
    private static final class Fixture extends DataStorage {

        /**
         * 今日零点时刻戳。
         *
         * @return 返回今日零点时刻戳。
         */
        private static long todayBeginning() {
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            return day.getTimeInMillis();
        }

        /**
         * 首个签入事件时刻。
         */
        final long firstSignInTime = todayBeginning() + 2 * HOUR;

        /**
         * 明细事件。
         */
        final List<DataStorage.ContactEvent> events = new ArrayList<>();

        /**
         * 汇总数据；{@code null} 表示该日没有汇总。
         */
        List<UnitDayRecord> summary;

        /**
         * 汇总的日界偏移，用于制造日界口径不匹配的情形。
         */
        long summaryDayBeginOffset = 0L;

        /**
         * 明细路径被调用次数。
         */
        int detailCalls = 0;

        Fixture() throws Exception {
            super(new Properties());
            // 单元 100001：签入后签出，时长一小时
            this.events.add(event(100001L, "SignIn", this.firstSignInTime));
            this.events.add(event(100001L, "SignOut", this.firstSignInTime + 3600000L));
            // 单元 100002：只有签入，未闭合
            this.events.add(event(100002L, "SignIn", this.firstSignInTime + 1000L));
            // 单元 100003：只有签入，未闭合
            this.events.add(event(100003L, "SignIn", this.firstSignInTime + 2000L));
        }

        /**
         * 写入或清除该日的汇总数据。
         *
         * @param present      是否存在汇总数据。
         * @param versionMatch 汇总口径版本是否与服务端一致。
         */
        void writeSummary(boolean present, boolean versionMatch) {
            if (!present) {
                this.summary = null;
                return;
            }

            long beginning = todayBeginning();
            long ending = beginning + 24L * 60 * 60 * 1000L;

            List<UnitDayRecord> list = new ArrayList<>();
            Map<Long, long[]> stats = this.detailStats(beginning, ending);
            for (Map.Entry<Long, long[]> entry : stats.entrySet()) {
                list.add(new UnitDayRecord(entry.getKey(), (int) entry.getValue()[0],
                        entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]));
            }
            this.summary = list;
            this.summaryVersion = versionMatch ? UnitDayRecord.VERSION : UnitDayRecord.VERSION + 1;
            this.summaryDayBegin = beginning + this.summaryDayBeginOffset;
        }

        /**
         * 按明细路径的同口径现算各单元的当日统计。
         *
         * @param beginning 统计日起始时间戳。
         * @param ending    统计日结束时间戳。
         * @return 返回单元 ID 到「事件数、时长、首次活动、小时掩码」的映射。
         */
        private Map<Long, long[]> detailStats(long beginning, long ending) {
            Map<Long, long[]> result = new HashMap<>();
            UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
            long current = -1L;
            for (DataStorage.ContactEvent event : this.events) {
                if (event.time < beginning || event.time >= ending) {
                    continue;
                }
                if (event.contactId != current) {
                    if (current >= 0L) {
                        result.put(current, new long[] {aggregator.getEventCount(), aggregator.settle(),
                                aggregator.getFirstActive(), aggregator.getHourMask()});
                    }
                    current = event.contactId;
                    aggregator.reset();
                }
                aggregator.add(event.event, event.time);
            }
            if (current >= 0L) {
                result.put(current, new long[] {aggregator.getEventCount(), aggregator.settle(),
                        aggregator.getFirstActive(), aggregator.getHourMask()});
            }
            return result;
        }

        /**
         * 汇总口径版本。
         */
        int summaryVersion = UnitDayRecord.VERSION;

        /**
         * 汇总记录的日界起始时间戳。
         */
        long summaryDayBegin;

        /**
         * 标记行声明的数据行数；与实际行数不符时视为数据不完整。
         */
        int summaryUnitCount = -1;

        @Override
        public UnitDailySummary queryUnitDailySummary(String domain, int year, int month, int date,
                                                        long dayBegin) {
            if (null == this.summary) {
                return UnitDailySummary.unavailable(UnitDailySummary.REASON_NO_DATA);
            }
            if (this.summaryVersion != UnitDayRecord.VERSION) {
                return UnitDailySummary.unavailable(UnitDailySummary.REASON_VERSION_MISMATCH);
            }
            if (this.summaryDayBegin != dayBegin) {
                return UnitDailySummary.unavailable(UnitDailySummary.REASON_DAY_BOUNDARY_MISMATCH);
            }
            if (this.summaryUnitCount >= 0 && this.summaryUnitCount != this.summary.size()) {
                return UnitDailySummary.unavailable(UnitDailySummary.REASON_INCOMPLETE);
            }
            return UnitDailySummary.available(this.summary);
        }

        @Override
        public List<JSONObject> queryUnitContacts(String domain, int maxContacts) {
            List<JSONObject> contacts = new ArrayList<>();
            for (long id = 100001; id <= 100003; ++id) {
                contacts.add(new JSONObject().put("id", id).put("name", "worker-" + id)
                        .put("device", "GPU").put("platform", "Linux"));
            }
            return contacts;
        }

        @Override
        public List<ContactEvent> queryContactEvents(String domain, long from, long to, boolean unitOnly) {
            ++this.detailCalls;
            List<DataStorage.ContactEvent> result = new ArrayList<>();
            for (DataStorage.ContactEvent event : this.events) {
                if (event.time >= from && event.time < to) {
                    result.add(event);
                }
            }
            return result;
        }

        /**
         * 与真实数据库的聚合等价：按单元给出最近活动时间与活跃天数。
         */
        @Override
        public List<UnitTrail> queryUnitTrail(String domain, long from, long to, long zoneOffsetMillis) {
            Map<Long, Long> lastActive = new HashMap<>();
            Map<Long, java.util.Set<Integer>> days = new HashMap<>();
            for (ContactEvent event : this.events) {
                if (event.time < from || event.time >= to) {
                    continue;
                }
                Long last = lastActive.get(event.contactId);
                if (last == null || event.time > last) {
                    lastActive.put(event.contactId, event.time);
                }
                java.util.Set<Integer> set = days.get(event.contactId);
                if (set == null) {
                    set = new java.util.HashSet<>();
                    days.put(event.contactId, set);
                }
                set.add((int) ((event.time + zoneOffsetMillis) / 86400000L));
            }
            List<UnitTrail> result = new ArrayList<>();
            for (Map.Entry<Long, Long> entry : lastActive.entrySet()) {
                result.add(new UnitTrail(entry.getKey(), entry.getValue(),
                        days.get(entry.getKey()).size()));
            }
            return result;
        }

        /**
         * 期望的当日总时长。
         *
         * @return 返回明细路径算出的总时长。
         */
        long expectedTotalDuration() {
            long total = 0L;
            for (long[] value : this.detailStats(this.dayBeginning(), this.dayEnding()).values()) {
                total += value[1];
            }
            return total;
        }

        /**
         * 期望的指定单元当日时长。
         *
         * @param id 单元 ID 。
         * @return 返回明细路径算出的时长。
         */
        long expectedDuration(long id) {
            long[] value = this.detailStats(this.dayBeginning(), this.dayEnding()).get(id);
            return (null == value) ? 0L : value[1];
        }

        /**
         * 统计日起始时间戳。
         *
         * @return 返回起始时间戳。
         */
        private long dayBeginning() {
            return todayBeginning();
        }

        /**
         * 统计日结束时间戳。
         *
         * @return 返回结束时间戳。
         */
        private long dayEnding() {
            return this.dayBeginning() + 24L * 60 * 60 * 1000L;
        }

        /**
         * 读取台账快照。
         *
         * @return 返回台账快照。
         */
        UnitLedger ledger() throws Exception {
            StatisticDataManager manager = new StatisticDataManager();
            Field field = StatisticDataManager.class.getDeclaredField("storage");
            field.setAccessible(true);
            field.set(manager, this);

            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);

            return manager.queryUnitLedger("units.example", day);
        }
    }
}
