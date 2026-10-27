/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.material.house;

import cube.aigc.psychology.material.Label;
import cube.aigc.psychology.material.Thing;
import org.json.JSONObject;

/**
 * 窗户。
 */
public class Window extends Thing {

    private boolean open = false;

    public Window(JSONObject json) {
        super(json);
        if (Label.HouseWindowOpened == this.paintingLabel) {
            this.open = true;
        }
    }

    /**
     * 判断是否打开。
     *
     * @return 打开。
     */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * 判断是否涂鸦。
     *
     * @return 涂鸦。
     */
    @Override
    public boolean isDoodle() {
        if (super.isDoodle()) {
            return true;
        }

        if (this.texture.avg >= 8 && this.texture.max >= 30) {
            return true;
        }

        return false;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("open", this.open);
        return json;
    }
}
