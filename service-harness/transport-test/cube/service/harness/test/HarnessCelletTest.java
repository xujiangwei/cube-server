package cube.service.harness.test;

import cell.api.Nucleus;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.core.Kernel;
import cube.service.harness.HarnessCellet;
import cube.service.harness.store.StoreConfig;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.sql.*;

/**
 * HarnessCellet 在真实 Cell 环境下的业务应答测试。
 *
 * <p>以真实 Nucleus/Kernel 装载 HarnessCellet 与测试用 Auth 模块，经由真实
 * Cell 会话发送 HarnessAction，覆盖：</p>
 *
 * <ul>
 *   <li>业务执行器被占满时同步返回 503 （队列满语义）；</li>
 *   <li>Auth 模块卸载后认证不可用返回 503 （随后重新安装并启动，仅恢复环境）；</li>
 *   <li>审批与 trace 动作对非授权调用方返回 403 ；</li>
 *   <li>重复键的原始 JSON 被严格校验拒绝，返回 400 ；</li>
 *   <li>畸形报文仍返回 sn 关联的 500 内部错误；</li>
 *   <li>服务停止后请求返回 503 。</li>
 * </ul>
 *
 * <p>测试以私有临时配置覆盖 harness.config 系统属性，结束后恢复原值。</p>
 */
public final class HarnessCelletTest {

    /**
     * 测试入口：搭建临时配置与真实 Cell 环境，按顺序执行各业务应答场景断言。
     *
     * <p>结束时恢复 harness.config 系统属性原值并删除临时配置文件。</p>
     *
     * @param args 未使用的命令行参数。
     * @throws Exception 当环境搭建、Cellet 装载或断言失败时抛出。
     */
    public static void main(String[] args) throws Exception {
        // 私有临时配置：development 模式、随机 HTTP 端口与测试 worker 凭据
        Path config = Files.createTempFile("harness-cellet-", ".properties");
        Properties p = new Properties();
        p.setProperty("mode", "development");
        p.setProperty("http.port", "0");
        p.setProperty("worker.credential", "worker-private-cellet-fixture-1234");
        Path root = Files.createTempDirectory("harness-cellet-store-");
        p.setProperty("store.enabled", "true");
        p.setProperty("store.dialect", "sqlite");
        p.setProperty("store.database", "harness_cellet");
        p.setProperty("store.identity", "cellet-bootstrap-identity-123456");
        p.setProperty("store.sqlite.root", root.toString());
        p.setProperty("store.sqlite.file", root.resolve("harness_cellet.db").toString());
        p.setProperty("execution.enabled", "true");
        p.setProperty("execution.repository", root.resolve("repository").toString());
        p.setProperty("execution.tenant.templates", StoreBootstrapTest.writeTemplates(root.resolve("templates"), "resources-Baize2").toString());
        try (OutputStream out = Files.newOutputStream(config)) {
            p.store(out, "Private test-only configuration");
        }
        // 以系统属性显式指定配置文件，并保存原值以便恢复
        String previous = System.getProperty("harness.config");
        System.setProperty("harness.config", config.toString());
        // 搭建真实 Cell 环境：Nucleus + Kernel + 测试 Auth 模块 + HarnessCellet
        Nucleus server = new Nucleus();
        Kernel kernel = new Kernel(server);
        server.setParameter("kernel", kernel);
        FixtureAuth auth = new FixtureAuth();
        auth.add("user-private-cellet-fixture-123456", "example.com", 10001, 600000);
        auth.add("second-private-cellet-fixture-123456", "second.example.com", 10002, 600000);
        kernel.installModule("Auth", auth);
        HarnessCellet cellet = new HarnessCellet();
        // gate 阻塞业务任务直到放行；running 等待 4 个业务线程全部启动
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(4);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            server.getTalkService().startServer("127.0.0.1", port);
            HarnessSessionTest.check(server.getCelletService().installCellet(cellet), "Cellet installation failed");
            server.getCelletService().activateCellet(port, cellet);
            kernel.startup();
            try (HarnessSessionTest.Client client = new HarnessSessionTest.Client(port, "user-private-cellet-fixture-123456")) {
                // A rejected token must never create tenant data.
                ActionDialect invalid = new Packet(HarnessAction.TaskProfiles.name, new JSONObject().put("credential", "invalid-user-token")).toDialect();
                ActionDialect denied = client.performer.syncTransmit(client.director, "Harness", invalid, 5000);
                HarnessSessionTest.check(denied != null && new Packet(denied).data.getInt("status") == 401, "Invalid token was accepted");
                StoreConfig database = new StoreConfig(p);
                try (Connection c = database.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM harness_tenant")) {
                    HarnessSessionTest.check(r.next() && r.getInt(1) == 0, "Invalid token initialized a tenant");
                }
                JSONObject listed = client.call(HarnessAction.TaskProfiles, new JSONObject(), null, 200);
                HarnessSessionTest.check(listed.getJSONArray("profiles").length() == 1
                        && listed.getJSONArray("profiles").getJSONObject(0).getString("profileId").equals("resources-Baize2"), "First access did not initialize task profiles");
                // A different tenant can create a run without first fetching task-profiles.
                JSONObject body = new JSONObject().put("taskProfileId", "resources-Baize2")
                        .put("input", new JSONObject().put("instruction", "hello").put("attachmentIds", new org.json.JSONArray()));
                ActionDialect direct = new Packet(HarnessAction.CreateRun.name, new JSONObject()
                        .put("credential", "second-private-cellet-fixture-123456").put("key", "direct-first-access")
                        .put("contentType", "application/json")).toDialect();
                direct.addParam("body", body.toString().getBytes("UTF-8"));
                ActionDialect created = client.performer.syncTransmit(client.director, "Harness", direct, 5000);
                HarnessSessionTest.check(created != null && new Packet(created).data.getInt("status") == 202, "Direct run did not initialize tenant");
                System.out.println("PASS: real Cell invalid-token isolation, first task-profiles and direct-run tenant initialization");
                ThreadPoolExecutor executor = (ThreadPoolExecutor) cellet.getService().businessExecutor();
                // 先以 4 个任务占满业务执行器线程并阻塞在 gate 上
                for (int i = 0; i < 4; i++) {
                    executor.execute(() -> {
                        running.countDown();
                        try {
                            gate.await();
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                HarnessSessionTest.check(running.await(5, TimeUnit.SECONDS), "Business workers did not start");
                // 再提交 32 个排队任务，制造执行器与队列双满
                for (int i = 0; i < 32; i++) {
                    executor.execute(() -> {
                        try {
                            gate.await();
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                // 队列满时 Capabilities 同步返回 503
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 503);
                gate.countDown();
                // 队列排空后恢复 200
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 200);
                // Auth 模块卸载后认证不可用，返回 503
                kernel.uninstallModule("Auth");
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 503);
                // 重新安装并启动 Auth，恢复后续场景所需环境
                kernel.installModule("Auth", auth);
                auth.start();
                // 非授权调用方执行审批/trace 返回 403
                client.call(HarnessAction.DecideApproval, new JSONObject().put("role", "approver"), null, 403);
                client.call(HarnessAction.Trace, new JSONObject().put("role", "trace"), null, 403);
                // 重复键的原始 JSON 被严格校验拒绝，返回 400
                client.call(HarnessAction.CreateRun, new JSONObject().put("key", "invalid-json").put("contentType", "application/json"),
                        "{\"taskProfileId\":\"a\",\"taskProfileId\":\"b\"}".getBytes("UTF-8"), 400);
                // 畸形报文（data 为非 JSON 字符串）仍返回 sn 关联的 500 内部错误
                ActionDialect malformed = new Packet(HarnessAction.Capabilities.name, new JSONObject()).toDialect();
                long sn = malformed.getParamAsLong("sn");
                malformed.addParam("data", "malformed packet data");
                ActionDialect response = client.performer.syncTransmit(client.director, "Harness", malformed, 5000);
                HarnessSessionTest.check(response != null && new Packet(response).data.getInt("status") == 500
                        && new Packet(response).sn == sn, "Internal error lost request correlation");
                // 停止服务后请求返回 503
                cellet.getService().stop();
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 503);
            }
            System.out.println("PASS: real Cell correlated service queue-full, raw JSON validation, internal error and stopped-service replies");
        }
        finally {
            gate.countDown();
            kernel.dispose();
            kernel.shutdown();
            server.destroy();
            if (previous == null) {
                System.clearProperty("harness.config");
            }
            else {
                System.setProperty("harness.config", previous);
            }
            Files.deleteIfExists(config);
        }
    }
}
