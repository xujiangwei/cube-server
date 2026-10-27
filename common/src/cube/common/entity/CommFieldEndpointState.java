/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 通信节点状态。
 */
public enum CommFieldEndpointState {

    /**
     * 正常状态。
     */
    Normal(0),

    /**
     * 正在建立通话。
     */
    Calling(10),

    /**
     * 当前线路忙。
     */
    Busy(11),

    /**
     * 通话已接通。
     */
    CallConnected(13),

    /**
     * 通话结束。
     */
    CallBye(15),

    /**
     * 未知的状态。
     */
    Unknown(99);

    /** 码。 */
    public final int code;

    CommFieldEndpointState(int code) {
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
    public final static CommFieldEndpointState parse(int code) {
        for (CommFieldEndpointState state : CommFieldEndpointState.values()) {
            if (state.code == code) {
                return state;
            }
        }

        return CommFieldEndpointState.Unknown;
    }
}
