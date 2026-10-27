/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.FileStorageAction;

/**
 * 批量获取访问记录。
 */
public class ListSharingTraces extends NoticeData {

    /** 通知动作名：查询分享访问记录。 */
    public final static String ACTION = FileStorageAction.ListSharingTraces.name;

    /** 请求参数：分享码。 */
    public final static String SHARING_CODE = "sharingCode";

    /** 请求参数：分页起始序号。 */
    public final static String BEGIN = "begin";

    /** 请求参数：分页结束序号。 */
    public final static String END = "end";

    /** 请求参数：起始时间。 */
    public final static String BEGIN_TIME = "beginTime";

    /** 请求参数：结束时间。 */
    public final static String END_TIME = "endTime";

    public ListSharingTraces(long contactId, String domain, String sharingCode, int begin, int end) {
        super(ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(SHARING_CODE, sharingCode);
        this.put(BEGIN, begin);
        this.put(END, end);
    }

    public ListSharingTraces(long contactId, String domain, long beginTime, long endTime) {
        super(ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(BEGIN_TIME, beginTime);
        this.put(END_TIME, endTime);
    }
}
