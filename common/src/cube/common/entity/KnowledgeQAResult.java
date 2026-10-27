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
 * 知识库问答结果。
 */
public class KnowledgeQAResult implements JSONable {

    /** 查询。 */
    public final String query;

    /** 提示词。 */
    public String prompt;

    /** 记录。 */
    public GeneratingRecord record;

    /** 来源列表。 */
    public List<KnowledgeSource> sources;

    public KnowledgeQAResult(String query) {
        this.query = query;
    }

    public KnowledgeQAResult(String query, String prompt, GeneratingRecord record) {
        this.query = query;
        this.prompt = prompt;
        this.record = record;
        this.sources = new ArrayList<>();
    }

    public KnowledgeQAResult(JSONObject json) {
        this.sources = new ArrayList<>();
        this.query = json.getString("query");
        if (json.has("prompt")) {
            this.prompt = json.getString("prompt");
        }
        if (json.has("record")) {
            this.record = new GeneratingRecord(json.getJSONObject("record"));
        }
        if (json.has("sources")) {
            JSONArray array = json.getJSONArray("sources");
            for (int i = 0; i < array.length(); ++i) {
                KnowledgeSource source = new KnowledgeSource(array.getJSONObject(i));
                this.sources.add(source);
            }
        }
    }

    /**
     * 用另一结果重置当前对象。
     *
     * <p>保留对象引用、替换内部状态，便于在流式生成中复用实例。</p>
     *
     * @param other 来源结果。
     */
    public void reset(KnowledgeQAResult other) {
        this.prompt = other.prompt;
        this.sources = other.sources;
        this.record = other.record;
        this.record.query = this.query;
    }

    /**
     * 把来源转为数组。
     *
     * @return 来源 JSON 数组。
     */
    public JSONArray sourcesToArray() {
        JSONArray array = new JSONArray();
        for (KnowledgeSource source : this.sources) {
            array.put(source.toJSON());
        }
        return array;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("query", this.query);
        if (null != this.prompt) {
            json.put("prompt", this.prompt);
        }
        if (null != this.record) {
            json.put("record", this.record.toCompactJSON());
        }
        if (!this.sources.isEmpty()) {
            json.put("sources", this.sourcesToArray());
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
        JSONObject json = this.toJSON();
        if (json.has("prompt")) {
            json.remove("prompt");
        }
        if (json.has("record")) {
            // 将 record 里的 query 修改为原 query
            JSONObject recordJson = json.getJSONObject("record");
            recordJson.put("query", this.query);
        }
        return json;
    }
}
