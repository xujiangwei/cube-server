/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.aigc;

/** Default admission budgets and deadlines; no detection properties are required. */
public final class SpecializedDetectionDefaults {
    public static final int MAX_SESSIONS = 2;
    public static final int DATA_THREADS = 8;
    public static final int DATA_QUEUE = 64;
    public static final int CONTROL_THREADS = 2;
    public static final int CONTROL_QUEUE = 64;
    public static final int DATA_TIMEOUT_MS = 180000;
    public static final int CONTROL_TIMEOUT_MS = 30000;
    // Allow service deadline errors to reach the caller before the gateway expires.
    public static final int GATEWAY_DATA_TIMEOUT_MS = DATA_TIMEOUT_MS + 10000;
    public static final int GATEWAY_CONTROL_TIMEOUT_MS = CONTROL_TIMEOUT_MS + 10000;

    private SpecializedDetectionDefaults() {}
}
