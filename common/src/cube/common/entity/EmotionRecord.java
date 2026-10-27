/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 情绪记录。
 *
 * <p>一次情绪识别的结果，标明来源是语音还是聊天。</p>
 */
public class EmotionRecord extends Entity {

    /** 来源：语音。 */
    public final static String SOURCE_SPEECH = "speech";

    /** 来源：聊天。 */
    public final static String SOURCE_CHAT = "chat";

    /** 联系人 ID。 */
    public final long contactId;

    /** 情绪。 */
    public final Emotion emotion;

    /** 来源。 */
    public final String source;

    /** 来源数据。 */
    public JSONObject sourceData;

    public EmotionRecord(long id, long contactId, Emotion emotion, long timestamp, String source) {
        super(id, "", timestamp);
        this.contactId = contactId;
        this.emotion = emotion;
        this.source = source;
    }

    public EmotionRecord(long contactId, Emotion emotion, String source) {
        super();
        this.contactId = contactId;
        this.emotion = emotion;
        this.source = source;
    }

    public EmotionRecord(JSONObject json) {
        super(json);
        this.contactId = json.getLong("contactId");
        this.emotion = Emotion.parse(json.getString("emotion"));
        this.source = json.getString("source");
        if (json.has("data")) {
            this.sourceData = json.getJSONObject("data");
        }
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
        json.put("emotion", this.emotion.name());
        json.put("source", this.source);
        if (null != this.sourceData) {
            json.put("data", this.sourceData);
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
        JSONObject json = super.toCompactJSON();
        json.put("contactId", this.contactId);
        json.put("emotion", this.emotion.name());
        json.put("source", this.source);
        return json;
    }
}
