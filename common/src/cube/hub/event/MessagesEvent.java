/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import cube.common.entity.Group;
import cube.common.entity.Message;
import cube.hub.data.DataHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息数据事件。
 */
public class MessagesEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "Messages";

    private Contact partner;

    private Group group;

    private int beginIndex;

    private int endIndex;

    private List<Message> messages;

    public MessagesEvent(Group group, int beginIndex, int endIndex, List<Message> messages) {
        super(NAME);
        this.group = group;
        this.beginIndex = beginIndex;
        this.endIndex = endIndex;
        this.messages = messages;
    }

    public MessagesEvent(Contact partner, int beginIndex, int endIndex, List<Message> messages) {
        super(NAME);
        this.partner = partner;
        this.beginIndex = beginIndex;
        this.endIndex = endIndex;
        this.messages = messages;
    }

    public MessagesEvent(JSONObject json) {
        super(json);
        if (json.has("group")) {
            this.group = new Group(json.getJSONObject("group"));
        }
        else if (json.has("partner")) {
            this.partner = new Contact(json.getJSONObject("partner"));
        }

        this.beginIndex = json.getInt("begin");
        this.endIndex = json.getInt("end");

        this.messages = new ArrayList<>();
        JSONArray array = json.getJSONArray("messages");
        for (int i = 0; i < array.length(); ++i) {
            this.messages.add(new Message(array.getJSONObject(i)));
        }
    }

    /**
     * 获取消息列表。
     *
     * @return 消息列表。
     */
    public List<Message> getMessages() {
        return this.messages;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        if (null != this.group) {
            json.put("group", this.group.toJSON());
        }
        else if (null != this.partner) {
            json.put("partner",
                    DataHelper.filterContactAvatarFileLabel(this.partner.toJSON()));
        }
        json.put("begin", this.beginIndex);
        json.put("end", this.endIndex);

        JSONArray array = new JSONArray();
        for (Message message : this.messages) {
            array.put(message.toJSON());
        }
        json.put("messages", array);
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
        if (null != this.group) {
            json.put("group",
                    DataHelper.filterContactAvatarFileLabel(this.group.toCompactJSON()));
        }
        else if (null != this.partner) {
            json.put("partner",
                    DataHelper.filterContactAvatarFileLabel(this.partner.toCompactJSON()));
        }
        json.put("begin", this.beginIndex);
        json.put("end", this.endIndex);

        JSONArray array = new JSONArray();
        for (Message message : this.messages) {
            array.put(message.toJSON());
        }
        json.put("messages", array);
        return json;
    }
}
