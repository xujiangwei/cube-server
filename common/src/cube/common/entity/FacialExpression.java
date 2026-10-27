/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 面部表情。
 */
public class FacialExpression implements JSONable {

    /** 文件。 */
    public final FileLabel file;

    /** 表情。 */
    public final Expression expression;

    /** 检测框。 */
    public final BoundingBox boundingBox;

    public FacialExpression(JSONObject json) {
        this.file = new FileLabel(json.getJSONObject("file"));
        this.expression = Expression.parse(json.getString("expression"));
        this.boundingBox = new BoundingBox(json.getJSONObject("bbox"));
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("file", this.file.toCompactJSON());
        json.put("expression", this.expression.name());
        json.put("bbox", this.boundingBox.toJSON());
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
