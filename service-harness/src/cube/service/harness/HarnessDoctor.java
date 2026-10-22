/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cube.service.harness.execution.ModelProtocol;
import cube.service.harness.execution.ToolProtocol;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreConfig;
import org.json.JSONObject;

/**
 * 私有配置静态自检入口：只加载并校验配置、（按需）建连校验存储，不启动任何服务。
 * 适用于部署前确认 harness.properties 可用、数据库可达、契约清单完整；
 * 输出配置与协议支持情况，凭据一律不打印。
 */
public final class HarnessDoctor {
    /**
     * 执行配置自检：要求 JDK 8，加载私有配置，启用存储时构造 JdbcRunStore 校验连通性，
     * 最后打印健康/运维监听地址、本地契约校验结果与支持的 worker 协议版本。
     *
     * @param args 命令行参数，当前实现未使用
     * @throws IllegalStateException 运行的 JVM 不是 JDK 8（java.version 非 1.8.*）
     * @throws Exception             配置加载或校验失败、存储不可用、契约资源缺失
     */
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.version").startsWith("1.8.")) {
            throw new IllegalStateException("JDK 8 required");
        }
        HarnessConfig config = HarnessConfig.load();
        if (Boolean.parseBoolean(config.values.getProperty("store.enabled", "false"))) {
            new JdbcRunStore(new StoreConfig(config.values));
        }
        JSONObject manifest = StrictJson.object(Contract.resource("manifest.json"));
        for (String path : manifest.keySet()) {
            if (!RuntimeSupport.sha(Contract.resource(path)).equals(manifest.getString(path))) {
                throw new IllegalArgumentException("CONTRACT_DIGEST_MISMATCH");
            }
        }
        ModelProtocol.verify();
        ToolProtocol.verify();
        System.out.println("Harness config valid; HTTP=" + config.host() + ":" + config.port()
                + "; contracts=valid; workerProtocols=probe:1,model:2/3,runner:" + ToolProtocol.VERSION);
    }
}
