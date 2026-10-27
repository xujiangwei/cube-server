/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.psychology.algorithm.Representation;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PaintingLabel implements JSONable {

    /** 状态正常。 */
    public final static int STATE_NORMAL = 0;

    /** 标签状态：忽略。 */
    public final static int STATE_IGNORE = 1;

    private long sn;

    private long timestamp;

    private String description;

    private List<EvaluationScore> evaluationScores;

    private List<Representation> representations;

    public PaintingLabel(JSONObject json) {
        this.sn = json.getLong("sn");
        this.timestamp = json.getLong("timestamp");
        this.description = json.getString("description");
        if (json.has("evaluationScores")) {
            this.evaluationScores = new ArrayList<>();
            JSONArray array = json.getJSONArray("evaluationScores");
            for (int i = 0; i < array.length(); ++i) {
                this.evaluationScores.add(new EvaluationScore(array.getJSONObject(i)));
            }
        }
        if (json.has("representations")) {
            this.representations = new ArrayList<>();
            JSONArray array = json.getJSONArray("representations");
            for (int i = 0; i < array.length(); ++i) {
                this.representations.add(new Representation(array.getJSONObject(i)));
            }
        }
    }

    public PaintingLabel(long sn, long timestamp, String description, JSONArray evaluationScores) {
        this.sn = sn;
        this.timestamp = timestamp;
        this.description = description;
        this.evaluationScores = new ArrayList<>();
        for (int i = 0; i < evaluationScores.length(); ++i) {
            this.evaluationScores.add(new EvaluationScore(evaluationScores.getJSONObject(i)));
        }
    }

    /**
     * 获取序列号。
     *
     * @return 序列号。
     */
    public long getSn() {
        return this.sn;
    }

    /**
     * 获取时间戳。
     *
     * @return 时间戳。
     */
    public long getTimestamp() {
        return this.timestamp;
    }

    /**
     * 获取描述。
     *
     * @return 描述。
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * 获取评估分数列表。
     *
     * @return 评估分数列表。
     */
    public List<EvaluationScore> getEvaluationScores() {
        return this.evaluationScores;
    }

    /**
     * 获取评估分数数组。
     *
     * @return 分数 JSON 数组。
     */
    public JSONArray getEvaluationScoresAsJSONArray() {
        JSONArray array = new JSONArray();
        for (EvaluationScore score : this.evaluationScores) {
            JSONObject scoreJson = score.toJSON();
            scoreJson.put("indicator", score.indicator.getCode());
            array.put(scoreJson);
        }
        return array;
    }

    /**
     * 获取表征列表。
     *
     * @return 表征列表。
     */
    public List<Representation> getRepresentations() {
        return this.representations;
    }

    /**
     * 获取表征数组。
     *
     * @return 表征 JSON 数组。
     */
    public JSONArray getRepresentationsAsJSONArray() {
        if (null == this.representations) {
            return null;
        }
        JSONArray array = new JSONArray();
        for (Representation representation : this.representations) {
            array.put(representation.toJSON());
        }
        return array;
    }

    /**
     * 设置表征列表。
     *
     * @param array 表征列表。
     */
    public void setRepresentations(JSONArray array) {
        this.representations = new ArrayList<>();
        for (int i = 0; i < array.length(); ++i) {
            this.representations.add(new Representation(array.getJSONObject(i)));
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();
        if (null != this.evaluationScores) {
            json.put("evaluationScores", this.getEvaluationScoresAsJSONArray());
        }
        if (null != this.representations) {
            json.put("representations", this.getRepresentationsAsJSONArray());
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
        json.put("sn", this.sn);
        json.put("timestamp", this.timestamp);
        json.put("description", this.description);
        return json;
    }
}
