/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common;

/**
 * 语言。
 */
public enum Language {

    /**
     * 中文。
     */
    Chinese("cn"),

    /**
     * 英文。
     */
    English("en"),

    /**
     * 其他。
     */
    Other(""),

    ;

    /** 简体形式标识。 */
    public final String simplified;

    Language(String simplified) {
        this.simplified = simplified;
    }

    /** 判断是否为英文。 */
    public boolean isEnglish() {
        return this.simplified.equalsIgnoreCase(Language.English.simplified);
    }

    /** 判断是否为中文。 */
    public boolean isChinese() {
        return this.simplified.equalsIgnoreCase(Language.Chinese.simplified);
    }

    /** 按名称解析语言。 */
    public static Language parse(String name) {
        for (Language language : Language.values()) {
            if (language.simplified.equalsIgnoreCase(name) || language.name().equalsIgnoreCase(name)) {
                return language;
            }
        }
        return Other;
    }
}
