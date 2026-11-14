package cube.service.harness.test;

import cell.api.Nucleus;
import cell.carpet.CellListener;
import cube.aigc.completion.*;
import cube.core.*;
import cube.plugin.PluginSystem;
import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import cube.common.action.HarnessAction;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static cube.service.harness.test.RuntimeTest.*;

/** Private diagnostic fixture. Never packaged in either production JAR. */
public final class CapabilityEvaluationFixture {
    static final String SOURCE_SECRET = "evaluation-source-private-fixture-key-123456";
    static ToolBroker broker;
    static ProviderStore metadata;
    static JSONObject fixture;
    static String upstream;
    static boolean filteredProfile;
    static String businessSkill = "fhe/patrol";
    static Set<String> selectedCases = new HashSet<>();

    static void prepare(Path directory, Path packages, Path businessZip, String endpoint) throws Exception {
        upstream = endpoint;
        TEST_PERMISSIONS.addAll(Arrays.asList("data.query", "data.process", "capability.invoke"));
        CommonToolsTest.prepare(Files.createDirectory(directory), packages, endpoint + "/search");
        Properties p = CommonToolsTest.settings;
        p.setProperty("runtime.allowTrustedExecutables", "true");
        for (String name : Arrays.asList("data", "capability", "processing")) {
            Properties pc = new Properties();
            pc.setProperty("secret.f-fixture", SOURCE_SECRET);
            pc.setProperty("capability.allowed.units", "OmniMultimodal,OmniPerception,SpecializedDetection");
            pc.setProperty("capability.timeout.millis", System.getProperty("patrol.eval.capability.timeout", "20000"));
            pc.setProperty("capability.queue.timeout.millis", System.getProperty("patrol.eval.capability.queue.timeout", "0"));
            Path file = root.resolve(name + ".properties");
            Files.write(file, bytes(ExecutionTest.properties(pc)));
            p.setProperty("broker.provider." + name + ".class", "cube.service.harness.skill." +
                    (name.equals("data") ? "DataQueryProvider" : name.equals("processing") ? "DataProcessProvider" : "CapabilityInvokeProvider"));
            p.setProperty("broker.provider." + name + ".config", file.toString());
            String d = upload(Files.readAllBytes(CommonToolsTest.packagePath(packages, name))).getString("digest");
            catalog.lifecycle(T, d, 1, "ready");
            CommonToolsTest.releases.put(name, d);
        }
        p.setProperty("broker.providers", p.getProperty("broker.providers") + ",data,processing,capability");
        Path guard = root.resolve("workflow.properties");
        Files.write(guard, bytes("workflow.max.repairs=2\n"));
        p.setProperty("broker.completion.guard.class", "cube.service.harness.skill.WorkflowGuard");
        p.setProperty("broker.completion.guard.config", guard.toString());
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        p.setProperty("runtime.signer.evaluation", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        p.setProperty("admin.tenant." + T + ".signers", "evaluation");
        repo = new PackageRepository(catalog, new AssetFiles(root.resolve("repository"), 8000000, "runtime-test-repo"),
                Collections.singletonMap("evaluation", keys.getPublic()), true, true);
        resources = new ResourceTools(catalog, repo).files(CommonToolsTest.files);
        metadata = new ProviderStore(store, false);
        metadata.putSource(T, "source-f", "1", new JSONObject().put("baseUrl", endpoint).put("secretRef", "f-fixture")
                .put("identityMapping", "cube-subject-hmac-v1").put("allowedPaths", new JSONArray().put("/api/patrol/records").put("/api/media/content")));
        metadata.grant(T, S, "source-f@1", true);
        broker = new ToolBroker(new ToolJobStore(store, false), identity, p).files(CommonToolsTest.files).context(resources, metadata, () -> new Bridge(endpoint));
        CommonToolsTest.broker = broker;
        RemoteAdmin admin = new RemoteAdmin(catalog, repo, metadata, new ProfileRegistry(catalog), broker, p, TEST_PERMISSIONS);
        byte[] zip = Files.readAllBytes(businessZip);
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(businessZip.toFile())) {
            businessSkill = new JSONObject(new String(RuntimeSupport.read(archive.getInputStream(archive.getEntry("manifest.json")), 262144), StandardCharsets.UTF_8)).getString("name");
            String config = new String(RuntimeSupport.read(archive.getInputStream(archive.getEntry("data-processing.json")), 65536), StandardCharsets.UTF_8);
            filteredProfile = config.contains("evaluation-filtered");
        }
        JSONObject headers = new JSONObject().put("contentType", "application/zip").put("key", key());
        JSONObject checked = admin.handle(identity, HarnessAction.ValidatePackage, headers, zip);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update(checked.getString("digest").getBytes(StandardCharsets.US_ASCII));
        headers.put("signer", "evaluation").put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        String business = admin.handle(identity, HarnessAction.PublishRelease, headers, zip).getString("digest");
        CommonToolsTest.releases.put("business", business);
        profiles.publish(T, ModelProfiles.model("Baize").put("version", "1"));
        profiles.publish(T, ModelProfiles.model("Baize2"));
        JSONObject task = task("1", business, "Baize", 4096).put("id", "evaluation").put("runtime", ref("trusted-cpu"))
                .put("skillReleases", new JSONArray(CommonToolsTest.releases.values())).put("toolSetVersion", "broker-tools")
                .put("requiredCapabilities", new JSONArray().put("resources").put("resource_decisions").put("tool.execute"));
        task.getJSONObject("contextPolicy").put("budgetVersion", "transport-bytes-v1").put("maxInputBytes", 48000).put("outputReserve", 2048);
        task.getJSONObject("executionLimits").put("maxRepairs", 2);
        profiles.publish(T, task);
        profiles.bind(T, "task", "evaluation", "evaluation@1", 0);
        Path deployment = root.resolve("server-evaluation");
        profiles.configure(p, T, task, deployment);
        Files.write(deployment.resolve("Baize2.json"), bytes(ModelProfiles.model("Baize2").toString()));
        p.setProperty("evaluation.media.url", endpoint);
        p.setProperty("execution.permissions", String.join(",", TEST_PERMISSIONS));
        p.setProperty("execution.tenant.templates", templateDirectory().toString());
        Files.write(root.resolve("evaluation.properties"), bytes(ExecutionTest.properties(p)));
        fixture = new JSONObject().put("tenant", T).put("subject", S).put("releases", CommonToolsTest.releases)
                .put("permissions", new JSONArray(TEST_PERMISSIONS)).put("business", business);
        Files.write(root.resolve("evaluation.fixture.json"), bytes(fixture.toString(2)));
    }

    static JSONObject invoke(JdbcRunStore.Lease lease, String tool, JSONObject args) throws Exception {
        String id = key();
        intent(lease, "call", id, new JSONObject().put("proposalId", id).put("tool", tool).put("argumentsJson", args.toString()));
        JSONObject job = broker.enqueue(lease, row(lease.runId, "call", id), resources.executable(identity, lease.runId, tool));
        String worker = "evaluation-" + key();
        broker.jobs.identity(worker, "tool-runtime", 1);
        JSONObject wire = broker.jobs.poll(worker, "tool-runtime", "instance", true, "tool", broker::wire);
        broker.jobs.accepted(wire.getString("jobId"), wire.getString("jobHash"));
        job = broker.jobs.job(job.getString("job_id"));
        JSONObject exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(185);
        while (exchange.getString("status").equals("pending") && System.nanoTime() < end) {
            Thread.sleep(25);
            exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        }
        if (!exchange.getString("status").equals("finished")) throw new IllegalStateException("TOOL_COMPLETION_UNKNOWN");
        JSONObject data = new JSONObject(exchange.getString("dataJson"));
        JSONObject result = data.has("errorCode") ? data : new JSONObject().put("status", "success").put("dataJson", data.toString()).put("artifacts", new JSONArray());
        JSONObject report = new JSONObject().put("jobId", wire.getString("jobId")).put("jobHash", wire.getString("jobHash"))
                .put("actualFinished", true).put("effectStatus", data.has("errorCode") ? "none" : "completed").put("resultJson", result.toString());
        JSONObject saved = broker.report(job, report);
        confirm(lease, "call", id, new JSONObject(saved.getString("result_json")));
        return data;
    }

    static JdbcRunStore.Lease businessRun(JSONArray inputs) {
        JSONObject req = request("Independent provider diagnostic");
        req.getJSONObject("input").put("attachmentIds", inputs);
        String run = profiles.createRun(identity, key(), "evaluation", req).getString("runId");
        JdbcRunStore.Lease lease = running(run, 60000);
        for (String d : CommonToolsTest.releases.values()) resources.activate(identity, run, d);
        return lease;
    }

    static JSONObject query(String region) {
        return new JSONObject().put("skill", businessSkill).put("connectorId", "patrol-records")
                .put("argumentsJson", new JSONObject().put("from", "2026-11-13T00:00:00+08:00").put("to", "2026-11-14T00:00:00+08:00").put("regionId", region).toString());
    }

    static JSONObject toolCall(String tool, JSONObject args, JSONArray inputs) throws Exception {
        return invoke(businessRun(inputs), tool, args);
    }

    static JSONArray tools(Path image) throws Exception {
        JSONArray rows = new JSONArray();
        String imageId = MediaToolsTest.input(image, "application/octet-stream");
        JSONObject snapshot = new JSONObject().put("data", new JSONObject().put("items", new JSONArray().put(record(imageId))));
        Path snapshotFile = root.resolve("snapshot.json");
        Files.write(snapshotFile, bytes(snapshot.toString()));
        String snapshotId = MediaToolsTest.input(snapshotFile, "application/json");
        for (int n = 1; n <= 8; n++) {
            if (!selectedCases.isEmpty() && !selectedCases.contains(String.format("T%02d", n))) continue;
            JSONObject row = new JSONObject().put("caseId", String.format("T%02d", n)).put("layer", "tool");
            long start = System.nanoTime();
            JSONArray evidence = new JSONArray();
            try {
                if (n == 1) {
                    JSONObject calc = toolCall("common.calculator.evaluate", new JSONObject().put("expression", "0.1+0.2"), new JSONArray());
                    JSONObject clock = toolCall("common.time.query", new JSONObject().put("action", "convert").put("instant", "2026-11-13T00:00:00Z"), new JSONArray());
                    evidence.put(calc).put(clock); check(calc.getString("result").equals("0.3") && clock.getString("offset").equals("+08:00"), "decimal/time");
                } else if (n == 2) {
                    String id = CommonToolsTest.input("西区记录17；拍摄时间未知");
                    JSONObject read = toolCall("common.attachment.read", new JSONObject().put("inputId", id), new JSONArray().put(id));
                    JSONObject denied = toolCall("common.attachment.read", new JSONObject().put("inputId", id), new JSONArray());
                    evidence.put(read).put(denied); check(read.getString("text").contains("西区记录17") && denied.has("errorCode"), "binding");
                } else if (n == 3) {
                    JSONObject read = toolCall("common.knowledge.search", new JSONObject().put("query", "Harness"), new JSONArray());
                    evidence.put(read); check(read.getJSONArray("citations").length() == 1 && !read.toString().contains("SECRET_OTHER_USER"), "knowledge ACL");
                } else if (n == 4) {
                    JSONObject search = toolCall("common.search.query", new JSONObject().put("query", "林道巡检"), new JSONArray());
                    JSONObject page = toolCall("common.web.read", new JSONObject().put("url", "https://fixture.invalid/evaluation"), new JSONArray());
                    evidence.put(search).put(page); check(search.getJSONArray("results").length() == 1 && page.getString("text").contains("Fixture"), "controlled upstream");
                    row.put("upstream", "deterministic HTTP/search and web fixture; not search-engine quality");
                } else {
                    JdbcRunStore.Lease lease = businessRun(new JSONArray().put(imageId).put(snapshotId));
                    JSONObject args = n == 5 || n == 8 ? new JSONObject().put("skill", businessSkill).put("connectorId", "uploaded-records")
                            .put("argumentsJson", new JSONObject().put("inputId", snapshotId).toString()) : query(n == 6 ? "paged" : "duplicate");
                    JSONObject found = invoke(lease, "common.data.query", args); evidence.put(found);
                    check(!found.has("errorCode"), "query " + found);
                    JSONArray pages = new JSONArray().put(found.getString("pageRef"));
                    if (n == 6) {
                        check(!found.getBoolean("complete"), "first page incomplete");
                        JSONObject partial = invoke(lease, "common.processing.process", new JSONObject().put("action", "process").put("skill", businessSkill)
                                .put("profileId", "patrol-summary").put("pageRefs", new JSONArray().put(found.getString("pageRef"))));
                        evidence.put(partial);
                        check(!partial.getJSONObject("coverage").getBoolean("queryComplete"), "missing page disclosed");
                        JSONObject last = invoke(lease, "common.data.query", new JSONObject(args.toString()).put("cursor", found.getString("nextCursor")));
                        evidence.put(last); check(last.getBoolean("complete"), "last page complete"); pages.put(last.getString("pageRef"));
                        JdbcRunStore.Lease faultLease = businessRun(new JSONArray().put(imageId));
                        JSONObject faultArgs = query("partial");
                        JSONObject first = invoke(faultLease, "common.data.query", faultArgs);
                        JSONObject failed = invoke(faultLease, "common.data.query", new JSONObject(faultArgs.toString()).put("cursor", first.getString("nextCursor")));
                        evidence.put(first).put(failed);
                        check(failed.optString("errorCode").equals("DATA_QUERY_INCOMPLETE"), "second-page HTTP failure explicit");
                    }
                    if (n >= 6) {
                        String profile = n == 8 ? "uploaded-summary" : n == 7 && filteredProfile ? "evaluation-filtered" : "patrol-summary";
                        JSONObject processed = invoke(lease, "common.processing.process", new JSONObject().put("action", "process").put("skill", businessSkill)
                                .put("profileId", profile).put("pageRefs", pages)); evidence.put(processed);
                        check(!processed.has("errorCode") && processed.getJSONObject("coverage").getBoolean("queryComplete"), "processing complete " + processed);
                        if (n == 7) {
                            JSONObject stats = processed.getJSONObject("stats");
                            check(processed.getInt("totalItems") == 1 && stats.getInt("inputRecords") == (filteredProfile ? 3 : 2), "dedupe");
                            if (filteredProfile) check(stats.getInt("filteredRecords") == 1 && stats.getInt("duplicateRecords") == 1, "filter then dedupe");
                            JSONObject groups = invoke(lease, "common.processing.process", new JSONObject().put("action", "read").put("skill", businessSkill)
                                    .put("profileId", profile).put("datasetRef", processed.getString("datasetRef")).put("view", "groups"));
                            evidence.put(groups);
                            check(groups.getInt("totalItems") == 1 && groups.getString("itemsJson").contains("recordCount"), "group aggregate");
                        }
                        if (n == 8) {
                            String media = found.getJSONArray("mediaRefs").getJSONObject(0).getString("mediaRef");
                            JSONObject analysis = invoke(lease, "common.capability.invoke", new JSONObject().put("skill", businessSkill).put("profileId", "describe")
                                    .put("mediaRef", media).put("content", "描述这张照片中实际可见的内容；不猜测时间。"));
                            evidence.put(analysis); check(!analysis.has("errorCode") && !analysis.optString("answer").isEmpty(), "real media result " + analysis);
                        }
                    }
                }
                row.put("passed", true);
            } catch (Throwable e) {
                row.put("passed", false).put("error", e.getClass().getSimpleName() + ":" + e.getMessage());
            }
            row.put("evidence", evidence).put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            rows.put(row);
            JSONObject summary = new JSONObject(row.toString());
            summary.remove("evidence");
            System.out.println(summary);
        }
        return rows;
    }

    static JSONObject record(String imageId) {
        return new JSONObject().put("id", "evaluation-record-1").put("version", "1").put("capturedAt", JSONObject.NULL)
            .put("device", new JSONObject().put("id", "evaluation-device").put("name", "公开照片测试设备（合成）"))
            .put("region", new JSONObject().put("id", "evaluation-region").put("name", "公开样本测试区域（合成）"))
            .put("media", new JSONObject().put("id", "evaluation-photo-1").put("version", "1").put("mediaType", "image/jpeg").put("inputId", imageId));
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 6) selectedCases.addAll(Arrays.asList(args[6].split(",")));
        prepare(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), args[4]);
        try {
            if (args[0].equals("tools")) Files.write(root.resolve("tool-results.json"), bytes(tools(Paths.get(args[5])).toString(2)));
        } finally {
            broker.close(); CommonToolsTest.files.close();
        }
    }

    /** Test-only listener supplies an HTTP-backed media bridge alongside ordinary Harness. */
    public static final class Carpet implements CellListener {
        final FixtureAuthCarpet auth = new FixtureAuthCarpet();
        public void cellPreinitialize(Nucleus nucleus) {
            auth.cellPreinitialize(nucleus);
            try {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(Paths.get(System.getProperty("harness.config")))) { p.load(in); }
                ((Kernel) nucleus.getParameter("kernel")).installModule("AIGC", new Bridge(p.getProperty("evaluation.media.url")));
            } catch (Exception e) { throw new IllegalStateException("EVALUATION_BRIDGE_CONFIG", e); }
        }
        public void cellInitialized(Nucleus nucleus) { auth.cellInitialized(nucleus); }
        public void cellPredestroy(Nucleus nucleus) { auth.cellPredestroy(nucleus); }
        public void cellDestroyed(Nucleus nucleus) { auth.cellDestroyed(nucleus); }
    }

    static final class Bridge extends AbstractModule implements CompletionProvider, MediaProvider {
        final String endpoint;
        final ExecutorService pool = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "evaluation-media"); t.setDaemon(true); return t; });
        Bridge(String endpoint) { this.endpoint = endpoint; }
        public void start() { started.set(true); }
        public void stop() { started.set(false); pool.shutdownNow(); }
        public void onTick(cube.core.Module module, Kernel kernel) { }
        public <T extends PluginSystem> T getPluginSystem() { return null; }
        public JSONArray completionModels(String token) { return new JSONArray(); }
        public JSONObject completionCapacity(String token, String model, boolean tools) { return new JSONObject().put("registered", 0).put("available", 0); }
        public void submitCompletion(String token, String id, JSONObject body, long timeout, Listener listener) { throw new UnsupportedOperationException("USE_CONFIGURED_EXTERNAL_GATEWAY"); }
        public JSONArray mediaUnits() {
            // Proxy routes, not a claim that physical Cube Units registered with this JVM.
            JSONArray routes = new JSONArray();
            for (String unit : Arrays.asList("OmniMultimodal", "OmniPerception", "SpecializedDetection")) {
                JSONObject route = new JSONObject().put("unit", unit).put("instanceId", "evaluation-http-" + unit)
                        .put("capacity", 1).put("ready", true);
                if (unit.equals("SpecializedDetection")) route.put("profiles", new JSONArray().put("fire-smoke").put("thing"))
                        .put("slicedProfiles", new JSONArray()).put("availableImages", 1)
                        .put("statusExpiresAt", System.currentTimeMillis() + 30000);
                routes.put(route);
            }
            return routes;
        }
        public void invokeMedia(JSONObject execution, byte[] image, Consumer<JSONObject> callback) {
            pool.submit(() -> {
                try {
                    byte[] body = bytes(new JSONObject().put("execution", execution).put("imageBase64", Base64.getEncoder().encodeToString(image)).toString());
                    HttpURLConnection c = (HttpURLConnection) new URL(endpoint + "/media/invoke").openConnection();
                    c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(5000); c.setReadTimeout(185000);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = c.getOutputStream()) { out.write(body); }
                    if (c.getResponseCode() == 200) {
                        JSONObject receipt = new JSONObject(new String(RuntimeSupport.read(c.getInputStream(), 262144), StandardCharsets.UTF_8));
                        callback.accept(receipt);
                    }
                    c.disconnect();
                } catch (Exception ignored) {
                    // Missing a physical receipt is unknown; never manufacture completion or retry.
                }
            });
        }
    }
}
