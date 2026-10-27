/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.listener;

import cube.aigc.Page;

/**
 * 页面读取回调。
 *
 * <p>在联网抓取到网页内容后回调，用于把正文交回调用链。</p>
 */
public interface ReadPageListener {

    void onCompleted(String url, Page page);
}
