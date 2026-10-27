/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

public enum TextSplitter {

    /**
     * 自动语义分割。
     */
    Auto("auto"),

    /**
     * 根据标点符号分割。
     */
    Punctuation("punctuation"),

    /**
     * 根据文本行进行分割，即一行文本分割为一段。
     */
    Line("line"),

    /**
     * 语义分割后以标题为前缀。
     */
    Prefix("prefix"),

    /**
     * 不分割。
     */
    None("none"),

    ;

    /** 切分策略名称。 */
    public final String name;

    TextSplitter(String name) {
        this.name = name;
    }

    /**
     * 按名称解析切分策略。
     *
     * @param name 策略名称。
     * @return 策略；未知名称返回 {@code null} 。
     */
    public static TextSplitter parse(String name) {
        for (TextSplitter splitter : TextSplitter.values()) {
            if (splitter.name.equalsIgnoreCase(name)) {
                return splitter;
            }
        }
        return TextSplitter.None;
    }
}
