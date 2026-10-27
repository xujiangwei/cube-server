/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.aigc.psychology.composition.BigFiveFactor;
import org.json.JSONArray;

/**
 * 大五人格全景象限。
 *
 * <p>按亲和与进取两轴把人格划分为四个象限。</p>
 */
public enum BigFivePanoramaQuadrant {

    /**
     * 宜人性/外向性。
     */
    ObligingnessExtraversion(-1, -1),

    /**
     * 外向性/进取性。
     */
    ExtraversionAchievement(-1, 1),

    /**
     * 进去性/尽责性。
     */
    AchievementConscientiousness(1, 1),

    /**
     * 尽责性/宜人性。
     */
    ConscientiousnessObligingness(1, -1)

    ;

    /** X 坐标 原始。 */
    public final int xOri;

    /** Y 坐标 原始。 */
    public final int yOri;

    /** 象限。 */
    public final BigFiveFactor[] quadrant;

    BigFivePanoramaQuadrant(int xOri, int yOri) {
        this.xOri = xOri;
        this.yOri = yOri;
        if (xOri < 0 && yOri < 0) {
            this.quadrant = new BigFiveFactor[] { BigFiveFactor.Obligingness, BigFiveFactor.Extraversion };
        }
        else if (xOri < 0 && yOri > 0) {
            this.quadrant = new BigFiveFactor[] { BigFiveFactor.Extraversion, BigFiveFactor.Achievement };
        }
        else if (xOri > 0 && yOri > 0) {
            this.quadrant = new BigFiveFactor[] { BigFiveFactor.Achievement, BigFiveFactor.Conscientiousness };
        }
        else {
            this.quadrant = new BigFiveFactor[] { BigFiveFactor.Conscientiousness, BigFiveFactor.Obligingness };
        }
    }

    /**
     * 转换为象限数组。
     *
     * @return 象限 JSON 数组。
     */
    public JSONArray toJSONArray() {
        JSONArray quadrant = new JSONArray();
        quadrant.put(this.quadrant[0].toJSON());
        quadrant.put(this.quadrant[1].toJSON());
        return quadrant;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param x 编码值。
     * @param y 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static BigFivePanoramaQuadrant parse(double x, double y) {
        for (BigFivePanoramaQuadrant bfp : BigFivePanoramaQuadrant.values()) {
            if (bfp.xOri < 0.0d && x < 0.0d && bfp.yOri < 0.0d && y < 0.0d) {
                return bfp;
            }
            else if (bfp.xOri > 0.0d && x > 0.0d && bfp.yOri < 0.0d && y < 0.0d) {
                return bfp;
            }
            else if (bfp.xOri < 0.0d && x < 0.0d && bfp.yOri > 0.0d && y > 0.0d) {
                return bfp;
            }
            else if (bfp.xOri > 0.0d && x > 0.0d && bfp.yOri > 0.0d && y > 0.0d) {
                return bfp;
            }
        }

        return ObligingnessExtraversion;
    }
}
