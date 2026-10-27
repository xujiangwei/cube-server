/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.app;

import cube.aigc.psychology.algorithm.BigFivePersonality;
import cube.aigc.psychology.composition.HexagonDimensionScore;
import cube.common.JSONable;
import cube.util.ConfigUtils;
import cube.util.Gender;
import org.json.JSONObject;

/**
 * 客户。
 */
public class Customer implements JSONable {

    /**
     * 正常状态。
     */
    public final static int STATE_NORMAL = 0;

    /**
     * 已删除。
     */
    public final static int STATE_DELETE = 1;

    /** ID。 */
    public long id;

    /** 名称。 */
    public String name;

    /** 性别。 */
    public Gender gender;

    /** 年龄。 */
    public int age;

    /** 手机号。 */
    public String mobile;

    /** 评论。 */
    public String comment;

    /** 时间戳。 */
    public long timestamp;

    /** 状态。 */
    public int state = STATE_NORMAL;

    /** 六边形维度分数。 */
    public HexagonDimensionScore hexagonScore;

    /** 人格。 */
    public BigFivePersonality personality;

    public Customer(String name, Gender gender, int age, String mobile, String comment, long timestamp) {
        this.id = ConfigUtils.generateSerialNumber();
        this.name = name;
        this.gender = gender;
        this.age = age;
        this.mobile = mobile;
        this.comment = comment;
        this.timestamp = timestamp;
    }

    public Customer(long id, String name, Gender gender, int age, String mobile, String comment, long timestamp,
                    int state) {
        this.id = id;
        this.name = name;
        this.gender = gender;
        this.age = age;
        this.mobile = mobile;
        this.comment = comment;
        this.timestamp = timestamp;
        this.state = state;
    }

    public Customer(JSONObject json) {
        this.id = json.getLong("id");
        this.name = json.getString("name");
        this.gender = Gender.parse(json.getString("gender"));
        this.age = json.getInt("age");
        this.mobile = json.getString("mobile");
        this.comment = json.has("comment") ? json.getString("comment") : null;
        this.timestamp = json.has("timestamp") ? json.getLong("timestamp") : System.currentTimeMillis();
        this.state = json.has("state") ? json.getInt("state") : STATE_NORMAL;
        this.hexagonScore = json.has("hexagonScore") ?
                new HexagonDimensionScore(json.getJSONObject("hexagonScore")) : null;
        this.personality = json.has("personality") ?
                new BigFivePersonality(json.getJSONObject("personality")) : null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("name", this.name);
        json.put("gender", this.gender.name);
        json.put("age", this.age);
        json.put("mobile", this.mobile);
        json.put("timestamp", this.timestamp);
        if (null != this.comment) {
            json.put("comment", this.comment);
        }
        if (null != this.hexagonScore) {
            json.put("hexagonScore", this.hexagonScore.toJSON());
        }
        if (null != this.personality) {
            json.put("personality", this.personality.toJSON());
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
