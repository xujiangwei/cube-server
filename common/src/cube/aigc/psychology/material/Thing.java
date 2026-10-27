/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.material;

import cube.common.entity.Material;
import cube.vision.BoundingBox;
import cube.vision.Box;
import org.json.JSONObject;

import java.util.List;

/**
 * 素材。
 */
public abstract class Thing extends Material {

    /** 绘画标签。 */
    protected Label paintingLabel;

    /**
     * 是否是反向推理来的元素。
     */
    protected boolean backwardReasoning = false;

    public Thing(Thing other) {
        super(other);
        this.paintingLabel = other.paintingLabel;
        this.backwardReasoning = other.backwardReasoning;
    }

    public Thing(String label, BoundingBox boundingBox, Box box) {
        super(label, boundingBox, box);
        this.paintingLabel = Label.parse(this.label);
    }

    public Thing(Material material) {
        super(material);
        this.paintingLabel = Label.parse(material.label);
    }

    public Thing(Label paintingLabel) {
        super(paintingLabel.name);
        this.paintingLabel = paintingLabel;
    }

    public Thing(JSONObject json) {
        super(json);
        this.paintingLabel = Label.parse(this.label);
    }

    /**
     * 获取标签。
     *
     * @return 标签。
     */
    public Label getLabel() {
        return this.paintingLabel;
    }

    /**
     * 判断是否反向推理。
     *
     * @return 反向推理。
     */
    public boolean isBackwardReasoning() {
        return this.backwardReasoning;
    }

    /**
     * 获取宽度。
     *
     * @return 宽度。
     */
    public int getWidth() {
        return this.box.width;
    }

    /**
     * 获取高度。
     *
     * @return 高度。
     */
    public int getHeight() {
        return this.box.height;
    }

    /**
     * 统计组件列表。
     *
     * @return 数量。
     */
    public int numComponents() {
        return 0;
    }

    /**
     * 获取最大面积物件。
     *
     * @param list 最大面积物件。
     * @return 最大面积物件。
     */
    protected <T> T getMaxAreaThing(List<T> list) {
        if (list.isEmpty()) {
            return null;
        }
        int max = 0;
        T result = null;
        for (T t : list) {
            if (t instanceof Thing) {
                Thing thing = (Thing) t;
                int area = thing.area;
                if (area > max) {
                    max = area;
                    result = t;
                }
            }
        }
        return result;
    }

    /**
     * 获取子物件。
     *
     * @param label 子物件。
     * @return 子物件。
     */
    public List<Thing> getSubThings(Label label) {
        return null;
    }
}
