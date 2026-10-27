/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cube.hub.data.ChannelCode;
import org.json.JSONObject;

/**
 * 频道码信令。
 */
public class ChannelCodeSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "ChannelCode";

    private ChannelCode channelCode;

    public ChannelCodeSignal(String code) {
        super(NAME);
        setCode(code);
    }

    public ChannelCodeSignal(ChannelCode channelCode) {
        super(NAME);
        this.channelCode = channelCode;
    }

    public ChannelCodeSignal(JSONObject json) {
        super(json);
        if (json.has("channelCode")) {
            this.channelCode = new ChannelCode(json.getJSONObject("channelCode"));
        }
    }

    /**
     * 获取通道码。
     *
     * @return 通道码。
     */
    public ChannelCode getChannelCode() {
        return this.channelCode;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        if (null != this.channelCode) {
            json.put("channelCode", this.channelCode.toJSON());
        }
        return json;
    }
}
