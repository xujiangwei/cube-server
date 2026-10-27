/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util.calc;

import java.util.ArrayList;
import java.util.List;

public class FrameStructureDescription {

    /** 帧结构列表。 */
    public List<FrameStructure> frameStructures;

    public FrameStructureDescription() {
        this.frameStructures = new ArrayList<>();
    }

    /**
     * 添加帧结构。
     *
     * @param structure 帧结构。
     */
    public void addFrameStructure(FrameStructure structure) {
        if (this.frameStructures.contains(structure)) {
            return;
        }
        this.frameStructures.add(structure);
    }

    /**
     * 判断是否上。
     *
     * @return 上。
     */
    public boolean isTop() {
        return this.frameStructures.contains(FrameStructure.TopSpace);
    }

    /**
     * 判断是否下。
     *
     * @return 下。
     */
    public boolean isBottom() {
        return this.frameStructures.contains(FrameStructure.BottomSpace);
    }

    /**
     * 判断是否左。
     *
     * @return 左。
     */
    public boolean isLeft() {
        return this.frameStructures.contains(FrameStructure.LeftSpace);
    }

    /**
     * 判断是否右。
     *
     * @return 右。
     */
    public boolean isRight() {
        return this.frameStructures.contains(FrameStructure.RightSpace);
    }

    /**
     * 判断是否居中上左。
     *
     * @return 居中上左。
     */
    public boolean isCenterTopLeft() {
        return this.frameStructures.contains(FrameStructure.CenterTopLeftSpace);
    }

    /**
     * 判断是否居中上右。
     *
     * @return 居中上右。
     */
    public boolean isCenterTopRight() {
        return this.frameStructures.contains(FrameStructure.CenterTopRightSpace);
    }

    /**
     * 判断是否居中下左。
     *
     * @return 居中下左。
     */
    public boolean isCenterBottomLeft() {
        return this.frameStructures.contains(FrameStructure.CenterBottomLeftSpace);
    }

    /**
     * 判断是否居中下右。
     *
     * @return 居中下右。
     */
    public boolean isCenterBottomRight() {
        return this.frameStructures.contains(FrameStructure.CenterBottomRightSpace);
    }

    /**
     * 判断是否左上角。
     *
     * @return 左上角。
     */
    public boolean isTopLeftCorner() {
        return this.frameStructures.contains(FrameStructure.TopLeftCorner);
    }

    /**
     * 判断是否右上角。
     *
     * @return 右上角。
     */
    public boolean isTopRightCorner() {
        return this.frameStructures.contains(FrameStructure.TopRightCorner);
    }

    /**
     * 判断是否左下角。
     *
     * @return 左下角。
     */
    public boolean isBottomLeftCorner() {
        return this.frameStructures.contains(FrameStructure.BottomLeftCorner);
    }

    /**
     * 判断是否右下角。
     *
     * @return 右下角。
     */
    public boolean isBottomRightCorner() {
        return this.frameStructures.contains(FrameStructure.BottomRightCorner);
    }

    public boolean isNotInCorner() {
        return this.frameStructures.contains(FrameStructure.NotInCorner) ||
                (!this.isTopLeftCorner() && !this.isTopRightCorner() && !this.isBottomLeftCorner() && !this.isBottomRightCorner());
    }

    public boolean isInCorner() {
        return this.isTopLeftCorner() || this.isTopRightCorner() || this.isBottomLeftCorner() ||
                this.isBottomRightCorner();
    }
}
