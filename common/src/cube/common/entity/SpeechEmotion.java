/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 语音情绪。
 */
public class SpeechEmotion implements JSONable {

    /** 文件。 */
    public FileLabel file;

    /** 耗时。 */
    public long elapsed;

    /** 情绪。 */
    public Emotion emotion;

    /** 分数。 */
    public double score = 0.0;

    /** 时长。 */
    public double duration = 0.0;

    public SpeechEmotion(JSONObject json) {
        this.file = json.has("file") ? new FileLabel(json.getJSONObject("file")) : null;
        this.elapsed = json.has("elapsed") ? json.getLong("elapsed") : 0;

        if (json.has("emotion")) {
            Object obj = json.get("emotion");
            if (obj instanceof String) {
                this.emotion = Emotion.parse(obj.toString());
            }
            else {
                this.emotion = Emotion.parse(json.getJSONObject("emotion").getString("name"));
            }
        }
        else {
            this.emotion = Emotion.None;
        }
        this.score = json.getDouble("score");
        this.duration = json.getDouble("duration");
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[");
        buf.append(this.emotion.name());
        buf.append(",");
        buf.append(this.score);
        buf.append(",");
        buf.append(this.duration);
        buf.append("]");
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (null != this.file) {
            json.put("file", this.file.toJSON());
        }
        json.put("elapsed", this.elapsed);
        json.put("emotion", this.emotion.toJSON());
        json.put("score", this.score);
        json.put("duration", this.duration);
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
