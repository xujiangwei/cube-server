/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.complex.widget.Builders;
import cube.aigc.complex.widget.Widget;
import org.json.JSONObject;

/**
 * 控件资源。
 *
 * <p>以控件形式呈现的复合资源。</p>
 */
public class WidgetResource extends ComplexResource {

    private Widget widget;

    public WidgetResource(Widget widget) {
        super(Subject.Widget);
        this.widget = widget;
    }

    public WidgetResource(JSONObject json) {
        super(Subject.Widget, json);
        this.widget = Builders.buildWidget(json.getJSONObject("payload"));
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("payload", this.widget.toJSON());
        return json;
    }
}
