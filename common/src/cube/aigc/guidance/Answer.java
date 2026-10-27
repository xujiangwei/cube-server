/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.guidance;

import org.json.JSONObject;

/**
 * 候选答案。
 *
 * <p>引导问题下的一个可选项：编码用于跳转匹配，内容面向用户展示。</p>
 */
public class Answer {

    /** 答案编码，用于路由匹配。 */
    public final String code;

    /** 答案正文。 */
    public final String content;

    public Answer(JSONObject json) {
        this.code = json.has("code") ? json.getString("code") : null;
        this.content = json.has("content") ? json.getString("content") : null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (null != this.code) {
            json.put("code", this.code);
        }
        if (null != this.content) {
            json.put("content", this.content);
        }
        return json;
    }
}
