/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.copilot;

import cell.util.Utils;

public enum PersonalityTrait {

    BPD("边缘型", "情绪极度不稳定，害怕被抛弃，黑白分明的思维（“你要么全好，要么全坏”），有自伤史。"),

    NPD("自恋型", "夸大自我重要性，需要过度赞美，缺乏共情，对批评极度敏感（即使是隐性的）。"),

    AvPD("回避型", "极度社交抑制，感觉自己不如别人，对负面评价极其恐惧。"),

    OCPD("强迫型", "追求完美、控制欲强、刻板、过分投入工作，情感表达僵硬。"),

    DPD("依赖型", "难以独立做决定，需要他人过度照顾，害怕分离。"),

    APD("反社会型", "表面顺从实则抵抗，或者缺乏悔意。"),

    HFOP("“高功能”普通人", "仅仅是遇到了具体的生活危机（失恋、丧亲、职场倦怠），功能尚可，只是痛苦。"),

    Random("随机", "从以上选项中随机选择。")

    ;

    /** 显示。 */
    public final String display;

    /** 描述。 */
    public final String description;

    PersonalityTrait(String display, String description) {
        this.display = display;
        this.description = description;
    }

    /**
     * 随机取一个枚举值。
     *
     * @return 随机的枚举值。
     */
    public static PersonalityTrait random() {
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
    public static PersonalityTrait parse(String name) {
        for (PersonalityTrait pt : PersonalityTrait.values()) {
            if (pt.name().equalsIgnoreCase(name) || pt.display.equalsIgnoreCase(name)) {
                return pt;
            }
        }
        return PersonalityTrait.Random;
    }
}
