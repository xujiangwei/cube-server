/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import org.json.JSONObject;

/**
 * 按钮组件。
 */
public class Button extends Widget {

    /** 控件名称常量。 */
    public final static String NAME = "Button";

    private String text;

    private ButtonListener listener;

    public Button(JSONObject json) {
        super(json);
        this.text = json.getString("text");
    }

    public Button(String text, ButtonListener listener, Object context) {
        super(NAME);
        this.text = text;
        this.listener = listener;
        this.context = context;
    }

    /**
     * 判断 JSON 是否描述一个按钮控件。
     *
     * @param json 控件 JSON 。
     * @return 是按钮返回 {@code true} 。
     */
    public static boolean isButton(JSONObject json) {
        return json.has("name") && json.getString("name").equals(NAME);
    }

    /**
     * 获取点击监听器。
     *
     * @return 监听器；未注册返回 {@code null} 。
     */
    public ButtonListener getListener() {
        return this.listener;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("text", this.text);
        json.put("loading", false);
        return json;
    }
}
