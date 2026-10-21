package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;
import cube.service.harness.execution.ExecutionRuntime;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.json.*;

/**
 * N06/N07 多用户执行编排集成测试：三个域/联系人身份共享一个 ExecutionRuntime，
 * 验证跨租户隔离、作用域幂等、文件归属、后台扫描与重启后的租约恢复；
 * 底层为一次性 sqlite 存储库。
 *
 * <p>文件输入/产物按身份与租约绑定；同一 idempotency key 在不同身份下必须得到
 * 不同 run；runnable 分页扫描必须覆盖全部用户；模拟崩溃（租约立即过期）后，
 * 恢复流程必须为每个 run 取得递增的新 fence，且工件归属不丢失。
 */
public final class MultiUserExecutionTest {
    /**
     * 轮询等待条件成立：每次 AssertionError 后休眠 25ms 重试，10 秒仍未成立
     * 则抛出最后一次断言错误。
     *
     * @param condition 可能抛出 AssertionError 的条件动作
     * @throws Exception 条件中的受检异常或最终超时失败
     */
    private static void await(Checked condition) throws Exception {
        long until = System.currentTimeMillis() + 10000;
        while (true) {
            try {
                condition.run();
                return;
            } catch (AssertionError e) {
                if (System.currentTimeMillis() >= until) {
                    throw e;
                }
            }
            Thread.sleep(25);
        }
    }

    /**
     * 测试入口：三个身份（本域、同域第二联系人、异域）各自创建输入/run/工件，
     * 校验隔离与分页调度，再以崩溃重启验证租约恢复取得新 fence。
     *
     * @param args 未使用的命令行参数
     * @throws Exception 存储、文件或断言失败
     */
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-users-"), "");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) {
            p.load(in);
        }
        p.setProperty("execution.repository", root.resolve("repository").toString());
        p.setProperty("execution.repository.namespace", "runtime-test-repo");
        p.setProperty("execution.permissions", String.join(",", TEST_PERMISSIONS));
        p.setProperty("files.enabled", "true");
        p.setProperty("files.root.id", "multi-user-files");
        p.setProperty("files.root", Files.createDirectory(root.resolve("files")).toString());
        p.setProperty("files.download.key", "multi-user-test-private-handle-key-123456");
        // 三个身份：本域 identity、同域第二联系人、异域身份；后者验证跨租户隔离。
        ProfileRegistry.Identity[] users = {
            identity, new ProfileRegistry.Identity(T, "10002", TEST_PERMISSIONS),
            new ProfileRegistry.Identity("other.example.com", S, TEST_PERMISSIONS)
        };
        // 异域身份需要自己域内的模型/runtime/task profile 与 active 绑定。
        profiles.publish(users[2].tenant, model);
        profiles.publish(users[2].tenant, runtime);
        JSONObject foreignTask = catalog.profile(T, "task", "task", "1").put("skillReleases", new JSONArray());
        profiles.publish(users[2].tenant, foreignTask);
        catalog.bind(users[2].tenant, "task", "task", "task@1", 0);
        String[] runs = new String[3];
        String[] inputs = new String[3];
        String[] artifacts = new String[3];
        long[] fences = new long[3];
        byte[] content = bytes("private user input");
        try (ExecutionRuntime rt = new ExecutionRuntime(store, p, 3000)) {
            // 每个身份：上传同一内容的私有输入、创建 run、重放同一 key 必须得到同一 run。
            for (int i = 0; i < users.length; i++) {
                ProfileRegistry.Identity u = users[i];
                JSONObject input = new JSONObject().put("fileName", "input.txt").put("mediaType", "text/plain")
                        .put("size", content.length).put("sha256", RuntimeSupport.sha(content));
                inputs[i] = rt.files.create(u, "same-input-key", input).getString("inputId");
                rt.files.uploadInput(u, inputs[i], new ByteArrayInputStream(content));
                rt.files.complete(u, inputs[i], RuntimeSupport.sha(content));
                JSONObject req = request("user-specific execution");
                req.getJSONObject("input").getJSONArray("attachmentIds").put(inputs[i]);
                runs[i] = rt.create(u, "same-run-key", req).getString("runId");
                check(rt.create(u, "same-run-key", req).getString("runId").equals(runs[i]), "replay lost");
                JdbcRunStore.Lease l = store.acquire(u.tenant, u.subject, runs[i], "file-test", 1, 10000);
                store.transition(l, rt.read(u, runs[i]).getJSONObject("snapshot").getLong("version"), "running", new JSONObject());
                // 租约边界读取输入：只能读到本身份绑定的私有内容。
                check(new String(rt.files.readBound(l, "read", inputs[i], 1024, Collections.singleton("text/plain")), "UTF-8")
                        .equals("private user input"), "lease input identity lost");
                rt.files.consumed(runs[i], "read", inputs[i]);
                artifacts[i] = rt.files.writeText(l, "write", new JSONObject().put("fileName", "result.txt")
                        .put("text", "private " + u.tenant + "/" + u.subject)).getString("artifactId");
                rt.files.checkDelivery(u, runs[i]);
                store.release(l, rt.read(u, runs[i]).getJSONObject("snapshot").getLong("version"));
            }
            // 同一 idempotency key 在不同身份下必须产生三个不同 run。
            check(new HashSet<>(Arrays.asList(runs)).size() == 3, "users shared idempotency");
            // 后台分页扫描：每一页都必须取到一个用户的 run，合计覆盖全部用户。
            Set<String> paged = new HashSet<>();
            for (int i = 0; i < users.length; i++) {
                paged.add(rt.jobs.runnable(1, i).getJSONObject(0).getString("run_id"));
            }
            check(paged.equals(new HashSet<>(Arrays.asList(runs))), "multi-tenant scan pages lost users");
            // 跨身份访问一律拒绝：他人 run/事件/输入文件/产物均不可见。
            for (int i = 0; i < users.length; i++) {
                for (int j = 0; j < users.length; j++) {
                    if (i == j) {
                        continue;
                    }
                    final int owner = i;
                    final int caller = j;
                    fail("RUN_NOT_FOUND", () -> rt.read(users[caller], runs[owner]));
                    fail("RUN_NOT_FOUND", () -> rt.events(users[caller], runs[owner], 0, 100));
                    fail("FILE_NOT_FOUND", () -> rt.files.metadata(users[caller], inputs[owner], "input"));
                    fail("FILE_NOT_FOUND", () -> rt.files.openDownload(users[caller], artifacts[owner], null));
                }
            }
            rt.start();
            await(() -> {
                try (Connection c = config.connect(); Statement q = c.createStatement();
                     ResultSet rows = q.executeQuery("SELECT COUNT(*) FROM harness_model_job")) {
                    check(rows.next() && rows.getInt(1) == 3, "not all users scheduled");
                }
            });
            for (int i = 0; i < users.length; i++) {
                fences[i] = store.read(users[i].tenant, users[i].subject, runs[i]).getLong("fence");
            }
        }
        // 测试缩短崩溃后的租约等待；恢复仍必须取得新 fence。
        ControlFileTest.sql("UPDATE harness_run SET lease_until=0 WHERE state='running'");
        try (ExecutionRuntime restarted = new ExecutionRuntime(new JdbcRunStore(config), p, 3000)) {
            restarted.start();
            await(() -> {
                for (int i = 0; i < users.length; i++) {
                    // 崩溃恢复：每个 run 的 fence 必须递增，工件归属必须保持。
                    check(store.read(users[i].tenant, users[i].subject, runs[i]).getLong("fence") > fences[i], "user not recovered");
                    check(restarted.files.artifacts(users[i], runs[i]).length() == 1, "artifact ownership lost");
                }
            });
        }
        System.out.println("PASS: three domain/contact identities, scoped idempotency/files/leases, all-user scheduling and restart recovery");
    }
}
