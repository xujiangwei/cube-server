/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.common.JSONable;
import org.json.JSONObject;

public class Line implements JSONable {

    /** 粗细。 */
    public double thickness;

    /** 类型。 */
    public String type;

    public Line(JSONObject json) {
        this.thickness = json.getDouble("thickness");
        this.type = json.getString("type");
    }

    /**
     * 判断是否粗。
     *
     * @return 粗。
     */
    public boolean isThick() {
        return this.type.equalsIgnoreCase("thick");
    }

    /**
     * 判断是否细。
     *
     * @return 细。
     */
    public boolean isThin() {
        return this.type.equalsIgnoreCase("thin");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("thickness", this.thickness);
        json.put("type", this.type);
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
