/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 知识文档内容分段。
 */
public class KnowledgeSegment implements JSONable {

    /** 序列号。 */
    public final long sn;

    /** 文档 ID。 */
    public final long docId;

    /** UUID。 */
    public final String uuid;

    /** 内容。 */
    public String content;

    /** 分类。 */
    public String category;

    public KnowledgeSegment(long sn, long docId, String uuid, String content, String category) {
        this.sn = sn;
        this.docId = docId;
        this.uuid = uuid;
        this.content = content;
        this.category = category;
    }

    public KnowledgeSegment(JSONObject json) {
        if (json.has("sn")) {
            this.sn = json.getLong("sn");
        }
        else {
            this.sn = 0;
        }

        this.docId = json.getLong("docId");
        this.uuid = json.getString("uuid");
        this.content = json.getString("content");

        if (json.has("category")) {
            this.category = json.getString("category");
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
        json.put("sn", this.sn);
        json.put("docId", this.docId);
        json.put("uuid", this.uuid);
        json.put("content", this.content);
        if (null != this.category) {
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
