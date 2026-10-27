/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.notice;

/**
 * 计算文件数量。
 */
public class FileStorageCountFiles extends NoticeData {

    /** 通知动作名：统计文件数量。 */
    public final static String ACTION = "CountFiles";

    /** 请求参数：域名。 */
    public final static String DOMAIN = "domain";

    public FileStorageCountFiles(String domain) {
        super(ACTION);
        this.put(DOMAIN, domain);
    }
}
