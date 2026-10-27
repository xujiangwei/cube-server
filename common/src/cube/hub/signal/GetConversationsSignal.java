/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 获取消息信令。
 */
public class GetConversationsSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "GetConversations";

    private int numConversations = 10;

    private int numRecentMessages = 5;

    public GetConversationsSignal(String channelCode) {
        super(NAME);
        setCode(channelCode);
    }

    public GetConversationsSignal(JSONObject json) {
        super(json);
        if (json.has("numConversations")) {
            this.numConversations = json.getInt("numConversations");
        }
        if (json.has("numRecentMessages")) {
            this.numRecentMessages = json.getInt("numRecentMessages");
        }
    }

    /**
     * 获取数量会话。
     *
     * @return 数量会话。
     */
    public int getNumConversations() {
        return this.numConversations;
    }

    /**
     * 设置数量会话。
     *
     * @param value 数量会话。
     */
    public void setNumConversations(int value) {
        this.numConversations = value;
    }

    /**
     * 获取数量最近消息列表。
     *
     * @return 数量最近消息列表。
     */
    public int getNumRecentMessages() {
        return this.numRecentMessages;
    }

    /**
     * 设置数量最近消息列表。
     *
     * @param value 数量最近消息列表。
     */
    public void setNumRecentMessages(int value) {
        this.numRecentMessages = value;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
         JSONObject json = super.toJSON();
         json.put("numConversations", this.numConversations);
         json.put("numRecentMessages", this.numRecentMessages);
         return json;
    }
}
