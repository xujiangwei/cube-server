/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 知识文档进度。
 */
public class KnowledgeQAProgress implements JSONable {

    private final long sn;

    private AIGCChannel channel;

    private long start;

    private long end;

    private int progress;

    private int totalProgress;

    private int percent = 0;

    private int code = -1;

    private KnowledgeQAResult result;

    public KnowledgeQAProgress(AIGCChannel channel) {
        this.sn = Utils.generateSerialNumber();
        this.channel = channel;
        this.start = System.currentTimeMillis();
    }

    public KnowledgeQAProgress(JSONObject json) {
        this.sn = json.getLong("sn");
        this.channel = new AIGCChannel(json.getJSONObject("channel"));
        this.start = json.getLong("start");
        this.end = json.getLong("end");
        this.percent = json.getInt("percent");
        this.code = json.getInt("code");
        if (json.has("result")) {
            this.result = new KnowledgeQAResult(json.getJSONObject("result"));
        }
    }

    /**
     * 获取开始。
     *
     * @return 开始。
     */
    public long getStart() {
        return this.start;
    }

    /**
     * 定义总数进度。
     *
     * @param value 总数进度。
     */
    public void defineTotalProgress(int value) {
        this.progress = 0;
        this.totalProgress = value;
    }

    /**
     * 更新进度。
     *
     * @param value 进度。
     */
    public int updateProgress(int value) {
        if (this.totalProgress == 0) {
            return 100;
        }

        this.progress += value;
        this.percent = (int) Math.floor(((float) this.progress / (float) this.totalProgress) * 100.0);
        return this.percent;
    }

    /**
     * 获取进度百分比。
     *
     * @return 进度百分比。
     */
    public int getProgressPercent() {
        if (this.totalProgress == 0) {
            return 0;
        }

        return (int) Math.floor(((float) this.progress / (float) this.totalProgress) * 100.0);
    }

    /**
     * 设置码。
     *
     * @param code 码。
     */
    public void setCode(int code) {
        this.code = code;
    }

    /**
     * 设置结果。
     *
     * @param result 结果。
     */
    public void setResult(KnowledgeQAResult result) {
        this.result = result;
        this.end = System.currentTimeMillis();
    }

    /**
     * 获取结果。
     *
     * @return 结果。
     */
    public KnowledgeQAResult getResult() {
        return this.result;
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
        json.put("channel", this.channel.toCompactJSON());
        json.put("start", this.start);
        json.put("end", this.end);
        json.put("percent", this.percent);
        json.put("code", this.code);
        if (null != this.result) {
            json.put("result", this.result.toCompactJSON());
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
