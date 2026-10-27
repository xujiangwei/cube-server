/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.common.entity.TextConstraint;
import cube.file.ImageOperation;
import org.json.JSONObject;

/**
 * 水印操作。
 */
public class WatermarkOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "Watermark";

    private String text;

    private TextConstraint constraint;

    public WatermarkOperation(String text, TextConstraint constraint) {
        this.text = text;
        this.constraint = constraint;
    }

    public WatermarkOperation(JSONObject json) {
        super(json);
        this.text = json.getString("text");
        this.constraint = new TextConstraint(json.getJSONObject("constraint"));
    }

    /**
     * 获取文本。
     *
     * @return 文本。
     */
    public String getText() {
        return this.text;
    }

    /**
     * 获取水印文本约束。
     *
     * @return 文本约束；未设置返回 {@code null} 。
     */
    public TextConstraint getTextConstraint() {
        return this.constraint;
    }

    /**
     * 获取操作名称。
     *
     * @return 固定返回 {@link #Operation} 。
     */
    @Override
    public String getOperation() {
        return Operation;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("text", this.text);
        json.put("constraint", this.constraint.toJSON());
        return json;
    }
}
