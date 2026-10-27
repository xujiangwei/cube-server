/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology;

/**
 * 角色枚举。
 *
 * <p>定义咨询场景中的参与者角色。</p>
 */
public enum Role {

    /**
     * 客户/来访者。
     */
    Customer("customer"),

    /**
     * 咨询师。
     */
    Counselor("counselor"),

    /**
     * 其他。
     */
    Other("other"),

    ;

    /** 标签。 */
    public final String label;

    Role(String label) {
        this.label = label;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param label 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static Role parse(String label) {
        for (Role role : Role.values()) {
            if (role.label.equalsIgnoreCase(label)) {
                return role;
            }
        }
        return Other;
    }
}
