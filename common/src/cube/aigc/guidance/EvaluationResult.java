/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.guidance;

import cell.util.log.Logger;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评估结果。
 *
 * <p>引导流程收集完回答后的判定结果：结论、描述与逐项布尔检查，
 * 可导出为 Markdown 供报告使用。</p>
 */
public class EvaluationResult implements JSONable {

    /** 结果名称。 */
    public String name;

    private JSONObject result;

    private Map<String, Boolean> evaluationItems;

    private String description;

    private Map<String, String> qaPairs;

    private boolean terminated = false;

    public EvaluationResult(String name) {
        this.name = name;
        this.evaluationItems = new LinkedHashMap<>();
        this.qaPairs = new LinkedHashMap<>();
    }

    public EvaluationResult(JSONObject json) {
        this.name = json.getString("name");
        this.result = json.has("result") ? json.getJSONObject("result") : null;
        this.evaluationItems = new LinkedHashMap<>();
        JSONArray array = json.getJSONArray("items");
        for (int i = 0; i < array.length(); ++i) {
            JSONObject data = array.getJSONObject(i);
            this.evaluationItems.put(data.getString("item"), data.getBoolean("value"));
        }
        this.description = json.has("description") ? json.getString("description") : null;
        this.qaPairs = new LinkedHashMap<>();
    }

    /**
     * 判断结果是否有效。
     *
     * <p>未被终止且已产生结论时视为有效。</p>
     *
     * @return 有效返回 {@code true} 。
     */
    public boolean isValid() {
        return (null != this.result) || (null != this.description) || !this.evaluationItems.isEmpty();
    }

    /**
     * 设置是否终止流程。
     *
     * @param value 为 {@code true} 表示终止。
     */
    public void setTerminated(boolean value) {
        this.terminated = value;
    }

    /**
     * 判断是否已终止流程。
     *
     * @return 已终止返回 {@code true} 。
     */
    public boolean hasTerminated() {
        return this.terminated;
    }

    /**
     * 设置结论。
     *
     * @param value 结论对象。
     */
    public void setResult(Object value) {
        this.result = new JSONObject();
        this.result.put("value", value.toString());
    }

    /**
     * 设置描述。
     *
     * @param description 描述文本。
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 添加一项布尔检查。
     *
     * @param name  检查项名称。
     * @param value 是否通过。
     */
    public void addItem(String name, boolean value) {
        this.evaluationItems.put(name, value);
    }

    /**
     * 获取检查项结果。
     *
     * @param name 检查项名称。
     * @return 是否通过；不存在返回 {@code false} 。
     */
    public boolean getItem(String name) {
        return this.evaluationItems.get(name);
    }

    /**
     * 导出为 Markdown 。
     *
     * @return Markdown 文本。
     */
    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        buf.append("**").append(this.name).append("**\n\n");

        if (null != this.result && this.result.has("value")) {
            buf.append("- ***");
            String value = this.result.getString("value");
            try {
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    boolean boolValue = Boolean.parseBoolean(value.toLowerCase());
                    buf.append(boolValue ? "是" : "否");
                }
                else {
                    buf.append(value);
                }
            } catch (Exception e) {
                Logger.e(this.getClass(), "#toMarkdown", e);
            }
            buf.append("***").append("\n\n");
        }

        for (Map.Entry<String, Boolean> entry : this.evaluationItems.entrySet()) {
            buf.append("> ").append(entry.getKey());
            buf.append("    ").append(entry.getValue() ? "是" : "否");
            buf.append("\n");
        }

        if (!this.evaluationItems.isEmpty()) {
            buf.append("\n");
        }

        if (null != this.description) {
            buf.append(this.description);
        }

        return buf.toString().trim();
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
        if (null != this.result) {
            json.put("result", this.result);
        }
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Boolean> entry : this.evaluationItems.entrySet()) {
            JSONObject item = new JSONObject();
            item.put("item", entry.getKey());
            item.put("value", entry.getValue().booleanValue());
            array.put(item);
        }
        json.put("items", array);
        if (null != this.description) {
            json.put("description", this.description);
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
