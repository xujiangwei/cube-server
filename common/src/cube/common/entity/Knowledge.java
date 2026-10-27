/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.Consts;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识条目。
 *
 * <p>知识库中的一条知识，包含内容与关联的文档来源。</p>
 */
public class Knowledge implements JSONable {

    /** 查询。 */
    public String query;

    /** 元数据列表。 */
    public List<Metadata> metadataList;

    public Knowledge(String query) {
        this.query = query;
        this.metadataList = new ArrayList<>();
    }

    public Knowledge(JSONObject json) {
        this.query = json.getString("query");
        this.metadataList = new ArrayList<>();
        if (json.has("metadata")) {
            JSONArray array = json.getJSONArray("metadata");
            for (int i = 0; i < array.length(); ++i) {
                Metadata metadata = new Metadata(array.getJSONObject(i));
                this.metadataList.add(metadata);
            }
        }
    }

    /**
     * 判断是否空。
     *
     * @return 空。
     */
    public boolean isEmpty() {
        return this.metadataList.isEmpty();
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

        JSONArray array = new JSONArray();
        for (Metadata metadata : this.metadataList) {
            array.put(metadata.toJSON());
        }
        json.put("metadata", array);

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

    /**
     * 生成提示词。
     *
     * @return 提示词。
     */
    public String generatePrompt() {
        StringBuilder buf = new StringBuilder();
        for (Metadata metadata : this.metadataList) {
            buf.append(metadata.content);
            buf.append("\n\n");
        }
        String prompt = Consts.formatQuestion(buf.toString(), this.query);
        return prompt;
    }


    /**
     * 将相同的源进行合并。
     * @return 列表；无数据时为空列表。
     */
    public List<KnowledgeSource> mergeSources() {
        List<KnowledgeSource> list = new ArrayList<>();

        List<Metadata> mdList = new ArrayList<>();
        for (Metadata metadata : this.metadataList) {
            if (mdList.contains(metadata)) {
                continue;
            }
            mdList.add(metadata);
        }

        for (Metadata metadata : mdList) {
            KnowledgeSource source = metadata.getKnowledgeSource();
            if (null != source) {
                list.add(source);
            }
        }

        return list;
    }

    public class Metadata {

        public final static String DOCUMENT_PREFIX = "document:";

        public final static String ARTICLE_PREFIX = "article:";

        public final static String SEGMENT_PREFIX = "segment:";

        private String content;

        private String source;

        private KnowledgeSource knowledgeSource;

        private double score;

        public Metadata(JSONObject json) {
            this.content = json.getString("content");
            this.source = json.getString("source");
            String s = json.getString("score");
            this.score = Double.parseDouble(s);
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("content", this.content);
            json.put("source", this.source);
            json.put("score", Double.toString(this.score));
            return json;
        }

        public String getContent() {
            return this.content;
        }

        public String getSourceKey() {
            if (this.source.startsWith(DOCUMENT_PREFIX)) {
                return this.source.substring(DOCUMENT_PREFIX.length());
            }
            else if (this.source.startsWith(ARTICLE_PREFIX)) {
                return this.source.substring(ARTICLE_PREFIX.length());
            }
            else {
                return this.source.substring(SEGMENT_PREFIX.length());
            }
        }

        public boolean isDocument() {
            return this.source.startsWith(DOCUMENT_PREFIX);
        }

        public boolean isArticle() {
            return this.source.startsWith(ARTICLE_PREFIX);
        }

        public void setKnowledgeSource(KnowledgeSource knowledgeSource) {
            this.knowledgeSource = knowledgeSource;
        }

        public KnowledgeSource getKnowledgeSource() {
            return this.knowledgeSource;
        }


        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Metadata) {
                if (this.source.equals(((Metadata) obj).source)) {
                    return true;
                }

                // File Code 或 ID 重复
                String key = this.getSourceKey();
                String otherKey = ((Metadata) obj).getSourceKey();
                if (null != key && null != otherKey) {
                    return key.equals(otherKey);
                }
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.source.hashCode();
        }
    }
}
