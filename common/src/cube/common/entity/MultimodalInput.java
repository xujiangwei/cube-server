/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import cube.util.JSONUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态输入。
 */
public class MultimodalInput implements JSONable {

    /** 单元。 */
    public String unit;

    /** 内容。 */
    public String content;

    /** 历史。 */
    public List<JSONObject> history;

    /** 文件码列表。 */
    public List<String> fileCodes;

    /** 文件标签列表。 */
    public List<FileLabel> fileLabels;

    /** 流列表。 */
    public List<String> streams;

    /** 选项。 */
    public JSONObject option;

    /** 是否可记录。 */
    public boolean recordable = false;

    public MultimodalInput(JSONObject json) {
        this.unit = json.has("unit") ? json.getString("unit") : "OmniGround";
        this.content = json.getString("content");
        this.history = json.has("history") ? JSONUtils.toObjectList(json.getJSONArray("history")) : null;
        this.fileCodes = json.has("files") ? JSONUtils.toStringList(json.getJSONArray("files")) : new ArrayList<>();
        this.option = json.has("option") ? json.getJSONObject("option") : null;
        this.recordable = json.has("recordable") && json.getBoolean("recordable");
    }

    /**
     * 转换为单元调用格式。
     *
     * @return 单元可直接消费的 JSON 。
     */
    public JSONObject toUnitJson() {
        JSONObject json = new JSONObject();
        json.put("unit", this.unit);
        json.put("content", this.content);

        if (null != this.history) {
            JSONArray array = JSONUtils.toObjectArray(this.history);
            json.put("history", array);
        }

        if (null != this.fileLabels) {
            JSONArray files = new JSONArray();
            for (FileLabel fileLabel : this.fileLabels) {
                files.put(fileLabel.toJSON());
            }
            json.put("files", files);
        }
        else if (null != this.fileCodes) {
            json.put("files", JSONUtils.toStringArray(this.fileCodes));
        }
        else {
            json.put("files", new JSONArray());
        }

        if (null != this.option) {
            json.put("option", this.option);
        }

        return json;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("unit", this.unit);
        json.put("content", this.content);

        if (null != this.history) {
            JSONArray array = JSONUtils.toObjectArray(this.history);
            json.put("history", array);
        }

        if (null != this.fileCodes) {
            json.put("files", JSONUtils.toStringArray(this.fileCodes));
        }
        else {
            json.put("files", new JSONArray());
        }

        if (null != this.option) {
            json.put("option", this.option);
        }
        json.put("recordable", this.recordable);
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
