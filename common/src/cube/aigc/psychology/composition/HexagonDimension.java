/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

public enum HexagonDimension {

    /**
     * 情绪。
     */
    Mood("Mood",
            "Mood",
            "情绪"),

    /**
     * 认知。
     */
    Cognition("Cognition",
            "Cognition",
            "认知"),

    /**
     * 行为。
     */
    Behavior("Behavior",
            "Behavior",
            "行为"),

    /**
     * 人际关系敏感。
     */
    InterpersonalRelationship("InterpersonalRelationship",
            "Sensitive of Interpersonal Relationship",
            "人际关系敏感"),

    /**
     * 自我评价。
     */
    SelfAssessment("SelfAssessment",
            "Self-assessment",
            "自我评价"),

    /**
     * 心理健康。
     */
    MentalHealth("MentalHealth",
            "Mental Health",
            "心理健康")

    ;

    /** 名称。 */
    public final String name;

    /** 英文展示名称。 */
    public final String displayNameInEnglish;
    /** 中文展示名称。 */
    public final String displayNameInChinese;

    HexagonDimension(String name, String displayNameInEnglish, String displayNameInChinese) {
        this.name = name;
        this.displayNameInEnglish = displayNameInEnglish;
        this.displayNameInChinese = displayNameInChinese;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param name 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static HexagonDimension parse(String name) {
        for (HexagonDimension hd : HexagonDimension.values()) {
            if (hd.name.equalsIgnoreCase(name) || hd.displayNameInEnglish.equalsIgnoreCase(name) ||
                hd.displayNameInChinese.equalsIgnoreCase(name)) {
                return hd;
            }
        }
        // 兼容旧版本
        if (name.equalsIgnoreCase("Emotion")) {
            return HexagonDimension.Mood;
        }
        return null;
    }
}
