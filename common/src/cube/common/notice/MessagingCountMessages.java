/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import org.json.JSONObject;

/**
 * 计算消息条目数。
 */
public class MessagingCountMessages extends NoticeData {

    /** 通知动作名：统计消息数量。 */
    public final static String ACTION = "CountMessages";

    /** 请求参数：域名。 */
    public final static String DOMAIN = "domain";

    /** 响应字段：消息数量。 */
    public final static String COUNT = "count";

    public MessagingCountMessages(String domain) {
        super(ACTION);
        this.put(DOMAIN, domain);
        this.put(COUNT, 0);
    }

    public MessagingCountMessages(JSONObject json) {
        super(json, DOMAIN, COUNT);
    }

    /** 获取域名。 */
    public String getDomain() {
        return this.getString(DOMAIN);
    }

    /** 设置消息数量。 */
    public void setCount(int value) {
        this.put(COUNT, value);
    }

    /** 获取消息数量。 */
    public int getCount() {
        return this.getInt(COUNT);
    }
}
