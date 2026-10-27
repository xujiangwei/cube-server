/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.app;

import cube.aigc.psychology.algorithm.BigFivePersonality;
import cube.aigc.psychology.composition.HexagonDimensionScore;
import cube.common.JSONable;
import cube.common.entity.Membership;
import cube.common.entity.Point;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class UserProfile implements JSONable {

    /** 时间戳。 */
    public final long timestamp;

    /** 会员信息。 */
    public Membership membership;

    /** 总数点列表。 */
    public int totalPoints = 0;

    /** 点列表。 */
    public List<Point> pointList = new ArrayList<>();

    /**
     * 本月用量。
     */
    public int usageOfThisMonth = 0;

    /**
     * 每月用量限制。
     */
    public int limitPerMonth = 0;

    /** 允许报告。 */
    public int permissibleReports = 0;

    /** 六边形维度分数。 */
    public HexagonDimensionScore hexagonScore;

    /** 人格。 */
    public BigFivePersonality personality;

    public UserProfile() {
        this.timestamp = System.currentTimeMillis();
    }

    public UserProfile(JSONObject json) {
        this.timestamp = json.getLong("timestamp");
        if (json.has("membership")) {
            this.membership = new Membership(json.getJSONObject("membership"));
        }

        this.totalPoints = json.getInt("totalPoints");
        JSONArray array = json.getJSONArray("pointList");
        for (int i = 0; i < array.length(); ++i) {
            this.pointList.add(new Point(array.getJSONObject(i)));
        }

        this.usageOfThisMonth = json.getInt("usageOfThisMonth");
        this.limitPerMonth = json.getInt("limitPerMonth");

        if (json.has("permissibleReports")) {
            this.permissibleReports = json.getInt("permissibleReports");
        }
        if (json.has("hexagonScore")) {
            this.hexagonScore = new HexagonDimensionScore(json.getJSONObject("hexagonScore"));
        }
        if (json.has("personality")) {
            this.personality = new BigFivePersonality(json.getJSONObject("personality"));
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("timestamp", this.timestamp);
        if (null != this.membership) {
            json.put("membership", this.membership.toJSON());
        }

        json.put("totalPoints", this.totalPoints);
        JSONArray array = new JSONArray();
        for (Point point : this.pointList) {
            array.put(point.toJSON());
        }
        json.put("pointList", array);

        json.put("usageOfThisMonth", this.usageOfThisMonth);
        json.put("limitPerMonth", this.limitPerMonth);

        // 兼容 1.0.10 及之前版本
        json.put("permissibleReports", this.permissibleReports);
        // 兼容 1.0.10 及之前版本
        if (null != this.hexagonScore) {
            json.put("hexagonScore", this.hexagonScore.toJSON());
        }
        // 兼容 1.0.10 及之前版本
        if (null != this.personality) {
            json.put("personality", this.personality.toJSON());
        }

        // 旧版本兼容 1.0.4 及之前的版本
        json.put("numReports", this.permissibleReports);

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
