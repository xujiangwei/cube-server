/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.ConversationType;
import cube.common.entity.Message;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮询会话消息。
 */
public class RollPollingEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "RollPolling";

    private ConversationType conversationType;

    private String conversationName;

    private List<Message> messageList;

    public RollPollingEvent(ConversationType conversationType,
                            String conversationName, List<Message> messageList) {
        super(NAME);
        this.conversationType = conversationType;
        this.conversationName = conversationName;
        this.messageList = messageList;
    }

    public RollPollingEvent(JSONObject json) {
        super(json);
        this.conversationType = ConversationType.parse(json.getInt("conversationType"));
        this.conversationName = json.getString("conversationName");

        this.messageList = new ArrayList<>();
        JSONArray list = json.getJSONArray("messageList");
        for (int i = 0; i < list.length(); ++i) {
            Message message = new Message(list.getJSONObject(i));
            this.messageList.add(message);
        }
    }

    /**
     * 获取会话类型。
     *
     * @return 会话类型。
     */
    public ConversationType getConversationType() {
        return this.conversationType;
    }

    /**
     * 获取会话名称。
     *
     * @return 会话名称。
     */
    public String getConversationName() {
        return this.conversationName;
    }

    /**
     * 设置消息列表。
     *
     * @param list 消息列表。
     */
    public void setMessageList(List<Message> list) {
        this.messageList = list;
    }

    /**
     * 获取消息列表。
     *
     * @return 消息列表。
     */
    public List<Message> getMessageList() {
        return this.messageList;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("conversationType", this.conversationType.code);
        json.put("conversationName", this.conversationName);

        JSONArray array = new JSONArray();
        for (Message message : this.messageList) {
            array.put(message.toJSON());
        }
        json.put("messageList", array);

        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = super.toCompactJSON();
        json.put("conversationType", this.conversationType.code);
        json.put("conversationName", this.conversationName);

        JSONArray array = new JSONArray();
        for (Message message : this.messageList) {
            array.put(message.toJSON());
        }
        json.put("messageList", array);

        return json;
    }
}
