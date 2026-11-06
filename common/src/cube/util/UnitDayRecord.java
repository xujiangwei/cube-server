/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

/**
 * 工作单元的单日活动汇总行。
 *
 * <p>由 service 侧的日统计采集写入日汇总表，console 侧读取它以替代事件明细扫描。
 * 字段口径与 {@link UnitDayAggregator} 完全一致，两侧共用同一份实现，
 * 避免「采集侧算一套、读取侧算另一套」导致的数据漂移。</p>
 */
public final class UnitDayRecord {

    /**
     * 工作单元日汇总的口径版本。
     *
     * <p>口径变化时递增：写入侧与读取侧比对版本，不一致时读取侧回落到明细路径，
     * 避免用旧口径的汇总冒充新口径的结果。</p>
     *
     * <p>版本 2 起汇总表增加了 {@code unit_count} 列（标记行携带该日数据行数），
     * 表结构与判定口径同时变化，版本必须一并递增。</p>
     */
    public static final int VERSION = 2;

    /**
     * 单元 ID 。
     */
    public final long contactId;

    /**
     * 当日事件总数。
     */
    public final int eventCount;

    /**
     * 当日在线时长，单位：毫秒。
     */
    public final long durationMillis;

    /**
     * 当日首次活动时间戳。
     */
    public final long firstActive;

    /**
     * 当日活跃小时掩码：第 n 位为 1 表示该单元在第 n 小时有事件。
     */
    public final long hourMask;

    /**
     * 构造函数。
     *
     * @param contactId      单元 ID 。
     * @param eventCount     当日事件总数。
     * @param durationMillis 当日在线时长，单位：毫秒。
     * @param firstActive    当日首次活动时间戳。
     * @param hourMask       当日活跃小时掩码。
     */
    public UnitDayRecord(long contactId, int eventCount, long durationMillis,
                         long firstActive, long hourMask) {
        this.contactId = contactId;
        this.eventCount = eventCount;
        this.durationMillis = durationMillis;
        this.firstActive = firstActive;
        this.hourMask = hourMask;
    }
}
