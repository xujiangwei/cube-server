/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.file.ImageOperation;
import cube.vision.Color;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 替换图像颜色。
 */
public class ReplaceColorOperation extends ImageOperation {

    /** 操作名称，用于在 JSON 中标识本操作。 */
    public final static String Operation = "ReplaceColor";

    /** 待替换的目标颜色。 */
    private Color targetColor;

    /** 替换成的颜色。 */
    private Color replaceColor;

    /** 颜色匹配容差，取值越大匹配范围越宽。 */
    private int fuzzFactor = 20;

    /** 批量替换的子操作列表；非空时忽略本层的单色替换。 */
    private List<ReplaceColorOperation> operations;

    public ReplaceColorOperation(Color targetColor, Color replaceColor) {
        this.targetColor = targetColor;
        this.replaceColor = replaceColor;
    }

    public ReplaceColorOperation(Color targetColor, Color replaceColor, int fuzzFactor) {
        this.targetColor = targetColor;
        this.replaceColor = replaceColor;
        this.fuzzFactor = fuzzFactor;
    }

    public ReplaceColorOperation(List<ReplaceColorOperation> operations) {
        this.operations = operations;
    }

    public ReplaceColorOperation(JSONObject json) {
        super(json);

        if (json.has("operations")) {
            JSONArray array = json.getJSONArray("operations");
            this.operations = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); ++i) {
                ReplaceColorOperation rco = new ReplaceColorOperation(array.getJSONObject(i));
                this.operations.add(rco);
            }
        }
        else {
            this.targetColor = new Color(json.getJSONObject("target"));
            this.replaceColor = new Color(json.getJSONObject("replace"));
            this.fuzzFactor = json.getInt("fuzz");
        }
    }

    /**
     * 获取目标颜色。
     *
     * @return 目标颜色。
     */
    public Color getTargetColor() {
        return this.targetColor;
    }

    /**
     * 获取替换颜色。
     *
     * @return 替换颜色。
     */
    public Color getReplaceColor() {
        return this.replaceColor;
    }

    /**
     * 设置颜色匹配容差。
     *
     * @param fuzzFactor 容差值。
     */
    public void setFuzzFactor(int fuzzFactor) {
        this.fuzzFactor = fuzzFactor;
    }

    /**
     * 获取颜色匹配容差。
     *
     * @return 容差值。
     */
    public int getFuzzFactor() {
        return this.fuzzFactor;
    }

    /**
     * 获取批量替换的子操作列表。
     *
     * @return 子操作列表；单色替换时返回 {@code null} 。
     */
    public List<ReplaceColorOperation> getOperationList() {
        return this.operations;
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

        if (null != this.operations) {
            JSONArray array = new JSONArray();
            for (ReplaceColorOperation rco : this.operations) {
                array.put(rco.toJSON());
            }
            json.put("operations", array);
        }
        else {
            json.put("target", this.targetColor.toJSON());
            json.put("replace", this.replaceColor.toJSON());
            json.put("fuzz", this.fuzzFactor);
        }

        return json;
    }
}
