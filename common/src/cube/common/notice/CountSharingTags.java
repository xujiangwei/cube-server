/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import org.json.JSONObject;

/**
 * 计算分享标签。
 */
public class CountSharingTags extends NoticeData {

    /** 通知动作名：统计分享标签数量。 */
    public final static String ACTION = "CountSharingTags";

    /** 请求参数：域名。 */
    public final static String DOMAIN = "domain";

    /** 请求参数：联系人 ID 。 */
    public final static String CONTACT_ID = "contactId";

    /** 响应字段：有效标签数量。 */
    public final static String VALID_NUMBER = "valid";

    /** 响应字段：已失效标签数量。 */
    public final static String INVALID_NUMBER = "invalid";

    public CountSharingTags(String domain, long contactId) {
        super(ACTION);
        this.put(DOMAIN, domain);
        this.put(CONTACT_ID, contactId);
    }

    /** 从响应中解析有效标签数量。 */
    public static int parseValidNumber(JSONObject json) {
        return json.has(VALID_NUMBER) ? json.getInt(VALID_NUMBER) : 0;
    }

    /** 从响应中解析已失效标签数量。 */
    public static int parseInvalidNumber(JSONObject json) {
        return json.has(INVALID_NUMBER) ? json.getInt(INVALID_NUMBER) : 0;
    }
}
