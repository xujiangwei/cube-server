/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.ImageOperation;
import cube.vision.Rectangle;
import org.json.JSONObject;

/**
 * 剪裁图像。
 */
public class CropOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "Crop";

    private Rectangle cropRect;

    public CropOperation(int x, int y, int width, int height) {
        super();
        this.cropRect = new Rectangle(x, y, width, height);
    }

    public CropOperation(Rectangle rect) {
        super();
        this.cropRect = rect;
    }

    public CropOperation(JSONObject json) {
        super(json);
        this.cropRect = new Rectangle(json.getJSONObject("rect"));
    }

    /**
     * 设置裁剪区域。
     *
     * @param rect 裁剪矩形，坐标相对于原图左上角。
     */
    public void setCropRect(Rectangle rect) {
        this.cropRect = rect;
    }

    /**
     * 获取裁剪区域。
     *
     * @return 裁剪矩形。
     */
    public Rectangle getCropRect() {
        return this.cropRect;
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

        json.put("rect", this.cropRect.toJSON());

        return json;
    }
}
