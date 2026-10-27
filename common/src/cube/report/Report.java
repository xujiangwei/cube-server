/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.report;

import cell.util.log.Logger;
import cube.common.JSONable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 报告描述类。
 */
public class Report implements JSONable {

    /**
     * 报告名称。
     */
    private String name;

    /**
     * 报告时间戳。
     */
    private long timestamp;

    /**
     * 报告人描述。
     */
    private String reporter;

    public Report(String name) {
        this(name, System.currentTimeMillis());
    }

    public Report(String name, long timestamp) {
        this.name = name;
        this.timestamp = timestamp;
    }

    public Report(JSONObject json) {
        try {
            this.name = json.getString("name");
            this.timestamp = json.getLong("timestamp");
            this.reporter = json.getString("reporter");
        } catch (JSONException e) {
            Logger.e(Report.class, "#Report - JSON error", e);
        }
    }

    /**
     * 获取报告名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取采集时间戳。
     *
     * @return 时间戳，单位毫秒。
     */
    public long getTimestamp() {
        return this.timestamp;
    }

    /**
     * 设置上报者标识。
     *
     * @param reporter 上报者。
     */
    public void setReporter(String reporter) {
        this.reporter = reporter;
    }

    /**
     * 获取上报者标识。
     *
     * @return 上报者；未设置返回 {@code null} 。
     */
    public String getReporter() {
        return this.reporter;
    }

    /**
     * 重置统计值。
     *
     * <p>由子类在采集周期开始时调用，清空上一轮的累计数据。</p>
     */
    protected void reset() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        json.put("timestamp", this.timestamp);
        json.put("reporter", this.reporter);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }

    /**
     * 从 JSON 中提取报告名称。
     *
     * @param json 报告 JSON 。
     * @return 报告名称。
     * @throws JSONException 缺少名称字段。
     */
    public static String extractName(JSONObject json) throws JSONException {
        return json.getString("name");
    }
}
