/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util.calc;

public class AreaDesc {

    /** 面积。 */
    public final double area;

    /** 结构。 */
    public final FrameStructure structure;

    public AreaDesc(double area, FrameStructure structure) {
        this.area = area;
        this.structure = structure;
    }
}
