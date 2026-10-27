/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 语音分段。
 *
 * <p>一段连续语音及其所属说话人。</p>
 */
public class VoiceSegment implements JSONable {

    /** 开始。 */
    public final double start;

    /** 结束。 */
    public final double end;

    /** 时长。 */
    public final double duration;

    public VoiceSegment(JSONObject json) {
        this.start = json.getDouble("start");
        this.end = json.getDouble("end");
        this.duration = json.getDouble("duration");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("start", this.start);
        json.put("end", this.end);
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
