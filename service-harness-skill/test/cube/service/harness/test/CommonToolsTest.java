package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;
import com.sun.net.httpserver.HttpServer;
import cube.service.harness.execution.*;
import cube.service.harness.files.FileService;
import cube.service.harness.runtime.*;
import cube.service.harness.skill.*;
import cube.service.harness.store.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real JDBC, author packages, Broker grants, exact evidence, file binding and duplicate ACKs. */
public final class CommonToolsTest {
    static Properties settings;
    static FileService files;
    static ToolBroker broker;
    static final Map<String, String> releases = new LinkedHashMap<>();
    static final String[] NAMES = {"search", "web", "knowledge", "attachment", "time", "calculator"};
    static final String[] CLASSES = {"SearchProvider", "FixtureWebProvider", "KnowledgeProvider", "TextAttachmentProvider", "TimeProvider", "CalculatorProvider"};
    static final String[] TOOLS = {"common.search.query", "common.web.read", "common.knowledge.search", "common.attachment.read", "common.time.query", "common.calculator.evaluate"};

    static void prepare(Path directory, Path packages, String searchUrl) throws Exception {
        TEST_PERMISSIONS.addAll(Arrays.asList("web.search", "web.read", "knowledge.read", "attachment.read", "time.read", "calculator.evaluate"));
        identity = new ProfileRegistry.Identity(T, S, TEST_PERMISSIONS);
        ToolTest.prepare(directory, "");
        settings = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) { settings.load(in); }
        settings.setProperty("files.root", Files.createDirectory(root.resolve("files")).toString());
        settings.setProperty("files.root.id", "common-files-" + key());
        settings.setProperty("files.download.key", key() + key());
        settings.setProperty("execution.repository", root.resolve("repository").toString());
        settings.setProperty("execution.repository.namespace", "runtime-test-repo");
        files = new FileService(store, settings, identity);
        profiles.files(files.ledger);
        settings.setProperty("execution.repository", root.resolve("repository").toString());
        settings.setProperty("execution.repository.namespace", "runtime-test-repo");
        settings.setProperty("runner.grant.key", key() + key());
        settings.setProperty("broker.node.id", "common-fixture-node");
        settings.setProperty("broker.providers", String.join(",", NAMES));
        byte[] index = bytes(new JSONObject().put("documents", new JSONArray()
                .put(new JSONObject().put("id", "visible").put("tenant", T).put("subjects", new JSONArray().put(S))
                        .put("source", "kb:fixture@1").put("text", "Harness visible evidence 中文资料"))
                .put(new JSONObject().put("id", "hidden").put("tenant", T).put("subjects", new JSONArray().put("other"))
                        .put("source", "kb:hidden@1").put("text", "Harness SECRET_OTHER_USER"))).toString());
        Files.write(root.resolve("knowledge.json"), index);
        releases.clear();
        JSONObject runtimeProfile = new JSONObject(runtime.toString()).put("id", "trusted-cpu");
        profiles.publish(T, runtimeProfile);
        for (int i = 0; i < NAMES.length; i++) {
            String name = NAMES[i];
            Properties privateConfig = new Properties();
            if (name.equals("search")) privateConfig.setProperty("search.url", searchUrl);
            if (name.equals("knowledge")) {
                privateConfig.setProperty("knowledge.index", root.resolve("knowledge.json").toString());
                privateConfig.setProperty("knowledge.index.sha256", RuntimeSupport.sha(index));
            }
            Path configFile = root.resolve(name + ".properties");
            Files.write(configFile, bytes(ExecutionTest.properties(privateConfig)));
            settings.setProperty("broker.provider." + name + ".class", "cube.service.harness.skill." + CLASSES[i]);
            settings.setProperty("broker.provider." + name + ".config", configFile.toString());
            String digest = upload(Files.readAllBytes(packages.resolve(name + "-v1.zip"))).getString("digest");
            catalog.lifecycle(T, digest, 1, "ready");
            releases.put(name, digest);
        }
        JSONObject task = task("1", releases.get("search"), "scripted", 8192)
                .put("id", "common-tools").put("runtime", ref("trusted-cpu"))
                .put("skillReleases", new JSONArray(releases.values())).put("toolSetVersion", "broker-tools")
                .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"));
        task.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
        profiles.publish(T, task);
        profiles.bind(T, "task", "common-tools", "common-tools@1", 0);
        profiles.configure(settings, T, task, root.resolve("server-common"));
        Files.write(root.resolve("common.properties"), bytes(ExecutionTest.properties(settings)));
        Files.write(root.resolve("common.fixture.json"), bytes(new JSONObject().put("releases", releases)
                .put("permissions", new JSONArray(TEST_PERMISSIONS)).put("tenant", T).put("subject", S).toString()));
    }

    static String input(String text) throws Exception {
        byte[] raw = bytes(text);
        JSONObject descriptor = new JSONObject().put("fileName", "fixture.txt").put("mediaType", "text/plain")
                .put("size", raw.length).put("sha256", RuntimeSupport.sha(raw));
        String id = files.create(key(), descriptor).getString("inputId");
        files.uploadInput(id, new ByteArrayInputStream(raw)); files.complete(id, descriptor.getString("sha256"));
        return id;
    }

    static JSONObject call(String tool, JSONObject args, JSONArray inputIds) throws Exception {
        String run = profiles.createRun(identity, key(), "common-tools", request("verify common tools")
                .put("input", new JSONObject().put("instruction", "verify common tools").put("attachmentIds", inputIds))).getString("runId");
        JdbcRunStore.Lease lease = running(run, 60000);
        for (String digest : releases.values()) resources.activate(identity, run, digest);
        intent(lease, "call", "test-call", new JSONObject().put("proposalId", "test-call").put("tool", tool).put("argumentsJson", JdbcRunStore.canonical(args)));
        JSONObject job = broker.enqueue(lease, row(run, "call", "test-call"), resources.executable(identity, run, tool));
        String worker = "worker-" + key(), runtimeId = "runtime-" + key();
        broker.jobs.identity(worker, runtimeId, 1);
        JSONObject wire = broker.jobs.poll(worker, runtimeId, "instance", true, "tool", broker::wire);
        check(wire != null, "tool dispatched");
        broker.jobs.accepted(wire.getString("jobId"), wire.getString("jobHash"));
        job = broker.jobs.job(job.getString("job_id"));
        JSONObject exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        // Repeating the accepted invocation must share the same durable exchange.
        broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (exchange.getString("status").equals("pending") && System.nanoTime() < until) {
            Thread.sleep(10); exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        }
        check(exchange.getString("status").equals("finished"), "provider finished");
        JSONObject data = new JSONObject(exchange.getString("dataJson"));
        JSONObject result = data.has("errorCode") ? data : new JSONObject().put("status", "success")
                .put("dataJson", data.toString()).put("artifacts", new JSONArray());
        JSONObject report = new JSONObject().put("jobId", wire.getString("jobId")).put("jobHash", wire.getString("jobHash"))
                .put("actualFinished", true).put("effectStatus", data.has("errorCode") ? "none" : "completed").put("resultJson", result.toString());
        JSONObject saved = broker.report(job, report);
        check(broker.report(job, report).getString("report_hash").equals(saved.getString("report_hash")), "ACK replay identity");
        JSONObject persisted = new JSONObject(saved.getString("result_json"));
        check(persisted.getString("status").equals(result.getString("status")), "server accepts exact provider evidence");
        confirm(lease, "call", "test-call", persisted);
        return data;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("prepare")) {
            prepare(Files.createDirectory(Paths.get(args[1])), Paths.get(args[2]), args[3]);
            files.close(); return;
        }
        AtomicInteger requests = new AtomicInteger();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/search", request -> {
            requests.incrementAndGet();
            byte[] body = bytes("{\"results\":[{\"title\":\"3D tools\",\"url\":\"https://example.com/modeling\",\"content\":\"Fixture source evidence\",\"engine\":\"fixture\"}]}");
            request.getResponseHeaders().set("Content-Type", "application/json"); request.sendResponseHeaders(200, body.length);
            try (OutputStream out = request.getResponseBody()) { out.write(body); }
        });
        upstream.start();
        try {
            prepare(Files.createTempDirectory(Paths.get(args[0]).getParent(), "broker-tests-"), Paths.get(args[0]),
                    "http://127.0.0.1:" + upstream.getAddress().getPort() + "/search");
            broker = new ToolBroker(new ToolJobStore(store, config.mysql()), identity, settings).files(files);
            check(call(TOOLS[0], new JSONObject().put("query", "主流3D建模软件"), new JSONArray()).getJSONArray("results").length() == 1, "search result");
            check(requests.get() == 1, "transport replays do not repeat upstream HTTP");
            check(call(TOOLS[1], new JSONObject().put("url", "https://fixture.invalid/"), new JSONArray()).getString("text").contains("Fixture page evidence"), "page fixture");
            JSONObject knowledge = call(TOOLS[2], new JSONObject().put("query", "Harness"), new JSONArray());
            check(knowledge.getJSONArray("citations").length() == 1 && !knowledge.toString().contains("SECRET"), "real broker ACL");
            String inputId = input("中文附件 evidence");
            check(call(TOOLS[3], new JSONObject().put("inputId", inputId), new JSONArray().put(inputId)).getString("text").contains("中文附件"), "bound input");
            check(call(TOOLS[3], new JSONObject().put("inputId", inputId), new JSONArray()).has("errorCode"), "unbound input denied");
            check(call(TOOLS[4], new JSONObject().put("action", "convert").put("instant", "2026-10-25T00:00:00Z"), new JSONArray()).getString("offset").equals("+08:00"), "time evidence");
            check(call(TOOLS[5], new JSONObject().put("expression", "0.1+0.2"), new JSONArray()).getString("result").equals("0.3"), "calculator evidence");
            System.out.println("PASS: all six providers through real Broker/JDBC; ACL/file binding, replay and duplicate ACKs");
        } finally {
            if (broker != null) broker.close(); if (files != null) files.close(); upstream.stop(0);
        }
    }
}
