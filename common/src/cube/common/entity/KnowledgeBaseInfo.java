/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 知识库信息。
 */
public class KnowledgeBaseInfo implements JSONable {

    /** 联系人 ID。 */
    public long contactId;

    /** 名称。 */
    public String name;

    /** 显示名称。 */
    public String displayName;

    /** 分类。 */
    public String category;

    /** 单元 ID。 */
    public long unitId;

    /** 存储大小。 */
    public long storeSize;

    /** 时间戳。 */
    public long timestamp;

    public KnowledgeBaseInfo(long contactId, String name, String displayName, String category,
                             long unitId, long storeSize, long timestamp) {
        this.contactId = contactId;
        this.name = name;
        this.displayName = displayName;
        this.category = category;
        this.unitId = unitId;
        this.storeSize = storeSize;
        this.timestamp = timestamp;
    }

    public KnowledgeBaseInfo(JSONObject json) {
        this.contactId = json.getLong("contactId");
        this.name = json.getString("name");
        this.displayName = json.getString("displayName");
        this.unitId = json.getLong("unitId");
        this.storeSize = json.getLong("storeSize");
        this.timestamp = json.getLong("timestamp");
        if (json.has("category") && json.getString("category").trim().length() > 0) {
            this.category = json.getString("category");
        }
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof KnowledgeBaseInfo) {
            KnowledgeBaseInfo other = (KnowledgeBaseInfo) obj;
            return other.contactId == this.contactId && other.name.equals(this.name);
        }
        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 哈希值。
     */
    @Override
    public int hashCode() {
        return (new Long(this.contactId)).hashCode() * 7 - this.name.hashCode();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("contactId", this.contactId);
        json.put("name", this.name);
        json.put("displayName", this.displayName);
        json.put("unitId", this.unitId);
        json.put("storeSize", this.storeSize);
        json.put("timestamp", this.timestamp);
        if (null != this.category && this.category.length() > 0) {
            json.put("category", this.category);
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
