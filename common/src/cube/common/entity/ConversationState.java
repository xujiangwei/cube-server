/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 会话的状态。
 */
public enum ConversationState {

    /**
     * 正常状态。
     */
    Normal(1),

    /**
     * 重要的或置顶的状态。
     */
    Important(2),

    /**
     * 已删除状态。
     */
    Deleted(3),

    /**
     * 已销毁状态。
     */
    Destroyed(4);

    /** 码。 */
    public final int code;

    ConversationState(int code) {
        this.code = code;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        return Integer.toString(this.code);
    }

    /**
     * 按编码解析。
     *
     * <p>遍历全部枚举值逐个匹配，未命中时返回 {@code null} 。</p>
     *
     * @param code 编码值。
     * @return 对应枚举；未命中返回 {@code null} 。
     */
    public static ConversationState parse(int code) {
        for (ConversationState state : ConversationState.values()) {
            if (state.code == code) {
                return state;
            }
        }

        return Deleted;
    }
}
