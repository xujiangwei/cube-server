/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.report;

import cell.util.log.Logger;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JVM 信息报告。
 */
public class JVMReport extends Report {

    /** 报告名称。 */
    public final static String NAME = "JVMReport";

    /** JVM 最大可用内存。 */
    public long maxMemory;

    /** JVM 已使用的系统内存。 */
    public long totalMemory;

    /** JVM 空闲的系统内存。 */
    public long freeMemory;

    /** 内核启动时间。 */
    public long systemStartTime;

    /** 截止本次快照生成时内核运行的时长，单位：毫秒。 */
    public long systemDuration;

    public JVMReport(String reporter, long timestamp) {
        super(JVMReport.NAME, timestamp);
        this.setReporter(reporter);

        this.maxMemory = Runtime.getRuntime().maxMemory();
        this.totalMemory = Runtime.getRuntime().totalMemory();
        this.freeMemory = Runtime.getRuntime().freeMemory();
    }

    public JVMReport(JSONObject json) {
        super(json);

        try {
            this.maxMemory = json.getLong("maxMemory");
            this.totalMemory = json.getLong("totalMemory");
            this.freeMemory = json.getLong("freeMemory");
            this.systemStartTime = json.getLong("systemStartTime");
            this.systemDuration = json.getLong("systemDuration");
        } catch (JSONException e) {
            Logger.e(JVMReport.class, "#JVMReport - JSON error", e);
        }
    }

    /**
     * 采集一次 JVM 指标快照。
     *
     * <p>读取堆内存、线程数、GC 次数等运行时数据并填充到报告。</p>
     */
    public void dump() {
        this.maxMemory = 0;
        this.totalMemory = 0;
        this.freeMemory = 0;
    }

    /**
     * 设置系统启动时间。
     *
     * @param startTime 启动时间戳，单位毫秒。
     */
    public void setSystemStartTime(long startTime) {
        this.systemStartTime = startTime;
        this.systemDuration = this.getTimestamp() - startTime;
    }

    /**
     * 按系数缩放数值。
     *
     * <p>用于把不同量纲的指标归一到同一尺度，便于同图展示。</p>
     *
     * @param factor 缩放系数。
     */
    public void scaleValue(double factor) {
        this.maxMemory = Math.round((double) this.maxMemory / factor);
        this.totalMemory = Math.round((double) this.totalMemory / factor);
        this.freeMemory = Math.round((double) this.freeMemory / factor);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        try {
            json.put("maxMemory", this.maxMemory);
            json.put("totalMemory", this.totalMemory);
            json.put("freeMemory", this.freeMemory);
            json.put("systemStartTime", this.systemStartTime);
            json.put("systemDuration", this.systemDuration);
        } catch (JSONException e) {
            Logger.e(JVMReport.class, "#toJSON - JSON error", e);
        }
        return json;
    }
}
