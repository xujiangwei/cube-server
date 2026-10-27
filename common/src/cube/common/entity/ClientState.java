/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 客户端状态。
 */
public enum ClientState {

    /**
     * 一般状态。
     */
    Normal(0),

    /**
     * 已删除状态。
     */
    Deleted(1),

    /**
     * 禁用状态。
     */
    Disabled(9),

    ;

    /**
     * 状态代码。
     */
    public final int code;

    ClientState(int code) {
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
    public static ClientState parse(int code) {
        for (ClientState state : ClientState.values()) {
            if (state.code == code) {
                return state;
            }
        }

        return Disabled;
    }
}
