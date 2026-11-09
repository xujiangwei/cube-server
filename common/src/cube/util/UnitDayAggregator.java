/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

/**
 * 工作单元的单日活动聚合器。
 *
 * <p>把某工作单元在某一天内的活动事件按时间升序喂进来，产出该单元当日的汇总值：
 * 事件总数、在线时长、首次活动时间、以及按小时的活跃掩码。</p>
 *
 * <p><b>会话配对口径</b>（service 侧采集与 console 侧降级路径共用同一份实现）：</p>
 * <ul>
 *   <li>{@code SignIn} 与 {@code Comeback} 开启会话：只有当前没有未闭合会话时才生效，
 *       重复签入不重置起点；</li>
 *   <li>{@code SignOut} 关闭会话，累计「关闭时刻 − 开始时刻」；</li>
 *   <li>{@code DeviceTimeout} 关闭会话，累计「关闭时刻 − 开始时刻 − 心跳周期」，
 *       负值按 0 计；</li>
 *   <li>统计区间结束时仍未闭合的会话，按「区间结束 − 开始时刻」计入。</li>
 * </ul>
 *
 * <p>调用方必须按时间升序喂入同一单元的事件，否则会话配对结果失真。</p>
 */
public final class UnitDayAggregator {

    /**
     * 签入事件名：开启会话。
     */
    public static final String EVENT_SIGN_IN = "SignIn";

    /**
     * 签出事件名：关闭会话。
     */
    public static final String EVENT_SIGN_OUT = "SignOut";

    /**
     * 回归事件名：与签入同义，开启会话。
     */
    public static final String EVENT_COME_BACK = "Comeback";

    /**
     * 设备超时事件名：关闭会话，并扣除一个心跳周期。
     */
    public static final String EVENT_DEVICE_TIMEOUT = "DeviceTimeout";

    /**
     * 心跳周期：超时事件比最后一次心跳晚到，扣除这一个周期。
     */
    private static final long HEARTBEAT_CYCLE = 15000L;

    /**
     * 统计日的起始时间戳（含）。
     */
    private final long beginning;

    /**
     * 统计日的结束时间戳（不含）。
     */
    private final long ending;

    /**
     * 当前未闭合会话的开始时刻，0 表示没有未闭合会话。
     */
    private long sessionStart;

    /**
     * 已闭合会话累计的时长，单位：毫秒。
     */
    private long duration;

    /**
     * 当日首个事件时间戳。
     */
    private long firstActive;

    /**
     * 当日事件总数。
     */
    private int eventCount;

    /**
     * 当日活跃小时掩码：第 n 位为 1 表示该单元在第 n 小时有事件。
     */
    private long hourMask;

    /**
     * 构造函数。
     *
     * @param beginning 统计日的起始时间戳（含）。
     * @param ending    统计日的结束时间戳（不含）。
     */
    public UnitDayAggregator(long beginning, long ending) {
        this.beginning = beginning;
        this.ending = ending;
        this.reset();
    }

    /**
     * 清空累计状态，开始统计下一个单元。
     */
    public void reset() {
        this.sessionStart = 0L;
        this.duration = 0L;
        this.firstActive = 0L;
        this.eventCount = 0;
        this.hourMask = 0L;
    }

    /**
     * 喂入一条活动事件。
     *
     * @param event 事件名。
     * @param time  事件时间戳。
     */
    public void add(String event, long time) {
        ++this.eventCount;

        if (this.firstActive == 0L) {
            this.firstActive = time;
        }

        int slice = (int) ((time - this.beginning) / (60L * 60 * 1000L));
        if (slice < 0) {
            slice = 0;
        } else if (slice > 23) {
            slice = 23;
        }
        this.hourMask |= (1L << slice);

        if (EVENT_SIGN_IN.equals(event) || EVENT_COME_BACK.equals(event)) {
            // 已在会话中时重复签入不重置起点
            if (this.sessionStart == 0L) {
                this.sessionStart = time;
            }
            return;
        }

        if (EVENT_DEVICE_TIMEOUT.equals(event)) {
            if (this.sessionStart > 0L) {
                this.duration += Math.max(0L, (time - this.sessionStart) - HEARTBEAT_CYCLE);
            }
            this.sessionStart = 0L;
        } else if (EVENT_SIGN_OUT.equals(event)) {
            if (this.sessionStart > 0L) {
                this.duration += Math.max(0L, time - this.sessionStart);
            }
            this.sessionStart = 0L;
        }
    }

    /**
     * 结算当前单元，产出当日汇总值。
     *
     * <p>统计区间结束时仍未闭合的会话按「区间结束 − 开始时刻」计入。结算后聚合器
     * 仍可继续使用，但需先调用 {@link #reset()}。</p>
     *
     * @return 当日在线时长，单位：毫秒。
     */
    public long settle() {
        if (this.sessionStart > 0L) {
            this.duration += Math.max(0L, this.ending - this.sessionStart);
            this.sessionStart = 0L;
        }

        return this.duration;
    }

    /**
     * 获取当日事件总数。
     *
     * @return 返回事件总数。
     */
    public int getEventCount() {
        return this.eventCount;
    }

    /**
     * 获取当日首次活动时间戳。
     *
     * @return 返回首次活动时间戳；没有事件时返回 0 。
     */
    public long getFirstActive() {
        return this.firstActive;
    }

    /**
     * 获取当日活跃小时掩码。
     *
     * @return 返回小时掩码：第 n 位为 1 表示该单元在第 n 小时有事件。
     */
    public long getHourMask() {
        return this.hourMask;
    }
}
