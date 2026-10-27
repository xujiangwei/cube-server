/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

/**
 * MBTI 维度枚举。
 *
 * <p>四个维度共八极，组合成 16 型人格。</p>
 */
public enum MyersBriggsTypeIndicator {

    /**
     * 内倾。
     */
    Introversion("内倾", "I"),

    /**
     * 外倾。
     */
    Extraversion("外倾", "E"),

    /**
     * 感觉。
     */
    Sensing("感觉", "S"),

    /**
     * 直觉。
     */
    Intuition("直觉", "N"),

    /**
     * 情感。
     */
    Feeling("情感", "F"),

    /**
     * 思考。
     */
    Thinking("思考", "T"),

    /**
     * 判断。
     */
    Judging("判断", "J"),

    /**
     * 知觉。
     */
    Perceiving("知觉", "P")

    ;

    /** 显示名称。 */
    public final String displayName;

    /** 码。 */
    public final String code;

    MyersBriggsTypeIndicator(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param nameOrCode 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static MyersBriggsTypeIndicator parse(String nameOrCode) {
        for (MyersBriggsTypeIndicator indicator : MyersBriggsTypeIndicator.values()) {
            if (indicator.displayName.equals(nameOrCode) || indicator.code.equalsIgnoreCase(nameOrCode)) {
                return indicator;
            }
        }

        return null;
    }
}
