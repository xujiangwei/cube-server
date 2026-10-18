/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.files;

import cube.service.harness.store.JdbcRunStore;
import org.json.JSONObject;

import java.util.*;

/**
 * Validated seconds -> milliseconds; immutable times are saved on the individual object.
 */
public final class FileConfig {

    public final boolean cleanup;

    public final long scan,
            grace,
            upload,
            input,
            artifact,
            download,
            claim,
            retry,
            retryMax,
            maxFile,
            maxBytes;

    public final int batch;

    public final String rootId, policy;

    private final Set<String> keys = new HashSet<>();

    private final Properties p;

    public FileConfig(Properties p) {
        this.p = p;
        cleanup = bool("cleanup.enabled", true);
        scan = seconds("cleanup.scan.interval.seconds", 60, 5, 3600);
        batch = (int) number("cleanup.scan.batch.size", 100, 1, 10000);
        grace = seconds("cleanup.temp.grace.seconds", 60, 0, 86400);
        upload = seconds("cleanup.upload.ttl.seconds", 86400, 60, 604800);
        input = seconds("retention.input.ttl.seconds", 604800, 60, 31536000);
        artifact = seconds("retention.artifact.ttl.seconds", 2592000, 60, 31536000);
        download = seconds("file.download.lease.seconds", 300, 1, 3600);
        claim = seconds("cleanup.claim.lease.seconds", 120, 30, 3600);
        retry = seconds("cleanup.retry.initial.seconds", 30, 1, 3600);
        retryMax = seconds("cleanup.retry.max.seconds", 3600, 1, 86400);
        maxFile = number("files.max.file.bytes", 10485760, 1, 67108864);
        maxBytes = number("files.max.bytes", 1073741824, 1, 1099511627776L);
        rootId = p.getProperty("files.root.id", "");
        if (!rootId.matches("[A-Za-z0-9_.:-]{1,128}") || retry > retryMax || maxFile > maxBytes)
            throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        JSONObject settings = new JSONObject();
        for (String key : keys) settings.put(key, p.getProperty(key, defaultValue(key)));
        policy = JdbcRunStore.hash(settings.put("rootId", rootId));
        for (String key : p.stringPropertyNames())
            if ((key.startsWith("cleanup.")
                    || key.startsWith("retention.")
                    || key.startsWith("file.download."))
                    && !keys.contains(key)) throw new IllegalArgumentException("UNKNOWN_FILE_CONFIG");
    }

    private final Map<String, String> defaults = new HashMap<>();

    private String defaultValue(String key) {
        return defaults.get(key);
    }

    private boolean bool(String k, boolean d) {
        keys.add(k);
        defaults.put(k, "" + d);
        String s = p.getProperty(k, "" + d);
        if (!s.equals("true") && !s.equals("false"))
            throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        return Boolean.parseBoolean(s);
    }

    private long number(String k, long d, long min, long max) {
        keys.add(k);
        defaults.put(k, "" + d);
        String s = p.getProperty(k, "" + d);
        if (!s.matches("[0-9]{1,16}")) throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        long n = Long.parseLong(s);
        if (n < min || n > max) throw new IllegalArgumentException("INVALID_FILE_CONFIG");
        return n;
    }

    private long seconds(String k, long d, long min, long max) {
        return Math.multiplyExact(number(k, d, min, max), 1000L);
    }
}
