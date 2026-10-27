/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import cube.common.entity.Group;
import cube.hub.data.wechat.PlainMessage;
import org.json.JSONObject;

/**
 * 发送消息事件。
 */
public class SendMessageEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "SendMessage";

    private Contact partner;

    private Group group;

    private PlainMessage plainMessage;

    public SendMessageEvent(Contact account, Contact partner, PlainMessage plainMessage) {
        super(NAME, account);
        this.partner = partner;
        this.plainMessage = plainMessage;
    }

    public SendMessageEvent(Contact account, Group group, PlainMessage plainMessage) {
        super(NAME, account);
        this.group = group;
        this.plainMessage = plainMessage;
    }

    public SendMessageEvent(JSONObject json) {
        super(json);

        if (json.has("partner")) {
            this.partner = new Contact(json.getJSONObject("partner"));
        }

        if (json.has("group")) {
            this.group = new Group(json.getJSONObject("group"));
        }

        if (json.has("plainMessage")) {
            this.plainMessage = new PlainMessage(json.getJSONObject("plainMessage"));
        }
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
     * 获取组。
     *
     * @return 组。
     */
    public Group getGroup() {
        return this.group;
    }

    /**
     * 获取明文消息。
     *
     * @return 消息对象。
     */
    public PlainMessage getPlainMessage() {
        return this.plainMessage;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        return make(json);
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
        return make(json);
    }

    private JSONObject make(JSONObject json) {
        if (null != this.partner) {
            json.put("partner", this.partner.toCompactJSON());
        }

        if (null != this.group) {
            json.put("group", this.group.toCompactJSON());
        }

        if (null != this.plainMessage) {
            json.put("plainMessage", this.plainMessage.toJSON());
        }

        return json;
    }
}
