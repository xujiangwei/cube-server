/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.material.person;

import cube.aigc.psychology.material.Thing;
import org.json.JSONObject;

/**
 * 嘴。
 */
public class Mouth extends Thing {

    public Mouth(JSONObject json) {
        super(json);
    }

    /**
     * 判断是否打开。
     *
     * @return 打开。
     */
    public boolean isOpen() {
        // 判断纵横比
        double ar = this.box.getAspectRatio();
        return ar <= 1.13;
    }

    /**
     * 一字型。
     * @return 满足条件返回 {@code true} 。
     */
    public boolean isStraight() {
        // 判断纵横比
        double ar = this.box.getAspectRatio();
        return ar > 3.33;
    }
}
