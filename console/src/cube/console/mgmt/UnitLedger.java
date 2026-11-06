/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.mgmt;

import java.util.List;

/**
 * 单元台账的数据库派生快照。
 *
 * <p>这里只保存「由统计库算出来、且不会随节点上报变化」的部分：单元基础信息与活动统计。
 * 在线状态与承载能力来自节点上报，属于内存态，实时叠加而不进入本快照 ——
 * 快照被缓存时若把上报内容一起存进去，刚上报的单元会被冻在离线态。</p>
 */
public final class UnitLedger {

    /**
     * 单个物理实体的活动统计。
     */
    public static final class Unit {

        /**
         * 物理实体 ID。
         */
        public final long id;

        /**
         * 实体名称。
         */
        public final String name;

        /**
         * 最近一次设备名。
         */
        public final String device;

        /**
         * 最近一次设备平台。
         */
        public final String platform;

        /**
         * 统计日内的活动事件数。
         */
        public final int eventCount;

        /**
         * 统计日内的在线时长，单位：毫秒。
         */
        public final long durationMillis;

        /**
         * 回溯窗口内的最近活动时间。
         */
        public final long lastActiveTime;

        /**
         * 统计日内的首次活动时间。
         */
        public final long firstActiveTime;

        /**
         * 回溯窗口内的活跃天数。
         */
        public final int activeDays;

        public Unit(long id, String name, String device, String platform, int eventCount,
                    long durationMillis, long lastActiveTime, long firstActiveTime, int activeDays) {
            this.id = id;
            this.name = name;
            this.device = device;
            this.platform = platform;
            this.eventCount = eventCount;
            this.durationMillis = durationMillis;
            this.lastActiveTime = lastActiveTime;
            this.firstActiveTime = firstActiveTime;
            this.activeDays = activeDays;
        }
    }

    /**
     * 单元列表，按「最近活动时间倒序、ID 升序」排列。
     * <p>该顺序是最终排序规则在「在线优先」之外的剩余部分：在线分组内、离线分组内都按此序，
     * 未激活分组内活跃时间全为零即等价于 ID 升序，因此缓存快照后仍可稳定还原最终顺序。</p>
     */
    public final List<Unit> units;

    /**
     * 台账中的实体总数。
     */
    public final int total;

    /**
     * 统计日内有活动的实体数。
     */
    public final int activeCount;

    /**
     * 统计日内所有活跃实体的在线时长之和，单位：毫秒。
     */
    public final long totalDurationMillis;

    /**
     * 统计日内的活跃时段切片，每小时的活跃实体数，长度固定为 24。
     */
    public final int[] timelineUnits;

    /**
     * 活跃峰值所在的小时，-1 表示当日无活动。
     */
    public final int peakSlice;

    /**
     * 统计日起始时间戳。
     */
    public final long beginning;

    /**
     * 统计日结束时间戳。
     */
    public final long ending;

    public UnitLedger(List<Unit> units, int total, int activeCount, long totalDurationMillis,
                      int[] timelineUnits, int peakSlice, long beginning, long ending) {
        this.units = units;
        this.total = total;
        this.activeCount = activeCount;
        this.totalDurationMillis = totalDurationMillis;
        this.timelineUnits = timelineUnits;
        this.peakSlice = peakSlice;
        this.beginning = beginning;
        this.ending = ending;
    }
}
