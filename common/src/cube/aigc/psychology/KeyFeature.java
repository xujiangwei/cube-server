/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology;

import cube.aigc.psychology.algorithm.Score;
import cube.aigc.psychology.indicator.Indicable;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键特征。
 *
 * <p>标记一项对结论有决定性影响的特征。</p>
 */
public class KeyFeature implements JSONable {

    private String name;

    private String description;

    private List<Score> indicators;

    public KeyFeature(String name, String description) {
        this.name = name;
        this.description = description;
        this.indicators = new ArrayList<>();
    }

    public KeyFeature(JSONObject json) {
        this.name = json.getString("name");
        this.description = json.getString("description");
        this.indicators = new ArrayList<>();
        JSONArray array = json.getJSONArray("indicators");
        for (int i = 0; i < array.length(); ++i) {
            Score score = new Score(array.getJSONObject(i));
            this.indicators.add(score);
        }
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
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
     * 设置描述。
     *
     * @param description 描述。
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 添加指标分数。
     *
     * @param score 指标分数。
     */
    public void addIndicatorScore(Score score) {
        this.indicators.add(score);
    }

    /**
     * 获取指标分数列表。
     *
     * @return 指标分数列表。
     */
    public List<Score> getIndicatorScores() {
        return this.indicators;
    }

    /**
     * 获取指标分数。
     *
     * @param indicator 指标分数。
     * @return 指标分数。
     */
    public Score getIndicatorScore(Indicable indicator) {
        for (Score score : this.indicators) {
            if (score.indicator == indicator ||
                score.indicator.getCode().equalsIgnoreCase(indicator.getCode())) {
                return score;
            }
        }
        return null;
    }

    /**
     * 构造提示词。
     *
     * @param theme 提示词。
     * @param attribute 提示词。
     * @return 提示词。
     */
    public String makePrompt(Theme theme, Attribute attribute) {
        StringBuilder buf = new StringBuilder();
        if (attribute.language.isChinese()) {
            switch (theme) {
                case Generic:
                case HouseTreePerson:
                    buf.append("在房树人心理绘画中，");
                    buf.append(this.description);
                    buf.append("绘画者为").append(attribute.getGenderText()).append("性");
                    buf.append("，年龄是").append(attribute.getAgeText()).append("。");
                    buf.append("请根据房树人理论说明一下以上表述。");
                    break;
                default:
                    break;
            }
        }
        else {
            // TODO XJW
        }
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        json.put("description", this.description );
        JSONArray array = new JSONArray();
        for (Score score : this.indicators) {
            array.put(score.toJSON());
        }
        json.put("indicators", array);
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
