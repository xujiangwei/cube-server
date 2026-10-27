/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 会议邀请信息。
 */
public class Invitation implements JSONable {

    /**
     * 被邀请人 ID 。
     */
    protected Long id;

    /**
     * 被邀请人。
     */
    protected String invitee;

    /**
     * 被邀请人显示的名称。
     */
    protected String displayName;

    /**
     * 是否接受邀请。
     */
    protected boolean accepted = false;

    /**
     * 接受邀请时间。
     */
    protected long acceptionTime;

    /**
     * 构造函数。
     * @param id id。
     * @param invitee invitee。
     * @param displayName displayName。
     */
    public Invitation(Long id, String invitee, String displayName, boolean accepted, long acceptionTime) {
        this.id = id;
        this.invitee = invitee;
        this.displayName = displayName;
        this.accepted = accepted;
        this.acceptionTime = acceptionTime;
    }

    public Invitation(JSONObject json) {
        this.id = json.getLong("id");
        this.invitee = json.getString("invitee");
        this.displayName = json.getString("displayName");
        this.accepted = json.getBoolean("accepted");
        this.acceptionTime = json.getLong("acceptionTime");
    }

    /**
     * 获取ID。
     *
     * @return ID。
     */
    public Long getId() {
        return this.id;
    }

    /**
     * 获取被邀请者。
     *
     * @return 被邀请者。
     */
    public String getInvitee() {
        return this.invitee;
    }

    /**
     * 获取显示名称。
     *
     * @return 显示名称。
     */
    public String getDisplayName() {
        return this.displayName;
    }

    /**
     * 获取已接受。
     *
     * @return 已接受。
     */
    public boolean getAccepted() {
        return this.accepted;
    }

    /**
     * 设置已接受。
     *
     * @param value 已接受。
     */
    public void setAccepted(boolean value) {
        this.accepted = value;
        this.acceptionTime = System.currentTimeMillis();
    }

    /**
     * 获取受理时间。
     *
     * @return 受理时间。
     */
    public long getAcceptionTime() {
        return this.acceptionTime;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id.longValue());
        json.put("invitee", this.invitee);
        json.put("displayName", this.displayName);
        json.put("accepted", this.accepted);
        json.put("acceptionTime", this.acceptionTime);
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
