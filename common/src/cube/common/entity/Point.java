/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 坐标点。
 *
 * <p>二维平面上的一个点。</p>
 */
public class Point extends Entity {

    private long contactId;

    private int point;

    private String source;

    private String comment;

    public Point(String domain, long timestamp, long contactId, int point, String source, String comment) {
        super(0L, domain, timestamp);
        this.contactId = contactId;
        this.point = point;
        this.source = source;
        this.comment = comment;
    }

    public Point(long id, String domain, long timestamp, long contactId, int point, String source, String comment) {
        super(id, domain, timestamp);
        this.contactId = contactId;
        this.point = point;
        this.source = source;
        this.comment = comment;
    }

    public Point(JSONObject json) {
        super(json);
        this.contactId = json.getLong("contactId");
        this.point = json.getInt("point");
        this.source = json.getString("source");
        this.comment = json.getString("comment");
    }

    /**
     * 获取联系人 ID。
     *
     * @return 联系人 ID。
     */
    public long getContactId() {
        return this.contactId;
    }

    /**
     * 获取点。
     *
     * @return 点。
     */
    public int getPoint() {
        return this.point;
    }

    /**
     * 获取来源。
     *
     * @return 来源。
     */
    public String getSource() {
        return this.source;
    }

    /**
     * 获取评论。
     *
     * @return 评论。
     */
    public String getComment() {
        return this.comment;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contactId", this.contactId);
        json.put("point", this.point);
        json.put("source", this.source);
        json.put("comment", this.comment);
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
