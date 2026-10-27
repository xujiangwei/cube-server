/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 联系人偏好设置。
 */
public class ContactPreference implements JSONable {

    private long contactId;

    private JSONArray models;

    public ContactPreference(long contactId, JSONArray models) {
        this.contactId = contactId;
        this.models = models;
    }

    public ContactPreference(JSONObject json) {
        this.contactId = json.getLong("contactId");
        this.models = json.getJSONArray("models");
    }

    /** 获取偏好模型列表。 */
    public JSONArray getModels() {
        return this.models;
    }

    /**
     * 判断是否已偏好指定模型。
     *
     * @param modelName 模型名称。
     * @return 已偏好返回 {@code true} 。
     */
    public boolean containsModel(String modelName) {
        for (int i = 0; i < this.models.length(); ++i) {
            String name = this.models.getString(i);
            if (name.equalsIgnoreCase(modelName)) {
                return true;
            }
        }
        return false;
    }

    /** 序列化为 JSON 。 */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("contactId", this.contactId);
        json.put("models", this.models);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
