/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.hub.data.ChannelCode;
import org.json.JSONObject;

/**
 * 应答。
 */
public class AckEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "Ack";

    private String ackSignal;

    public AckEvent(ChannelCode channelCode, String ackSignal) {
        super(NAME);
        setCode(channelCode.code);
        this.ackSignal = ackSignal;
    }

    public AckEvent(long sn) {
        super(sn, NAME);
    }

    public AckEvent(JSONObject json) {
        super(json);
        if (json.has("ackSignal")) {
            this.ackSignal = json.getString("ackSignal");
        }
    }

    /**
     * 获取应答信号名。
     *
     * @return 信号名。
     */
    public String getAckSignal() {
        return this.ackSignal;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        if (null != this.ackSignal) {
            json.put("ackSignal", this.ackSignal);
        }
        return json;
    }
}
