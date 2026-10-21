package cube.service.harness.test;

import cube.service.harness.files.FileService;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.ControlStore;
import cube.service.harness.store.JdbcRunStore;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import org.json.*;
import static cube.service.harness.test.RuntimeTest.*;

/**
 * 真实 SHM 传输测试使用的持久离线数据夹具，含控制等待与产物。
 *
 * <p>在 ExecutionTest 准备好的一次性存储之上，开启 files/trace/feedback
 * 配置并创建 FileService，然后生成两类数据写入 {@code fixture.json}：
 * 一个达到 files.max.file.bytes 上限的大文本产物、一个空文本产物及其
 * 所在 run 的终态回执；再为 input/approval 两类控制各建一个等待中的
 * run 与等待记录。实际配置（含 files.root 等绝对路径）回写
 * {@code store.local.properties}，供传输测试进程复用。
 *
 * <p>存储、身份与辅助方法（root/store/config/identity/T/S/key/bytes/
 * version/running/request/profiles）均来自共享夹具
 * {@link RuntimeTest}，本类不重复搭建。
 */
public final class HarnessTransportFixture {
    /**
     * 夹具生成入口：args[0] 为存储根目录，args[1] 可选覆盖最大文件字节数。
     *
     * @param args args[0] 一次性存储根目录；args[1] 可选，files.max.file.bytes 取值
     * @throws Exception 存储访问、文件读写或夹具构造失败
     */
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Paths.get(args[0]), "");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) {
            p.load(in);
        }
        // 开启文件服务、trace 与 feedback，并固定下载密钥与资源仓库路径。
        p.setProperty("files.enabled", "true");
        p.setProperty("files.root", Files.createDirectory(root.resolve("files")).toString());
        p.setProperty("files.root.id", "transport-files");
        p.setProperty("files.max.file.bytes", args.length > 1 ? args[1] : "1048576");
        p.setProperty("files.download.key", "transport-download-key-private-fixture-12345");
        p.setProperty("execution.repository", root.resolve("repository").toString());
        p.setProperty("trace.enabled", "true");
        p.setProperty("feedback.enabled", "true");
        JSONObject fixture = new JSONObject(new String(Files.readAllBytes(root.resolve("fixture.json")), "UTF-8"));
        try (FileService files = new FileService(store, p, identity)) {
            // 先落一个 succeeded 的 run：大产物写满上限字节，空产物写空文本。
            String run = store.create(T, S, key(), "fixture", new JSONObject()).getString("runId");
            JdbcRunStore.Lease lease = running(run, 60000);
            int size = Integer.parseInt(p.getProperty("files.max.file.bytes"));
            String text = new String(new char[size]).replace('\0', 'x');
            fixture.put("artifact", files.writeText(lease, "large", new JSONObject().put("fileName", "文件.txt").put("text", text)));
            fixture.put("emptyArtifact", files.writeText(lease, "empty", new JSONObject().put("fileName", "empty.txt").put("text", "")));
            store.finish(lease, version(run), "succeeded", "confirmed", new JSONObject().put("fixture", true), new JSONArray());
            fixture.put("artifactRun", run);
            ControlStore controls = new ControlStore(store, config.mysql()).files(files.ledger);
            // input/approval 两类控制各建等待中的 run：input 带问题与 checkpoint 摘要，
            // approval 带参数摘要与参数原文；等待记录一并写入夹具。
            for (String kind : new String[]{"input", "approval"}) {
                String waiting = profiles.createRun(identity, key(), "task", request("waiting fixture")).getString("runId");
                JdbcRunStore.Lease l = running(waiting, 60000);
                JSONObject details = new JSONObject().put("binding", "transport-" + kind);
                if (kind.equals("input")) {
                    details.put("question", "Continue?").put("checkpointDigest", RuntimeSupport.sha(bytes("checkpoint")));
                } else {
                    details.put("argumentsHash", JdbcRunStore.hash(new JSONObject().put("fixture", true)))
                            .put("arguments", new JSONObject().put("fixture", true));
                }
                controls.waitFor(l, version(waiting), kind, details, controls.now() + 600000);
                store.release(l, version(waiting));
                fixture.put(kind + "Run", waiting).put(kind + "Wait", controls.current(T, S, waiting));
            }
        }
        // 回写实际配置与夹具数据，供传输测试按路径读取。
        Files.write(root.resolve("store.local.properties"), bytes(ExecutionTest.properties(p)));
        Files.write(root.resolve("fixture.json"), bytes(fixture.toString(2)));
    }
}
