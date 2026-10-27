/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.copilot;

import cell.util.Utils;

public enum AttachmentType {

    Secure("安全型", "信任咨询师，能建立联盟。"),

    Anxious("焦虑型", "时刻担心咨询师不喜欢自己，需要大量确认。"),

    Avoidant("回避型", "拒绝亲密，觉得咨询没用，甚至想逃离。"),

    Disorganized("混乱型", "既想靠近又想推开，通常有创伤史。"),

    Random("随机", "从以上选项中随机选择。")

    ;

    /** 显示。 */
    public final String display;

    /** 描述。 */
    public final String description;

    AttachmentType(String display, String description) {
        this.display = display;
        this.description = description;
    }

    /**
     * 随机取一个枚举值。
     *
     * @return 随机的枚举值。
     */
    public static AttachmentType random() {
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
    public static AttachmentType parse(String name) {
        for (AttachmentType at : AttachmentType.values()) {
            if (at.name().equalsIgnoreCase(name) || at.display.equalsIgnoreCase(name)) {
                return at;
            }
        }
        return AttachmentType.Random;
    }
}
