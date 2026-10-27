/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

/**
 * 成员角色。
 */
public enum Role {

    /**
     * 管理员。
     */
    Administrator(1),

    /**
     * 普通成员。
     */
    Member(9),

    /**
     * 未知角色。
     */
    Unknown(0)

    ;

    /** 码。 */
    public final int code;

    Role(int code) {
        this.code = code;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param code 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static Role parse(int code) {
        for (Role role : Role.values()) {
            if (role.code == code) {
                return role;
            }
        }

        return Unknown;
    }
}
