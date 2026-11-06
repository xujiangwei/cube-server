/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.test;

import cube.util.UnitDayAggregator;
import cube.util.UnitDayRecord;

/**
 * 工作单元日汇总的口径验证。
 *
 * <p>用真实的汇总表行结构校验聚合器：事件数、在线时长、首次活动与小时掩码。
 * 这里的期望值就是 console 侧明细回落路径的口径，两端必须一致。</p>
 */
public class UnitDailyAggregateTest {

    private static int assertions = 0;

    /**
     * 一小时的毫秒数。
     */
    private static final long HOUR = 60L * 60 * 1000L;

    public static void main(String[] args) {
        signInThenOut();
        repeatedSignInKeepsFirstStart();
        deviceTimeoutDeductsHeartbeat();
        unclosedSessionRunsToDayEnd();
        timeoutWithoutSessionIsIgnored();
        comebackOpensSession();
        hourMaskCoversWholeDay();
        versionIsShared();

        System.out.println("UnitDailyAggregateTest: " + assertions + " assertions passed");
    }

    /**
     * 签入后签出：时长为两者之差。
     */
    private static void signInThenOut() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("SignIn", beginning + HOUR);
        aggregator.add("SignOut", beginning + 3L * HOUR);

        check(2 == aggregator.getEventCount(), "sign in then out: event count");
        check(2L * HOUR == aggregator.settle(), "sign in then out: duration is the gap between them");
        check(beginning + HOUR == aggregator.getFirstActive(), "sign in then out: first active time");
    }

    /**
     * 重复签入不重置起点：时长以首个签入为准。
     */
    private static void repeatedSignInKeepsFirstStart() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("SignIn", beginning + HOUR);
        aggregator.add("SignIn", beginning + 2L * HOUR);
        aggregator.add("SignOut", beginning + 3L * HOUR);

        check(3 == aggregator.getEventCount(), "repeated sign in: event count");
        check(2L * HOUR == aggregator.settle(),
                "repeated sign in: the first sign in keeps the session start");
    }

    /**
     * 超时关闭会话：扣除一个心跳周期。
     */
    private static void deviceTimeoutDeductsHeartbeat() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("SignIn", beginning + HOUR);
        aggregator.add("DeviceTimeout", beginning + 2L * HOUR);

        check(HOUR - 15000L == aggregator.settle(),
                "device timeout: the heartbeat cycle is deducted from the duration");
    }

    /**
     * 未闭合会话按统计日结束时刻计入。
     */
    private static void unclosedSessionRunsToDayEnd() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("SignIn", beginning + HOUR);

        check(23L * HOUR == aggregator.settle(), "unclosed session: runs to the end of the day");
    }

    /**
     * 没有会话时的超时事件不产生时长。
     */
    private static void timeoutWithoutSessionIsIgnored() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("DeviceTimeout", beginning + HOUR);

        check(1 == aggregator.getEventCount(), "timeout without session: event count");
        check(0L == aggregator.settle(), "timeout without session: no duration is counted");
    }

    /**
     * 回归事件开启会话。
     */
    private static void comebackOpensSession() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("Comeback", beginning + HOUR);
        aggregator.add("SignOut", beginning + 2L * HOUR);

        check(HOUR == aggregator.settle(), "comeback: opens a session like sign in");
    }

    /**
     * 小时掩码覆盖当天全部小时。
     */
    private static void hourMaskCoversWholeDay() {
        long beginning = 1000000000000L;
        long ending = beginning + 24L * HOUR;

        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        aggregator.add("SignIn", beginning);
        aggregator.add("SignIn", beginning + HOUR);
        aggregator.add("SignIn", beginning + 23L * HOUR);

        check(3L == Long.bitCount(aggregator.getHourMask()), "hour mask: three hours are marked");
        check(1L == (aggregator.getHourMask() & 1L), "hour mask: the first hour is marked");
        check(1L == ((aggregator.getHourMask() >> 23) & 1L), "hour mask: the last hour is marked");
    }

    /**
     * 口径版本由两端共用。
     */
    private static void versionIsShared() {
        check(UnitDayRecord.VERSION >= 1, "version: the shared version is positive");
    }

    private static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
