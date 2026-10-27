/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 联系人分区参与人。
 */
public class ContactZoneParticipant implements JSONable {

    /** ID。 */
    public final Long id;

    /** 时间戳。 */
    public long timestamp;

    /** 类型。 */
    public final ContactZoneParticipantType type;

    /** 邀请者 ID。 */
    public final Long inviterId;

    /** 附言。 */
    public final String postscript;

    /** 状态。 */
    public ContactZoneParticipantState state;

    /** 关联联系人。 */
    public AbstractContact linkedContact;

    public ContactZoneParticipant(JSONObject json) {
        this(json.getLong("id"), ContactZoneParticipantType.parse(json.getInt("type")),
                json.getLong("timestamp"),
                json.getLong("inviterId"),
                json.has("postscript") ? json.getString("postscript") : null,
                ContactZoneParticipantState.parse(json.getInt("state")));

        if (json.has("linkedContact")) {
            if (this.type == ContactZoneParticipantType.Contact) {
                this.linkedContact = new Contact(json.getJSONObject("linkedContact"));
            }
            else if (this.type == ContactZoneParticipantType.Group) {
                this.linkedContact = new Group(json.getJSONObject("linkedContact"));
            }
        }
    }

    public ContactZoneParticipant(Long id, ContactZoneParticipantType type, long timestamp,
                                  Long inviterId, String postscript, ContactZoneParticipantState state) {
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
        this.inviterId = inviterId;
        this.postscript = (null != postscript) ? postscript : "";
        this.state = state;
    }

    /**
     * 构造函数。
     * @param linkedContact linkedContact。
     */
    public ContactZoneParticipant(AbstractContact linkedContact) {
        this(Utils.generateSerialNumber(),
                linkedContact instanceof Contact ?
                        ContactZoneParticipantType.Contact : ContactZoneParticipantType.Group,
                System.currentTimeMillis(),
                0L,
                "",
                ContactZoneParticipantState.Normal);
        this.linkedContact = linkedContact;
    }

    /**
     * 设置连接的联系人实例。
     * @param linkedContact linkedContact。
     */
    public void setLinkedContact(AbstractContact linkedContact) {
        this.linkedContact = linkedContact;
    }

    /**
     * 获取关联联系人。
     *
     * @return 关联联系人。
     */
    public AbstractContact getLinkedContact() {
        return this.linkedContact;
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param object 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (null != object && object instanceof ContactZoneParticipant) {
            ContactZoneParticipant other = (ContactZoneParticipant) object;
            if (other.id.equals(this.id) && other.type.code == this.type.code) {
                return true;
            }
        }

        return false;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();
        json.put("postscript", this.postscript);

        if (null != this.linkedContact) {
            json.put("linkedContact", this.linkedContact.toJSON());
        }

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
        JSONObject json = new JSONObject();
        json.put("id", this.id.longValue());
        json.put("type", this.type.code);
        json.put("timestamp", this.timestamp);
        json.put("state", this.state.code);
        json.put("inviterId", this.inviterId.longValue());
        return json;
    }
}
