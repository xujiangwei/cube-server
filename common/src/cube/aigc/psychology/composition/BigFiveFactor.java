/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import org.json.JSONObject;

/**
 * 大五人格的各个因子。
 */
public enum BigFiveFactor {

    /**
     * 宜人性。
     */
    Obligingness("Obligingness", "宜人性"),

    /**
     * 尽责性。
     */
    Conscientiousness("Conscientiousness", "尽责性"),

    /**
     * 外向性。
     */
    Extraversion("Extraversion", "外向性"),

    /**
     * 进取性。
     */
    Achievement("Achievement", "进取性"),

    /**
     * 情绪性。
     */
    Neuroticism("Neuroticism", "情绪性"),

    ;

    /** 码。 */
    public final String code;

    /** 名称。 */
    public final String name;

    BigFiveFactor(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("code", this.code);
        json.put("name", this.name);
        return json;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param codeOrName 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static BigFiveFactor parse(String codeOrName) {
        for (BigFiveFactor tbf : BigFiveFactor.values()) {
            if (tbf.code.equalsIgnoreCase(codeOrName) || tbf.name.equals(codeOrName)) {
                return tbf;
            }
        }
        return Neuroticism;
    }
}
