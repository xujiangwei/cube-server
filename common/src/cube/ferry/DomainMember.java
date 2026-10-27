/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cube.common.entity.Entity;
import org.json.JSONObject;

/**
 * 域成员。
 */
public class DomainMember extends Entity {
    
    /** 正常。 */
    public final static int NORMAL = 0;

    /** 成员状态：已退出。 */
    public final static int QUIT = 1;

    /** 成员状态：已禁用。 */
    public final static int DISABLED = 2;

    private Long contactId;

    private JoinWay joinWay;

    private long joinTime;

    private Role role;

    private int state;

    public DomainMember(String domainName, Long contactId, JoinWay joinWay, long joinTime, Role role, int state) {
        super(contactId, domainName);
        this.contactId = contactId;
        this.joinWay = joinWay;
        this.joinTime = joinTime;
        this.role = role;
        this.state = state;
    }

    public DomainMember(JSONObject json) {
        super(json);
        this.contactId = json.getLong("contactId");
        this.joinWay = JoinWay.parse(json.getInt("joinWay"));
        this.joinTime = json.getLong("joinTime");
        this.role = Role.parse(json.getInt("role"));
        this.state = json.getInt("state");
    }

    /**
     * 获取联系人 ID。
     *
     * @return 联系人 ID。
     */
    public Long getContactId() {
        return this.contactId;
    }

    /**
     * 获取拼接时间。
     *
     * @return 拼接时间。
     */
    public long getJoinTime() {
        return this.joinTime;
    }

    /**
     * 设置加入方式。
     *
     * @param joinWay 加入方式。
     */
    public void setJoinWay(JoinWay joinWay) {
        this.joinWay = joinWay;
    }

    /**
     * 获取加入方式。
     *
     * @return 加入方式。
     */
    public JoinWay getJoinWay() {
        return this.joinWay;
    }

    /**
     * 设置角色。
     *
     * @param role 角色。
     */
    public void setRole(Role role) {
        this.role = role;
    }

    /**
     * 获取角色。
     *
     * @return 角色。
     */
    public Role getRole() {
        return this.role;
    }

    /**
     * 设置状态。
     *
     * @param state 状态。
     */
    public void setState(int state) {
        this.state = state;
    }

    /**
     * 获取状态。
     *
     * @return 状态。
     */
    public int getState() {
        return this.state;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contactId", this.contactId.longValue());
        json.put("joinWay", this.joinWay.code);
        json.put("joinTime", this.joinTime);
        json.put("role", this.role.code);
        json.put("state", this.state);
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
