/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.aigc.Usage;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 多模态输出。
 */
public class MultimodalOutput implements JSONable {

    /** 序列号。 */
    public final long sn;

    /** 单元。 */
    public final String unit;

    /** 回答。 */
    public String answer;

    /** 思考过程。 */
    public String thought;

    /** 结果载荷。 */
    public JSONObject resultPayload;

    /** 用量。 */
    public Usage usage;

    /** 时间戳。 */
    public long timestamp;

    /** 上下文。 */
    public ComplexContext context;

    public MultimodalOutput(long sn, String unit, String answer, String thought, JSONObject resultPayload) {
        this.sn = sn;
        this.unit = unit;
        this.answer = answer;
        this.thought = thought;
        this.resultPayload = resultPayload;
        this.timestamp = System.currentTimeMillis();
    }

    public MultimodalOutput(JSONObject json) {
        if (json.has("sn")) {
            this.sn = json.getLong("sn");
        }
        else {
            this.sn = Utils.generateSerialNumber();
        }

        if (json.has("unit")) {
            this.unit = json.getString("unit");
        }
        else {
            this.unit = "";
        }

        if (json.has("answer")) {
            this.answer = json.getString("answer");
        }
        else {
            this.answer = "";
        }

        if (json.has("thought")) {
            this.thought = json.getString("thought");
        }
        else {
            this.thought = "";
        }

        if (json.has("resultPayload")) {
            this.resultPayload = json.getJSONObject("resultPayload");
        }

        if (json.has("usage")) {
            this.usage = new Usage(json.getJSONObject("usage"));
        }

        if (json.has("timestamp")) {
            this.timestamp = json.getLong("timestamp");
        }
        else {
            this.timestamp = System.currentTimeMillis();
        }

        if (json.has("context")) {
            this.context = new ComplexContext(json.getJSONObject("context"));
        }
    }

    public MultimodalOutput(GeneratingRecord record) {
        this.sn = record.sn;
        this.unit = record.unit;
        this.answer = record.answer;
        this.thought = record.thought;
        this.resultPayload = record.resultPayload;
        this.context = record.context;
        this.timestamp = record.timestamp;
    }

    /**
     * 转换为对话记录。
     *
     * @return 生成记录；无文本内容时返回 {@code null} 。
     */
    public GeneratingRecord toRecord() {
        return new GeneratingRecord(this.sn,
                this.unit, "", this.answer, this.thought, this.resultPayload,
                this.timestamp, this.context);
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
        json.put("unit", this.unit);
        json.put("answer", this.answer);
        json.put("thought", this.thought);
        json.put("timestamp", this.timestamp);

        if (null != this.resultPayload) {
            json.put("resultPayload", this.resultPayload);
        }

        if (null != this.usage) {
            json.put("usage", this.usage.toJSON());
        }

        if (null != this.context) {
            json.put("context", this.context.toJSON());
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
        JSONObject json = this.toJSON();
        if (json.has("context")) {
            json.remove("context");
        }
        return json;
    }
}
