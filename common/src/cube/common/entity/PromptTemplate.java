/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 提示词模板。
 *
 * <p>含占位参数的模板，渲染时按参数表替换。</p>
 */
public class PromptTemplate implements JSONable {

    /** 名称。 */
    public final String name;

    /** 模板参数表，渲染时按占位符替换。 */
    public final Map<String, String> parameters;

    public PromptTemplate(JSONObject json) {
        this.name = json.getString("name");
        this.parameters = new HashMap<>();
        JSONObject paramMap = json.getJSONObject("parameters");
        for (String key : paramMap.keySet()) {
            this.parameters.put(key, paramMap.getString(key));
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        JSONObject paramMap = new JSONObject();
        Iterator<Map.Entry<String, String>> iter = this.parameters.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, String> e = iter.next();
            paramMap.put(e.getKey(), e.getValue());
        }
        json.put("parameters", paramMap);
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
