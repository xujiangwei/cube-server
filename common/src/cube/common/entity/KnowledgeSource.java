/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import cube.util.FileUtils;
import org.json.JSONObject;

/**
 * 知识源。
 */
public class KnowledgeSource implements JSONable {

    private KnowledgeDocument document;

    private KnowledgeArticle article;

    private String segment;

    public KnowledgeSource(KnowledgeDocument document) {
        this.document = document;
    }

    public KnowledgeSource(KnowledgeArticle article) {
        this.article = article;
    }

    public KnowledgeSource(String segment) {
        this.segment = segment;
    }

    public KnowledgeSource(JSONObject json) {
        if (json.has("document")) {
            this.document = new KnowledgeDocument(json.getJSONObject("document"));
        }
        if (json.has("article")) {
            this.article = new KnowledgeArticle(json.getJSONObject("article"));
        }
        if (json.has("segment")) {
            this.segment = json.getString("segment");
        }
    }

    /**
     * 获取文档。
     *
     * @return 文档。
     */
    public KnowledgeDocument getDocument() {
        return this.document;
    }

    /**
     * 获取文章。
     *
     * @return 文章。
     */
    public KnowledgeArticle getArticle() {
        return this.article;
    }

    /**
     * 获取分段。
     *
     * @return 分段。
     */
    public String getSegment() {
        return this.segment;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        if (null != this.document) {
            return "文件《" + FileUtils.extractFileName(this.document.getFileLabel().getFileName()) + "》";
        }
        else if (null != this.article) {
            return "文章《" + this.article.title + "》";
        }
        else if (null != this.segment) {
            return "文本片段《" + this.segment.substring(0, Math.min(20, this.segment.length())).trim().replaceAll("\n", "") + "...》";
        }
        else {
            return "";
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
        if (null != this.document) {
            json.put("document", this.document.toCompactJSON());
        }
        if (null != this.article) {
            json.put("article", this.article.toCompactJSON());
        }
        if (null != this.segment) {
            json.put("segment", this.segment);
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
