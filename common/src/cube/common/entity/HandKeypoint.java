/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

/**
 * 手势关键点。
 */
public enum HandKeypoint {

    /**
     * 手腕。
     */
    Wrist(0),

    /**
     * 拇指。
     */
    ThumbCmc(1),
    ThumbMcp(2),
    ThumbDip(3),
    ThumbTip(4),

    /**
     * 食指。
     */
    IndexMcp(5),
    IndexPip(6),
    IndexDip(7),
    IndexTip(8),

    /**
     * 中指。
     */
    MiddleMcp(9),
    MiddlePip(10),
    MiddleDip(11),
    MiddleTip(12),

    /**
     * 无名指。
     */
    RingMcp(13),
    RingPip(14),
    RingDip(15),
    RingTip(16),

    /**
     * 小指。
     */
    PinkyMcp(17),
    PinkyPip(18),
    PinkyDip(19),
    PinkyTip(20),

    Unknown(99),

    ;

    /** 码。 */
    public final int code;

    HandKeypoint(int code) {
        this.code = code;
    }

    /**
     * 按编码解析。
     *
     * <p>遍历全部枚举值逐个匹配，未命中时返回 {@code null} 。</p>
     *
     * @param code 编码值。
     * @return 对应枚举；未命中返回 {@code null} 。
     */
    public static HandKeypoint parse(int code) {
        for (HandKeypoint pk : HandKeypoint.values()) {
            if (pk.code == code) {
                return pk;
            }
        }
        return Unknown;
    }

    /**
     * 按编码解析。
     *
     * <p>遍历全部枚举值逐个匹配，未命中时返回 {@code null} 。</p>
     *
     * @param nameOrCode 编码值。
     * @return 对应枚举；未命中返回 {@code null} 。
     */
    public static HandKeypoint parse(String nameOrCode) {
        for (HandKeypoint pk : HandKeypoint.values()) {
            if (pk.name().equalsIgnoreCase(nameOrCode)
                    || Integer.toString(pk.code).equals(nameOrCode)) {
                return pk;
            }
        }
        return Unknown;
    }
}
