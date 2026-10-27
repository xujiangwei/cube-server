/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 情绪占比。
 *
 * <p>承载正向、负向与中性情绪的占比统计。</p>
 */
public class EmotionRatio implements JSONable {

    /** 分数。 */
    public final double score;

    /** 正向占比。 */
    public final int positiveRatio;

    /** 负向占比。 */
    public final int negativeRatio;

    /** 中性占比。 */
    public final int neutralRatio;

    public EmotionRatio(double score, int positiveRatio, int negativeRatio, int neutralRatio) {
        this.score = score;
        this.positiveRatio = positiveRatio;
        this.negativeRatio = negativeRatio;
        this.neutralRatio = neutralRatio;
    }

    public EmotionRatio(JSONObject json) {
        this.score = json.getDouble("score");
        this.positiveRatio = json.getInt("positiveRatio");
        this.negativeRatio = json.getInt("negativeRatio");
        this.neutralRatio = json.getInt("neutralRatio");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("score", this.score);
        json.put("positiveRatio", this.positiveRatio);
        json.put("negativeRatio", this.negativeRatio);
        json.put("neutralRatio", this.neutralRatio);
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
