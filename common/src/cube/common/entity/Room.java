/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 会议房间。
 */
public class Room implements JSONable {

    /**
     * 最大允许参与人数。
     */
    protected int maxParticipants = 50;

    /**
     * 参与者群组。
     */
    protected Group participantGroup;

    /**
     * 参与者群组 ID 。
     */
    protected Long participantGroupId = 0L;

    /**
     * 通信场域。
     */
    protected CommField commField;

    /**
     * 通信场域 ID 。
     */
    protected Long commFieldId = 0L;


    /**
     * 构造函数。
     * @param participantGroupId participantGroupId。
     * @param commFieldId commFieldId。
     */
    public Room(Long participantGroupId, Long commFieldId) {
        this.participantGroupId = participantGroupId;
        this.commFieldId = commFieldId;
    }

    /**
     * 构造函数。
     * @param json JSON 数据。
     */
    public Room(JSONObject json) {
        this.maxParticipants = json.getInt("maxParticipants");
        this.participantGroupId = json.getLong("participantGroupId");
        this.commFieldId = json.getLong("commFieldId");
    }

    /**
     * 获取参与者上限。
     *
     * @return 参与者上限。
     */
    public int getMaxParticipants() {
        return this.maxParticipants;
    }

    /**
     * 设置参与者上限。
     *
     * @param max 参与者上限。
     */
    public void setMaxParticipants(int max) {
        this.maxParticipants = max;
    }

    /**
     * 获取参与者分组 ID。
     *
     * @return 参与者分组 ID。
     */
    public Long getParticipantGroupId() {
        return this.participantGroupId;
    }

    /**
     * 设置参与者分组。
     *
     * @param group 参与者分组。
     */
    public void setParticipantGroup(Group group) {
        this.participantGroup = group;
        this.participantGroupId = group.getId();
    }

    /**
     * 获取参与者分组。
     *
     * @return 参与者分组。
     */
    public Group getParticipantGroup() {
        return this.participantGroup;
    }

    /**
     * 获取通用字段 ID。
     *
     * @return 通用字段 ID。
     */
    public Long getCommFieldId() {
        return this.commFieldId;
    }

    /**
     * 设置通用字段。
     *
     * @param commField 通用字段。
     */
    public void setCommField(CommField commField) {
        this.commField = commField;
        this.commFieldId = commField.getId();
    }

    /**
     * 获取通用字段。
     *
     * @return 通用字段。
     */
    public CommField getCommField() {
        return this.commField;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("maxParticipants", this.maxParticipants);
        json.put("participantGroupId", this.participantGroupId.longValue());
        json.put("commFieldId", this.commFieldId.longValue());
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
        return this.toJSON();
    }
}
