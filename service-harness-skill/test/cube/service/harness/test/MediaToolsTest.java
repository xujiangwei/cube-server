/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;
import cube.aigc.completion.MediaProvider;
import cube.common.action.HarnessAction;
import cube.service.harness.execution.*;
import cube.service.harness.skill.CapabilityInvokeProvider;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.*;
import org.json.*;

/**
 * 媒体类技能的端到端测试。
 *
 * <p>覆盖真实技能包、远程签名网关、JDBC/Broker、绑定图片与带标签的 Unit 夹具。</p>
 */
public final class MediaToolsTest {
    /**
     * 夹具签名密钥。
     */
    static final String SECRET = "M1-http-protocol-fixture-key-1234567890";

    /**
     * 向测试服务器写回一个响应。
     *
     * @param x 服务端请求。
     * @param status 状态码。
     * @param type 媒体类型。
     * @param bytes 响应体字节。
     * @throws IOException 写出响应过程中的异常。
     */
    static void send(HttpExchange x, int status, String type, byte[] bytes) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 计算数据源请求头的签名值。
     *
     * @param value 待签名的规范串。
     * @return 返回十六进制小写的 HMAC-SHA256 签名。
     */
    static String signature(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes("UTF-8"), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : mac.doFinal(value.getBytes("UTF-8"))) {
                out.append(String.format("%02x", b & 255));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 工具 Broker。
     */
    static ToolBroker broker;
    /**
     * 数据源与授权存储。
     */
    static ProviderStore metadata;
    /**
     * 已发起的推理次数。
     */
    static AtomicInteger inferences = new AtomicInteger();
    /**
     * 已请求取消的次数。
     */
    static AtomicInteger cancellations = new AtomicInteger();
    /**
     * 被延迟回执的回调。
     */
    static List<Consumer<JSONObject>> delayed = new ArrayList<>();
    /**
     * 已发起的物理执行记录。
     */
    static List<JSONObject> executions = new ArrayList<>();
    /**
     * 是否延迟回执。
     */
    static boolean delay;
    static boolean longWait;
    static int responseDelayMillis;

    /**
     * 上传一个本地文件并完成绑定。
     *
     * @param path 文件路径。
     * @param type 媒体类型。
     * @return 返回输入标识。
     * @throws Exception 上传过程中的异常。
     */
    static String input(Path path, String type) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        JSONObject descriptor = new JSONObject().put("fileName", path.getFileName().toString()).put("mediaType", type).put("size", bytes.length).put("sha256", RuntimeSupport.sha(bytes));
        String id = CommonToolsTest.files.create(key(), descriptor).getString("inputId");
        CommonToolsTest.files.uploadInput(id, new ByteArrayInputStream(bytes));
        CommonToolsTest.files.complete(id, descriptor.getString("sha256"));
        return id;
    }

    /**
     * 构造一个物理执行回执。
     *
     * @param execution 物理执行记录。
     * @return 返回回执对象。
     */
    static JSONObject physical(JSONObject execution) {
        return new JSONObject().put("requestId", execution.getString("requestId")).put("instanceId", execution.getString("instanceId"))
                .put("actualFinished", true).put("success", true).put("payload", new JSONObject().put("response", "Fixture: trees and building; no real inference")
                        .put("resultPayload", new JSONObject().put("fixture", true)));
    }

    /**
     * 经 Broker 调用一次技能，并核对回执与证据。
     *
     * @param lease 运行租约。
     * @param tool 技能操作名。
     * @param args 调用参数。
     * @return 返回技能返回的数据。
     * @throws Exception 调用过程中的异常。
     */
    static JSONObject call(JdbcRunStore.Lease lease, String tool, JSONObject args) throws Exception {
        String id = key();
        intent(lease, "call", id, new JSONObject().put("proposalId", id).put("tool", tool).put("argumentsJson", args.toString()));
        JSONObject job = broker.enqueue(lease, row(lease.runId, "call", id), resources.executable(identity, lease.runId, tool));
        String worker = "media-worker-" + key();
        broker.jobs.identity(worker, "runtime", 1);
        if (longWait && tool.equals("common.capability.invoke")) {
            check(broker.jobs.poll(worker, "runtime", "legacy", true, broker::wire) == null,
                    "legacy Runner never receives an unsupported long wait");
        }
        JSONObject wire = broker.jobs.poll(worker, "runtime", "instance", true, "tool",
                (j,w,r,i) -> broker.wire(j,w,r,i,longWait));
        check(wire != null, "tool dispatched");
        if (longWait && tool.equals("common.capability.invoke")) {
            check(wire.getString("waitProtocol").equals("provider-await-v1") && wire.getInt("timeoutMillis") > 30000,
                    "long wait is negotiated and signed into wire");
        }
        broker.jobs.accepted(wire.getString("jobId"), wire.getString("jobHash"));
        job = broker.jobs.job(job.getString("job_id"));
        JSONObject exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        // 重放已受理的调用不得再次触达上游。
        broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(longWait ? 75 : 8);
        while (exchange.getString("status").equals("pending") && System.nanoTime() < until) {
            Thread.sleep(10);
            try {
                exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
            } catch (StoreException unknown) {
                if (unknown.code.equals("BROKER_OUTCOME_UNKNOWN")) {
                    return ToolJobStore.error(unknown.code);
                }
                throw unknown;
            }
        }
        check(!exchange.getString("status").equals("pending"), "provider returned within budget");
        JSONObject result = new JSONObject(exchange.getString("dataJson"));
        if (result.has("errorCode")) {
            // 未知或失败的任务在这个夹具里刻意保持未决状态。
            return result;
        }
        JSONObject normalized = new JSONObject().put("status", "success").put("dataJson", result.toString()).put("artifacts", new JSONArray());
        JSONObject report = new JSONObject().put("jobId", wire.getString("jobId")).put("jobHash", wire.getString("jobHash"))
                .put("actualFinished", true).put("effectStatus", "completed").put("resultJson", normalized.toString());
        broker.report(job, report);
        broker.report(job, report);
        confirm(lease, "call", id, normalized);
        return result;
    }

    /**
     * 测试入口。
     *
     * @param args 第一个参数为技能包目录，第二个参数为 forest 工程目录。
     * @throws Exception 测试执行过程中的异常。
     */
    public static void main(String[] args) throws Exception {
        Path packages = Paths.get(args[0]);
        Path forest = Paths.get(args[1]);
        longWait = args.length > 2 && "long-wait".equals(args[2]);
        TEST_PERMISSIONS.addAll(Arrays.asList("data.query", "data.process", "capability.invoke"));
        CommonToolsTest.prepare(Files.createTempDirectory(packages.getParent(), "media-test-"), packages, "http://127.0.0.1:1/search");
        Properties settings = CommonToolsTest.settings;
        for (String name : Arrays.asList("data", "capability", "processing")) {
            Properties pc = new Properties();
            pc.setProperty("capability.allowed.units", "OmniMultimodal,OmniPerception,SpecializedDetection");
            pc.setProperty("capability.timeout.millis", longWait ? "60000" : "1000");
            pc.setProperty("capability.queue.timeout.millis", longWait ? "5000" : "0");
            pc.setProperty("secret.f-fixture", SECRET);
            Path file = root.resolve(name + ".properties");
            Files.write(file, bytes(ExecutionTest.properties(pc)));
            settings.setProperty("broker.provider." + name + ".class", "cube.service.harness.skill." + (name.equals("data") ? "DataQueryProvider" : name.equals("processing") ? "DataProcessProvider" : "CapabilityInvokeProvider"));
            settings.setProperty("broker.provider." + name + ".config", file.toString());
            String digest = upload(Files.readAllBytes(packages.resolve(name + "-v1.zip"))).getString("digest");
            CommonToolsTest.releases.put(name, digest);
        }
        settings.setProperty("broker.providers", settings.getProperty("broker.providers") + ",data,capability,processing");
        Path guardConfig = root.resolve("workflow.properties");
        Files.write(guardConfig, bytes("workflow.max.repairs=2\n"));
        settings.setProperty("broker.completion.guard.class", "cube.service.harness.skill.WorkflowGuard");
        settings.setProperty("broker.completion.guard.config", guardConfig.toString());
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keys = gen.generateKeyPair();
        repo = new PackageRepository(catalog, new AssetFiles(root.resolve("repository"), 8000000, "runtime-test-repo"), Collections.singletonMap("f-author", keys.getPublic()), true, true);
        resources = new ResourceTools(catalog, repo).files(CommonToolsTest.files);
        metadata = new ProviderStore(store, false);
        MediaProvider bridge = new MediaProvider() {
            @Override
            public JSONArray mediaUnits() {
                JSONArray out = new JSONArray();
                for (String unit : Arrays.asList("OmniMultimodal", "OmniPerception", "SpecializedDetection")) {
                    for (String node : Arrays.asList("unit-a", "unit-b")) {
                        JSONObject descriptor = new JSONObject().put("unit", unit).put("instanceId", node).put("capacity", 1).put("ready", true);
                        if ("SpecializedDetection".equals(unit)) descriptor
                                .put("profiles",new JSONArray().put("uav-fire-smoke").put("fire-smoke").put("thing"))
                                .put("slicedProfiles",new JSONArray().put("uav-fire-smoke"))
                                .put("availableImages",1)
                                .put("statusExpiresAt",System.currentTimeMillis()+30000);
                        out.put(descriptor);
                    }
                }
                return out;
            }

            @Override
            public void invokeMedia(JSONObject execution, byte[] image, Consumer<JSONObject> callback) {
                inferences.incrementAndGet();
                executions.add(execution);
                callback.accept(new JSONObject().put("requestId", execution.getString("requestId"))
                        .put("instanceId", execution.getString("instanceId")).put("actualFinished", false)
                        .put("stage", "physical-dispatch").put("dispatchedAt", System.currentTimeMillis()));
                if (delay) {
                    delayed.add(callback);
                } else if (responseDelayMillis > 0) {
                    Thread delivery = new Thread(() -> {
                        try { Thread.sleep(responseDelayMillis); callback.accept(physical(execution)); }
                        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                    });
                    delivery.setDaemon(true); delivery.start();
                } else {
                    callback.accept(physical(execution));
                }
            }

            @Override
            public void cancelMedia(String requestId) {
                cancellations.incrementAndGet();
            }
        };
        broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).files(CommonToolsTest.files).context(resources, metadata, () -> bridge);
        RemoteAdmin admin = new RemoteAdmin(catalog, repo, metadata, new ProfileRegistry(catalog), broker, settings, TEST_PERMISSIONS);
        byte[] zip = Files.readAllBytes(packages.getParent().resolve("fhe-patrol-m4-test.zip"));
        Properties unguardedSettings = new Properties(); unguardedSettings.putAll(settings);
        unguardedSettings.remove("broker.completion.guard.class");
        try (ToolBroker unguarded = new ToolBroker(new ToolJobStore(store, false), identity, unguardedSettings)) {
            fail("WORKFLOW_GUARD_UNAVAILABLE", () -> unguarded.validatePackage(repo.validate(new ByteArrayInputStream(zip))));
        }
        JSONObject headers = new JSONObject().put("contentType", "application/zip").put("key", key());
        JSONObject validation = admin.handle(identity, HarnessAction.ValidatePackage, headers, zip);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(validation.getString("digest").getBytes(StandardCharsets.US_ASCII));
        headers.put("signer", "f-author").put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        settings.setProperty("admin.tenant." + T + ".signers", "f-author");
        fail("ADMIN_SIGNER_FORBIDDEN", () -> admin.handle(identity, HarnessAction.PublishRelease, new JSONObject(headers.toString()).put("key", key()).put("signer", "unapproved"), zip));
        Signature other = Signature.getInstance("SHA256withRSA");
        other.initSign(gen.generateKeyPair().getPrivate());
        other.update(validation.getString("digest").getBytes("US-ASCII"));
        String badSignature = Base64.getEncoder().encodeToString(other.sign());
        fail("PACKAGE_SIGNATURE_INVALID", () -> admin.handle(identity, HarnessAction.PublishRelease, new JSONObject(headers.toString()).put("key", key()).put("signature", badSignature), zip));
        JSONObject published = admin.handle(identity, HarnessAction.PublishRelease, headers, zip);
        check(admin.handle(identity, HarnessAction.PublishRelease, headers, zip).similar(published), "remote signed publication replay");
        fail("ADMIN_PERMISSION_FORBIDDEN", () -> admin.handle(identity, HarnessAction.PublishPolicy,
                new JSONObject().put("key", key()), bytes("{\"permissions\":[\"deployment.forbidden\"]}")));
        String business = published.getString("digest");
        CommonToolsTest.releases.put("business", business);
        JSONObject task = task("media", business, "scripted", 8192).put("id", "media").put("runtime", ref("trusted-cpu"))
                .put("skillReleases", new JSONArray(CommonToolsTest.releases.values())).put("toolSetVersion", "broker-tools")
                .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"));
        if (longWait) task.put("executionLimits", new JSONObject().put("maxRounds",8).put("maxTools",32).put("maxRepairs",2).put("maxLifetimeMillis",300000));
        profiles.publish(T, task);
        profiles.bind(T, "task", "media", "media@media", 0);
        try {
            String image = input(forest.resolve("media/warmup.jpg"), "application/octet-stream");
            JSONObject snapshot = new JSONObject(new String(Files.readAllBytes(forest.resolve("fixtures/uploaded-snapshot.json")), StandardCharsets.UTF_8));
            snapshot.getJSONObject("data").getJSONArray("items").getJSONObject(0).getJSONObject("media").put("inputId", image);
            JSONObject secondRecord = new JSONObject(snapshot.getJSONObject("data").getJSONArray("items").getJSONObject(0).toString());
            secondRecord.put("id", "fixture-record-2");
            secondRecord.getJSONObject("media").put("id", "fixture-photo-2");
            snapshot.getJSONObject("data").getJSONArray("items").put(secondRecord);
            Path snapshotFile = root.resolve("snapshot.json");
            Files.write(snapshotFile, bytes(snapshot.toString()));
            String data = input(snapshotFile, "application/json");
            String run = profiles.createRun(identity, key(), "media", request("single picture").put("input", new JSONObject().put("instruction", "single picture").put("attachmentIds", new JSONArray().put(data).put(image)))).getString("runId");
            JdbcRunStore.Lease lease = running(run, 60000);
            for (String digest : CommonToolsTest.releases.values()) {
                resources.activate(identity, run, digest);
            }
            check(!workflow(run).getBoolean("satisfied"), "M3 rejects final before any query");
            JSONObject query = new JSONObject().put("skill", "fhe/patrol").put("connectorId", "uploaded-records").put("argumentsJson", new JSONObject().put("inputId", data).toString());
            JSONObject queried = call(lease, "common.data.query", query);
            check(!queried.has("errorCode"), "query error: " + queried);
            check(queried.getBoolean("complete"), "snapshot coverage");
            check(gaps(run) == 1, "M3 requires processing after complete query");
            String ref = queried.getJSONArray("mediaRefs").getJSONObject(0).getString("mediaRef");
            JSONObject processed = call(lease, "common.processing.process", new JSONObject().put("action", "process")
                    .put("skill", "fhe/patrol").put("profileId", "uploaded-summary")
                    .put("pageRefs", new JSONArray().put(queried.getString("pageRef"))).put("limit", 1));
            check(!processed.has("errorCode"), "snapshot processing: " + processed);
            check(processed.getJSONObject("coverage").getBoolean("queryComplete"), "snapshot processing coverage");
            check(processed.getInt("totalItems") == 2 && processed.getInt("nextOffset") == 1, "record paging keeps full dataset");
            check(gaps(run) == 2, "M3 requires both records analyzed");
            check(new JSONArray(processed.getString("itemsJson")).getJSONObject(0).getJSONArray("sources")
                    .getJSONObject(0).getString("mediaRef").equals(ref), "processing retains original media capability reference");
            broker.close();
            broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).files(CommonToolsTest.files)
                    .context(resources, new ProviderStore(store, false), () -> bridge);
            check(gaps(run) == 2, "M3 evidence survives Broker restart");
            JSONObject read = new JSONObject().put("action", "read").put("skill", "fhe/patrol").put("profileId", "uploaded-summary")
                    .put("datasetRef", processed.getString("datasetRef")).put("view", "groups");
            JSONObject group = call(lease, "common.processing.process", read);
            check(!group.has("errorCode") && group.getInt("totalItems") == 1, "persisted dataset readable through restarted Broker");
            JSONObject secondPage = call(lease, "common.processing.process", new JSONObject(read.toString()).put("view", "records").put("offset", 1));
            check(secondPage.getInt("nextOffset") == -1 && new JSONArray(secondPage.getString("itemsJson")).getJSONObject(0)
                    .getJSONObject("record").getString("recordId").equals("fixture-record-2"), "dataset continuation returns actual remaining record");
            check(call(lease, "common.processing.process", new JSONObject(read.toString()).put("offset", 3))
                    .getString("errorCode").equals("DATA_PROCESS_ARGUMENT_INVALID"), "offset beyond dataset rejected");
            String otherRun = profiles.createRun(identity, key(), "media", request("another run")).getString("runId");
            JdbcRunStore.Lease otherLease = running(otherRun, 60000);
            for (String digest : CommonToolsTest.releases.values()) resources.activate(identity, otherRun, digest);
            check(call(otherLease, "common.processing.process", read).getString("errorCode").equals("MEDIA_REF_FORBIDDEN"), "dataset cannot cross runs");
            check(call(otherLease, "common.processing.process", new JSONObject().put("action", "process").put("skill", "fhe/patrol")
                    .put("profileId", "uploaded-summary").put("pageRefs", new JSONArray().put(queried.getString("pageRef"))))
                    .getString("errorCode").equals("MEDIA_REF_FORBIDDEN"), "page cannot cross runs");
            check(call(lease, "common.processing.process", new JSONObject(read.toString()).put("datasetRef", "forged"))
                    .getString("errorCode").equals("MEDIA_REF_FORBIDDEN"), "forged dataset rejected");
            check(call(lease, "common.processing.process", new JSONObject().put("action", "process").put("skill", "fhe/patrol")
                    .put("profileId", "uploaded-summary").put("pageRefs", new JSONArray().put("forged")))
                    .getString("errorCode").equals("MEDIA_REF_FORBIDDEN"), "forged page rejected");
            JSONObject invoke = new JSONObject().put("skill", "fhe/patrol").put("profileId", "describe").put("mediaRef", ref).put("content", "Describe only the actual image");
            if (longWait) {
                queueCancellation(lease, invoke);
                for (int millis : new int[]{1000,21000,31000}) {
                    store.renew(lease, 60000);
                    responseDelayMillis = millis;
                    int before = inferences.get();
                    long start = System.nanoTime();
                    JSONObject value = call(lease, "common.capability.invoke", invoke);
                    check(!value.has("errorCode") && inferences.get() == before + 1, "one confirmed physical call across boundary " + millis);
                    check(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) >= millis, "real elapsed wait " + millis);
                    check(metadata.finished(broker.jobs.job(executions.get(executions.size()-1).getString("requestId"))), "durable receipt before delivery");
                }
                responseDelayMillis = 2000;
                int previous = inferences.get();
                java.util.concurrent.atomic.AtomicReference<JSONObject> expiredResult = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread waiting = new Thread(() -> {
                    try { expiredResult.set(call(lease, "common.capability.invoke", invoke)); }
                    catch (Throwable error) { failure.set(error); }
                });
                waiting.start();
                long dispatchedBy = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (inferences.get() == previous && System.nanoTime() < dispatchedBy) Thread.sleep(10);
                check(inferences.get() == previous + 1, "deadline regression dispatches once before task expiry");
                try (java.sql.Connection c = config.connect(); java.sql.PreparedStatement p = c.prepareStatement("UPDATE harness_run SET created_at=? WHERE run_id=?")) {
                    p.setLong(1, System.currentTimeMillis() - 300001); p.setString(2, run); p.executeUpdate();
                }
                waiting.join(3000);
                check(!waiting.isAlive() && failure.get() == null && expiredResult.get().has("errorCode"), "task deadline ends waiting without replay");
                store.finish(lease, version(run), "timed_out", "unknown", new JSONObject().put("errorCode", "RUN_LIFETIME_EXCEEDED"), new JSONArray());
                JSONObject lateJob = broker.jobs.job(executions.get(executions.size()-1).getString("requestId"));
                long receiptBy = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (!metadata.finished(lateJob) && System.nanoTime() < receiptBy) Thread.sleep(10);
                check(metadata.finished(lateJob) && store.read(T,S,run).getJSONObject("snapshot").getString("state").equals("timed_out")
                        && inferences.get() == previous + 1, "late receipt persists after task expiry without changing terminal state or replaying");
                System.out.println("PASS provider-await-v1: real 1s/21s/31s completions; legacy refusal; task-expiry cancellation and late durable receipt preserve timed_out; one dispatch each.");
                return;
            }
            JSONObject result = call(lease, "common.capability.invoke", invoke);
            check(!result.has("errorCode"), "initial capability result: " + result);
            check(result.getString("answer").contains("Fixture") && inferences.get() == 1, "actual fixture dispatched once, no invented count");
            check(CommonToolsTest.files.artifacts(run).length() == 1, "one trusted source artifact despite replay");
            check(CommonToolsTest.files.artifacts(run).getJSONObject(0).getJSONObject("origin").getBoolean("confirmed"), "source metadata authenticates provider and confirmed call");
            check(gaps(run) == 1, "M3 cannot reuse analysis across distinct records sharing bytes");
            String secondRef = new JSONArray(secondPage.getString("itemsJson")).getJSONObject(0).getJSONArray("sources").getJSONObject(0).getString("mediaRef");
            check(!call(lease, "common.capability.invoke", new JSONObject(invoke.toString()).put("mediaRef", secondRef)).has("errorCode"), "second record analyzed");
            check(workflow(run).getBoolean("satisfied"), "M3 complete confirmed snapshot chain passes");
            JSONObject submission = reportSubmission(processed.getString("datasetRef"), ref, secondRef, result.getString("answer"));
            JSONObject report = report(run, submission);
            check(report.getBoolean("satisfied"), "M4 real provider/JDBC provenance passes: " + report);
            JSONObject citation = report.getJSONObject("report").getJSONArray("reports").getJSONObject(0)
                    .getJSONArray("datasets").getJSONObject(0).getJSONArray("findings").getJSONObject(0).getJSONObject("citation");
            check(CommonToolsTest.files.metadata(citation.getString("artifactId"), "artifact").getString("sha256").equals(citation.getString("artifactSha256")), "M4 citation binds real artifact bytes");
            JSONObject forged = new JSONObject(submission.toString());
            forged.getJSONArray("reports").getJSONObject(0).getJSONArray("datasets").getJSONObject(0).getJSONArray("findings").getJSONObject(0).put("mediaRef", "forged");
            check(!report(run, forged).getBoolean("satisfied"), "M4 forged media reference rejected");
            check(!report(otherRun, submission).getBoolean("satisfied"), "M4 cross-run evidence rejected");
            broker.close();
            broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).files(CommonToolsTest.files).context(resources, new ProviderStore(store, false), () -> bridge);
            check(report(run, submission).getJSONObject("report").similar(report.getJSONObject("report")), "M4 normalized report survives Broker restart");
            evidenceFilters(run);
            cube.service.harness.protocol.Contract.parse("artifact-view", bytes(CommonToolsTest.files.artifacts(run).getJSONObject(0).toString()));
            check(call(lease, "common.capability.invoke", new JSONObject(invoke.toString()).put("mediaRef", "forged")).has("errorCode"), "forged media denied");
            delay = true;
            check(call(lease, "common.capability.invoke", invoke).has("errorCode"), "timeout remains unknown");
            check(call(lease, "common.capability.invoke", invoke).has("errorCode"), "other live node selected");
            check(!executions.get(2).getString("instanceId").equals(executions.get(3).getString("instanceId")), "two Unit nodes share load and retain unknown reservations");
            check(call(lease, "common.capability.invoke", invoke).has("errorCode") && inferences.get() == 4, "unknown capacity cannot be reused");
            check(cancellations.get() == 2, "timeouts request cancellation without assuming physical completion");
            try (StoreConfig reopenedConfig = new StoreConfig(settings)) {
                ProviderStore reopened = new ProviderStore(new JdbcRunStore(reopenedConfig), false);
                JSONObject unknownJob = broker.jobs.job(executions.get(2).getString("requestId"));
                check(!reopened.finished(unknownJob), "strict store re-open retains lack of physical completion");
                fail("CAPABILITY_EXECUTION_ALREADY_STARTED", () -> reopened.reserve(unknownJob, bridge.mediaUnits(), "OmniMultimodal", new JSONObject()));
            }
            JSONObject savedReceipt = physical(executions.get(2));
            delayed.get(0).accept(savedReceipt);
            JSONObject completedJob = broker.jobs.job(executions.get(2).getString("requestId"));
            // Repeat the exact durable bytes, not a newly timestamped receipt.
            metadata.receipt(completedJob, savedReceipt);
            fail("CAPABILITY_RECEIPT_FORBIDDEN", () -> metadata.receipt(completedJob, new JSONObject(savedReceipt.toString()).put("instanceId", "foreign")));
            fail("CAPABILITY_RECEIPT_CONFLICT", () -> metadata.receipt(completedJob, new JSONObject(savedReceipt.toString()).put("success", false)));
            delay = false;
            JSONObject afterReceipt = call(lease, "common.capability.invoke", invoke);
            check(!afterReceipt.has("errorCode") && afterReceipt.getString("answer").contains("Fixture"), "physical late receipt releases only its slot: " + afterReceipt);
            check(CommonToolsTest.files.artifacts(run).length() == 3, "late unknown result did not generate another artifact");
            http(lease, forest);
            JSONObject modelFile = CommonToolsTest.files.writeText(lease, "model-file", new JSONObject().put("fileName", "media-source.json").put("text", "{\"kind\":\"media-source\"}"));
            check(!CommonToolsTest.files.metadata(modelFile.getString("artifactId"), "artifact").has("origin"), "model-written same-name file cannot impersonate provider");
            String grantRef = sourceForTest(metadata);
            String grantKey = key();
            metadata.grantRemote(T, S, grantKey, "grant-request", S, grantRef, true);
            metadata.grantRemote(T, S, key(), "revoke-request", S, grantRef, false);
            metadata.grantRemote(T, S, grantKey, "grant-request", S, grantRef, true);
            fail("DATA_SOURCE_FORBIDDEN", () -> metadata.authorize(T, S, "source-test@1"));
            JSONObject bound = catalog.binding(T, "policy", "default");
            String bindKey = key();
            String target = bound.getString("target");
            JSONObject once = catalog.bindRemote(T, S, bindKey, "request-hash", target, bound.getLong("version"));
            check(JdbcRunStore.canonical(catalog.bindRemote(T, S, bindKey, "request-hash", target, bound.getLong("version"))).equals(JdbcRunStore.canonical(once)), "CAS acknowledgement survives repeat after commit");
            fail("IDEMPOTENCY_CONFLICT", () -> catalog.bindRemote(T, S, bindKey, "changed-request", target, bound.getLong("version")));
            fail("VERSION_CONFLICT", () -> catalog.bindRemote(T, S, key(), "other-request", target, bound.getLong("version")));
            detectionReservations(lease);
            System.out.println("PASS M1/M2/M3/M4: signed package, confirmed provider/JDBC provenance, normalized report, forged/cross-run citations, restart, source revocation, evidence filtering, unknown execution, two Units. No live inference executed.");
        } finally {
            broker.close();
            CommonToolsTest.files.close();
        }
    }

    static JSONObject workflow(String run) throws Exception {
        return broker.completion(identity, run, profiles.pinned(identity, run), catalog.activeSkills(T, S, run));
    }
    static JSONObject report(String run, JSONObject submission) throws Exception {
        return broker.completion(identity, run, profiles.pinned(identity, run), catalog.activeSkills(T, S, run),
                new JSONObject().put("kind", "final").put("text", submission.toString()));
    }
    static JSONObject reportSubmission(String dataset, String first, String second, String answer) {
        JSONArray findings = new JSONArray(); int index = 0;
        for (String ref : Arrays.asList(first, second)) findings.put(new JSONObject().put("recordIndex", index++)
                .put("profileId", "describe").put("mediaRef", ref).put("observation", answer)
                .put("assessment", new JSONObject().put("status", "not-assessed").put("reason", "Protocol fixture").put("ruleIds", new JSONArray())));
        return new JSONObject().put("schemaVersion", 1).put("reports", new JSONArray().put(new JSONObject().put("skill", "fhe/patrol")
                .put("summary", "Two fixture records").put("limitations", new JSONArray().put("No real inference"))
                .put("datasets", new JSONArray().put(new JSONObject().put("datasetRef", dataset).put("findings", findings)))));
    }
    static int gaps(String run) throws Exception { return workflow(run).getJSONArray("checks").getJSONObject(0).getInt("missingCount"); }

    /** Change only test DB observation flags, then restore them: each conjunct must be enforced. */
    static void evidenceFilters(String run) throws Exception {
        JSONArray facts = metadata.completionEvidence(T, S, run);
        check(facts.length() == 4, "query + dataset + two analysis facts");
        fail("RUN_NOT_FOUND", () -> metadata.completionEvidence(T, "another-user", run));
        try (java.sql.Connection c = config.connect()) {
            for (String condition : Arrays.asList("actual_finished=0", "effect_status='unknown'")) {
                try (java.sql.PreparedStatement p = c.prepareStatement("UPDATE harness_tool_job SET " + condition + " WHERE job_id IN (SELECT job_id FROM harness_provider_fact WHERE run_id=?)")) { p.setString(1, run); p.executeUpdate(); }
                check(metadata.completionEvidence(T, S, run).length() == 0, "unfinished/unknown facts excluded");
                try (java.sql.PreparedStatement p = c.prepareStatement("UPDATE harness_tool_job SET actual_finished=1,effect_status='completed' WHERE job_id IN (SELECT job_id FROM harness_provider_fact WHERE run_id=?)")) { p.setString(1, run); p.executeUpdate(); }
            }
            try (java.sql.PreparedStatement p = c.prepareStatement("UPDATE harness_tool_call SET status='intent' WHERE run_id=? AND result_json IS NOT NULL")) { p.setString(1, run); p.executeUpdate(); }
            check(metadata.completionEvidence(T, S, run).length() == 0, "unconfirmed facts excluded");
            try (java.sql.PreparedStatement p = c.prepareStatement("UPDATE harness_tool_call SET status='confirmed',result_json=replace(result_json,'success','failure') WHERE run_id=?")) { p.setString(1, run); p.executeUpdate(); }
            check(metadata.completionEvidence(T, S, run).length() == 0, "confirmed errors excluded");
            try (java.sql.PreparedStatement p = c.prepareStatement("UPDATE harness_tool_call SET result_json=replace(result_json,'failure','success') WHERE run_id=?")) { p.setString(1, run); p.executeUpdate(); }
        }
        check(metadata.completionEvidence(T, S, run).similar(facts) && workflow(run).getBoolean("satisfied"), "restored confirmed evidence passes");
    }

    /**
     * 走签名 HTTP 通道的数据源查询与能力调用测试。
     *
     * @param lease 运行租约。
     * @param forest forest 工程目录。
     * @throws Exception 测试执行过程中的异常。
     */
    static void http(JdbcRunStore.Lease lease, Path forest) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean loop = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/", x -> {
            StringBuilder canonical = new StringBuilder("GET\n").append(x.getRequestURI().toASCIIString());
            for (String name : Arrays.asList("X-Harness-Source", "X-Harness-Tenant", "X-Harness-Subject", "X-Harness-Issued-At", "X-Harness-Nonce")) {
                canonical.append('\n').append(x.getRequestHeaders().getFirst(name));
            }
            if (!signature(canonical.toString()).equals(x.getRequestHeaders().getFirst("X-Harness-Signature")) || !S.equals(x.getRequestHeaders().getFirst("X-Harness-Subject"))) {
                send(x, 403, "application/json", bytes("{}"));
                return;
            }
            requests.incrementAndGet();
            if (x.getRequestURI().getPath().equals("/api/media/content")) {
                send(x, 200, "image/jpeg", Files.readAllBytes(forest.resolve("media/warmup.jpg")));
            } else {
                JSONObject page = new JSONObject(new String(Files.readAllBytes(forest.resolve("fixtures/forest-records-page.json")), StandardCharsets.UTF_8));
                boolean continuation = x.getRequestURI().getRawQuery().contains("cursor=");
                if (continuation && !x.getRequestURI().getRawQuery().contains("snapshotId=fixture-snapshot")) {
                    send(x, 409, "application/json", bytes("{}"));
                    return;
                }
                page.getJSONObject("data").put("nextCursor", !continuation || loop.get() ? "fixture-next" : "").put("snapshotId", "fixture-snapshot");
                send(x, 200, "application/json", bytes(page.toString()));
            }
        });
        server.start();
        try {
            metadata.putSource(T, "source-f", "1", new JSONObject().put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())
                    .put("secretRef", "f-fixture").put("identityMapping", "cube-subject-hmac-v1")
                    .put("allowedPaths", new JSONArray().put("/api/patrol/records").put("/api/media/content")));
            metadata.grant(T, S, "source-f@1", true);
            JSONObject query = new JSONObject().put("skill", "fhe/patrol").put("connectorId", "patrol-records")
                    .put("argumentsJson", "{\"from\":\"2026-11-03T00:00:00+08:00\",\"to\":\"2026-11-04T00:00:00+08:00\",\"regionId\":\"\"}");
            JSONObject found = call(lease, "common.data.query", query);
            check(!found.has("errorCode"), "HTTP query: " + found);
            check(!found.getBoolean("complete"), "first page cannot claim complete coverage");
            JSONObject continuation = new JSONObject(query.toString()).put("cursor", found.getString("nextCursor"));
            JSONObject last = call(lease, "common.data.query", continuation);
            check(last.getBoolean("complete"), "opaque cursor keeps query and upstream snapshot");
            JSONObject process = new JSONObject().put("action", "process").put("skill", "fhe/patrol").put("profileId", "patrol-summary")
                    .put("pageRefs", new JSONArray().put(found.getString("pageRef")));
            JSONObject partial = call(lease, "common.processing.process", process);
            check(!partial.getJSONObject("coverage").getBoolean("queryComplete"), "processing a first page discloses incomplete scope");
            process.getJSONArray("pageRefs").put(last.getString("pageRef"));
            JSONObject processed = call(lease, "common.processing.process", process);
            check(processed.getJSONObject("coverage").getBoolean("queryComplete"), "full confirmed page chain is complete");
            check(processed.getJSONObject("stats").getInt("duplicateRecords") == 1
                    && new JSONArray(processed.getString("itemsJson")).getJSONObject(0).getJSONArray("sources").length() == 2,
                    "cross-page exact dedup retains both original sources");
            JSONObject read = new JSONObject().put("action", "read").put("skill", "fhe/patrol").put("profileId", "patrol-summary")
                    .put("datasetRef", processed.getString("datasetRef"));
            loop.set(true);
            check(call(lease, "common.data.query", continuation).getString("errorCode").equals("DATA_QUERY_INCOMPLETE"), "repeated upstream cursor cannot claim completion");
            String ref = found.getJSONArray("mediaRefs").getJSONObject(0).getString("mediaRef");
            JSONObject invoke = new JSONObject().put("skill", "fhe/patrol").put("profileId", "describe").put("mediaRef", ref).put("content", "Describe");
            check(!call(lease, "common.capability.invoke", invoke).has("errorCode"), "signed HTTP image fetch -> Unit fixture");
            int before = requests.get();
            metadata.grant(T, S, "source-f@1", false);
            check(call(lease, "common.processing.process", read).getString("errorCode").equals("DATA_SOURCE_FORBIDDEN")
                    && requests.get() == before, "revocation also blocks immutable dataset reads without upstream requests");
            fail("DATA_SOURCE_FORBIDDEN", () -> workflow(lease.runId));
            check(call(lease, "common.capability.invoke", invoke).getString("errorCode").equals("DATA_SOURCE_FORBIDDEN") && requests.get() == before, "revoked source never reaches F");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 登记一个仅用于测试的数据源并授予访问权。
     *
     * @param store 数据源存储。
     * @return 返回数据源引用。
     */
    static String sourceForTest(ProviderStore store) {
        store.putSource(T, "source-test", "1", new JSONObject().put("fixture", true));
        store.grant(T, S, "source-test@1", true);
        return "source-test@1";
    }

    static void detectionReservations(JdbcRunStore.Lease lease) throws Exception {
        JSONObject fire=detectionNode("fire-only","fire-smoke"),thing=detectionNode("thing-only","thing");
        JSONArray units=new JSONArray().put(fire).put(thing);
        JSONObject request=new JSONObject().put("option",new JSONObject().put("operation","detect").put("profile","thing"));
        Properties admission = new Properties(); admission.setProperty("capability.allowed.units", "SpecializedDetection");
        JSONObject profileConfig = new JSONObject().put("version", "1").put("profiles", new JSONArray().put(new JSONObject()
                .put("id", "objects").put("adapter", "specialized-detection").put("unit", "SpecializedDetection")
                .put("mediaTypes", new JSONArray().put("image/jpeg"))
                .put("optionsJson", "{\"operation\":\"detect\",\"profile\":\"thing\",\"classes\":[\"person\"]}")));
        QueryProvider.ProviderInvocationLookup lookup = new QueryProvider.ProviderInvocationLookup() {
            public JSONObject source(String ref) { throw new AssertionError("admission does not read source"); }
            public JSONArray mediaUnits() { return units; }
        };
        try (CapabilityInvokeProvider provider = new CapabilityInvokeProvider(admission)) {
            thing.put("availableImages", 0);
            provider.preflightConfiguration(lookup, "analysis-profiles.json", profileConfig);
            JSONObject unavailableJob = detectionJob(lease);
            fail("CAPABILITY_CAPACITY_UNAVAILABLE", () -> metadata.reserve(unavailableJob, units, "SpecializedDetection", request));
            broker.jobs.cancel(lease, unavailableJob.getString("job_id"));
            thing.put("availableImages", 1);
        }
        JSONObject job=detectionJob(lease);
        JSONObject execution=metadata.reserve(job,units,"SpecializedDetection",request);
        check("thing-only".equals(execution.getString("instanceId")),"durable reservation matches profile");
        JSONObject next=detectionJob(lease);
        fail("CAPABILITY_CAPACITY_UNAVAILABLE",()->metadata.reserve(next,units,"SpecializedDetection",request));
        thing.getJSONArray("profiles").put("fire-smoke");
        JSONObject other=new JSONObject(request.toString());other.getJSONObject("option").put("profile","fire-smoke");
        JSONObject second=metadata.reserve(next,units,"SpecializedDetection",other);
        check("fire-only".equals(second.getString("instanceId")),"profiles share the same persistent physical slot");
        metadata.receipt(job,new JSONObject().put("actualFinished",true).put("instanceId","thing-only").put("requestId",job.getString("job_id")));
        thing.put("statusExpiresAt",0);
        JSONObject third=detectionJob(lease);
        fail("CAPABILITY_NOT_READY",()->metadata.reserve(third,units,"SpecializedDetection",request));
        thing.put("statusExpiresAt",System.currentTimeMillis()+30000);
        request.getJSONObject("option").put("sliced",true);
        fail("CAPABILITY_NOT_READY",()->metadata.reserve(third,units,"SpecializedDetection",request));
        thing.put("slicedProfiles",new JSONArray().put("thing"));
        check(metadata.reserve(third,units,"SpecializedDetection",request).getString("instanceId").equals("thing-only"),"reported slicing enables reservation");
        metadata.receipt(next,new JSONObject().put("actualFinished",true).put("instanceId","fire-only").put("requestId",next.getString("job_id")));
        metadata.receipt(third,new JSONObject().put("actualFinished",true).put("instanceId","thing-only").put("requestId",third.getString("job_id")));
        for(JSONObject done:Arrays.asList(job,next,third)) broker.jobs.cancel(lease,done.getString("job_id"));
    }

    static JSONObject detectionJob(JdbcRunStore.Lease lease) throws Exception {
        String id=key();
        JSONObject arguments=new JSONObject().put("skill","fhe/patrol").put("profileId","describe").put("mediaRef","fixture").put("content","Fixture");
        intent(lease,"call",id,new JSONObject().put("proposalId",id).put("tool","common.capability.invoke").put("argumentsJson",arguments.toString()));
        return broker.enqueue(lease,row(lease.runId,"call",id),resources.executable(identity,lease.runId,"common.capability.invoke"));
    }

    static void queueCancellation(JdbcRunStore.Lease lease, JSONObject invoke) throws Exception {
        store.renew(lease, 60000);
        JSONObject first = detectionJob(lease), second = detectionJob(lease);
        for (JSONObject occupied : Arrays.asList(first, second)) {
            String worker = "occupied-" + key();
            broker.jobs.identity(worker, "runtime", 1);
            JSONObject wire = broker.jobs.poll(worker, "runtime", "occupied", true, "tool",
                    (j,w,r,i) -> broker.wire(j,w,r,i,true));
            check(wire.getString("jobId").equals(occupied.getString("job_id")), "occupied test job accepted before the waiter");
            broker.jobs.accepted(wire.getString("jobId"), wire.getString("jobHash"));
        }
        JSONArray units = new JSONArray();
        for (String node : Arrays.asList("unit-a", "unit-b")) units.put(new JSONObject().put("unit", "OmniMultimodal")
                .put("instanceId", node).put("capacity", 1).put("ready", true));
        JSONObject request = new JSONObject().put("deadlineAt", System.currentTimeMillis() + 60000);
        JSONObject one = metadata.reserve(first, units, "OmniMultimodal", request);
        JSONObject two = metadata.reserve(second, units, "OmniMultimodal", request);
        int before = inferences.get();
        java.util.concurrent.atomic.AtomicReference<JSONObject> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { result.set(call(lease, "common.capability.invoke", invoke)); }
            catch (Throwable failure) { error.set(failure); }
        });
        caller.start();
        String queued = null;
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (queued == null && System.nanoTime() < until) {
            try (java.sql.Connection c = config.connect(); java.sql.PreparedStatement p = c.prepareStatement(
                    "SELECT f.job_id,f.value_json FROM harness_provider_fact f JOIN harness_tool_job j ON f.job_id=j.job_id WHERE j.run_id=? AND j.status='accepted' AND j.actual_finished=0 AND f.fact_kind='media-phase'")) {
                p.setString(1, lease.runId);
                try (java.sql.ResultSet r = p.executeQuery()) {
                    while (r.next()) if ("queued".equals(new JSONObject(r.getString(2)).optString("phase"))) queued = r.getString(1);
                }
            }
            if (queued == null) Thread.sleep(10);
        }
        check(queued != null, "Provider reaches capacity queue with both physical slots reserved");
        broker.jobs.cancel(lease, queued);
        caller.join(3000);
        check(!caller.isAlive() && inferences.get() == before, "queue cancellation never dispatches a physical request");
        check(error.get() instanceof StoreException || result.get() != null && result.get().has("errorCode"), "queued cancellation returns an explicit failure");
        try (java.sql.Connection c = config.connect(); java.sql.PreparedStatement p = c.prepareStatement(
                "SELECT COUNT(*) FROM harness_provider_fact WHERE job_id=? AND fact_kind='execution'")) {
            p.setString(1, queued);
            try (java.sql.ResultSet r = p.executeQuery()) { r.next(); check(r.getInt(1) == 0, "cancelled waiter never acquires another reservation"); }
        }
        for (JSONObject execution : Arrays.asList(one, two)) {
            JSONObject job = broker.jobs.job(execution.getString("requestId"));
            metadata.receipt(job, new JSONObject().put("requestId", execution.getString("requestId"))
                    .put("instanceId", execution.getString("instanceId")).put("actualFinished", true).put("success", false).put("unsent", true));
            broker.jobs.cancel(lease, job.getString("job_id"));
        }
        System.out.println("PASS capacity queue cancellation: both slots occupied, explicit cancellation, zero physical dispatches or new reservations.");
    }

    static JSONObject detectionNode(String id,String profile) {
        return new JSONObject().put("unit","SpecializedDetection").put("instanceId",id).put("capacity",1).put("ready",true)
                .put("availableImages",1).put("profiles",new JSONArray().put(profile)).put("slicedProfiles",new JSONArray())
                .put("statusExpiresAt",System.currentTimeMillis()+30000);
    }
}
