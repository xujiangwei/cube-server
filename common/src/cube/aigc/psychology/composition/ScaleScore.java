/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.common.JSONable;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class ScaleScore implements JSONable {

    private Map<String, JSONObject> items;

    public ScaleScore() {
        this.items = new LinkedHashMap<>();
    }

    public ScaleScore(JSONObject json) {
        this.items = new LinkedHashMap<>();
        Iterator<String> iter = json.keys();
        while (iter.hasNext()) {
            String key = iter.next();
            JSONObject value = json.getJSONObject(key);
            this.items.put(key, value);
        }
    }

    /**
     * 添加条目。
     *
     * @param key 条目。
     * @param name 条目。
     * @param value 条目。
     * @param level 条目。
     */
    public void addItem(String key, String name, Object value, ScaleFactorLevel level) {
        JSONObject pair = new JSONObject();
        pair.put("name", name);
        pair.put("value", value);
        pair.put("level", level.toJSON());
        this.items.put(key, pair);
    }

    /**
     * 获取条目。
     *
     * @param name 条目。
     * @return 条目。
     */
    public Object getItem(String name) {
        return this.items.get(name);
    }

    /**
     * 获取条目名称。
     *
     * @param key 条目名称。
     * @return 条目名称。
     */
    public String getItemName(String key) {
        JSONObject data = this.items.get(key);
        if (null == data) {
            return null;
        }

        return data.getString("name");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, JSONObject> e : this.items.entrySet()) {
            String key = e.getKey();
            JSONObject value = e.getValue();
            json.put(key, value);
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
        return this.toJSON();
    }
}
