/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.vision;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 大小描述。
 */
public class Size implements JSONable {

    /** 宽度。 */
    public int width;

    /** 高度。 */
    public int height;

    public Size() {
    }

    public Size(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public Size(JSONObject json) {
        this.width = json.getInt("width");
        this.height = json.getInt("height");
    }

    /**
     * 计算面积。
     * @return 计算结果。
     */
    public int calculateArea() {
        return this.width * this.height;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(this.width);
        buf.append("x");
        buf.append(this.height);
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("width", this.width);
        json.put("height", this.height);
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
     * 从字符串形式解析数据。
     * @param string string。
     * @return 处理结果。
     */
    public static Size parse(String string) {
        String[] data = string.split("x");
        if (data.length == 2) {
            return new Size(Integer.parseInt(data[0]), Integer.parseInt(data[1]));
        }

        return null;
    }
}
