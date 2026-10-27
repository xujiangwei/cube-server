/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

/**
 * 遍历访问记录。
 */
public class TraverseVisitTrace extends NoticeData {

    /** 通知动作名：遍历访问记录。 */
    public final static String ACTION = "TraverseVisitTrace";

    /** 请求参数：域名。 */
    public final static String DOMAIN = "domain";

    /** 请求参数：联系人 ID 。 */
    public final static String CONTACT_ID = "contactId";

    /** 请求参数：分享码。 */
    public final static String SHARING_CODE = "sharingCode";

    public TraverseVisitTrace(String domain, long contactId, String sharingCode) {
        super(ACTION);
        this.put(DOMAIN, domain);
        this.put(CONTACT_ID, contactId);
        this.put(SHARING_CODE, sharingCode);
    }
}
