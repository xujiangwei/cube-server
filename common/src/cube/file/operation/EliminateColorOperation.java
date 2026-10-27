/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.ImageOperation;
import cube.vision.Color;
import org.json.JSONObject;

/**
 * 剔除图像颜色。
 */
public class EliminateColorOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "EliminateColor";

    private Color reservedColor;

    private Color fillColor;

    public EliminateColorOperation(Color reservedColor, Color fillColor) {
        this.reservedColor = reservedColor;
        this.fillColor = fillColor;
    }

    public EliminateColorOperation(JSONObject json) {
        super(json);

        this.reservedColor = new Color(json.getJSONObject("reserved"));
        this.fillColor = new Color(json.getJSONObject("fill"));
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
     * 获取保留的颜色。
     *
     * <p>除该颜色外的像素都会被替换为填充色。</p>
     *
     * @return 保留的颜色。
     */
    public Color getReservedColor() {
        return this.reservedColor;
    }

    /**
     * 获取填充色。
     *
     * @return 用于替换被消除像素的颜色。
     */
    public Color getFillColor() {
        return this.fillColor;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();

        json.put("reserved", this.reservedColor.toJSON());
        json.put("fill", this.fillColor.toJSON());

        return json;
    }
}
