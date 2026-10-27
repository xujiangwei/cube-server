/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cell.util.Utils;
import cube.common.JSONable;
import cube.common.entity.GeneratingRecord;
import cube.common.entity.KnowledgeBaseInfo;
import cube.common.entity.KnowledgeQAResult;
import cube.common.state.AIGCStateCode;
import cube.util.EmojiFilter;
import org.json.JSONObject;

import java.util.Date;

/**
 * 应用事件。
 */
public class AppEvent implements JSONable {

    /**
     * 会话激活事件。
     */
    public final static String Session = "Session";

    /**
     * 对话事件。
     */
    public final static String Chat = "Chat";

    /**
     * 知识库问答事件。
     */
    public final static String Knowledge = "Knowledge";

    /**
     * 提交了新文件事件。
     */
    public final static String NewFile = "NewFile";

    /**
     * 删除已提交文件事件。
     */
    public final static String DeleteFile = "DeleteFile";

    /**
     * 导入知识库文档事件。
     */
    public final static String ImportKnowledgeDoc = "ImportKnowledgeDoc";

    /**
     * 移除知识库文档事件。
     */
    public final static String RemoveKnowledgeDoc = "RemoveKnowledgeDoc";

    /**
     * 新建知识库。
     */
    public final static String NewKnowledgeBase = "NewKnowledgeBase";

    /**
     * 删除知识库。
     */
    public final static String DeleteKnowledgeBase = "DeleteKnowledgeBase";

    /** 事件名称，取值见本类的 {@code Session} 、{@code Chat} 等常量。 */
    public final String event;

    /** 事件产生时间戳，单位毫秒。 */
    public final long timestamp;

    /** 事件产生时间的可读字符串。 */
    public final String time;

    /** 事件关联的联系人 ID ，无关联时为 0 。 */
    public long contactId;

    /**
     * 事件载荷。
     *
     * <p>写入前已过滤表情字符，避免下游解析失败。</p>
     */
    public JSONObject data;

    public AppEvent(String event, long timestamp, JSONObject data) {
        this.event = event;
        this.timestamp = timestamp;
        this.time = Utils.gsDateFormat.format(new Date(this.timestamp));
        this.data = this.filterData(data);
    }

    public AppEvent(String event, long timestamp, long contactId, JSONObject data) {
        this.event = event;
        this.timestamp = timestamp;
        this.time = Utils.gsDateFormat.format(new Date(this.timestamp));
        this.contactId = contactId;
        this.data = this.filterData(data);
    }

    public AppEvent(String event, long timestamp, String time, long contactId, JSONObject data) {
        this.event = event;
        this.timestamp = timestamp;
        this.time = time;
        this.contactId = contactId;
        this.data = this.filterData(data);
    }

    public AppEvent(JSONObject json) {
        this.event = json.getString("event");
        this.timestamp = json.getLong("timestamp");
        this.time = json.has("time") ? json.getString("time") :
                Utils.gsDateFormat.format(new Date(this.timestamp));
        this.data = json.has("data") ? json.getJSONObject("data") : null;
        this.contactId = json.has("contactId") ? json.getLong("contactId") : 0;
    }

    private JSONObject filterData(JSONObject data) {
        if (null == data) {
            return null;
        }

        // 过滤表情字符
        String str = data.toString();
        String result = EmojiFilter.filterEmoji(str);
        try {
            return new JSONObject(result);
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * 获取事件载荷，空值安全。
     *
     * @return 载荷对象；无载荷时返回空 JSON 对象。
     */
    public JSONObject getSafeData() {
        return (null != this.data) ? this.data : new JSONObject();
    }

    /**
     * 读取字符串字段，空值安全。
     *
     * @param key 字段名。
     * @return 字段值；载荷为空或字段缺失时返回空字符串。
     */
    public String getSafeString(String key) {
        return (null != this.data) ? (this.data.has(key) ? this.data.getString(key) : "") : "";
    }
    
    /**
     * 读取整数字段，空值安全。
     *
     * @param key 字段名。
     * @return 字段值；载荷为空或字段缺失时返回 0 。
     */
    public int getSafeInt(String key) {
        return (null != this.data) ? (this.data.has(key) ? this.data.getInt(key) : 0) : 0;
    }

    /** 序列化为完整 JSON 。 */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("event", this.event);
        json.put("timestamp", this.timestamp);
        json.put("time", this.time);
        json.put("contactId", this.contactId);
        if (null != this.data) {
            json.put("data", this.data);
        }
        return json;
    }

    /** 序列化为精简 JSON 。 */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }


    /**
     * 构造对话成功事件载荷。
     *
     * @param record 生成记录。
     * @return 带成功状态码的载荷。
     */
    public static JSONObject createChatSuccessfulData(GeneratingRecord record) {
        JSONObject json = record.toJSON();
        json.put("code", AIGCStateCode.Ok.code);
        return json;
    }

    /**
     * 构造对话失败事件载荷。
     *
     * @param sn        请求序列号。
     * @param stateCode 失败状态码。
     * @param query     用户原始提问。
     * @param unit      失败发生的单元，允许为 {@code null} 。
     * @return 失败载荷。
     */
    public static JSONObject createChatFailedData(long sn, AIGCStateCode stateCode,
                                                  String query, String unit) {
        JSONObject json = new JSONObject();
        json.put("sn", sn);
        json.put("code", stateCode.code);
        json.put("query", query);
        if (null != unit) {
            json.put("unit", unit);
        }
        return json;
    }

    /**
     * 构造知识库问答成功事件载荷。
     *
     * @param result 问答结果。
     * @return 带成功状态码的载荷。
     */
    public static JSONObject createKnowledgeSuccessfulData(KnowledgeQAResult result) {
        JSONObject json = result.toCompactJSON();
        json.put("sn", result.record.sn);
        json.put("code", AIGCStateCode.Ok.code);
        return json;
    }

    /**
     * 构造知识库问答失败事件载荷。
     *
     * @param sn        请求序列号。
     * @param stateCode 失败状态码。
     * @param query     用户原始提问。
     * @return 失败载荷。
     */
    public static JSONObject createKnowledgeFailedData(long sn, AIGCStateCode stateCode,
                                                       String query) {
        JSONObject json = new JSONObject();
        json.put("sn", sn);
        json.put("code", stateCode.code);
        json.put("query", query);
        return json;
    }

    /**
     * 补充知识库删除事件所需的字段。
     *
     * @param info 被删除的知识库信息。
     * @param data 待填充的载荷，就地修改并返回。
     * @return 填充后的载荷。
     */
    public static JSONObject createDeleteKnowledgeBaseData(KnowledgeBaseInfo info, JSONObject data) {
        data.put("name", info.name);
        data.put("category", info.category);
        data.put("baseTimestamp", info.timestamp);
        return data;
    }
}
