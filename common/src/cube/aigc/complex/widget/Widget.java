/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import cell.util.Utils;
import org.json.JSONObject;

/**
 * UI组件。
 */
public abstract class Widget {

    /** 控件 ID 。 */
    protected final long id;

    /** 控件名称，用于客户端选择渲染方式。 */
    protected final String name;

    /** 是否一次性控件；{@code true} 表示渲染后即丢弃。 */
    protected boolean disposable;

    /** 控件附加上下文，由业务侧自行解释。 */
    protected Object context;

//    protected Map<String, String> attributes;

    public Widget(String name) {
        this.id = Utils.generateSerialNumber();
        this.name = name;
        this.disposable = false;
//        this.attributes = new HashMap<>();
    }

    public Widget(JSONObject json) {
        this.id = json.getLong("id");
        this.name = json.getString("name");
        this.disposable = json.getBoolean("disposable");
    }

    /**
     * 获取控件 ID 。
     *
     * @return 控件 ID 。
     */
    public long getId() {
        return this.id;
    }

    /**
     * 获取控件名称。
     *
     * @return 控件名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 判断是否为一次性控件。
     *
     * @return 一次性返回 {@code true} 。
     */
    public boolean isDisposable() {
        return this.disposable;
    }

    /**
     * 设置是否为一次性控件。
     *
     * @param value 为 {@code true} 表示渲染后丢弃。
     */
    public void setDisposable(boolean value) {
        this.disposable = value;
    }

    /**
     * 获取控件附加上下文。
     *
     * @return 上下文对象。
     */
    public Object getContext() {
        return this.context;
    }

//    public void addAttribute(String key, String value) {
//        this.attributes.put(key, value);
//    }

//    public String removeAttribute(String key) {
//        return this.attributes.remove(key);
//    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("name", this.name);
        json.put("disposable", this.disposable);

//        for (Map.Entry<String, String> e : this.attributes.entrySet()) {
//            json.put(e.getKey(), e.getValue());
//        }
        return json;
    }
}
