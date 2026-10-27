/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.Sentiment;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 说话人分段指标。
 *
 * <p>按分段统计的说话人指标。</p>
 */
public class SpeakerSegmentIndicator implements JSONable {

    /** 轨迹。 */
    public final String track;

    /** 节奏。 */
    public final int rhythm;

    /** 情感。 */
    public final Sentiment sentiment;

    public SpeakerSegmentIndicator(String track, int rhythm, Sentiment sentiment) {
        this.track = track;
        this.rhythm = rhythm;
        this.sentiment = sentiment;
    }

    public SpeakerSegmentIndicator(JSONObject json) {
        this.track = json.getString("track");
        this.rhythm = json.getInt("rhythm");
        this.sentiment = Sentiment.parse(json.getString("sentiment"));
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("track", this.track);
        json.put("rhythm", this.rhythm);
        json.put("sentiment", this.sentiment.code);
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
