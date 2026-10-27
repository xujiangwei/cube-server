/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.indicator;

/**
 * 指标。
 */
public enum SRBCIndicator implements Indicable {
    /**
     * 榕树型。
     */
    BanyanTree("榕树型", "BanyanTree", 9),

    /**
     * 橡树型。
     */
    OakTree("橡树型", "OakTree", 9),

    /**
     * 藤蔓型。
     */
    Vine("藤蔓型", "Vine", 9),

    /**
     * 向日葵型。
     */
    Sunflower("向日葵型", "Sunflower", 9),

    /**
     * 仙人掌型。
     */
    Cactus("仙人掌型", "Cactus", 9),

    /**
     * 竹子型。
     */
    Bamboo("竹子型", "Bamboo", 9),

    /**
     * 并蒂莲型。
     */
    TwinLotus("并蒂莲型", "TwinLotus", 9),

    /**
     * 蒲公英型。
     */
    Dandelion("蒲公英型", "Dandelion", 9),

    /**
     * 雪松型。
     */
    Cedar("雪松型", "Cedar", 9),

    /**
     * 未知。
     */
    Unknown("未知", "Unknown", 0)

    ;

    private final String name;

    private final String code;

    private final int priority;

    SRBCIndicator(String name, String code, int priority) {
        this.name = name;
        this.code = code;
        this.priority = priority;
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * 获取码。
     *
     * @return 码。
     */
    @Override
    public String getCode() {
        return this.code;
    }

    /**
     * 获取优先级。
     *
     * @return 优先级。
     */
    @Override
    public int getPriority() {
        return this.priority;
    }

    /**
     * 获取指标总数。
     *
     * @return 数量。
     */
    public static int length() {
        return SRBCIndicator.values().length - 1;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param nameOrCode 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static SRBCIndicator parse(String nameOrCode) {
        for (SRBCIndicator indicator : SRBCIndicator.values()) {
            if (indicator.code.equalsIgnoreCase(nameOrCode) ||
                    indicator.name.equalsIgnoreCase(nameOrCode)) {
                return indicator;
            }
        }
        return SRBCIndicator.Unknown;
    }
}
