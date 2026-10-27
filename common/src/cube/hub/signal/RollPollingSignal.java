/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cube.common.entity.ConversationType;
import org.json.JSONObject;

/**
 * 轮询信令。
 */
public class RollPollingSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "RollPolling";

    private ConversationType conversationType;

    private String conversationName;

    private int limit = 5;

    public RollPollingSignal(String code, ConversationType conversationType, String conversationName) {
        super(NAME);
        setCode(code);
        this.conversationType = conversationType;
        this.conversationName = conversationName;
    }

    public RollPollingSignal(JSONObject json) {
        super(json);
        this.conversationType = ConversationType.parse(json.getInt("conversationType"));
        this.conversationName = json.getString("conversationName");
        this.limit = json.getInt("limit");
    }

    /**
     * 获取会话类型。
     *
     * @return 会话类型。
     */
    public ConversationType getConversationType() {
        return this.conversationType;
    }

    /**
     * 获取会话名称。
     *
     * @return 会话名称。
     */
    public String getConversationName() {
        return this.conversationName;
    }

    /**
     * 设置拉取条数上限。
     *
     * @param limit 条数。
     */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    /**
     * 获取拉取条数上限。
     *
     * @return 条数。
     */
    public int getLimit() {
        return this.limit;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("conversationType", this.conversationType.code);
        json.put("conversationName", this.conversationName);
        json.put("limit", this.limit);
        return json;
    }
}
