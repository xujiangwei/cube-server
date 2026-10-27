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
 * 窗帘。
 */
public class Curtain extends Thing {

    private boolean open = false;

    public Curtain(JSONObject json) {
        super(json);

        if (Label.HouseCurtainOpened == this.paintingLabel) {
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
