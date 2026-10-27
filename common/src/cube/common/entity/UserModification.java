/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 用户修改项。
 *
 * <p>描述对用户资料的字段级修改。</p>
 */
public class UserModification implements JSONable {

    /** ID。 */
    public final long id;

    /** 显示名称。 */
    public String displayName;

    public UserModification(long id) {
        this.id = id;
    }

    public UserModification(JSONObject json) {
        this.id = json.getLong("id");
        if (json.has("displayName")) {
            this.displayName = json.getString("displayName");
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        if (null != this.displayName) {
            json.put("displayName", this.displayName);
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
