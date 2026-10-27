/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.aigc.psychology.Painting;

/**
 * 绘画置信度。
 *
 * <p>评估一次绘画分析的可靠程度，按置信等级给出不同提示。</p>
 */
public class PaintingConfidence {

    /** 置信等级：较高。 */
    public final static int LEVEL_HIGHER = 5;

    /** 等级高。 */
    public final static int LEVEL_HIGH = 4;

    /** 等级正常。 */
    public final static int LEVEL_NORMAL = 3;

    /** 等级低。 */
    public final static int LEVEL_LOW = 2;

    /** 等级下。 */
    public final static int LEVEL_LOWER = 1;

    private int confidenceLevel;

    public PaintingConfidence(Painting painting) {
        this.build(painting);
    }

    public PaintingConfidence(int level) {
        this.confidenceLevel = level;
    }

    /**
     * 获取置信等级。
     *
     * @return 等级值，取值见 {@code LEVEL_*} 。
     */
    public int getConfidenceLevel() {
        return this.confidenceLevel;
    }

    private void build(Painting painting) {
        if (!painting.isValid()) {
            this.confidenceLevel = LEVEL_LOWER;
            return;
        }

        if (painting.hasHouse() && painting.hasTree() && painting.hasPerson()) {
            this.confidenceLevel = LEVEL_HIGH;

            if (painting.getHouse().numComponents() >= 2
                    && painting.getTree().numComponents() >= 2
                    && painting.getPerson().numComponents() >= 2) {
                this.confidenceLevel = LEVEL_HIGHER;
            }
            else if (painting.getHouse().numComponents() > 3
                    || painting.getTree().numComponents() > 3
                    || painting.getPerson().numComponents() > 3) {
                this.confidenceLevel = LEVEL_HIGHER;
            }
            else {
                if (painting.getDrawingSet().getAll().size() > 4) {
                    this.confidenceLevel = LEVEL_HIGHER;
                }
            }
        }
        else if ((painting.hasHouse() && painting.hasTree()) ||
                (painting.hasTree() && painting.hasPerson()) ||
                (painting.hasPerson() && painting.hasHouse())) {
            this.confidenceLevel = LEVEL_NORMAL;
        }
        else {
            this.confidenceLevel = LEVEL_LOW;
        }
    }
}
