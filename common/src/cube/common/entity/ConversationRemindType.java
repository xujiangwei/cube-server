/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 会话提醒类型。
 */
public enum ConversationRemindType {

    /**
     * 正常接收。
     */
    Normal(1),

    /**
     * 接收不提醒。
     */
    Closed(2),

    /**
     * 接收但不关注。
     */
    NotCare(3),

    /**
     * 不接收。
     */
    Refused(4);

    /** 码。 */
    public final int code;

    ConversationRemindType(int code) {
        this.code = code;
    }

    /**
     * 按编码解析。
     *
     * <p>遍历全部枚举值逐个匹配，未命中时返回 {@code null} 。</p>
     *
     * @param code 编码值。
     * @return 对应枚举；未命中返回 {@code null} 。
     */
    public static ConversationRemindType parse(int code) {
        for (ConversationRemindType type : ConversationRemindType.values()) {
            if (type.code == code) {
                return type;
            }
        }

        return Normal;
    }
}
