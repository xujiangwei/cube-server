/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * AIGC 生成选项。
 */
public class GeneratingOption implements JSONable {

    /** 温度。 */
    public double temperature = 0.3;

    /** topP。 */
    public double topP = 0.85;

    /** 重复惩罚。 */
    public double repetitionPenalty = 1.05;

    /** topK。 */
    public int topK = 5;

    /** 最大新 token 数。 */
    public int maxNewTokens = 2048;

    /** 是否启用上下文识别，默认关闭。 */
    public boolean recognizeContext = false;

    public GeneratingOption() {
    }

    public GeneratingOption(boolean recognizeContext) {
        this.recognizeContext = recognizeContext;
    }

    public GeneratingOption(double temperature, double topP, double repetitionPenalty, int maxNewTokens, int topK) {
        this.temperature = temperature;
        this.topP = topP;
        this.repetitionPenalty = repetitionPenalty;
        this.maxNewTokens = maxNewTokens;
        this.topK = topK;
    }

    public GeneratingOption(JSONObject json) {
        if (json.has("temperature")) {
            this.temperature = json.getDouble("temperature");
        }

        if (json.has("topP")) {
            this.topP = json.getDouble("topP");
        }

        if (json.has("repetitionPenalty")) {
            this.repetitionPenalty = json.getDouble("repetitionPenalty");
        }

        if (json.has("topK")) {
            this.topK = json.getInt("topK");
        }

        if (json.has("maxNewTokens")) {
            this.maxNewTokens = json.getInt("maxNewTokens");
        }

        if (json.has("recognizeContext")) {
            this.recognizeContext = json.getBoolean("recognizeContext");
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
        json.put("temperature", this.temperature);
        json.put("topP", this.topP);
        json.put("repetitionPenalty", this.repetitionPenalty);
        json.put("topK", this.topK);
        json.put("maxNewTokens", this.maxNewTokens);
        json.put("recognizeContext", this.recognizeContext);
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
