/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.aigc.psychology.indicator.Indicable;
import cube.aigc.psychology.indicator.Indicator;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 得分。
 */
public class Score implements JSONable {

    /** 指标。 */
    public final Indicable indicator;

    /** 值。 */
    public double value;

    /** 权重。 */
    public double weight;

    public Score(Indicable indicator, double value) {
        this.indicator = indicator;
        this.value = value;
        this.weight = 1.0;
    }

    public Score(Indicable indicator, double value, double weight) {
        this.indicator = indicator;
        this.value = value;
        this.weight = weight;
    }

    public Score(JSONObject json) {
        this.indicator = Indicator.parse(json.getString("indicator"));
        this.value = json.getDouble("value");
        this.weight = json.getDouble("weight");
    }

    /**
     * 计算本项得分。
     *
     * @return 得分。
     */
    public double scoring() {
        return this.value * this.weight;
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Score) {
            Score other = (Score) obj;
            if (other.indicator == this.indicator) {
                return true;
            }
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
        return this.indicator.hashCode();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("indicator", this.indicator.getName());
        json.put("value", this.value);
        json.put("weight", this.weight);
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
        JSONObject json = new JSONObject();
        json.put("indicator", this.indicator.getName());
        json.put("value", this.value);
        json.put("weight", this.weight);
        return json;
    }
}
