/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.auth.AuthToken;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索结果。
 */
public class SearchResult implements JSONable {

    /** 序列号。 */
    public long sn;

    /** 引擎。 */
    public String engine;

    /** 创建时间。 */
    public long created;

    /** 原句。 */
    public String originSentence;

    /** 关键词列表。 */
    public List<String> keywords;

    /** 自然搜索结果。 */
    public List<OrganicResult> organicResults;

    /** 访问令牌。 */
    public AuthToken authToken;

    /**
     * 是否已被客户端拉取数据。
     */
    public boolean popup = false;

    public SearchResult(String originSentence) {
        this.sn = Utils.generateSerialNumber();
        this.engine = "unknown";
        this.created = System.currentTimeMillis();
        this.originSentence = originSentence;
    }

    /**
     * 判断是否结果。
     *
     * @return 结果。
     */
    public boolean hasResult() {
        return (null != this.organicResults && !this.organicResults.isEmpty());
    }

    /**
     * 添加自然搜索结果。
     *
     * @param position 自然搜索结果。
     * @param title 自然搜索结果。
     * @param link 自然搜索结果。
     * @param snippet 自然搜索结果。
     */
    public void addOrganicResult(int position, String title, String link, String snippet) {
        if (null == this.organicResults) {
            this.organicResults = new ArrayList<>();
        }

        OrganicResult result = new OrganicResult(position, title, link, snippet);
        this.organicResults.add(result);
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();

        json.put("sn", this.sn);
        json.put("engine", this.engine);
        json.put("created", this.created);

        if (null != this.keywords) {
            JSONArray array = new JSONArray();
            for (String word : this.keywords) {
                array.put(word);
            }
            json.put("keywords", array);
        }

        if (null != this.organicResults) {
            JSONArray array = new JSONArray();
            for (OrganicResult orgRes : this.organicResults) {
                array.put(orgRes.toJSON());
            }
            json.put("organicResults", array);
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

    public class OrganicResult {

        public int position;

        public String title;

        public String link;

        public String snippet;

        public OrganicResult(int position, String title, String link, String snippet) {
            this.position = position;
            this.title = title;
            this.link = link;
            this.snippet = snippet;
        }

        public OrganicResult(JSONObject json) {
            this.position = json.getInt("position");
            this.title = json.getString("title");
            this.link = json.getString("link");
            this.snippet = json.getString("snippet");
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("position", this.position);
            json.put("title", this.title);
            json.put("link", this.link);
            json.put("snippet", this.snippet);
            return json;
        }
    }
}
