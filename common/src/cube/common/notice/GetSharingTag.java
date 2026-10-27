/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

import cube.common.action.FileStorageAction;

/**
 * 获取文件的分享标签。
 */
public class GetSharingTag extends NoticeData {

    /** 通知动作名：获取分享标签。 */
    public final static String ACTION = FileStorageAction.GetSharingTag.name;

    /** 请求参数：分享码。 */
    public final static String SHARING_CODE = "sharingCode";

    public GetSharingTag(String sharingCode) {
        super(ACTION);
        this.put(SHARING_CODE, sharingCode);
    }
}
