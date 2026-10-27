/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.aigc.psychology.Term;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 知识策略。
 */
public class KnowledgeStrategy implements JSONable {

    private Term term;

    private String interpretation;

    private String advise;

    private String remark;

    private String explain;

    public KnowledgeStrategy(JSONObject json) {
        this.term = json.has("term") ?
                Term.parse(json.getString("term")) : Term.parse(json.getString("comment"));

        if (json.has("interpretation")) {
            this.interpretation = json.getString("interpretation");
        }
        if (json.has("advise")) {
            this.advise = json.getString("advise");
        }
        if (json.has("remark")) {
            this.remark = json.getString("remark");
        }
        if (json.has("explain")) {
            this.explain = json.getString("explain");
        }
    }

    /**
     * 获取术语。
     *
     * @return 术语对象。
     */
    public Term getTerm() {
        return this.term;
    }

    /**
     * 判断是否空。
     *
     * @return 空。
     */
    public boolean isEmpty() {
        return (null == this.interpretation);
    }

    /**
     * 获取解释文本。
     *
     * @return 解释；未设置返回 {@code null} 。
     */
    public String getInterpretation() {
        return this.interpretation;
    }

    /**
     * 获取建议文本。
     *
     * @return 建议；未设置返回 {@code null} 。
     */
    public String getAdvise() {
        return this.advise;
    }

    /**
     * 获取备注。
     *
     * @return 备注。
     */
    public String getRemark() {
        return this.remark;
    }

    /**
     * 获取补充说明。
     *
     * @return 说明；未设置返回 {@code null} 。
     */
    public String getExplain() {
        return this.explain;
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param obj 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof KnowledgeStrategy) {
            KnowledgeStrategy other = (KnowledgeStrategy) obj;
            if (other.term == this.term) {
                return true;
            }
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 哈希值。
     */
    @Override
    public int hashCode() {
        return this.term.hashCode();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();
        if (null != this.interpretation) {
            json.put("interpretation", this.interpretation);
        }
        if (null != this.advise) {
            json.put("advise", this.advise);
        }
        if (null != this.remark) {
            json.put("remark", this.remark);
        }
        if (null != this.explain) {
            json.put("explain", this.explain);
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
        JSONObject json = new JSONObject();
        json.put("term", this.term.word);
        return json;
    }
}
