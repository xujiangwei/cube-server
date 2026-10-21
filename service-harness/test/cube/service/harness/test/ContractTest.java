package cube.service.harness.test;

import cube.service.harness.protocol.*;
import cube.service.harness.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;
import java.io.IOException;

/**
 * 基础契约守护测试：校验 {@code contracts/} 下的跨语言契约资产与协议行为。
 *
 * <p>覆盖四类断言：1) {@link HarnessConfig} 配置边界——mode 键被忽略、
 * 健康 HTTP 仅限 loopback、worker.credential 至少 24 字符；2)
 * manifest.json 登记的每个契约文件 SHA256 与实际字节逐一比对；3)
 * fixtures.json 中每个 fixture 经 {@link Contract#parse} 的实际结果与
 * expected 声明一致；4) {@link WorkerRegistry} 的重复注册拒绝、断线后
 * nonce 不重用、旧会话拒绝与租约到期自动移除。
 *
 * <p>说明：schemas/profile.json 的 digest 比对失败是当前环境预置的契约
 * 资产与代码不一致问题；本测试只负责如实暴露该不一致，不修改契约或代码。
 */
public final class ContractTest {
    /**
     * 断言辅助：条件不成立时以给定消息抛出 AssertionError。
     *
     * @param condition 断言条件
     * @param message 失败时携带的断言消息
     * @throws AssertionError condition 为 false
     */
    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 把字节数组转为小写十六进制字符串，用于契约 digest 比对。
     *
     * @param bytes 待转换的摘要字节
     * @return 小写十六进制字符串，每字节两位（{@code %02x}）
     */
    static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte v : bytes) {
            b.append(String.format("%02x", v & 255));
        }
        return b.toString();
    }

    /**
     * 测试入口：按配置边界 → 契约 digest → fixture 行为 → 注册状态机顺序执行。
     *
     * @param args 未使用的命令行参数
     * @throws Exception 资源读取失败或线程中断
     */
    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        config.setProperty("worker.credential", "private-worker-credential-for-test");
        new HarnessConfig(config);
        // mode 键无论取何值都被忽略：旧 development/production 配置可直接加载。
        for (String legacyMode : new String[]{"development", "production", "unused"}) {
            config.setProperty("mode", legacyMode);
            new HarnessConfig(config);
        }
        // 健康 HTTP 监听地址必须为 loopback：填写远程地址应被拒绝。
        config.setProperty("http.host", "192.168.1.35");
        try {
            new HarnessConfig(config);
            throw new AssertionError("remote health HTTP accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("loopback"), "health listener restriction lost");
        }
        config.remove("http.host");
        // worker.credential 至少 24 字符：过短应被拒绝。
        config.setProperty("worker.credential", "short");
        try {
            new HarnessConfig(config);
            throw new AssertionError("short worker credential accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("worker.credential"), "worker authentication lost");
        }
        // manifest.json 逐文件登记 SHA256：对实际字节重新求摘要并比对。
        JSONObject manifest = StrictJson.object(Contract.resource("manifest.json"));
        for (String name : manifest.keySet()) {
            check(hex(MessageDigest.getInstance("SHA-256").digest(Contract.resource(name)))
                    .equals(manifest.getString(name)), "digest: " + name);
        }
        // fixtures.json 逐条执行：实际解析结果须与 expected 声明一致。
        JSONArray fixtures = (JSONArray) StrictJson.parse(Contract.resource("fixtures.json"));
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject f = fixtures.getJSONObject(i);
            String outcome = "OK";
            try {
                Contract.parse(f.getString("schema"), f.getString("raw").getBytes(StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                outcome = e.getMessage();
            }
            check(outcome.equals(f.getString("expected")), f.getString("name") + ": " + outcome);
        }
        // 注册状态机：同一请求在另一实例/连接上重复注册返回 DUPLICATE_WORKER。
        WorkerRegistry registry = new WorkerRegistry(1000);
        JSONObject request = new JSONObject(fixtures.getJSONObject(0).getString("raw"));
        String nonce = registry.apply(request, 1).getString("sessionId");
        try {
            registry.apply(new JSONObject(request.toString()).put("instanceId", "new"), 2);
            throw new AssertionError("duplicate accepted");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().equals("DUPLICATE_WORKER"), "duplicate code");
        }
        // 断开原连接后同实例可重新注册，且必须签发新 nonce。
        registry.disconnect(1);
        String next = registry.apply(new JSONObject(request.toString()).put("instanceId", "new"), 2).getString("sessionId");
        check(!nonce.equals(next), "nonce was reused");
        // 携带旧 nonce 的心跳在旧连接上按 STALE_SESSION 拒绝。
        try {
            registry.apply(request.put("action", "heartbeat").put("sessionId", nonce), 1);
            throw new AssertionError("stale accepted");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().equals("STALE_SESSION"), "stale code");
        }
        // 租约 1000ms：静默超过租约后注册表自动清空。
        Thread.sleep(1050);
        check(registry.snapshot().length() == 0, "lease not expired");
        System.out.println("PASS: mode-independent config, credential/HTTP boundaries, " + fixtures.length()
                + " fixtures, all digests, duplicate, stale session and lease expiry");
    }
}
