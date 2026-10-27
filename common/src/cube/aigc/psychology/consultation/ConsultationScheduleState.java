/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.consultation;

/**
 * 咨询日程状态。
 */
public enum ConsultationScheduleState {

    /**
     * 已预约。
     */
    Reserved(1),

    /**
     * 已取消。
     */
    Canceled(2),

    /**
     * 已过期。
     */
    Expired(3),

    /**
     * 已完成。
     */
    Completed(4),

    /**
     * 已删除。
     */
    Deleted(9),

    ;

    /** 码。 */
    public final int code;

    ConsultationScheduleState(int code) {
        this.code = code;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param code 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static ConsultationScheduleState parse(int code) {
        for (ConsultationScheduleState state : ConsultationScheduleState.values()) {
            if (state.code == code) {
                return state;
            }
        }
        return ConsultationScheduleState.Expired;
    }
}
