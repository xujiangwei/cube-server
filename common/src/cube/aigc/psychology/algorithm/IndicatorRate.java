/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import org.json.JSONObject;

/**
 * 指标评级。
 */
public enum IndicatorRate {

    /**
     * 无。
     */
    None(0, "略"),

    /**
     * 很低。
     */
    Lowest(1, "很低"),

    /**
     * 低。
     */
    Low(2, "低"),

    /**
     * 中等。
     */
    Medium(3, "中等"),

    /**
     * 高。
     */
    High(4, "高"),

    /**
     * 很高。
     */
    Highest(5, "很高")

    ;

    /** 值。 */
    public final int value;

    /** 显示名称。 */
    public final String displayName;

    IndicatorRate(int value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("value", this.value);
        json.put("displayName", this.displayName);
        return json;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param json 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static IndicatorRate parse(JSONObject json) {
        int value = json.getInt("value");
        return parse(value);
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param value 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static IndicatorRate parse(int value) {
        for (IndicatorRate ir : IndicatorRate.values()) {
            if (ir.value == value) {
                return ir;
            }
        }
        return null;
    }
}
