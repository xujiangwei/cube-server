/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 事件结果。
 */
public class EventResult implements JSONable {

    private Event event;

    public EventResult(Event event) {
        this.event = event;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.event.toCompactJSON();
        if (null != this.event.resultResource) {
            json.put("result", this.event.resultResource.toJSON());
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
