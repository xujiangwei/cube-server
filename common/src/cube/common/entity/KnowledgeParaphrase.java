/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 知识释义。
 */
public class KnowledgeParaphrase extends Entity {

    private long parentId;

    private String category;

    private String word;

    private String paraphrase;

    public KnowledgeParaphrase(long id, long parentId, String category, String word, String paraphrase) {
        super(id);
        this.parentId = parentId;
        this.category = category;
        this.word = word;
        this.paraphrase = paraphrase;
    }

    public KnowledgeParaphrase(JSONObject json) {
        super(json);
        this.parentId = json.getLong("parentId");
        this.category = json.getString("category");
        this.word = json.getString("word");
        this.paraphrase = json.getString("paraphrase");
    }

    /**
     * 获取父节点 ID。
     *
     * @return 父节点 ID。
     */
    public long getParentId() {
        return this.parentId;
    }

    /**
     * 获取分类。
     *
     * @return 分类。
     */
    public String getCategory() {
        return this.category;
    }

    /**
     * 获取词。
     *
     * @return 词。
     */
    public String getWord() {
        return this.word;
    }

    /**
     * 获取改写样本。
     *
     * @return 改写样本。
     */
    public String getParaphrase() {
        return this.paraphrase;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("parentId", this.parentId);
        json.put("category", this.category);
        json.put("word", this.word);
        json.put("paraphrase", this.paraphrase);
        return json;
    }
}
