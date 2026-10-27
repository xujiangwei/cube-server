/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import cube.vision.Point;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 手势估算。
 */
public class HandEstimation implements JSONable {

    private Map<HandKeypoint, Point> keypointPoints = new HashMap<>();

    public HandEstimation(JSONObject json) {
        for (String key : json.keySet()) {
            HandKeypoint keypoint = HandKeypoint.parse(key);
            this.keypointPoints.put(keypoint, new Point(json.getJSONObject(key)));
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
        for (Map.Entry<HandKeypoint, Point> entry : this.keypointPoints.entrySet()) {
            json.put(entry.getKey().name(), entry.getValue().toJSON());
        }
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
