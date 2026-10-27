/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import cube.common.entity.Group;
import cube.common.entity.Message;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 新消息事件。
 */
public class SubmitMessagesEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "SubmitMessages";

    private Group group;

    private Contact partner;

    private List<Message> messages;

    public SubmitMessagesEvent(Contact account, Group group, List<Message> messages) {
        super(NAME, account);
        this.group = group;
        this.messages = messages;
    }

    public SubmitMessagesEvent(Contact account, Contact partner, List<Message> messages) {
        super(NAME, account);
        this.partner = partner;
        this.messages = messages;
    }

    public SubmitMessagesEvent(JSONObject json) {
        super(json);

        if (json.has("group")) {
            this.group = new Group(json.getJSONObject("group"));
        }

        if (json.has("partner")) {
            this.partner = new Contact(json.getJSONObject("partner"));
        }

        this.messages = new ArrayList<>();
        JSONArray array = json.getJSONArray("messages");
        for (int i = 0; i < array.length(); ++i) {
            this.messages.add(new Message(array.getJSONObject(i)));
        }
    }

    /**
     * 获取组。
     *
     * @return 组。
     */
    public Group getGroup() {
        return this.group;
    }

    /**
     * 获取对端联系人。
     *
     * @return 联系人。
     */
    public Contact getPartner() {
        return this.partner;
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
            json.put("group", this.group.toCompactJSON());
        }

        if (null != this.partner) {
            json.put("partner", this.partner.toCompactJSON());
        }

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
            json.put("group", this.group.toCompactJSON());
        }

        if (null != this.partner) {
            json.put("partner", this.partner.toCompactJSON());
        }

        JSONArray array = new JSONArray();
        for (Message message : this.messages) {
            array.put(message.toJSON());
        }
        json.put("messages", array);

        return json;
    }
}
