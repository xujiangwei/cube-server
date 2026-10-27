/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

import java.util.List;

/**
 * AIGC Chat 历史记录。
 */
public class AIGCChatHistory extends Entity {

    /** 对话序列号。 */
    public final long sn;

    /** 所属通道码。 */
    public final String channelCode;

    /** 生成回答的单元名称。 */
    public String unit;

    /** 提问方联系人 ID 。 */
    public long queryContactId;

    /** 提问时间戳，单位毫秒。 */
    public long queryTime;

    /** 提问内容。 */
    public String queryContent;

    /** 提问携带的文件标签。 */
    public List<FileLabel> queryFileLabels;

    /** 回答方联系人 ID 。 */
    public long answerContactId;

    /** 回答时间戳，单位毫秒。 */
    public long answerTime;

    /** 回答内容。 */
    public String answerContent;

    /** 回答产生的文件标签。 */
    public List<FileLabel> answerFileLabels;

    /** 模型的思考过程。 */
    public String thought;

    /** 本轮对话的结构化上下文。 */
    public ComplexContext context;

    /** 用户反馈值，0 表示未反馈，1 表示差评。 */
    public int feedback = 0;

    /** 对话上下文 ID 。 */
    public long contextId = 0;

    public AIGCChatHistory(long sn, String channelCode, String unit, String domain) {
        super(0L, (null != domain ? domain : ""));
        this.sn = sn;
        this.channelCode = channelCode;
        this.unit = unit;
    }

    public AIGCChatHistory(long id, long sn, String channelCode, String domain) {
        super(id, (null != domain ? domain : ""));
        this.sn = sn;
        this.channelCode = channelCode;
    }

    public AIGCChatHistory(JSONObject json) {
        super(json);
        this.sn = json.getLong("sn");
        this.channelCode = json.has("channel") ? json.getString("channel") : "-";
        this.unit = json.getString("unit");
        this.queryContactId = json.getLong("queryContactId");
        this.queryTime = json.getLong("queryTime");
        this.queryContent = json.getString("queryContent");
        this.answerContactId = json.getLong("answerContactId");
        this.answerTime = json.getLong("answerTime");
        this.answerContent = json.getString("answerContent");
        this.thought = json.has("thought") ? json.getString("thought") : "";
        this.feedback = json.getInt("feedback");
        this.contextId = json.getLong("contextId");
    }

    /** 序列化为完整 JSON 。 */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("sn", this.sn);
        json.put("channel", this.channelCode);
        json.put("unit", this.unit);
        json.put("queryContactId", this.queryContactId);
        json.put("queryTime", this.queryTime);
        json.put("queryContent", this.queryContent);
        json.put("answerContactId", this.answerContactId);
        json.put("answerTime", this.answerTime);
        json.put("answerContent", this.answerContent);
        json.put("thought", (null != this.thought) ? this.thought : "");
        json.put("feedback", this.feedback);
        json.put("contextId", this.contextId);
        return json;
    }

    /** 序列化为精简 JSON 。 */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
