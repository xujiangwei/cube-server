/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.copilot;

import cell.util.Utils;

public enum PainLevel {

    Mild("1-3分", "轻描淡写，甚至带点幽默。"),

    Obvious("4-6分", "明显的焦虑和抑郁。"),

    Extreme("7-9分", "极度痛苦，可能伴随哭泣、愤怒爆发。"),

    DissociativeState("10分", "解离状态，无法正常交流，甚至有自杀风险。"),

    Random("随机", "从以上选项中随机选择。")

    ;

    /** 显示。 */
    public final String display;

    /** 描述。 */
    public final String description;

    PainLevel(String display, String description) {
        this.display = display;
        this.description = description;
    }

    /**
     * 随机取一个枚举值。
     *
     * @return 随机的枚举值。
     */
    public static PainLevel random() {
        int index = Utils.randomInt(0, values().length - 2);
        return values()[index];
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param name 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static PainLevel parse(String name) {
        for (PainLevel pl : PainLevel.values()) {
            if (pl.name().equalsIgnoreCase(name) || pl.display.equalsIgnoreCase(name)) {
                return pl;
            }
        }
        return PainLevel.Random;
    }
}
