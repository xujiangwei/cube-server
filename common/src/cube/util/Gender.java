/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.util;

/**
 * 性别。
 */
public enum Gender {

    /**
     * 男性。
     */
    Male("male"),

    /**
     * 女性。
     */
    Female("female"),

    /**
     * 未知。
     */
    Unknown("unknown")

    ;

    /** 名称。 */
    public final String name;

    Gender(String name) {
        this.name = name;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param name 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static Gender parse(String name) {
        for (Gender gender : Gender.values()) {
            if (gender.name.equalsIgnoreCase(name)) {
                return gender;
            }
        }
        return Gender.Unknown;
    }
}
