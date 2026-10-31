/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.aigc;

/**
 * Catalog of known detection profiles. Add future profiles here as they are introduced.
 * This catalog is not a provider capability declaration or a request allowlist:
 * the Python Unit determines support and returns the corresponding status code.
 */
public enum SpecializedDetectionProfile {
    /** Ground-view fire and smoke detection. */
    FireSmoke("fire-smoke"),
    /** UAV-view fire and smoke detection, with optional sliced inference. */
    UavFireSmoke("uav-fire-smoke");

    public final String name;

    SpecializedDetectionProfile(String name) {
        this.name = name;
    }
}
