/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 图表资源。
 */
public class ChartResource extends ComplexResource {

    private String title;

    private Chart chart;

    public ChartResource(String title, Chart chart) {
        super(Subject.Chart);
        this.title = title;
        this.chart = chart;
    }

    public ChartResource(JSONObject json) {
        super(Subject.Chart, json);

        JSONObject payload = json.getJSONObject("payload");
        this.title = payload.getString("title");
        this.chart = new Chart(payload.getJSONObject("chart"));
    }

    /**
     * 获取标题。
     *
     * @return 标题。
     */
    public String getTitle() {
        return this.title;
    }

    /**
     * 获取图表。
     *
     * @return 图表。
     */
    public Chart getChart() {
        return this.chart;
    }


    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();

        JSONObject payload = new JSONObject();
        payload.put("title", this.title);
        payload.put("chart", this.chart.toJSON());

        json.put("payload", payload);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
