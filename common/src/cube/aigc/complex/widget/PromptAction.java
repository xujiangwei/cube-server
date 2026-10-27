/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import org.json.JSONObject;

/**
 * 提示词动作。
 *
 * <p>点击后把预设提示词直接提交给模型，用于把常用问法固化到界面上。</p>
 */
public class PromptAction extends Action {

    /** 动作名称常量。 */
    public final static String NAME = "PromptAction";

    /** 预设提示词。 */
    public String prompt;

    /** 是否直接提交；{@code false} 表示先填入输入框由用户确认。 */
    public boolean direct = true;

    public PromptAction(String prompt) {
        super(NAME);
        this.prompt = prompt;
    }

    public PromptAction(JSONObject json) {
        super(json);
        this.prompt = json.getString("prompt");
        this.direct = json.getBoolean("direct");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("prompt", this.prompt);
        json.put("direct", this.direct);
        return json;
    }
}
