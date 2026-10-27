/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 面部表情。
 */
public class FacialExpressionResult implements JSONable {

    /** 文件。 */
    public final FileLabel file;

    /** 耗时。 */
    public final long elapsed;

    /** 列表。 */
    public final List<FacialExpression> list;

    /** 可视化。 */
    public FileLabel visualization;

    public FacialExpressionResult(JSONObject json) {
        this.file = new FileLabel(json.getJSONObject("file"));
        this.elapsed = json.getLong("elapsed");
        this.list = new ArrayList<>();
        JSONArray array = json.getJSONArray("list");
        for (int i = 0; i < array.length(); ++i) {
            JSONObject item = array.getJSONObject(i);
            item.put("file", this.file.toCompactJSON());
            this.list.add(new FacialExpression(item));
        }
        if (json.has("visualization")) {
            this.visualization = new FileLabel(json.getJSONObject("visualization"));
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
        json.put("file", this.file.toJSON());
        json.put("elapsed", this.elapsed);
        JSONArray array = new JSONArray();
        for (FacialExpression fe : this.list) {
            JSONObject item = fe.toJSON();
            item.remove("file");
            array.put(item);
        }
        json.put("list", array);
        if (null != this.visualization) {
            json.put("visualization", this.visualization.toJSON());
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
