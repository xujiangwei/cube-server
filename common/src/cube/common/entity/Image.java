/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.util.FileType;

/**
 * 图像实体。
 */
public class Image {

    /** 类型。 */
    public final FileType type;

    /** 宽度。 */
    public final int width;

    /** 高度。 */
    public final int height;

    public Image(FileType type, int width, int height) {
        this.type = type;
        this.width = width;
        this.height = height;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        return this.type.getPreferredExtension() + " " + this.width + "x" + this.height;
    }
}
