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
 * 单容器私有配置：默认读取服务工作目录的 config/harness.properties，
 * 构造时完成全部 fail-closed 校验，不提供任何带可用凭据的默认值——
 * 监听地址、凭据强度、白名单格式与租约范围任一项不满足即拒绝启动。
 */
public final class HarnessConfig {
    /**
     * 已加载并路径归一化的配置键值；启动期只读，之后不再修改。
     */
    final Properties values;

    /**
     * 校验私有配置：健康/运维 HTTP 监听仅限 loopback；worker 凭据至少 24 字符；
     * 管理凭据（若配置）须独立于 worker 凭据且至少 24 字符；execution.accepting 仅接受 true/false；
     * 审批/trace 白名单条目须为 {@code <域>/<联系人ID>}；启用 trace 时必须配置 reader；
     * lease.millis 落在 [1000, 60000] 区间。
     *
     * @param values 已加载并路径归一化的配置键值
     * @throws IOException 监听主机无法解析、监听地址非 loopback，或上述任一校验失败
     */
    public HarnessConfig(Properties values) throws IOException {
        this.values = values;
        if (!InetAddress.getByName(host()).isLoopbackAddress()) {
            throw new IOException("Health/admin HTTP listener requires loopback");
        }
        if (values.getProperty("worker.credential", "").length() < 24) {
            throw new IOException("Missing private credential: worker.credential");
        }
        String admin = values.getProperty("admission.admin.credential", "");
        if (!admin.isEmpty() && (admin.length() < 24 || admin.equals(values.getProperty("worker.credential")))) {
            throw new IOException("Independent admission administrator required");
        }
        if (!Arrays.asList("true", "false").contains(values.getProperty("execution.accepting", "true"))) {
            throw new IOException("Invalid execution.accepting");
        }
        for (String key : Arrays.asList("control.approver.subjects", "trace.reader.subjects")) {
            for (String value : values.getProperty(key, "").split(",")) {
                if (value.trim().isEmpty()) {
                    continue;
                }
                if (!value.trim().matches("[A-Za-z0-9_.:-]{1,128}/[1-9][0-9]{0,18}")) {
                    throw new IOException("Invalid Cube principal: " + key);
                }
            }
        }
        if (Boolean.parseBoolean(values.getProperty("trace.enabled", "false"))
                && values.getProperty("trace.reader.subjects", "").trim().isEmpty()) {
            throw new IOException("Cube trace reader required");
        }
        if (leaseMillis() < 1000 || leaseMillis() > 60000) {
            throw new IOException("lease.millis out of range");
        }
    }

    /**
     * 默认读取服务工作目录的 config/harness.properties；系统属性仅用于显式覆盖路径。
     * 基础服务、任务执行及模型后端配置均从同一文件加载。
     * 其中的 sqlite 存储与资源仓库相对路径按进程工作目录解析，缺失目录自动创建；
     * 符号链接、越界等仍由后续校验拒绝。
     *
     * @return 校验通过的私有配置
     * @throws IOException 配置文件缺失、读取失败或构造校验拒绝
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

    /**
     * @return 健康/运维 HTTP 监听地址，默认 127.0.0.1；仅接受 loopback 地址
     */
    String host() {
        return values.getProperty("http.host", "127.0.0.1");
    }

    /**
     * @return 健康/运维 HTTP 监听端口，默认 7088
     */
    int port() {
        return Integer.parseInt(values.getProperty("http.port", "7088"));
    }

    /**
     * @return worker 租约时长（毫秒），默认 6000；取值限定 [1000, 60000]
     */
    long leaseMillis() {
        return Long.parseLong(values.getProperty("lease.millis", "6000"));
    }

    /**
     * 校验 workerId 是否在私有配置中授权，且请求角色与配置角色一致。
     *
     * @param id   worker 请求携带的 workerId
     * @param role worker 请求携带的角色（model/runner）
     * @return 配置中存在 worker.<id>.role 且与请求角色相同返回 true，否则 false
     */
    boolean authorizedWorker(String id, String role) {
        return role.equals(values.getProperty("worker." + id + ".role"));
    }
}
