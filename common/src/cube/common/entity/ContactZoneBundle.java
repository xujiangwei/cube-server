/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 联系人分区操作绑定数据。
 */
public class ContactZoneBundle implements JSONable {

    /** 动作：新增。 */
    public final static int ACTION_ADD = 1;

    /** 动作：移除。 */
    public final static int ACTION_REMOVE = 0;

    /** 动作：更新。 */
    public final static int ACTION_UPDATE = 9;

    private ContactZone zone;

    private ContactZoneParticipant participant;

    private int action = ACTION_UPDATE;

    public ContactZoneBundle(ContactZone zone, ContactZoneParticipant participant, int action) {
        this.zone = zone;
        this.participant = participant;
        this.action = action;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("zone", this.zone.toCompactJSON());
        json.put("participant", this.participant.toJSON());
        json.put("action", this.action);
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
        json.put("zone", this.zone.toCompactJSON());
        json.put("participant", this.participant.toCompactJSON());
        json.put("action", this.action);
        return json;
    }
}
