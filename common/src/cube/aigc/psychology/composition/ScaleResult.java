/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.common.JSONable;
import org.json.JSONObject;

public class ScaleResult implements JSONable {

    /** 内容。 */
    public String content;

    /** 分数。 */
    public ScaleScore score;

    /** 提示词。 */
    public ScalePrompt prompt;

    /** 完成。 */
    public boolean complete;

    public ScaleResult(Scale scale) {
        this.complete = scale.isComplete();
    }

    public ScaleResult(String content, ScaleScore score, ScalePrompt prompt, Scale scale) {
        this.content = content;
        this.score = score;
        this.prompt = prompt;
        this.complete = scale.isComplete();
    }

    public ScaleResult(JSONObject json) {
        this.complete = json.getBoolean("complete");
        if (json.has("content")) {
            this.content = json.getString("content");
        }
        if (json.has("score")) {
            this.score = new ScaleScore(json.getJSONObject("score"));
        }
        if (json.has("prompt")) {
            this.prompt = new ScalePrompt(json.getJSONObject("prompt"));
        }
    }

    /**
     * 匹配因子名称。
     *
     * @param key 因子名称。
     * @return 因子名称。
     */
    public String matchFactorName(String key) {
        return this.score.getItemName(key);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();
        if (null != this.prompt) {
            json.put("prompt", this.prompt.toJSON());
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
        JSONObject json = new JSONObject();
        json.put("complete", this.complete);
        if (null != this.content) {
            json.put("content", this.content);
        }
        if (null != this.score) {
            json.put("score", this.score.toJSON());
        }
        return json;
    }
}
