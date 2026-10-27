/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.ImageOperation;
import org.json.JSONObject;

/**
 * 亮度对比度操作。
 */
public class BrightnessOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "Brightness";

    private int brightness;

    private int contrast;

    public BrightnessOperation(int brightness, int contrast) {
        this.brightness = brightness;
        this.contrast = contrast;
    }

    public BrightnessOperation(JSONObject json) {
        super(json);
        this.brightness = json.getInt("brightness");
        this.contrast = json.getInt("contrast");
    }

    /**
     * 获取亮度调整值。
     *
     * @return 亮度值，正值提亮、负值压暗。
     */
    public int getBrightness() {
        return this.brightness;
    }

    /**
     * 获取对比度调整值。
     *
     * @return 对比度值，负值趋向灰阶。
     */
    public int getContrast() {
        return this.contrast;
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
        json.put("brightness", this.brightness);
        json.put("contrast", this.contrast);
        return json;
    }
}
