/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.spi;

import cube.common.state.AIGCStateCode;

/**
 * A transport/admission error distinct from a plugin's business response.
 */
public final class PluginCallException extends RuntimeException {
    public final AIGCStateCode state;
    public final int httpStatus;

    public PluginCallException(AIGCStateCode state, int httpStatus, String message) {
        super(message);
        this.state = state;
        this.httpStatus = httpStatus;
    }
}
