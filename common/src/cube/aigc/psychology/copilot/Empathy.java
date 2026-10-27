/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.copilot;

import cell.util.Utils;

public enum Empathy {

    Idealization("理想化", "来访者：“你是我的救世主，只有你能救我。”"),

    Belittling("贬低", "来访者：“你也就是个混饭吃的，你根本不懂我。”"),

    Seduction("诱惑", "试图打破专业边界（测试咨询师的伦理）。"),

    Cooperation("合作", "理性地配合，但内心封闭。"),

    Random("随机", "从以上选项中随机选择。")

    ;

    /** 显示。 */
    public final String display;

    /** 描述。 */
    public final String description;

    Empathy(String display, String description) {
        this.display = display;
        this.description = description;
    }

    /**
     * 随机取一个枚举值。
     *
     * @return 随机的枚举值。
     */
    public static Empathy random() {
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
    public static Empathy parse(String name) {
        for (Empathy empathy : Empathy.values()) {
            if (empathy.name().equalsIgnoreCase(name) || empathy.display.equalsIgnoreCase(name)) {
                return empathy;
            }
        }
        return Empathy.Random;
    }
}
