/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.RiskManagementAction;

/**
 * 批量获取联系人行为。
 */
public class ListContactBehaviors extends NoticeData {

    /** 通知动作名：查询联系人行为记录。 */
    public final static String ACTION = RiskManagementAction.ListContactBehaviors.name;

    /** 请求参数：起始时间。 */
    public final static String BEGIN_TIME = "beginTime";

    /** 请求参数：结束时间。 */
    public final static String END_TIME = "endTime";

    /** 请求参数：行为类型。 */
    public final static String BEHAVIOR = "behavior";

    public ListContactBehaviors(long contactId, String domain, long beginTime, long endTime) {
        super(ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(BEGIN_TIME, beginTime);
        this.put(END_TIME, endTime);
    }

    public ListContactBehaviors(long contactId, String domain, long beginTime, long endTime, String behavior) {
        super(ACTION);
        this.put(CONTACT_ID, contactId);
        this.put(DOMAIN, domain);
        this.put(BEGIN_TIME, beginTime);
        this.put(END_TIME, endTime);
        this.put(BEHAVIOR, behavior);
    }
}
