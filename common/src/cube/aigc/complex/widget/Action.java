/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import org.json.JSONObject;

/**
 * 控件动作抽象基类。
 *
 * <p>描述用户与控件交互后要执行的行为，子类按动作类型扩展参数。</p>
 */
public abstract class Action {

    /** 动作名称。 */
    protected final String name;

    public Action(String name) {
        this.name = name;
    }

    public Action(JSONObject json) {
        this.name = json.getString("name");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        return json;
    }
}
