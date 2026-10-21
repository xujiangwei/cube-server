package cube.service.harness.test;

import cube.service.harness.store.*;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import org.json.*;

/**
 * 账本兼容性探针：针对公开账本 API 编译，并先用归档的 v6/v7 jar 执行。
 *
 * <p>用于验证跨版本账本兼容：旧 jar 以 seed 模式播种一个 run 并把回执
 * 写入文件，当前版本再以 finish/校验模式继续处理同一 run，确保旧数据
 * 的租约获取、状态迁移与终态回执在新版本上语义不变。三种模式由
 * args[0] 选择：seed 播种、finish 完成，其余模式只校验终态。
 */
public final class LedgerCompatibilityProbe {
    /**
     * 探针入口：按 args[0] 模式对同一账本执行播种、完成或终态校验。
     *
     * @param args args[0] 模式（seed/finish/其他校验模式）；args[1] 存储配置路径；
     *             args[2] 回执文件路径（seed 写入、其他模式读入）
     * @throws Exception 配置读取、存储访问或断言失败
     * @throws AssertionError 校验模式下 run 未处于 succeeded 终态
     */
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[1]))) {
            p.load(in);
        }
        JdbcRunStore store = new JdbcRunStore(new StoreConfig(p));
        if (args[0].equals("seed")) {
            // 播种：幂等键带归档版本标识，回执以 CREATE_NEW 落盘，防止覆盖旧证据。
            JSONObject receipt =
                    store.create(
                            "n16-tenant",
                            "n16-subject",
                            "n16-upgrade-" + Paths.get(args[2]).getFileName(),
                            "upgrade-probe",
                            new JSONObject().put("instruction", "preserve me"));
            Files.write(
                    Paths.get(args[2]),
                    receipt.toString(2).getBytes("UTF-8"),
                    StandardOpenOption.CREATE_NEW);
        } else {
            // 非播种模式：先按回执中的 runId 读回快照。
            String run =
                    new JSONObject(new String(Files.readAllBytes(Paths.get(args[2])), "UTF-8"))
                            .getString("runId");
            JSONObject row = store.read("n16-tenant", "n16-subject", run);
            if (args[0].equals("finish")) {
                // 完成：按快照 version 取租约，随后逐步迁移并终态回执；
                // 每步前重读 version，保证 CAS 基准最新。
                long version = row.getJSONObject("snapshot").getLong("version");
                JdbcRunStore.Lease lease =
                        store.acquire(
                                "n16-tenant",
                                "n16-subject",
                                run,
                                "compatible-recovery-node",
                                version,
                                60000);
                version =
                        store.read("n16-tenant", "n16-subject", run)
                                .getJSONObject("snapshot")
                                .getLong("version");
                store.transition(lease, version, "running", new JSONObject());
                version =
                        store.read("n16-tenant", "n16-subject", run)
                                .getJSONObject("snapshot")
                                .getLong("version");
                store.finish(
                        lease,
                        version,
                        "succeeded",
                        "confirmed",
                        new JSONObject().put("preserved", true),
                        new JSONArray());
            } else if (!row.getJSONObject("snapshot").getString("state").equals("succeeded")) {
                throw new AssertionError("final missing");
            }
        }
        System.out.println("PASS: " + args[0] + " schema=" + StoreMigrations.VERSION);
    }
}
