/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.event;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 事件对象。
 *
 * <p>承载事件名称、来源与载荷，用于在事件中心内传递流与分段相关的通知。</p>
 */
public class Event implements JSONable {

    /** 事件名称，取值见 {@link Events} 。 */
    public final String name;

    /** 事件来源标识。 */
    public final String source;

    /** 事件载荷。 */
    public final JSONObject payload;

    public Event(JSONObject json) {
        this.name = json.getString("name");
        this.source = json.getString("source");
        this.payload = json.getJSONObject("payload");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return 事件 JSON 。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        json.put("source", this.source);
        json.put("payload", this.payload);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 一致。</p>
     *
     * @return 事件 JSON 。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
