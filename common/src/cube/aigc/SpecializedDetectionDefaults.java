/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc;

/**
 * 检测服务的默认准入预算与超时时间，使用本类无需任何检测配置项。
 */
public final class SpecializedDetectionDefaults {
    /** 最大并发检测会话数。 */
    public static final int MAX_SESSIONS = 2;
    /** 数据通道线程数。 */
    public static final int DATA_THREADS = 8;
    /** 数据通道任务队列容量。 */
    public static final int DATA_QUEUE = 64;
    /** 控制通道线程数。 */
    public static final int CONTROL_THREADS = 2;
    /** 控制通道任务队列容量。 */
    public static final int CONTROL_QUEUE = 64;
    /** 数据通道超时时间，单位毫秒。 */
    public static final int DATA_TIMEOUT_MS = 180000;
    /** 控制通道超时时间，单位毫秒。 */
    public static final int CONTROL_TIMEOUT_MS = 30000;
    /**
     * 网关侧数据通道超时时间，单位毫秒。
     * 比服务端超时多 10 秒，使服务端的超时错误先于网关超时送达调用方。
     */
    public static final int GATEWAY_DATA_TIMEOUT_MS = DATA_TIMEOUT_MS + 10000;
    /** 网关侧控制通道超时时间，单位毫秒，比服务端超时多 10 秒。 */
    public static final int GATEWAY_CONTROL_TIMEOUT_MS = CONTROL_TIMEOUT_MS + 10000;

    /**
     * 私有构造方法，禁止实例化。
     */
    private SpecializedDetectionDefaults() {}
}
