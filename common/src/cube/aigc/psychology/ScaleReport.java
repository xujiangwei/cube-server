/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology;

import cube.aigc.psychology.composition.Scale;
import cube.aigc.psychology.composition.ScaleFactor;
import cube.aigc.psychology.composition.ScalePrompt;
import cube.aigc.psychology.composition.ScaleResult;
import cube.common.state.AIGCStateCode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 心理学量表报告。
 */
public class ScaleReport extends Report {

    private Scale scale;

    private List<ScaleFactor> factors;

    private boolean finished = false;

    private long finishedTimestamp;

    private AIGCStateCode state = AIGCStateCode.Ok;

    public ScaleReport(long contactId, Scale scale) {
        super(scale.getSN(), contactId, System.currentTimeMillis(), scale.getAttribute());
        this.scale = scale;
        this.factors = new ArrayList<>();

        ScaleResult result = this.scale.getResult();
        if (null != result && null != result.prompt) {
            for (ScalePrompt.Factor prompt : result.prompt.getFactors()) {
                String displayName = result.matchFactorName(prompt.factor);
                if (null == displayName) {
                    continue;
                }
                this.factors.add(new ScaleFactor(prompt.factor, displayName, prompt.score));
            }
        }

        this.state = AIGCStateCode.Processing;
    }

    public ScaleReport(long sn, long contactId, long timestamp, Attribute attribute, JSONArray factorArray,
                       Scale scale, int state, String remark) {
        super(sn, contactId, timestamp, attribute);
        this.factors = new ArrayList<>();
        this.scale = scale;
        this.setFactors(factorArray);
        this.state = AIGCStateCode.parse(state);
        this.remark = remark;
        this.finished = true;
        this.finishedTimestamp = this.scale.getEndTimestamp();
    }

    public ScaleReport(JSONObject json) {
        super(json);
        this.finished = json.getBoolean("finished");
        this.finishedTimestamp = json.getLong("finishedTimestamp");
        this.state = AIGCStateCode.parse(json.getInt("state"));
        this.factors = new ArrayList<>();
        JSONArray array = json.getJSONArray("factors");
        for (int i = 0; i < array.length(); ++i) {
            this.factors.add(new ScaleFactor(array.getJSONObject(i)));
        }
        if (json.has("scale")) {
            this.scale = new Scale(json.getJSONObject("scale"));
        }
    }

    /**
     * 获取结果。
     *
     * @return 结果。
     */
    public ScaleResult getResult() {
        return this.scale.getResult();
    }

    /**
     * 获取因子列表。
     *
     * @return 因子列表。
     */
    public List<ScaleFactor> getFactors() {
        return this.factors;
    }

    /**
     * 把因子转为数组。
     *
     * @return 因子数组。
     */
    public JSONArray getFactorsAsJSONArray() {
        JSONArray array = new JSONArray();
        for (ScaleFactor factor : this.factors) {
            array.put(factor.toJSON());
        }
        return array;
    }

    /**
     * 设置因子列表。
     *
     * @param array 因子列表。
     */
    public void setFactors(JSONArray array) {
        for (int i = 0; i < array.length(); ++i) {
            this.factors.add(new ScaleFactor(array.getJSONObject(i)));
        }
    }

    /**
     * 设置是否完成。
     *
     * @param value 是否完成。
     */
    public void setFinished(boolean value) {
        this.finished = value;
        this.finishedTimestamp = System.currentTimeMillis();
    }

    /**
     * 判断是否是否完成。
     *
     * @return 是否完成。
     */
    public boolean isFinished() {
        return this.finished;
    }

    /**
     * 设置状态。
     *
     * @param state 状态。
     */
    public void setState(AIGCStateCode state) {
        this.state = state;
    }

    /**
     * 获取状态。
     *
     * @return 状态。
     */
    public AIGCStateCode getState() {
        return this.state;
    }

    /**
     * 获取量表。
     *
     * @return 量表。
     */
    public Scale getScale() {
        return this.scale;
    }

    /**
     * 设置量表。
     *
     * @param scale 量表。
     */
    public void setScale(Scale scale) {
        this.scale = scale;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("finished", this.finished);
        json.put("finishedTimestamp", this.finishedTimestamp);
        json.put("state", this.state.code);
        json.put("factors", this.getFactorsAsJSONArray());
        if (null != this.scale) {
            json.put("scale", this.scale.toJSON());
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
        JSONObject json = super.toCompactJSON();
        json.put("finished", this.finished);
        json.put("finishedTimestamp", this.finishedTimestamp);
        json.put("state", this.state.code);
        json.put("factors", this.getFactorsAsJSONArray());
        if (null != this.scale) {
            json.put("scale", this.scale.toCompactJSON());
        }
        return json;
    }
}
