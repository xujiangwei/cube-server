/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cube.service.harness.protocol.Contract;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreConfig;

import java.security.MessageDigest;

/**
 * Validates private configuration without starting services or printing secrets.
 */
public final class HarnessDoctor {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.version").startsWith("1.8.")) throw new IllegalStateException("JDK 8 required");
        HarnessConfig config = HarnessConfig.load();
        if (Boolean.parseBoolean(config.values.getProperty("store.enabled", "false")))
            new JdbcRunStore(new StoreConfig(config.values));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Contract.resource("manifest.json"));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b & 255));
        System.out.println("Harness config valid; execution=scripted; HTTP=" + config.host() + ":" + config.port() + "; contracts=" + hex);
    }
}
