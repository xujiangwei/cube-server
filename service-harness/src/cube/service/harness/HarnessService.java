/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.util.log.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cube.core.AbstractModule;
import cube.core.Kernel;
import cube.plugin.PluginSystem;
import cube.service.harness.execution.ExecutionRuntime;
import cube.service.harness.execution.JettyExchange;
import cube.service.harness.execution.ModelProtocol;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreConfig;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Run admission awaits profiles/coordinator.
 */
public final class HarnessService extends AbstractModule {

    public static final String NAME = "Harness";

    private HarnessConfig config;

    private WorkerRegistry registry;

    private ThreadPoolExecutor executor, httpExecutor, businessExecutor;

    private HarnessBusiness business;

    private HttpServer http;

    private org.eclipse.jetty.server.Server executionHttp;

    private volatile String startupError;

    private JdbcRunStore store;

    private ExecutionRuntime execution;

    public HarnessService() {
    }

    public HarnessService(HarnessConfig config) {
        this.config = config;
    }

    private static ThreadPoolExecutor pool(String name, int size, int capacity) {
        return new ThreadPoolExecutor(
                size,
                size,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(capacity),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 启动摘要。只输出非敏感项：凭据、令牌一律不进入日志，长路径截断避免刷屏。
     */
    private String startupSummary() {
        java.util.Properties v = config.values;
        StringBuilder out = new StringBuilder();
        out.append("host=").append(config.host()).append(" port=").append(config.port())
                .append(" leaseMillis=").append(config.leaseMillis());
        out.append(" store=").append(store != null);
        if (store != null)
            out.append(" dialect=").append(config.values.getProperty("store.dialect", ""))
                    .append(" database=").append(config.values.getProperty("store.database", ""));
        out.append(" execution=").append(execution != null);
        if (execution != null)
            out.append(" accepting=").append(execution.accepting())
                    .append(" maxActive=").append(v.getProperty("execution.max.active", "128"))
                    .append(" permissions=").append(v.getProperty("execution.permissions", "").replace(",", "|"));
        out.append(" workers=").append(workerCount(v));
        out.append(" files=").append(v.getProperty("files.enabled", "false"));
        out.append(" trace=").append(v.getProperty("trace.enabled", "false"))
                .append(" feedback=").append(v.getProperty("feedback.enabled", "false"));
        out.append(" inferenceNode=").append(v.getProperty("inference.node.id", ""));
        return out.toString();
    }

    /**
     * 统计授权的 Python worker 数（worker.<id>.role），只计数不打印 workerId。
     */
    private static int workerCount(java.util.Properties v) {
        int count = 0;
        for (String name : v.stringPropertyNames())
            if (name.startsWith("worker.") && name.endsWith(".role")) count++;
        return count;
    }

    @Override
    public synchronized void start() {
        if (started.get()) return;
        try {
            if (config == null) config = HarnessConfig.load();
            // N03 is optional and fail-closed. DDL is only available in the explicit migration CLI.
            if (Boolean.parseBoolean(config.values.getProperty("store.enabled", "false")))
                store = new JdbcRunStore(new StoreConfig(config.values));

            registry = new WorkerRegistry(config.leaseMillis());
            if (Boolean.parseBoolean(config.values.getProperty("execution.enabled", "false"))) {
                if (store == null) throw new IllegalArgumentException("STORE_REQUIRED");
                execution =
                        new ExecutionRuntime(
                                store,
                                config.values,
                                config.leaseMillis(),
                                () -> {
                                    Object module =
                                            getKernel() == null
                                                    ? null
                                                    : getKernel().getModule("AIGC");
                                    return module instanceof cube.aigc.completion.CompletionProvider
                                            ? (cube.aigc.completion.CompletionProvider) module
                                            : null;
                                });
            }

            executor = pool("harness-cell", 2, 64);
            businessExecutor = pool("harness-business", 4, 32);
            business = new HarnessBusiness(this, config, execution);

            started.set(true);
            if (execution == null) {
                httpExecutor = pool("harness-http", 2, 32);
                http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 32);
                http.createContext("/", this::handleHttp);
                http.setExecutor(httpExecutor);
                http.start();
            } else {
                executionHttp = JettyExchange.start(config.host(), config.port(), this::handleHttp);
            }
            if (execution != null) execution.start();
            startupError = null;
            Logger.i(
                    HarnessService.class,
                    "#start - Harness started: " + startupSummary());
        } catch (Exception e) {
            startupError = "HARNESS_CONFIGURATION_ERROR";
            stop();
            Logger.e(HarnessService.class, "#start - Harness startup refused; check private configuration and port", e);
        }
    }

    @Override
    public synchronized void stop() {
        boolean running = started.getAndSet(false);
        if (running)
            Logger.i(
                    HarnessService.class,
                    "#stop - Harness stopping: "
                            + "execution="
                            + (execution != null)
                            + " http="
                            + (http != null || executionHttp != null));
        // Stop new work and close streams before draining requests that use the runtime.
        if (business != null) business.close();
        if (executionHttp != null) {
            try {
                executionHttp.stop();
            } catch (Exception ignored) {
            }
            executionHttp = null;
        }
        if (http != null) {
            http.stop(1);
            http = null;
        }
        for (ThreadPoolExecutor pool : Arrays.asList(executor, httpExecutor, businessExecutor))
            if (pool != null) {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                        pool.awaitTermination(3, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        business = null;
        if (execution != null) {
            execution.close();
            execution = null;
        }
        if (registry != null) registry.clear();
        store = null;
    }

    public JdbcRunStore runStore() {
        return store;
    }

    public ExecutorService executor() {
        return executor;
    }

    public void disconnect(long transport) {
        HarnessBusiness current = business;
        if (current != null && current.transfers != null) current.transfers.disconnect(transport);
        if (registry != null) registry.disconnect(transport);
        if (execution != null) {
            execution.workers.disconnect(transport);
            if (execution.runners != null) execution.runners.disconnect(transport);
        }
    }

    @Override
    public <T extends PluginSystem> T getPluginSystem() {
        return null;
    }

    @Override
    public void onTick(cube.core.Module module, Kernel kernel) {
        if (registry != null) registry.snapshot();
    }

    static boolean equal(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public JSONObject process(byte[] raw, long transport) {
        return process(raw, transport, () -> true);
    }

    public JSONObject process(
            byte[] raw, long transport, java.util.function.BooleanSupplier valid) {
        String id = "";
        int protocol = 1;
        try {
            if (!started.get()) throw new IllegalArgumentException("HARNESS_NOT_READY");
            JSONObject request = StrictJson.object(raw);
            if (request.optInt("protocolVersion", 0) == 2
                    || request.optInt("protocolVersion", 0) == 3
                    || request.optInt("protocolVersion", 0) == 4
                    || request.optInt("protocolVersion", 0) == 5
                    || request.optInt("protocolVersion", 0) == 6)
                protocol = request.getInt("protocolVersion");
            if (request.opt("requestId") instanceof String
                    && request.getString("requestId").length() <= 128)
                id = request.getString("requestId");
            if (protocol == 4 || protocol == 5 || protocol == 6) {
                cube.service.harness.execution.ToolProtocol.parse("runner-request", raw);
                if (!equal(
                        config.values.getProperty("worker.credential"),
                        request.getString("credential")))
                    throw new IllegalArgumentException("UNAUTHORIZED");
                if (execution == null || execution.runners == null)
                    throw new IllegalArgumentException("RUNNER_DISABLED");
                return execution.runners.apply(request, transport, valid);
            }
            if (protocol == 2 || protocol == 3) {
                if (execution == null) {
                    protocol = 1;
                    throw new IllegalArgumentException("PROTOCOL_MISMATCH");
                }
                ModelProtocol.parse(
                        protocol == 3 ? "model-worker-v3-request" : "model-worker-request", raw);
                if (!equal(
                        config.values.getProperty("worker.credential"),
                        request.getString("credential")))
                    throw new IllegalArgumentException("UNAUTHORIZED");
                return execution.workers.apply(request, transport, valid);
            }
            if (request.has("protocolVersion")
                    && (!(request.get("protocolVersion") instanceof Number)
                    || ((Number) request.get("protocolVersion")).doubleValue() != 1))
                throw new IllegalArgumentException("PROTOCOL_MISMATCH");
            Contract.validate(
                    request,
                    new JSONObject(
                            new String(
                                    Contract.resource("schemas/worker-request.json"),
                                    StandardCharsets.UTF_8)));
            if (!equal(
                    config.values.getProperty("worker.credential"),
                    request.getString("credential")))
                throw new IllegalArgumentException("UNAUTHORIZED");
            if (!config.authorizedWorker(request.getString("workerId"), request.getString("role")))
                throw new IllegalArgumentException("FORBIDDEN");
            return registry.apply(request, transport, valid)
                    .put("protocolVersion", 1)
                    .put("requestId", id)
                    .put("ok", true);
        } catch (StoreException e) {
            return error(id, e.code).put("protocolVersion", protocol);
        } catch (IllegalArgumentException e) {
            return error(id, e.getMessage()).put("protocolVersion", protocol);
        } catch (Exception e) {
            return error(id, "INTERNAL_ERROR").put("protocolVersion", protocol);
        }
    }

    public static JSONObject error(String id, String code) {
        return new JSONObject()
                .put("protocolVersion", 1)
                .put("requestId", id)
                .put("ok", false)
                .put("errorCode", code);
    }

    JSONObject capabilities() {
        if (execution != null) {
            JSONArray all = execution.workers.snapshot();
            if (execution.runners != null) {
                JSONArray runners = execution.runners.snapshot();
                for (int i = 0; i < runners.length(); i++) all.put(runners.get(i));
            }
            return new JSONObject()
                    .put("service", NAME)
                    .put("protocolVersion", execution.runners == null ? 3 : 4)
                    .put("registeredWorkers", all)
                    .put("infrastructureReady", true)
                    .put("runsReady", execution.accepting() && execution.workers.ready())
                    .put("accepting", execution.accepting())
                    .put(
                            "execution",
                            execution.runners == null ? "resource-loop" : "trusted-tool-loop")
                    .put("capacity", execution.workers.capacity())
                    .put(
                            "files",
                            execution.files == null ? JSONObject.NULL : execution.files.metrics());
        }
        return new JSONObject()
                .put("service", NAME)
                .put("protocolVersion", 1)
                .put("registeredWorkers", registry.snapshot())
                .put("infrastructureReady", registry.ready())
                .put("runsReady", false)
                .put("execution", "scripted");
    }

    private static byte[] body(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > StrictJson.MAX_BYTES)
                throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    HarnessIdentity authenticate(String code) {
        cube.core.Module auth = getKernel() == null ? null : getKernel().getModule("Auth");
        return HarnessIdentity.authenticate(auth instanceof cube.auth.AuthTokenProvider && auth.isStarted()
                ? (cube.auth.AuthTokenProvider) auth : null, code, HarnessIdentity.permissions(config.values));
    }

    public ExecutorService businessExecutor() {
        return businessExecutor;
    }

    public JSONObject processBusiness(cell.core.talk.dialect.ActionDialect request,
                                      cell.core.talk.TalkContext context, java.util.function.Function<String, OutputStream> opener) {
        HarnessBusiness current = business;
        if (!started.get() || current == null) return HarnessBusiness.error(503, "HARNESS_NOT_READY");
        return current.handle(request, context.getSessionId(), context::isValid, opener);
    }

    public void receiveFile(cell.core.talk.TalkContext context, cell.core.talk.PrimitiveInputStream stream) {
        HarnessBusiness current = business;
        if (current != null && current.transfers != null) current.transfers.receive(context.getSessionId(), stream);
        else try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * The service listener deliberately exposes only health and administration.
     */
    private void handleHttp(HttpExchange x) throws IOException {
        JSONObject response;
        try {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            if (path.equals("/healthz") || path.equals("/readyz")) {
                if (!method.equals("GET")) throw new HarnessBusiness.Failure(405, "METHOD_NOT_ALLOWED");
                boolean ready = started.get() && (path.equals("/healthz") || (execution != null
                        ? execution.accepting() && execution.workers.ready() : registry.ready()));
                response = HarnessBusiness.reply(ready ? 200 : 503, new JSONObject().put("service", NAME)
                        .put("ready", ready).put("runsReady", execution != null && execution.accepting() && execution.workers.ready()));
            } else if (path.startsWith("/harness-admin/v1/")) {
                String credential = config.values.getProperty("admission.admin.credential", "");
                if (credential.length() < 24 || !equal("Bearer " + credential,
                        Objects.toString(x.getRequestHeaders().getFirst("Authorization"), "")))
                    throw new HarnessBusiness.Failure(403, "FORBIDDEN");
                if (!path.equals("/harness-admin/v1/admission")) throw new HarnessBusiness.Failure(404, "NOT_FOUND");
                if (execution == null) throw new HarnessBusiness.Failure(503, "HARNESS_NOT_READY");
                Boolean accepting = null;
                if (method.equals("POST")) {
                    JSONObject value = StrictJson.object(body(x.getRequestBody()));
                    if (value.length() != 1 || !(value.opt("accepting") instanceof Boolean))
                        throw new HarnessBusiness.Failure(400, "INVALID_ADMISSION_CONTROL");
                    accepting = value.getBoolean("accepting");
                } else if (!method.equals("GET")) throw new HarnessBusiness.Failure(405, "METHOD_NOT_ALLOWED");
                response = HarnessBusiness.reply(200, execution.admission(accepting));
            } else throw new HarnessBusiness.Failure(404, "NOT_FOUND");
        } catch (Exception e) {
            response = HarnessBusiness.failure(e);
        }
        send(x, response.getInt("status"), response.getJSONObject("body"));
    }

    private static void send(HttpExchange x, int status, JSONObject result) throws IOException {
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        } finally {
            x.close();
        }
    }

}
