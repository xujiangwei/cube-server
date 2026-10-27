/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 边界盒。
 */
public class BoundingBox implements JSONable {

    private double x;

    private double y;

    private double width;

    private double height;

    public BoundingBox(JSONObject json) {
        this.x = json.getDouble("x");
        this.y = json.getDouble("y");
        this.width = json.getDouble("width");
        this.height = json.getDouble("height");
    }

    /**
     * 获取左上角 X 坐标。
     *
     * @return X 坐标。
     */
    public double getX() {
        return this.x;
    }

    /**
     * 获取左上角 Y 坐标。
     *
     * @return Y 坐标。
     */
    public double getY() {
        return this.y;
    }

    /**
     * 获取宽度。
     *
     * @return 宽度。
     */
    public double getWidth() {
        return this.width;
    }

    /**
     * 获取高度。
     *
     * @return 高度。
     */
    public double getHeight() {
        return this.height;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("x", this.x);
        json.put("y", this.y);
        json.put("width", this.width);
        json.put("height", this.height);
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
