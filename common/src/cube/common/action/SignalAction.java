/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.action;

/**
 * 信号单元动作。
 */
public enum SignalAction {

    Direct("direct"),

    Broadcast("broadcast"),

    Receipt("receipt")

    ;

    /** 动作名称，用于在协议中标识该动作。 */
    public final String name;

    SignalAction(String name) {
        this.name = name;
    }
}
