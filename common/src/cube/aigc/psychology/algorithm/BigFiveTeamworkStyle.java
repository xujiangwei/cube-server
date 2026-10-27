/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.vision.Point;

/**
 * 团队协作风格枚举。
 *
 * <p>由大五因子组合推导出的协作风格。</p>
 */
public enum BigFiveTeamworkStyle {

    /**
     * 竞争。
     */
    Competition("Competition"),

    /**
     * 协作。
     */
    Collaboration("Collaboration"),

    /**
     * 顺应。
     */
    Accommodation("Accommodation"),

    /**
     * 回避。
     */
    Avoidance("Avoidance"),

    /**
     * 折中。
     */
    Compromise("Compromise")

    ;

    /** 名称。 */
    public final String name;

    BigFiveTeamworkStyle(String name) {
        this.name = name;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param coordinate 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static BigFiveTeamworkStyle parse(Point coordinate) {
        if (coordinate.x < -5.0 && coordinate.y > 5.0) {
            return Competition;
        }
        else if (coordinate.x > 5.0 && coordinate.y > 5.0) {
            return Collaboration;
        }
        else if (coordinate.x > 5.0 && coordinate.y < -5.0) {
            return Accommodation;
        }
        else if (coordinate.x < -5.0 && coordinate.y < -5.0) {
            return Avoidance;
        }
        else {
            return Compromise;
        }
    }
}
