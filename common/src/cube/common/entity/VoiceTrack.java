/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 语音轨道记录。
 */
public class VoiceTrack implements JSONable {

    /** 轨迹。 */
    public String track;

    /** 标签。 */
    public String label;

    /** 显示。 */
    public String display;

    /** 分段。 */
    public VoiceSegment segment;

    /** 情绪。 */
    public SpeechEmotion emotion;

    /** 识别。 */
    public SpeechRecognitionInfo recognition;

    public VoiceTrack(JSONObject json) {
        this.track = json.getString("track");
        this.label = json.getString("label");
        this.display = json.has("display") ? json.getString("display") : this.label;
        this.segment = new VoiceSegment(json.getJSONObject("segment"));
        this.emotion = new SpeechEmotion(json.getJSONObject("emotion"));
        this.recognition = new SpeechRecognitionInfo(json.getJSONObject("recognition"));
    }

    public VoiceTrack(String track, String label, String display, VoiceSegment segment,
                      SpeechEmotion emotion, SpeechRecognitionInfo recognition) {
        this.track = track;
        this.label = label;
        this.display = display;
        this.segment = segment;
        this.emotion = emotion;
        this.recognition = recognition;
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
        json.put("label", this.label);
        json.put("display", this.display);
        json.put("segment", this.segment.toJSON());
        json.put("emotion", this.emotion.toJSON());
        json.put("recognition", this.recognition.toJSON());
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
