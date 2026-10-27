/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.consultation;

/**
 * 咨询形式。
 */
public enum ConsultationMethod {

    /**
     * 面对面。
     */
    FaceToFace(0),

    /**
     * 线上咨询。
     */
    Online(1),

    ;

    /** 码。 */
    public final int code;

    ConsultationMethod(int code) {
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
    public static ConsultationMethod parse(int code) {
        for (ConsultationMethod consultation : ConsultationMethod.values()) {
            if (consultation.code == code) {
                return consultation;
            }
        }
        return ConsultationMethod.FaceToFace;
    }
}
