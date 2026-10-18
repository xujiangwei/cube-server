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

/** Durable offline data, including controls and artifacts, for the actual SHM transport test. */
public final class HarnessTransportFixture {
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Paths.get(args[0]), "");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) { p.load(in); }
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
            String run = store.create(T, S, key(), "fixture", new JSONObject()).getString("runId");
            JdbcRunStore.Lease lease = running(run, 60000);
            int size = Integer.parseInt(p.getProperty("files.max.file.bytes"));
            String text = new String(new char[size]).replace('\0', 'x');
            fixture.put("artifact", files.writeText(lease, "large", new JSONObject().put("fileName", "文件.txt").put("text", text)));
            fixture.put("emptyArtifact", files.writeText(lease, "empty", new JSONObject().put("fileName", "empty.txt").put("text", "")));
            store.finish(lease, version(run), "succeeded", "confirmed", new JSONObject().put("fixture", true), new JSONArray());
            fixture.put("artifactRun", run);
            ControlStore controls = new ControlStore(store, config.mysql()).files(files.ledger);
            for (String kind : new String[]{"input", "approval"}) {
                String waiting = profiles.createRun(identity, key(), "task", request("waiting fixture")).getString("runId");
                JdbcRunStore.Lease l = running(waiting, 60000);
                JSONObject details = new JSONObject().put("binding", "transport-" + kind);
                if (kind.equals("input")) details.put("question", "Continue?").put("checkpointDigest", RuntimeSupport.sha(bytes("checkpoint")));
                else details.put("argumentsHash", JdbcRunStore.hash(new JSONObject().put("fixture", true))).put("arguments", new JSONObject().put("fixture", true));
                controls.waitFor(l, version(waiting), kind, details, controls.now() + 600000);
                store.release(l, version(waiting));
                fixture.put(kind + "Run", waiting).put(kind + "Wait", controls.current(T, S, waiting));
            }
        }
        Files.write(root.resolve("store.local.properties"), bytes(ExecutionTest.properties(p)));
        Files.write(root.resolve("fixture.json"), bytes(fixture.toString(2)));
    }
}

