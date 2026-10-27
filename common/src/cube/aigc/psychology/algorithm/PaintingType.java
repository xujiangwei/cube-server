/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

public enum PaintingType {

    HouseTreePerson("HTP", "房树人"),

    PersonInTheRain("PIR", "雨中人"),

    PersonSwordShield("PSS", "人剑盾")

    ;

    /** 名称。 */
    public final String name;

    /** 显示名称。 */
    public final String displayName;

    PaintingType(String name, String displayName) {
        this.name = name;
        this.displayName = displayName;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param name 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static PaintingType parse(String name) {
        for (PaintingType type : PaintingType.values()) {
            if (type.name.equalsIgnoreCase(name) ||
                type.displayName.equals(name)) {
                return type;
            }
        }
        return null;
    }
}
