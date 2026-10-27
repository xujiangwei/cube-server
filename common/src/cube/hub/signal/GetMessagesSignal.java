/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 获取消息信令。
 */
public class GetMessagesSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "GetMessages";

    private String partnerId;

    private String groupName;

    private int beginIndex;

    private int endIndex;

    public GetMessagesSignal(String channelCode, int beginIndex, int endIndex) {
        super(NAME);
        setCode(channelCode);
        this.beginIndex = beginIndex;
        this.endIndex = endIndex;
    }

    public GetMessagesSignal(JSONObject json) {
        super(json);
        if (json.has("groupName")) {
            this.groupName = json.getString("groupName");
        }
        if (json.has("partnerId")) {
            this.partnerId = json.getString("partnerId");
        }

        if (json.has("begin")) {
            this.beginIndex = json.getInt("begin");
        }

        if (json.has("end")) {
            this.endIndex = json.getInt("end");
        }
    }

    /**
     * 获取起始索引。
     *
     * @return 起始索引，从 0 开始。
     */
    public int getBeginIndex() {
        return this.beginIndex;
    }

    /**
     * 获取结束索引。
     *
     * @return 结束索引。
     */
    public int getEndIndex() {
        return this.endIndex;
    }

    /**
     * 设置对端 ID 。
     *
     * @param partnerId 对端标识。
     */
    public void setPartnerId(String partnerId) {
        this.partnerId = partnerId;
    }

    /**
     * 获取对端 ID 。
     *
     * @return 对端标识。
     */
    public String getPartnerId() {
        return this.partnerId;
    }

    /**
     * 设置组名称。
     *
     * @param groupName 组名称。
     */
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    /**
     * 获取组名称。
     *
     * @return 组名称。
     */
    public String getGroupName() {
        return this.groupName;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
         JSONObject json = super.toJSON();
         if (null != this.groupName) {
             json.put("groupName", this.groupName);
         }
         else if (null != this.partnerId) {
             json.put("partnerId", this.partnerId);
         }

         json.put("begin", this.beginIndex);
         json.put("end", this.endIndex);

         return json;
    }
}
