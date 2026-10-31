/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.listener;

import cube.aigc.SpecializedDetectionException;
import cube.common.entity.AIGCChannel;
import cube.common.entity.SpecializedDetectionOutput;

/**
 * 专用检测结果监听器。
 */
public interface SpecializedDetectionListener {
    /**
     * 检测成功回调。
     *
     * @param channel 请求所属的 AIGC 通道
     * @param output  检测结果
     */
    void onResponse(AIGCChannel channel, SpecializedDetectionOutput output);

    /**
     * 检测失败回调。
     *
     * @param channel 请求所属的 AIGC 通道
     * @param error   失败原因
     */
    void onFailed(AIGCChannel channel, SpecializedDetectionException error);
}
