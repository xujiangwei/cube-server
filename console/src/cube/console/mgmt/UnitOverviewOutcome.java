/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.mgmt;

import org.json.JSONObject;

/**
 * 单元概览查询结果。
 *
 * <p>除数据本身外，还携带缓存与限流的状态：页面据此判断数据是否可能过期，
 * 服务端据此决定返回 200、429 还是 503。</p>
 */
public final class UnitOverviewOutcome {

    /**
     * 响应数据；不可用时为 {@code null} 。
     */
    public final JSONObject data;

    /**
     * 数据来自过期快照（回源被拒绝或失败时的兜底）。
     */
    public final boolean stale;

    /**
     * 是否复用了同一键上其它请求正在进行的回源结果。
     */
    public final boolean merged;

    /**
     * 是否被限流拒绝（并发闸或回源配额耗尽，且没有可用的旧快照）。
     */
    public final boolean rejected;

    /**
     * 统计库是否不可用。
     */
    public final boolean failed;

    /**
     * 统计库是否尚未就绪。
     */
    public final boolean notReady;

    /**
     * 域内单元数是否超出可承载上限。
     */
    public final boolean tooLarge;

    private UnitOverviewOutcome(JSONObject data, boolean stale, boolean merged,
                                boolean rejected, boolean failed, boolean notReady, boolean tooLarge) {
        this.data = data;
        this.stale = stale;
        this.merged = merged;
        this.rejected = rejected;
        this.failed = failed;
        this.notReady = notReady;
        this.tooLarge = tooLarge;
    }

    /**
     * 构造成功结果。
     *
     * @param data   响应数据。
     * @param stale  数据是否来自过期快照。
     * @param merged 是否复用了其它请求的回源结果。
     * @return 查询结果。
     */
    public static UnitOverviewOutcome ready(JSONObject data, boolean stale, boolean merged) {
        return new UnitOverviewOutcome(data, stale, merged, false, false, false, false);
    }

    /**
     * 构造被限流的结果。
     *
     * @return 查询结果。
     */
    public static UnitOverviewOutcome rejected() {
        return new UnitOverviewOutcome(null, false, false, true, false, false, false);
    }

    /**
     * 构造统计库不可用的结果。
     *
     * @return 查询结果。
     */
    public static UnitOverviewOutcome failed() {
        return new UnitOverviewOutcome(null, false, false, false, true, false, false);
    }

    /**
     * 构造统计库尚未就绪的结果。
     *
     * @return 查询结果。
     */
    public static UnitOverviewOutcome notReady() {
        return new UnitOverviewOutcome(null, false, false, false, false, true, false);
    }

    /**
     * 构造单元数超限的结果。
     *
     * @return 查询结果。
     */
    public static UnitOverviewOutcome tooLarge() {
        return new UnitOverviewOutcome(null, false, false, false, false, false, true);
    }

    /**
     * 缓存状态标识，供响应头与指标使用。
     *
     * @return 缓存状态标识。
     */
    public String cacheState() {
        if (this.rejected || this.failed || this.notReady || this.tooLarge) {
            return "NONE";
        }
        if (this.stale) {
            return "STALE";
        }
        return this.merged ? "MERGED" : "FRESH";
    }
}
