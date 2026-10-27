/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 知识匹配模式。
 *
 * @deprecated 检索已改用统一的匹配配置，本类保留仅为兼容旧数据。
 */
public class KnowledgeMatchingSchema implements JSONable {

    private String category;

    private String sectionQuery;

    private String comprehensiveQuery;

    private int maxArticles = 5;

    private int maxParaphrases = 5;

    public KnowledgeMatchingSchema(String category, String comprehensiveQuery) {
        this.category = category;
//        this.sectionQuery = Consts.KNOWLEDGE_SECTION_PROMPT;
        this.comprehensiveQuery = comprehensiveQuery;
    }

    public KnowledgeMatchingSchema(JSONObject json) {
        if (json.has("category")) {
            this.category = json.getString("category");
        }
        if (json.has("sectionQuery")) {
            this.sectionQuery = json.getString("sectionQuery");
        }
        if (json.has("comprehensiveQuery")) {
            this.comprehensiveQuery = json.getString("comprehensiveQuery");
        }
        this.maxArticles = json.getInt("maxArticles");
        this.maxParaphrases = json.getInt("maxParaphrases");
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
     * 获取章节查询。
     *
     * @return 章节查询。
     */
    public String getSectionQuery() {
        return this.sectionQuery;
    }

    /**
     * 获取综合查询。
     *
     * @return 综合查询。
     */
    public String getComprehensiveQuery() {
        return this.comprehensiveQuery;
    }

    /**
     * 获取文章上限。
     *
     * @return 文章上限。
     */
    public int getMaxArticles() {
        return this.maxArticles;
    }

    /**
     * 获取改写样本上限。
     *
     * @return 改写样本上限。
     */
    public int getMaxParaphrases() {
        return this.maxParaphrases;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (null != this.category) {
            json.put("category", this.category);
        }
        if (null != this.sectionQuery) {
            json.put("sectionQuery", this.sectionQuery);
        }
        if (null != this.comprehensiveQuery) {
            json.put("comprehensiveQuery", this.comprehensiveQuery);
        }
        json.put("maxArticles", this.maxArticles);
        json.put("maxParaphrases", this.maxParaphrases);
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
