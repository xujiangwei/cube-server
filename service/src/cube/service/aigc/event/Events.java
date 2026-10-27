/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.event;

/**
 * 事件名称常量。
 *
 * <p>集中定义流与分段相关的事件名，供事件中心与监听器共用。</p>
 */
public final class Events {

    /** 流开始。 */
    public final static String StreamStarted = "streamStarted";

    /** 流结束。 */
    public final static String StreamStopped = "streamStopped";

    /** 分段数据到达。 */
    public final static String Segment = "segment";
}
