/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表控件。
 *
 * <p>以纵向列表形式承载多个子控件，子控件按加入顺序渲染。</p>
 */
public class ListView extends Widget {

    /** 控件名称常量。 */
    public final static String NAME = "ListView";

    /** 列表项。 */
    public List<Widget> items = new ArrayList<>();

    public ListView() {
        super(NAME);
    }

    public ListView(JSONObject json) {
        super(json);
        JSONArray array = json.getJSONArray("items");
        for (int i = 0; i < array.length(); ++i) {
            Widget item = Builders.buildWidget(array.getJSONObject(i));
            if (null != item) {
                this.items.add(item);
            }
        }
    }

    /**
     * 追加列表项。
     *
     * @param widget 子控件。
     */
    public void addItem(Widget widget) {
        this.items.add(widget);
    }

    /**
     * 统计列表项数量。
     *
     * @return 数量。
     */
    public int numItems() {
        return this.items.size();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        JSONArray array = new JSONArray();
        for (Widget item : this.items) {
            array.put(item.toJSON());
        }
        json.put("items", array);
        return json;
    }
}
