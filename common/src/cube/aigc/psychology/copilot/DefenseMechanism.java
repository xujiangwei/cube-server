/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.copilot;

import cell.util.Utils;

public enum DefenseMechanism {

    Rationalization("理智化", "不停地讲理论、分析，拒绝谈感受。"),

    Denial("否认", "“我没有生气，我只是就事论事。”"),

    Projection("投射", "“我觉得是你（咨询师）对我不耐烦，而不是我对你不耐烦。”"),

    Displacement("转移", "把对父母/伴侣的愤怒转移到咨询师身上。"),

    ActingOut("见诸行动", "在咨询室外给你发骚扰信息，或者迟到、忘付费。"),

    Random("随机", "从以上选项中随机选择。")

    ;

    /** 显示。 */
    public final String display;

    /** 描述。 */
    public final String description;

    DefenseMechanism(String display, String description) {
        this.display = display;
        this.description = description;
    }

    /**
     * 随机取一个枚举值。
     *
     * @return 随机的枚举值。
     */
    public static DefenseMechanism random() {
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
    public static DefenseMechanism parse(String name) {
        for (DefenseMechanism dm : DefenseMechanism.values()) {
            if (dm.name().equalsIgnoreCase(name) || dm.display.equalsIgnoreCase(name)) {
                return dm;
            }
        }
        return DefenseMechanism.Random;
    }
}
