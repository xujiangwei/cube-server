/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.vision.Rectangle;
import org.json.JSONObject;

/**
 * 条形码。
 */
public class BarCode extends Entity {

    /** 超大尺寸容器：1000×500 ，模块宽度 64 。 */
    public static Container Ultra = new Container(1000, 500, 64);

    /** 大尺寸容器：500×250 ，模块宽度 32 。 */
    public static Container Large = new Container(500, 250, 32);

    /** 常规尺寸容器：252×126 ，模块宽度 16 。 */
    public static Container Normal = new Container(252, 126, 16);

    /** 小尺寸容器：200×100 ，模块宽度 13 。 */
    public static Container Small = new Container(200, 100, 13);

    /** 条码承载的数据。 */
    public String data;

    /** 条码类型，默认 CODE128 。 */
    public String type = "CODE128";

    /** 条码绘制区域。 */
    public Rectangle rect;

    /** 图像宽度，单位像素。 */
    public int width;

    /** 图像高度，单位像素。 */
    public int height;

    /** 条码上方的标题文字。 */
    public String header;

    /** 条码下方的说明文字。 */
    public String footer;

    /** 文字字号。 */
    public int fontSize;

    public BarCode(JSONObject json) {
        super(json);
        this.data = json.getString("data");
        if (json.has("type")) {
            this.type = json.getString("type");
        }
        if (json.has("rect")) {
            this.rect = new Rectangle(json.getJSONObject("rect"));
        }

        this.width = json.has("width") ? json.getInt("width") : 500;
        this.height = json.has("height") ? json.getInt("height") : 250;
        this.header = json.has("header") ? json.getString("header") : null;
        this.footer = json.has("footer") ? json.getString("footer") : null;
        this.fontSize = json.has("fontSize") ? json.getInt("fontSize") : 32;
    }

    /**
     * 应用预设容器尺寸。
     *
     * <p>容器同时决定图像宽高与模块宽度，三者需保持一致，否则条码无法解码。</p>
     */
    public void setContainer(Container container) {
        this.width = container.width;
        this.height = container.height;
        this.fontSize = container.fontSize;
    }

    /** 序列化为完整 JSON 。 */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("data", this.data);
        json.put("type", this.type);

        if (null != this.rect) {
            json.put("rect", this.rect.toJSON());
        }

        json.put("width", this.width);
        json.put("height", this.height);

        if (null != this.header) {
            json.put("header", this.header);
        }
        if (null != this.footer) {
            json.put("footer", this.footer);
        }

        json.put("fontSize", this.fontSize);
        return json;
    }

    /** 序列化为精简 JSON 。 */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = super.toCompactJSON();
        json.put("data", this.data);
        json.put("type", this.type);
        if (null != this.rect) {
            json.put("rect", this.rect.toJSON());
        }
        return json;
    }

    /**
     * 按名称解析预设容器。
     *
     * @param text 容器名称，如 {@code Ultra} 、{@code Small} 。
     * @return 对应容器；名称未知时返回 {@code null} 。
     */
    public static Container parseContainer(String text) {
        if (text.equalsIgnoreCase("Ultra")) {
            return BarCode.Ultra;
        }
        else if (text.equalsIgnoreCase("Large")) {
            return BarCode.Large;
        }
        else if (text.equalsIgnoreCase("Normal")) {
            return BarCode.Normal;
        }
        else {
            return BarCode.Small;
        }
    }

    public static class Container {

        public int width;

        public int height;

        public int fontSize;

        public Container(int width, int height, int fontSize) {
            this.width = width;
            this.height = height;
            this.fontSize = fontSize;
        }
    }
}
