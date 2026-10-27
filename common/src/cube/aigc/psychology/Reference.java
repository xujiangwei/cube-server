/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology;

/**
 * 关注参考。
 */
public enum Reference {

    /**
     * 常态。
     */
    Normal("Normal"),

    /**
     * 非常态。
     */
    Abnormal("Abnormal");


    /** 名称。 */
    public final String name;

    Reference(String name) {
        this.name = name;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param name 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public final static Reference parse(String name) {
        if (Normal.name.equalsIgnoreCase(name)) {
            return Normal;
        }
        else {
            return Abnormal;
        }
    }
}
