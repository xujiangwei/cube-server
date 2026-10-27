/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import cube.vision.Color;
import org.json.JSONObject;

/**
 * 文本约束。
 */
public class TextConstraint implements JSONable {

    /** 字体。 */
    public String font = null;

    /** 点大小。 */
    public int pointSize = 24;

    /** 颜色。 */
    public Color color = new Color(0, 0, 0);

    public TextConstraint() {
    }

    public TextConstraint(JSONObject json) {
        this.pointSize = json.getInt("pointSize");
        this.color = new Color(json.getJSONObject("color"));
        if (json.has("font")) {
            this.font = json.getString("font");
        }
    }

    /**
     * 设置字体。
     *
     * @param font 字体。
     */
    public TextConstraint setFont(String font) {
        this.font = font;
        return this;
    }

    /**
     * 设置点大小。
     *
     * @param size 点大小。
     */
    public TextConstraint setPointSize(int size) {
        this.pointSize = size;
        return this;
    }

    /**
     * 设置颜色。
     *
     * @param color 颜色。
     */
    public TextConstraint setColor(Color color) {
        this.color = color;
        return this;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("pointSize", this.pointSize);
        json.put("color", this.color.toJSON());
        if (null != this.font) {
            json.put("font", this.font);
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
