/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

/**
 * 情感分类。
 */
public enum Sentiment {

    /**
     * 正面的。
     */
    Positive("positive"),

    /**
     * 负面的。
     */
    Negative("negative"),

    /**
     * 中立的。
     */
    Neutral("neutral"),

    /**
     * 正负面均含。
     */
    Both("both"),

    /**
     * 未定义的。
     */
    Undefined("undefined")

    ;

    /** 情感编码。 */
    public final String code;

    Sentiment(String code) {
        this.code = code;
    }

    /**
     * 按编码解析情感类型。
     *
     * @param code 编码。
     * @return 情感类型；未知编码返回 {@code null} 。
     */
    public static Sentiment parse(String code) {
        for (Sentiment sentiment : Sentiment.values()) {
            if (sentiment.code.equals(code)) {
                return sentiment;
            }
        }

        return Sentiment.Both;
    }
}
