/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.FileStorageAction;

/**
 * 批量获取分享标签。
 */
public class ListFiles extends NoticeData {

    /** 通知动作名：查询文件列表。 */
    public final static String ACTION = FileStorageAction.ListFiles.name;

    /** 请求参数：起始时间。 */
    public final static String BEGIN_TIME = "beginTime";

    /** 请求参数：结束时间。 */
    public final static String END_TIME = "endTime";

    public ListFiles(long contactId, String domain, long beginTime, long endTime) {
        super(ListFiles.ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(BEGIN_TIME, beginTime);
        this.put(END_TIME, endTime);
    }
}
