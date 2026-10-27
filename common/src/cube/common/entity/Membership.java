/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 会员信息。
 * 会员ID就是联系人ID。
 */
public class Membership extends Entity {

    /**
     * 普通会员。
     */
    public final static String TYPE_ORDINARY = "ordinary";

    /**
     * 资深会员。
     */
    public final static String TYPE_SENIOR = "senior";

    /**
     * 高级会员。
     */
    public final static String TYPE_PREMIUM = "premium";

    /**
     * 至尊会员。
     */
    public final static String TYPE_SUPREME = "supreme";

    /**
     * 正常状态。
     */
    public final static int STATE_NORMAL = 0;

    /**
     * 无效状态。
     */
    public final static int STATE_INVALID = 1;

    /**
     * 禁用状态。
     */
    public final static int STATE_FORBIDDEN = 9;

    /** 名称。 */
    public String name;

    /** 类型。 */
    public String type;

    /** 状态。 */
    public int state;

    /** 时长。 */
    public long duration;

    /** 描述。 */
    public String description;

    /** 上下文。 */
    public JSONObject context;

    public Membership(long contactId, String domain, String name, String type, int state,
                      long timestamp, long duration, String description, JSONObject context) {
        super(contactId, domain, timestamp);
        this.name = name;
        this.type = type;
        this.state = state;
        this.duration = duration;
        this.description = description;
        this.context = context;
    }

    public Membership(JSONObject json) {
        super(json);
        this.name = json.getString("name");
        this.type = json.getString("type");
        this.state = json.getInt("state");
        this.duration = json.getLong("duration");
        this.description = json.getString("description");
        if (json.has("context")) {
            this.context = json.getJSONObject("context");
        }
    }

    /**
     * 判断是否普通等级。
     *
     * @return 普通等级。
     */
    public boolean isOrdinaryRank() {
        return this.type.equalsIgnoreCase(Membership.TYPE_ORDINARY);
    }

    /**
     * 判断是否资深等级。
     *
     * @return 资深等级。
     */
    public boolean isSeniorRank() {
        return this.type.equalsIgnoreCase(Membership.TYPE_SENIOR);
    }

    /**
     * 判断是否高级等级。
     *
     * @return 高级等级。
     */
    public boolean isPremiumRank() {
        return this.type.equalsIgnoreCase(Membership.TYPE_PREMIUM);
    }

    /**
     * 判断是否至尊等级。
     *
     * @return 至尊等级。
     */
    public boolean isSupremeRank() {
        return this.type.equalsIgnoreCase(Membership.TYPE_SUPREME);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("name", this.name);
        json.put("type", this.type);
        json.put("state", this.state);
        json.put("duration", this.duration);
        json.put("description", this.description);
        if (null != this.context) {
            json.put("context", this.context);
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
        return this.toJSON();
    }
}
