/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 消息状态。
 */
public enum MessageState {

    /**
     * 消息处理失败。
     */
    Fault(1),

    /**
     * 未发送状态。
     */
    Unsent(5),

    /**
     * 正在发送状态。
     */
    Sending(9),

    /**
     * 已发送状态。
     */
    Sent(10),

    /**
     * 已被阅读状态。
     */
    Read(20),

    /**
     * 被拒绝阅读状态。
     */
    Forbidden(22),

    /**
     * 已撤回。
     */
    Retracted(30),

    /**
     * 已删除。
     */
    Deleted(40),

    /**
     * 被阻止发送。
     */
    SendBlocked(51),

    /**
     * 被阻止接收。
     */
    ReceiveBlocked(52),

    /**
     * 被系统阻断发送。
     */
    SystemBlocked(53),

    /**
     * 未知状态。
     */
    Unknown(0);

    /** 码。 */
    public final int code;

    MessageState(int code) {
        this.code = code;
    }

    /**
     * 获取码。
     *
     * @return 码。
     */
    public int getCode() {
        return this.code;
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
    public static MessageState parse(int code) {
        for (MessageState state : MessageState.values()) {
            if (state.code == code) {
                return state;
            }
        }

        return MessageState.Unknown;
    }
}
