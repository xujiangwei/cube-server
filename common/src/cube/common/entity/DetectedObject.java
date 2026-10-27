/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 已检测到的对象。
 */
public class DetectedObject extends Entity {

    private String className;

    private double probability;

    private BoundingBox bbox;

    /**
     * 构造函数。
     * @param className className。
     * @param probability probability。
     * @param bbox bbox。
     */
    public DetectedObject(String className, double probability, BoundingBox bbox) {
        this.className = className;
        this.probability = probability;
        this.bbox = bbox;
    }

    /**
     * 获取类名。
     *
     * @return 类名。
     */
    public String getClassName() {
        return this.className;
    }

    /**
     * 获取概率。
     *
     * @return 概率。
     */
    public double getProbability() {
        return this.probability;
    }

    /**
     * 获取检测框。
     *
     * @return 检测框。
     */
    public BoundingBox getBoundingBox() {
        return this.bbox;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("class", this.className);
        json.put("probability", this.probability);
        json.put("bound", this.bbox.toJSON());
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
