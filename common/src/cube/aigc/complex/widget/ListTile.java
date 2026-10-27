/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import org.json.JSONObject;

/**
 * 列表项控件。
 *
 * <p>标准的两行列表项：主标题、副标题、左右图标与点击动作。</p>
 */
public class ListTile extends Widget {

    /** 控件名称常量。 */
    public final static String NAME = "ListTile";

    /** 主标题。 */
    public final String title;

    /** 副标题。 */
    public String subtitle;

    /** 是否使用紧凑行高。 */
    public boolean dense = false;

    /** 前置控件，通常为头像或图标。 */
    public Widget leading;

    /** 后置控件，通常为箭头或状态。 */
    public Widget trailing;

    /** 点击时执行的动作。 */
    public Action onTap;

    public ListTile(String title) {
        super(NAME);
        this.title = title;
    }

    public ListTile(JSONObject json) {
        super(json);
        this.title = json.getString("title");
        this.subtitle = json.has("subtitle") ? json.getString("subtitle") : null;
        this.dense = json.has("dense") ? json.getBoolean("dense") : false;
        this.leading = json.has("leading") ? Builders.buildWidget(json.getJSONObject("leading")) : null;
        this.trailing = json.has("trailing") ? Builders.buildWidget(json.getJSONObject("trailing")) : null;
        this.onTap = json.has("onTap") ? Builders.buildAction(json.getJSONObject("onTap")) : null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("title", this.title);
        if (null != this.subtitle) {
            json.put("subtitle", this.subtitle);
        }
        json.put("dense", this.dense);
        if (null != this.leading) {
            json.put("leading", this.leading.toJSON());
        }
        if (null != this.trailing) {
            json.put("trailing", this.trailing.toJSON());
        }
        if (null != this.onTap) {
            json.put("onTap", this.onTap.toJSON());
        }
        return json;
    }
}
