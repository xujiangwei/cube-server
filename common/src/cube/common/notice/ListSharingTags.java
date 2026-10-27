/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.FileStorageAction;
import cube.util.ConfigUtils;

/**
 * 批量获取分享标签。
 */
public class ListSharingTags extends NoticeData {

    /** 通知动作名：查询分享标签列表。 */
    public final static String ACTION = FileStorageAction.ListSharingTags.name;

    /** 请求参数：分页起始序号。 */
    public final static String BEGIN = "begin";

    /** 请求参数：分页结束序号。 */
    public final static String END = "end";

    /** 请求参数：起始时间。 */
    public final static String BEGIN_TIME = "beginTime";

    /** 请求参数：结束时间。 */
    public final static String END_TIME = "endTime";

    /** 请求参数：是否只返回有效标签。 */
    public final static String VALID = "valid";

    /** 请求参数：排序方式。 */
    public final static String ORDER = "order";

    public ListSharingTags(long contactId, String domain, int begin, int end, boolean valid) {
        super(ListSharingTags.ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(BEGIN, begin);
        this.put(END, end);
        this.put(VALID, valid);
        this.put(ORDER, ConfigUtils.ORDER_DESC);
    }

    public ListSharingTags(long contactId, String domain, long beginTime, long endTime, boolean valid) {
        super(ListSharingTags.ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(BEGIN_TIME, beginTime);
        this.put(END_TIME, endTime);
        this.put(VALID, valid);
        this.put(ORDER, ConfigUtils.ORDER_ASC);
    }
}
