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

/** 多域、多用户共享一个运行时；包含文件归属、后台扫描与租约恢复。 */
public final class MultiUserExecutionTest {
    private static void await(Checked condition) throws Exception {
        long until = System.currentTimeMillis() + 10000;
        while (true) {
            try { condition.run(); return; }
            catch (AssertionError e) { if (System.currentTimeMillis() >= until) throw e; }
            Thread.sleep(25);
        }
    }

    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-users-"), "");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) { p.load(in); }
        p.setProperty("execution.repository", root.resolve("repository").toString());
        p.setProperty("execution.repository.namespace", "runtime-test-repo");
        p.setProperty("execution.permissions", String.join(",", TEST_PERMISSIONS));
        p.setProperty("files.enabled", "true");
        p.setProperty("files.root.id", "multi-user-files");
        p.setProperty("files.root", Files.createDirectory(root.resolve("files")).toString());
        p.setProperty("files.download.key", "multi-user-test-private-handle-key-123456");
        ProfileRegistry.Identity[] users = {
            identity, new ProfileRegistry.Identity(T, "10002", TEST_PERMISSIONS),
            new ProfileRegistry.Identity("other.example.com", S, TEST_PERMISSIONS)
        };
        profiles.publish(users[2].tenant, model);
        profiles.publish(users[2].tenant, runtime);
        JSONObject foreignTask = catalog.profile(T, "task", "task", "1").put("skillReleases", new JSONArray());
        profiles.publish(users[2].tenant, foreignTask);
        catalog.bind(users[2].tenant, "task", "task", "task@1", 0);
        String[] runs = new String[3], inputs = new String[3], artifacts = new String[3];
        long[] fences = new long[3];
        byte[] content = bytes("private user input");
        try (ExecutionRuntime rt = new ExecutionRuntime(store, p, 3000)) {
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
                check(new String(rt.files.readBound(l, "read", inputs[i], 1024, Collections.singleton("text/plain")), "UTF-8")
                        .equals("private user input"), "lease input identity lost");
                rt.files.consumed(runs[i], "read", inputs[i]);
                artifacts[i] = rt.files.writeText(l, "write", new JSONObject().put("fileName", "result.txt")
                        .put("text", "private " + u.tenant + "/" + u.subject)).getString("artifactId");
                rt.files.checkDelivery(u, runs[i]);
                store.release(l, rt.read(u, runs[i]).getJSONObject("snapshot").getLong("version"));
            }
            check(new HashSet<>(Arrays.asList(runs)).size() == 3, "users shared idempotency");
            Set<String> paged = new HashSet<>();
            for (int i = 0; i < users.length; i++)
                paged.add(rt.jobs.runnable(1, i).getJSONObject(0).getString("run_id"));
            check(paged.equals(new HashSet<>(Arrays.asList(runs))), "multi-tenant scan pages lost users");
            for (int i = 0; i < users.length; i++) for (int j = 0; j < users.length; j++) {
                if (i == j) continue;
                final int owner = i, caller = j;
                fail("RUN_NOT_FOUND", () -> rt.read(users[caller], runs[owner]));
                fail("RUN_NOT_FOUND", () -> rt.events(users[caller], runs[owner], 0, 100));
                fail("FILE_NOT_FOUND", () -> rt.files.metadata(users[caller], inputs[owner], "input"));
                fail("FILE_NOT_FOUND", () -> rt.files.openDownload(users[caller], artifacts[owner], null));
            }
            rt.start();
            await(() -> {
                try (Connection c = config.connect(); Statement q = c.createStatement();
                     ResultSet rows = q.executeQuery("SELECT COUNT(*) FROM harness_model_job")) {
                    check(rows.next() && rows.getInt(1) == 3, "not all users scheduled");
                }
            });
            for (int i = 0; i < users.length; i++) fences[i] = store.read(users[i].tenant, users[i].subject, runs[i]).getLong("fence");
        }
        // 测试缩短崩溃后的租约等待；恢复仍必须取得新 fence。
        ControlFileTest.sql("UPDATE harness_run SET lease_until=0 WHERE state='running'");
        try (ExecutionRuntime restarted = new ExecutionRuntime(new JdbcRunStore(config), p, 3000)) {
            restarted.start();
            await(() -> {
                for (int i = 0; i < users.length; i++) {
                    check(store.read(users[i].tenant, users[i].subject, runs[i]).getLong("fence") > fences[i], "user not recovered");
                    check(restarted.files.artifacts(users[i], runs[i]).length() == 1, "artifact ownership lost");
                }
            });
        }
        System.out.println("PASS: three domain/contact identities, scoped idempotency/files/leases, all-user scheduling and restart recovery");
    }
}
