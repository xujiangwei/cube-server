/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.listener;

import cube.aigc.SpecializedDetectionException;
import cube.common.entity.AIGCChannel;
import cube.common.entity.SpecializedDetectionOutput;

public interface SpecializedDetectionListener {
    void onResponse(AIGCChannel channel, SpecializedDetectionOutput output);

    void onFailed(AIGCChannel channel, SpecializedDetectionException error);
}
