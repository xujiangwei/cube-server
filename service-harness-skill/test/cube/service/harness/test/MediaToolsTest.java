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
        JSONObject wire = broker.jobs.poll(worker, "runtime", "instance", true, "tool", broker::wire);
        check(wire != null, "tool dispatched");
        broker.jobs.accepted(wire.getString("jobId"), wire.getString("jobHash"));
        job = broker.jobs.job(job.getString("job_id"));
        JSONObject exchange = broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        // 重放已受理的调用不得再次触达上游。
        broker.invokeArguments(job, wire.getJSONObject("grant"), args);
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
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
        TEST_PERMISSIONS.addAll(Arrays.asList("data.query", "capability.invoke"));
        CommonToolsTest.prepare(Files.createTempDirectory(packages.getParent(), "media-test-"), packages, "http://127.0.0.1:1/search");
        Properties settings = CommonToolsTest.settings;
        for (String name : Arrays.asList("data", "capability")) {
            Properties pc = new Properties();
            pc.setProperty("capability.allowed.units", "OmniMultimodal,OmniPerception,SpecializedDetection");
            pc.setProperty("capability.timeout.millis", "150");
            pc.setProperty("secret.f-fixture", SECRET);
            Path file = root.resolve(name + ".properties");
            Files.write(file, bytes(ExecutionTest.properties(pc)));
            settings.setProperty("broker.provider." + name + ".class", "cube.service.harness.skill." + (name.equals("data") ? "DataQueryProvider" : "CapabilityInvokeProvider"));
            settings.setProperty("broker.provider." + name + ".config", file.toString());
            String digest = upload(Files.readAllBytes(packages.resolve(name + "-v1.zip"))).getString("digest");
            CommonToolsTest.releases.put(name, digest);
        }
        settings.setProperty("broker.providers", settings.getProperty("broker.providers") + ",data,capability");
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
                        out.put(new JSONObject().put("unit", unit).put("instanceId", node).put("capacity", 1).put("ready", true));
                    }
                }
                return out;
            }

            @Override
            public void invokeMedia(JSONObject execution, byte[] image, Consumer<JSONObject> callback) {
                inferences.incrementAndGet();
                executions.add(execution);
                if (delay) {
                    delayed.add(callback);
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
        byte[] zip = Files.readAllBytes(forest.resolve("build/fhe-patrol-v1.zip"));
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
        profiles.publish(T, task);
        profiles.bind(T, "task", "media", "media@media", 0);
        try {
            String image = input(forest.resolve("media/warmup.jpg"), "application/octet-stream");
            JSONObject snapshot = new JSONObject(new String(Files.readAllBytes(forest.resolve("fixtures/uploaded-snapshot.json")), StandardCharsets.UTF_8));
            snapshot.getJSONObject("data").getJSONArray("items").getJSONObject(0).getJSONObject("media").put("inputId", image);
            Path snapshotFile = root.resolve("snapshot.json");
            Files.write(snapshotFile, bytes(snapshot.toString()));
            String data = input(snapshotFile, "application/json");
            String run = profiles.createRun(identity, key(), "media", request("single picture").put("input", new JSONObject().put("instruction", "single picture").put("attachmentIds", new JSONArray().put(data).put(image)))).getString("runId");
            JdbcRunStore.Lease lease = running(run, 60000);
            for (String digest : CommonToolsTest.releases.values()) {
                resources.activate(identity, run, digest);
            }
            JSONObject query = new JSONObject().put("skill", "fhe/patrol").put("connectorId", "uploaded-records").put("argumentsJson", new JSONObject().put("inputId", data).toString());
            JSONObject queried = call(lease, "common.data.query", query);
            check(!queried.has("errorCode"), "query error: " + queried);
            check(queried.getBoolean("complete"), "snapshot coverage");
            String ref = queried.getJSONArray("mediaRefs").getJSONObject(0).getString("mediaRef");
            JSONObject invoke = new JSONObject().put("skill", "fhe/patrol").put("profileId", "describe").put("mediaRef", ref).put("content", "Describe only the actual image");
            JSONObject result = call(lease, "common.capability.invoke", invoke);
            check(result.getString("answer").contains("Fixture") && inferences.get() == 1, "actual fixture dispatched once, no invented count");
            check(CommonToolsTest.files.artifacts(run).length() == 1, "one trusted source artifact despite replay");
            check(CommonToolsTest.files.artifacts(run).getJSONObject(0).getJSONObject("origin").getBoolean("confirmed"), "source metadata authenticates provider and confirmed call");
            cube.service.harness.protocol.Contract.parse("artifact-view", bytes(CommonToolsTest.files.artifacts(run).getJSONObject(0).toString()));
            check(call(lease, "common.capability.invoke", new JSONObject(invoke.toString()).put("mediaRef", "forged")).has("errorCode"), "forged media denied");
            delay = true;
            check(call(lease, "common.capability.invoke", invoke).has("errorCode"), "timeout remains unknown");
            check(call(lease, "common.capability.invoke", invoke).has("errorCode"), "other live node selected");
            check(!executions.get(1).getString("instanceId").equals(executions.get(2).getString("instanceId")), "two Unit nodes share load and retain unknown reservations");
            check(call(lease, "common.capability.invoke", invoke).has("errorCode") && inferences.get() == 3, "unknown capacity cannot be reused");
            check(cancellations.get() == 2, "timeouts request cancellation without assuming physical completion");
            try (StoreConfig reopenedConfig = new StoreConfig(settings)) {
                ProviderStore reopened = new ProviderStore(new JdbcRunStore(reopenedConfig), false);
                JSONObject unknownJob = broker.jobs.job(executions.get(1).getString("requestId"));
                check(!reopened.finished(unknownJob), "strict store re-open retains lack of physical completion");
                fail("CAPABILITY_EXECUTION_ALREADY_STARTED", () -> reopened.reserve(unknownJob, bridge.mediaUnits(), "OmniMultimodal", new JSONObject()));
            }
            delayed.get(0).accept(physical(executions.get(1)));
            delay = false;
            check(call(lease, "common.capability.invoke", invoke).getString("answer").contains("Fixture"), "physical late receipt releases only its slot");
            check(CommonToolsTest.files.artifacts(run).length() == 2, "late unknown result did not generate another artifact");
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
            System.out.println("PASS M1: signed package, bound image -> Broker -> Unit fixture -> source artifact; forged refs, timeout, restart, late receipts, two Units, revoked grant, CAS replay. No live inference executed.");
        } finally {
            broker.close();
            CommonToolsTest.files.close();
        }
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
            check(call(lease, "common.data.query", continuation).getBoolean("complete"), "opaque cursor keeps query and upstream snapshot");
            loop.set(true);
            check(call(lease, "common.data.query", continuation).getString("errorCode").equals("DATA_QUERY_INCOMPLETE"), "repeated upstream cursor cannot claim completion");
            String ref = found.getJSONArray("mediaRefs").getJSONObject(0).getString("mediaRef");
            JSONObject invoke = new JSONObject().put("skill", "fhe/patrol").put("profileId", "describe").put("mediaRef", ref).put("content", "Describe");
            check(!call(lease, "common.capability.invoke", invoke).has("errorCode"), "signed HTTP image fetch -> Unit fixture");
            int before = requests.get();
            metadata.grant(T, S, "source-f@1", false);
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
}
