/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排结果。
 *
 * <p>检索片段经过二次排序后的结果。</p>
 */
public class RetrieveReRankResult extends Entity {

    private String query;

    private List<Answer> answerList;

    private RetrieveReRankResultSource source;

    public RetrieveReRankResult(JSONObject json) {
        super(json);
        this.query = json.getString("query");
        this.answerList = new ArrayList<>();
        JSONArray list = json.getJSONArray("list");
        for (int i = 0; i < list.length(); ++i) {
            this.answerList.add(new Answer(list.getJSONObject(i)));
        }
        if (json.has("source")) {
            this.source = new RetrieveReRankResultSource(json.getJSONObject("source"));
        }
    }

    /**
     * 获取查询。
     *
     * @return 查询。
     */
    public String getQuery() {
        return this.query;
    }

    /**
     * 获取回答列表。
     *
     * @return 回答列表。
     */
    public List<Answer> getAnswerList() {
        return this.answerList;
    }

    /**
     * 判断是否回答。
     *
     * @return 回答。
     */
    public boolean hasAnswer() {
        return !this.answerList.isEmpty();
    }

    /**
     * 获取来源。
     *
     * @return 来源。
     */
    public RetrieveReRankResultSource getSource() {
        return this.source;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("query", this.query);
        JSONArray list = new JSONArray();
        for (Answer answer : this.answerList) {
            list.put(answer.toJSON());
        }
        json.put("list", list);
        if (null != this.source) {
            json.put("source", this.source.toJSON());
        }
        return json;
    }


    public class Answer {

        public long id;

        public String content;

        public double score;

        public Answer(JSONObject json) {
            this.id = json.getLong("id");
            this.content = json.getString("content");
            this.score = Double.parseDouble(json.get("score").toString());
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("id", this.id);
            json.put("content", this.content);
            json.put("score", this.score);
            return json;
        }
    }
}
