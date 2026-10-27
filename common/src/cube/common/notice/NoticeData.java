/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import org.json.JSONObject;

/**
 * 通知数据。
 */
public class NoticeData extends JSONObject {

    /** 协议字段：动作名。 */
    public final static String ACTION = "action";

    /** 协议字段：异步通知标识，用于把响应投递回发起方。 */
    public final static String ASYNC_NOTIFIER = "_async_notifier";

    /** 协议字段：联系人 ID 。 */
    public final static String CONTACT_ID = "contactId";

    /** 协议字段：域名。 */
    public final static String DOMAIN = "domain";

    /** 协议字段：处理参数。 */
    public final static String PARAMETER = "parameter";

    /** 协议字段：状态码。 */
    public final static String CODE = "code";

    /** 协议字段：响应数据。 */
    public final static String DATA = "data";

    /** 协议字段：文件码。 */
    public final static String FILE_CODE = "fileCode";

    /** 协议字段：文件路径。 */
    public final static String PATH = "path";

    /** 协议字段：文件标签。 */
    public final static String FILE_LABEL = "fileLabel";

    /** 协议字段：文件地址。 */
    public final static String URL = "url";

    /** 协议字段：返回条数上限。 */
    public final static String LIMIT = "limit";

    public NoticeData(String action) {
        super();
        this.put(NoticeData.ACTION, action);
    }

    public NoticeData(JSONObject json, String... names) {
        super(json, names);
        this.put(NoticeData.ACTION, json.getString(NoticeData.ACTION));
    }

    /** 获取动作名。 */
    public String getAction() {
        return this.getString(NoticeData.ACTION);
    }
}
