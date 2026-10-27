/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.copilot;

import cell.util.Utils;

public enum ChiefComplaintType {

    Somatization("躯体化", "来访者表述：“我失眠、胸闷、头痛，但医生查不出病。”（实际上是心理问题）。"),

    AcuteCrisis("急性危机", "刚经历分手、失业或亲人离世，处于崩溃边缘。"),

    LongTermChronic("长期慢性", "来访者表述：“我这种感觉已经持续10年了，没什么希望能好。”"),

    Unmotivated("无动机/被强制", "来访者表述：“是我老婆/父母逼我来的，我觉得我没问题。”"),

    Random("随机", "从以上选项中随机选择。")

    ;

    /** 显示。 */
    public final String display;

    /** 描述。 */
    public final String description;

    ChiefComplaintType(String display, String description) {
        this.display = display;
        this.description = description;
    }

    /**
     * 随机取一个枚举值。
     *
     * @return 随机的枚举值。
     */
    public static ChiefComplaintType random() {
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
    public static ChiefComplaintType parse(String name) {
        for (ChiefComplaintType cct : ChiefComplaintType.values()) {
            if (cct.name().equalsIgnoreCase(name) || cct.display.equalsIgnoreCase(name)) {
                return cct;
            }
        }
        return ChiefComplaintType.Random;
    }
}
