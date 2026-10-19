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
        if (!InetAddress.getByName(host()).isLoopbackAddress())
            throw new IOException("Health/admin HTTP listener requires loopback");
        if (values.getProperty("worker.credential", "").length() < 24)
            throw new IOException("Missing private credential: worker.credential");
        String admin = values.getProperty("admission.admin.credential", "");
        if (!admin.isEmpty() && (admin.length() < 24 || admin.equals(values.getProperty("worker.credential"))))
            throw new IOException("Independent admission administrator required");
        if (!Arrays.asList("true", "false").contains(values.getProperty("execution.accepting", "true")))
            throw new IOException("Invalid execution.accepting");
        for (String key : Arrays.asList("control.approver.subjects", "trace.reader.subjects")) {
            for (String value : values.getProperty(key, "").split(",")) {
                if (value.trim().isEmpty()) continue;
                if (!value.trim().matches("[A-Za-z0-9_.:-]{1,128}/[1-9][0-9]{0,18}"))
                    throw new IOException("Invalid Cube principal: " + key);
            }
        }
        if (Boolean.parseBoolean(values.getProperty("trace.enabled", "false"))
                && values.getProperty("trace.reader.subjects", "").trim().isEmpty())
            throw new IOException("Cube trace reader required");
        if (leaseMillis() < 1000 || leaseMillis() > 60000)
            throw new IOException("lease.millis out of range");
    }

    /**
     * 默认读取服务工作目录的 config/harness.properties；系统属性仅用于显式覆盖路径。
     * 基础服务、任务执行及模型后端配置均从同一文件加载。
     * 其中的 sqlite 存储与资源仓库相对路径按进程工作目录解析，缺失目录自动创建；
     * 符号链接、越界等仍由后续校验拒绝。
     */
    public static HarnessConfig load() throws IOException {
        String path = System.getProperty("harness.config", "config/harness.properties");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            p.load(in);
        }
        ConfigPaths.normalize(p);
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
