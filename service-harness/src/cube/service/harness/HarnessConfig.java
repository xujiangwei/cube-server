/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;

/**
 * Private per-container configuration; no defaults containing usable credentials.
 */
public final class HarnessConfig {
    final Properties values;

    public HarnessConfig(Properties values) throws IOException {
        this.values = values;
        if (!"development".equals(values.getProperty("mode")))
            throw new IOException("Production identity provider is not implemented at G0");
        if (!InetAddress.getByName(host()).isLoopbackAddress())
            throw new IOException("G0 HTTP listener requires loopback");
        for (String key : Arrays.asList("worker.credential", "api.credential"))
            if (values.getProperty(key, "").length() < 24) throw new IOException("Missing private credential: " + key);
        if (values.getProperty("worker.credential").equals(values.getProperty("api.credential")))
            throw new IOException("API and worker credentials must differ");
        if (leaseMillis() < 1000 || leaseMillis() > 60000) throw new IOException("lease.millis out of range");
    }

    public static HarnessConfig load() throws IOException {
        String path = System.getProperty("harness.config");
        if (path == null) throw new IOException("Set -Dharness.config to a private properties file");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            p.load(in);
        }
        return new HarnessConfig(p);
    }

    String host() {
        return values.getProperty("http.host", "127.0.0.1");
    }

    int port() {
        return Integer.parseInt(values.getProperty("http.port", "7088"));
    }

    long leaseMillis() {
        return Long.parseLong(values.getProperty("lease.millis", "6000"));
    }

    boolean authorizedWorker(String id, String role) {
        return role.equals(values.getProperty("worker." + id + ".role"));
    }
}
