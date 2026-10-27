/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.TextSplitter;
import org.json.JSONObject;

/**
 * 知识库文章。
 */
public class KnowledgeArticle extends Entity {

    /**
     * 文章所属联系人 ID 。
     */
    public long contactId;

    /**
     * 文章分类。
     */
    public String category;

    /**
     * 文章标题。
     */
    public String title;

    /**
     * 文章正文。
     */
    public String content;

    /**
     * 字数。
     */
    public int numWords;

    /**
     * 文章摘要。
     *
     * <p>由模型生成，用于知识库召回时快速判断相关性。</p>
     */
    public String summarization;

    /**
     * 作者。
     */
    public String author;

    /**
     * 发布年份。
     */
    public int year;

    /**
     * 发布月份，取值 1 到 12 。
     */
    public int month;

    /**
     * 发布日。
     */
    public int date;

    /**
     * 文章所属知识库名称。
     */
    public String baseName;

    /**
     * 可见范围，默认私有。
     */
    public KnowledgeScope scope = KnowledgeScope.Private;

    /**
     * 抽取到的关键词数量。
     */
    public int numKeywords;

    /**
     * 是否已激活。
     *
     * <p>未激活的文章不参与知识库检索。</p>
     */
    public boolean activated;

    /**
     * 切分出的文本段数量。
     */
    public int numSegments;

    /**
     * 文本切分策略，默认自动选择。
     */
    public TextSplitter splitter = TextSplitter.Auto;

    public KnowledgeArticle(String domain, long contactId, String baseName,
                            String category, String title, String content, String author,
                            int year, int month, int date, long timestamp, KnowledgeScope scope) {
        super(0L, domain, timestamp);
        this.contactId = contactId;
        this.baseName = baseName;
        this.category = category;
        this.title = title;
        this.content = content;
        this.author = author;
        this.year = year;
        this.month = month;
        this.date = date;
        this.scope = scope;
        this.numWords = content.length();
    }

    public KnowledgeArticle(long id, String domain, long contactId, String baseName,
                            String category, String title, String content,
                            String summarization, String author, int year, int month, int date, long timestamp,
                            KnowledgeScope scope, boolean activated, int numSegments) {
        super(id, domain, timestamp);
        this.contactId = contactId;
        this.baseName = baseName;
        this.category = category;
        this.title = title;
        this.content = content;
        this.summarization = summarization;
        this.author = author;
        this.year = year;
        this.month = month;
        this.date = date;
        this.scope = scope;
        this.activated = activated;
        this.numSegments = numSegments;
        this.numWords = content.length();
    }

    public KnowledgeArticle(JSONObject json) {
        super(json);
        this.contactId = json.getLong("contactId");
        this.baseName = json.getString("base");
        this.category = json.getString("category");
        this.title = json.getString("title");
        if (json.has("content")) {
            this.content = json.getString("content");
        }
        if (json.has("summarization")) {
            this.summarization = json.getString("summarization");
        }
        this.author = json.has("author") ? json.getString("author") : "Anonymity";
        this.year = json.getInt("year");
        this.month = json.getInt("month");
        this.date = json.getInt("date");

        this.scope = json.has("scope") ? KnowledgeScope.parse(json.getString("scope")) :
                KnowledgeScope.Private;
        this.activated = json.has("activated") && json.getBoolean("activated");
        this.numSegments = json.has("numSegments") ? json.getInt("numSegments") : 0;
        this.splitter = TextSplitter.parse(json.getString("splitter"));
        if (json.has("numWords")) {
            this.numWords = json.getInt("numWords");
        }
    }

    /**
     * 按 ID 与所属知识库判断相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof KnowledgeArticle) {
            KnowledgeArticle other = (KnowledgeArticle) obj;
            return other.id.longValue() == this.id.longValue();
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 基于 ID 的哈希值。
     */
    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 文章 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contactId", this.contactId);
        json.put("base", this.baseName);
        json.put("category", this.category);
        json.put("title", this.title);
        if (null != this.content) {
            json.put("content", this.content);
        }
        else {
            json.put("content", "");
        }
        if (null != this.summarization) {
            json.put("summarization", this.summarization);
        }
        else {
            json.put("summarization", "");
        }
        json.put("author", this.author);
        json.put("year", this.year);
        json.put("month", this.month);
        json.put("date", this.date);
        json.put("scope", this.scope.name);
        json.put("activated", this.activated);
        json.put("numSegments", this.numSegments);
        json.put("splitter", this.splitter.name);
        json.put("numWords", this.numWords);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>不包含正文，用于列表类接口。</p>
     *
     * @return 文章 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = super.toCompactJSON();
        json.put("contactId", this.contactId);
        json.put("base", this.baseName);
        json.put("category", this.category);
        json.put("title", this.title);
        json.put("author", this.author);
        json.put("year", this.year);
        json.put("month", this.month);
        json.put("date", this.date);
        json.put("scope", this.scope.name);
        json.put("activated", this.activated);
        json.put("numSegments", this.numSegments);
        json.put("splitter", this.splitter.name);
        json.put("numWords", this.numWords);
        return json;
    }
}
