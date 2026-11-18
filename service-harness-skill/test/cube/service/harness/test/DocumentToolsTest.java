package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;
import cube.service.harness.execution.*;
import cube.service.harness.files.FileService;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Real JDBC, package authorization, environment routing and binary artifact transfer. */
public final class DocumentToolsTest {
    static Properties settings;
    static FileService files;
    static String documentRelease;
    static ToolBroker broker;
    static final String TOOL = "common.document.watermark";

    static void prepare(Path directory, Path packages, Path environment) throws Exception {
        Files.createDirectories(directory);
        TEST_PERMISSIONS.add("document.watermark");
        identity = new ProfileRegistry.Identity(T, S, TEST_PERMISSIONS);
        ToolTest.prepare(directory, "");
        settings = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) { settings.load(in); }
        settings.setProperty("files.root", Files.createDirectory(root.resolve("files")).toString());
        settings.setProperty("files.root.id", "document-files-" + key());
        settings.setProperty("files.download.key", key() + key());
        settings.setProperty("execution.repository", root.resolve("repository").toString());
        settings.setProperty("execution.repository.namespace", "runtime-test-repo");
        files = new FileService(store, settings, identity);
        profiles.files(files.ledger);
        JSONObject documentRuntime = new JSONObject(runtime.toString()).put("id", "trusted-cpu").put("maxWorkspaceBytes", 33554432);
        profiles.publish(T, documentRuntime);
        documentRelease = upload(Files.readAllBytes(packages.resolve("document-v1.zip"))).getString("digest");
        catalog.lifecycle(T, documentRelease, 1, "ready");
        JSONObject task = task("1", documentRelease, "scripted", 8192).put("id", "document-tools")
                .put("runtime", ref("trusted-cpu")).put("toolSetVersion", "broker-tools")
                .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"));
        task.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
        task.getJSONObject("executionLimits").put("maxLifetimeMillis", 180000);
        profiles.publish(T, task);
        profiles.bind(T, "task", "document-tools", "document-tools@1", 0);
        settings.setProperty("runner.grant.key", key() + key());
        settings.setProperty("broker.node.id", "document-fixture");
        settings.setProperty("broker.providers", "document");
        settings.setProperty("broker.provider.document.class", "cube.service.harness.skill.DocumentProvider");
        Properties privateConfig = new Properties();
        privateConfig.setProperty("environment.manifest", environment.toAbsolutePath().toString());
        Path config = root.resolve("document.properties");
        Files.write(config, bytes(ExecutionTest.properties(privateConfig)));
        settings.setProperty("broker.provider.document.config", config.toString());
        settings.setProperty("broker.mock.enabled", "false");
        profiles.configure(settings, T, task, root.resolve("server-document"));
        Files.write(root.resolve("document.properties.server"), bytes(ExecutionTest.properties(settings)));
        JSONObject spec = new JSONObject(new String(Files.readAllBytes(environment), "UTF-8"));
        Files.write(root.resolve("document.fixture.json"), bytes(new JSONObject().put("release", documentRelease)
                .put("permissions", new JSONArray(TEST_PERMISSIONS)).put("tenant", T).put("subject", S)
                .put("environmentId", spec.getString("id")).put("environmentDigest", JdbcRunStore.hash(spec)).toString()));
    }

    static String input(byte[] raw) throws Exception {
        JSONObject metadata = new JSONObject().put("fileName", "source.pdf").put("mediaType", "application/pdf")
                .put("size", raw.length).put("sha256", RuntimeSupport.sha(raw));
        String id = files.create(key(), metadata).getString("inputId");
        files.uploadInput(id, new ByteArrayInputStream(raw));
        files.complete(id, metadata.getString("sha256"));
        return id;
    }

    static JSONObject job(String input, boolean bound) throws Exception {
        JSONObject arguments = new JSONObject().put("inputId", input).put("text", "内部资料");
        String run = profiles.createRun(identity, key(), "document-tools", request("watermark")
                .put("input", new JSONObject().put("instruction", "watermark").put("attachmentIds", bound ? new JSONArray().put(input) : new JSONArray()))).getString("runId");
        JdbcRunStore.Lease lease = running(run, 60000);
        resources.activate(identity, run, documentRelease);
        intent(lease, "call", "watermark", new JSONObject().put("proposalId", "watermark").put("tool", TOOL)
                .put("argumentsJson", JdbcRunStore.canonical(arguments)));
        return broker.enqueue(lease, row(run, "call", "watermark"), resources.executable(identity, run, TOOL));
    }

    static JSONObject assign(JSONObject job, String worker) {
        broker.jobs.identity(worker, worker + "-runtime", 1);
        JSONObject wire = broker.jobs.poll(worker, worker + "-runtime", "instance", true, "tool",
                (j,w,r,i) -> broker.wire(j,w,r,i,true));
        check(wire.getString("jobId").equals(job.getString("job_id")), "assigned wrong job");
        broker.jobs.accepted(wire.getString("jobId"), wire.getString("jobHash"));
        return wire;
    }

    static JSONObject exchange(JSONObject job, JSONObject wire, JSONObject transfer) throws Exception {
        return broker.fileExchange(broker.jobs.job(job.getString("job_id")), wire.getJSONObject("grant"), transfer);
    }

    static void test() throws Exception {
        broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).files(files);
        byte[] pdf = bytes("%PDF-1.7\n" + String.join("", Collections.nCopies(4000, "fixture bytes\n")) + "%%EOF\n");
        String input = input(pdf);
        fail("FILE_TYPE_MISMATCH", () -> input(bytes("not a PDF")));
        JSONObject job = job(input, true), wire = assign(job, "a");
        JSONObject env = wire.getJSONObject("fileProcessing");
        check(!broker.acceptsFileEnvironment(job, new JSONArray()), "plain runner accepted document");
        JSONArray acceptedEnvironment = new JSONArray().put(new JSONObject().put("environmentId", env.getString("environmentId"))
                .put("environmentDigest", env.getString("environmentDigest")));
        check(broker.acceptsFileEnvironment(job, acceptedEnvironment), "matching environment");
        JSONObject declared = new JSONObject(job.getString("definition_json")).getJSONObject("tool");
        JSONObject node = new JSONObject().put("runnerType", "tool").put("providerAwait", true);
        check(!broker.acceptsRunner(declared, node), "plain runner advertised PDF readiness");
        node.put("fileEnvironments", acceptedEnvironment);
        check(broker.acceptsRunner(declared, node), "PDF environment not ready");
        check(!broker.acceptsRunner(declared, new JSONObject(node.toString()).put("providerAwait", false)), "missing wait protocol");
        broker.files(null);
        check(!broker.supports(declared), "disabled files advertised PDF support");
        broker.files(files);
        check(!broker.acceptsFileEnvironment(job, new JSONArray().put(new JSONObject(acceptedEnvironment.getJSONObject(0).toString())
                .put("environmentDigest", String.join("", Collections.nCopies(64, "0"))))), "mismatched environment");
        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        for (long offset = 0; offset < pdf.length; ) {
            JSONObject chunk = exchange(job, wire, new JSONObject().put("operation", "read").put("offset", offset));
            byte[] bytes = Base64.getDecoder().decode(chunk.getString("data")); downloaded.write(bytes); offset += bytes.length;
        }
        check(Arrays.equals(pdf, downloaded.toByteArray()), "binary input assembly");
        fail("FILE_OPERATION_INVALID", () -> exchange(job, wire, new JSONObject().put("operation", "read").put("offset", 0).put("inputId", "forged")));
        JSONObject begin = new JSONObject().put("operation", "begin").put("size", pdf.length).put("sha256", RuntimeSupport.sha(pdf));
        fail("FILE_SIZE_INVALID", () -> exchange(job, wire, new JSONObject(begin.toString()).put("size", 1.5)));
        fail("FILE_SIZE_INVALID", () -> exchange(job, wire, new JSONObject(begin.toString()).put("size", "123")));
        fail("FILE_CHUNK_INVALID", () -> exchange(job, wire, new JSONObject().put("operation", "write").put("offset", 0).put("data", "a==")));
        JSONObject artifact = exchange(job, wire, begin);
        check(artifact.getString("artifactId").equals(exchange(job, wire, begin).getString("artifactId")), "begin replay");
        fail("IDEMPOTENCY_CONFLICT", () -> exchange(job, wire, new JSONObject(begin.toString()).put("size", pdf.length + 1)));
        fail("FILE_DIGEST_MISMATCH", () -> exchange(job, wire, new JSONObject().put("operation", "finish")));
        // An interrupted OS write may leave a prefix of a chunk. Replay validates it and resumes.
        exchange(job, wire, new JSONObject().put("operation", "write").put("offset", 0)
                .put("data", Base64.getEncoder().encodeToString(Arrays.copyOf(pdf, 137))));
        for (int offset = 0; offset < pdf.length; offset += 16384) {
            byte[] chunk = Arrays.copyOfRange(pdf, offset, Math.min(pdf.length, offset + 16384));
            JSONObject transfer = new JSONObject().put("operation", "write").put("offset", offset).put("data", Base64.getEncoder().encodeToString(chunk));
            exchange(job, wire, transfer); exchange(job, wire, transfer);
        }
        fail("FILE_CHUNK_CONFLICT", () -> exchange(job, wire, new JSONObject().put("operation", "write").put("offset", 0).put("data", "YQ==")));
        JSONObject finish = exchange(job, wire, new JSONObject().put("operation", "finish"));
        check(finish.getString("state").equals("ready"), "artifact not ready");
        check(finish.getString("artifactId").equals(exchange(job, wire, new JSONObject().put("operation", "finish")).getString("artifactId")), "finish replay");
        try (FileService.Download content = files.openDownload(finish.getString("artifactId"), "")) {
            check(Arrays.equals(RuntimeSupport.read(content.input, pdf.length), pdf), "artifact bytes");
        }
        JSONObject current = broker.jobs.job(job.getString("job_id"));
        JSONObject result = broker.invokeArguments(current, wire.getJSONObject("grant"), new JSONObject(current.getString("arguments_json")));
        for (int i = 0; i < 300 && result.getString("status").equals("pending"); i++) {
            Thread.sleep(10); result = broker.invokeArguments(current, wire.getJSONObject("grant"), new JSONObject(current.getString("arguments_json")));
        }
        JSONObject data = new JSONObject(result.getString("dataJson"));
        check(data.getJSONObject("artifact").getString("artifactId").equals(finish.getString("artifactId")), "provider fact");
        JSONObject report = new JSONObject().put("jobId", wire.getString("jobId")).put("jobHash", wire.getString("jobHash"))
                .put("actualFinished", true).put("effectStatus", "completed").put("resultJson", new JSONObject().put("status", "success")
                .put("dataJson", data.toString()).put("artifacts", new JSONArray()).toString());
        check(broker.report(current, report).getString("report_hash").equals(broker.report(current, report).getString("report_hash")), "report replay");
        JSONObject unbound = job(input, false), unboundWire = assign(unbound, "b");
        fail("FILE_NOT_BOUND", () -> exchange(unbound, unboundWire, new JSONObject().put("operation", "read").put("offset", 0)));
        broker.close(); files.close();
        pass("document environment routing, multi-chunk bytes, digest, quota allocation, idempotent begin/chunks/finish/report, unbound input and artifact download");
    }

    public static void main(String[] args) throws Exception {
        prepare(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
        if (!args[0].equals("prepare")) test();
    }
}
