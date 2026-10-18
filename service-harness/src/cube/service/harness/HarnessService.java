/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * G0 transport with an optional N03 ledger. Run admission awaits profiles/coordinator.
 */
public final class HarnessService extends AbstractModule {

    public static final String NAME = "Harness";

    private HarnessConfig config;

    private WorkerRegistry registry;

    private ThreadPoolExecutor executor, httpExecutor;

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
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(capacity), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
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
                execution = new ExecutionRuntime(store, config.values, config.leaseMillis(), () -> {
                    Object module = getKernel() == null ? null : getKernel().getModule("AIGC");
                    return module instanceof cube.aigc.completion.CompletionProvider ? (cube.aigc.completion.CompletionProvider) module : null;
                });
            }

            executor = pool("harness-cell", 2, 64);

            started.set(true);
            if (execution == null) {
                httpExecutor = pool("harness-http", 2, 32);
                http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 32);
                http.createContext("/", this::handleHttp);
                http.setExecutor(httpExecutor);
                http.start();
            } else executionHttp = JettyExchange.start(config.host(), config.port(), this::handleHttp);
            if (execution != null) execution.start();
            startupError = null;
        } catch (Exception e) {
            startupError = "HARNESS_CONFIGURATION_ERROR";
            stop();
            System.err.println("Harness startup refused; check private configuration and port");
        }
    }

    @Override
    public synchronized void stop() {
        started.set(false);
        if (execution != null) {
            execution.close();
            execution = null;
        }
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
        for (ThreadPoolExecutor pool : Arrays.asList(executor, httpExecutor))
            if (pool != null) {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(3, TimeUnit.SECONDS)) pool.shutdownNow();
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
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
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public JSONObject process(byte[] raw, long transport) {
        return process(raw, transport, () -> true);
    }

    public JSONObject process(byte[] raw, long transport, java.util.function.BooleanSupplier valid) {
        String id = "";
        int protocol = 1;
        try {
            if (!started.get()) throw new IllegalArgumentException("HARNESS_NOT_READY");
            JSONObject request = StrictJson.object(raw);
            if (request.optInt("protocolVersion", 0) == 2 || request.optInt("protocolVersion", 0) == 3 || request.optInt("protocolVersion", 0) == 4)
                protocol = request.getInt("protocolVersion");
            if (request.opt("requestId") instanceof String && request.getString("requestId").length() <= 128)
                id = request.getString("requestId");
            if (protocol == 4) {
                cube.service.harness.execution.ToolProtocol.parse("runner-request", raw);
                if (!equal(config.values.getProperty("worker.credential"), request.getString("credential")))
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
                ModelProtocol.parse(protocol == 3 ? "model-worker-v3-request" : "model-worker-request", raw);
                if (!equal(config.values.getProperty("worker.credential"), request.getString("credential")))
                    throw new IllegalArgumentException("UNAUTHORIZED");
                return execution.workers.apply(request, transport, valid);
            }
            if (request.has("protocolVersion") && (!(request.get("protocolVersion") instanceof Number) || ((Number) request.get("protocolVersion")).doubleValue() != 1))
                throw new IllegalArgumentException("PROTOCOL_MISMATCH");
            Contract.validate(request, new JSONObject(new String(Contract.resource("schemas/worker-request.json"), StandardCharsets.UTF_8)));
            if (!equal(config.values.getProperty("worker.credential"), request.getString("credential")))
                throw new IllegalArgumentException("UNAUTHORIZED");
            if (!config.authorizedWorker(request.getString("workerId"), request.getString("role")))
                throw new IllegalArgumentException("FORBIDDEN");
            return registry.apply(request, transport, valid).put("protocolVersion", 1).put("requestId", id).put("ok", true);
        } catch (StoreException e) {
            return error(id, e.code).put("protocolVersion", protocol);
        } catch (IllegalArgumentException e) {
            return error(id, e.getMessage()).put("protocolVersion", protocol);
        } catch (Exception e) {
            return error(id, "INTERNAL_ERROR").put("protocolVersion", protocol);
        }
    }

    public static JSONObject error(String id, String code) {
        return new JSONObject().put("protocolVersion", 1).put("requestId", id).put("ok", false).put("errorCode", code);
    }

    private JSONObject capabilities() {
        if (execution != null) {
            JSONArray all = execution.workers.snapshot();
            if (execution.runners != null) {
                JSONArray runners = execution.runners.snapshot();
                for (int i = 0; i < runners.length(); i++) all.put(runners.get(i));
            }
            return new JSONObject().put("service", NAME).put("protocolVersion", execution.runners == null ? 3 : 4).put("registeredWorkers", all).put("infrastructureReady", true)
                    .put("runsReady", execution.workers.ready()).put("execution", execution.runners == null ? "resource-loop" : "trusted-tool-loop").put("capacity", execution.workers.capacity())
                    .put("files", execution.files == null ? JSONObject.NULL : execution.files.metrics());
        }
        return new JSONObject().put("service", NAME).put("protocolVersion", 1).put("registeredWorkers", registry.snapshot()).put("infrastructureReady", registry.ready()).put("runsReady", false).put("execution", "scripted");
    }

    private static byte[] body(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > StrictJson.MAX_BYTES) throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    private void handleHttp(HttpExchange x) throws IOException {
        String requestId = UUID.randomUUID().toString();
        int status = 500;
        JSONObject result;
        try {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            if (path.equals("/healthz") || path.equals("/readyz")) {
                if (!method.equals("GET")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                boolean ok = started.get() && (path.equals("/healthz") || (execution != null ? execution.workers.ready() : registry.ready()));
                status = ok ? 200 : 503;
                result = new JSONObject().put("service", NAME).put("ready", ok).put("runsReady", execution != null && execution.workers.ready());
            } else {
                if (!path.startsWith("/harness/v1/") && !path.startsWith("/harness-admin/v1/"))
                    throw new HttpFailure(404, "NOT_FOUND");
                String authorization=Objects.toString(x.getRequestHeaders().getFirst("Authorization"), "");
                String approverCredential=config.values.getProperty("control.approver.credential","");
                boolean approver=execution!=null&&approverCredential.length()>=24&&equal("Bearer "+approverCredential,authorization);
                if (!equal("Bearer " + config.values.getProperty("api.credential"), authorization)&&!approver)
                    throw new HttpFailure(401, "UNAUTHORIZED");
                if(approver&&!(method.equals("GET")&&path.matches("/harness/v1/runs/[A-Za-z0-9_.:-]+")||method.equals("POST")&&path.matches("/harness/v1/runs/[A-Za-z0-9_.:-]+/approvals/[A-Za-z0-9_.:-]+/decision")))throw new HttpFailure(403,"FORBIDDEN");
                if (path.startsWith("/harness-admin/")) throw new HttpFailure(403, "FORBIDDEN");
                if (method.equals("GET") && path.equals("/harness/v1/capabilities")) {
                    status = 200;
                    result = capabilities();
                } else if (method.equals("GET") && path.equals("/harness/v1/task-profiles")) {
                    status = 200;
                    result = execution == null ? new JSONObject().put("profiles", new JSONArray()) : execution.taskProfiles();
                } else {
                    String normalized = path.replaceAll("/(runs|inputs|artifacts|task-profiles)/[A-Za-z0-9_.:-]+", "/$1/{id}");
                    String schema = null;
                    boolean valid = false;
                    if (normalized.equals("/harness/v1/runs")) {
                        if (!method.equals("POST")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        schema = "create-run";
                        valid = true;
                    } else if (normalized.matches("/harness/v1/runs/\\{id\\}/(cancel|resume|feedback)")) {
                        if (!method.equals("POST")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        String a = path.substring(path.lastIndexOf('/') + 1);
                        schema = a.equals("feedback") ? a : a + "-run";
                        valid = true;
                    } else if (normalized.matches("/harness/v1/runs/\\{id\\}/approvals/[A-Za-z0-9_.:-]+/decision")) {
                        if (!method.equals("POST")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        schema = "approval";
                        valid = true;
                    } else if (normalized.equals("/harness/v1/inputs")) {
                        if (!method.equals("POST")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        schema = "input-create";
                        valid = true;
                    } else if (normalized.equals("/harness/v1/inputs/{id}/complete")) {
                        if (!method.equals("POST")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        schema = "input-complete";
                        valid = true;
                    } else if (normalized.equals("/harness/v1/inputs/{id}/content")) {
                        if (!method.equals("PUT")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        valid = true;
                    } else if(normalized.equals("/harness/v1/artifacts/{id}/download-handle")) {
                        if(!method.equals("GET"))throw new HttpFailure(405,"METHOD_NOT_ALLOWED");valid=true;
                    } else if (normalized.matches("/harness/v1/(runs/\\{id\\}(/events|/artifacts)?|inputs/\\{id\\}|artifacts/\\{id\\}(/content)?|task-profiles/\\{id\\})")) {
                        if (!method.equals("GET")) throw new HttpFailure(405, "METHOD_NOT_ALLOWED");
                        valid = true;
                    }
                    if (!valid) throw new HttpFailure(404, "NOT_FOUND");
                    if (method.equals("POST") || method.equals("PUT")) {
                        String key = x.getRequestHeaders().getFirst("Idempotency-Key");
                        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}"))
                            throw new HttpFailure(400, "IDEMPOTENCY_KEY_REQUIRED");
                    }
                    JSONObject parsed = null;
                    if (schema != null) {
                        if (!Objects.toString(x.getRequestHeaders().getFirst("Content-Type"), "").toLowerCase(Locale.ROOT).startsWith("application/json"))
                            throw new HttpFailure(415, "UNSUPPORTED_MEDIA_TYPE");
                        parsed = Contract.parse(schema, body(x.getRequestBody()));
                    }
                    if (execution != null) {
                        if(path.matches("/harness/v1/runs/[A-Za-z0-9_.:-]+/(cancel|resume|approvals/[A-Za-z0-9_.:-]+/decision)")&&method.equals("POST")){
                            String[] parts=path.split("/");String operation=parts[5].equals("approvals")?"approval":parts[5];
                            if(operation.equals("approval")&&!approver)throw new HttpFailure(403,"APPROVER_FORBIDDEN");
                            String wait=operation.equals("approval")?parts[6]:operation.equals("resume")?parsed.getString("waitId"):"";
                            result=execution.control(parts[4],x.getRequestHeaders().getFirst("Idempotency-Key"),operation,wait,parsed,
                                approver?config.values.getProperty("control.approver.subject","approver"):execution.identity.subject,approver);
                            send(x,202,result);return;
                        }
                        if(execution.files!=null){
                            if(path.equals("/harness/v1/inputs")&&method.equals("POST")){send(x,201,execution.files.create(x.getRequestHeaders().getFirst("Idempotency-Key"),parsed));return;}
                            String[] parts=path.split("/");
                            if(path.matches("/harness/v1/inputs/[A-Za-z0-9_.:-]+/content")&&method.equals("PUT")){
                                if(!"application/octet-stream".equals(x.getRequestHeaders().getFirst("Content-Type")))throw new HttpFailure(415,"UNSUPPORTED_MEDIA_TYPE");
                                send(x,200,execution.files.uploadInput(parts[4],x.getRequestBody()));return;
                            }
                            if(path.matches("/harness/v1/inputs/[A-Za-z0-9_.:-]+/complete")&&method.equals("POST")){send(x,200,execution.files.complete(parts[4],parsed.getString("sha256")));return;}
                            if(path.matches("/harness/v1/(inputs|artifacts)/[A-Za-z0-9_.:-]+")&&method.equals("GET")){send(x,200,execution.files.metadata(parts[4],parts[3].equals("inputs")?"input":"artifact"));return;}
                            if(path.matches("/harness/v1/artifacts/[A-Za-z0-9_.:-]+/content")&&method.equals("GET")){execution.files.download(parts[4],x);return;}
                            if(path.matches("/harness/v1/artifacts/[A-Za-z0-9_.:-]+/download-handle")&&method.equals("GET")){send(x,200,execution.files.handle(parts[4]));return;}
                            if(path.matches("/harness/v1/runs/[A-Za-z0-9_.:-]+/artifacts")&&method.equals("GET")){send(x,200,new JSONObject().put("artifacts",execution.files.artifacts(parts[4])));return;}
                        }
                        if (path.equals("/harness/v1/runs") && method.equals("POST")) {
                            result = execution.create(x.getRequestHeaders().getFirst("Idempotency-Key"), parsed);
                            status = 202;
                            send(x, status, result);
                            return;
                        }
                        if (path.matches("/harness/v1/runs/[A-Za-z0-9_.:-]+") && method.equals("GET")) {
                            result = execution.read(path.substring(path.lastIndexOf('/') + 1));
                            send(x, 200, result);
                            return;
                        }
                        if (path.matches("/harness/v1/runs/[A-Za-z0-9_.:-]+/events") && method.equals("GET")) {
                            String run = path.split("/")[4];
                            long after = 0;
                            int limit = 100;
                            String query = Objects.toString(x.getRequestURI().getRawQuery(), "");
                            java.util.Set<String> seen = new java.util.HashSet<>();
                            if (!query.isEmpty()) for (String pair : query.split("&", -1)) {
                                String[] kv = pair.split("=", -1);
                                if (kv.length != 2 || !seen.add(kv[0]) || !kv[1].matches("[0-9]{1,15}"))
                                    throw new HttpFailure(400, "INVALID_EVENT_CURSOR");
                                if (kv[0].equals("after")) after = Long.parseLong(kv[1]);
                                else if (kv[0].equals("limit")) limit = Integer.parseInt(kv[1]);
                                else throw new HttpFailure(400, "INVALID_EVENT_CURSOR");
                            }
                            String last = x.getRequestHeaders().getFirst("Last-Event-ID");
                            if (last != null) {
                                if (!last.matches("[0-9]{1,15}") || (seen.contains("after") && after != Long.parseLong(last)))
                                    throw new HttpFailure(400, "INVALID_EVENT_CURSOR");
                                after = Long.parseLong(last);
                            }
                            if (limit < 1 || limit > 100) throw new HttpFailure(400, "INVALID_EVENT_LIMIT");
                            result = execution.events(run, after, limit);
                            if (Objects.toString(x.getRequestHeaders().getFirst("Accept"), "").contains("text/event-stream")) {
                                execution.stream(x, result);
                                return;
                            }
                            send(x, 200, result);
                            return;
                        }
                        if (path.matches("/harness/v1/task-profiles/[A-Za-z0-9_.:-]+") && method.equals("GET")) {
                            String profile = path.substring(path.lastIndexOf('/') + 1);
                            JSONArray list = execution.taskProfiles().getJSONArray("profiles");
                            for (int i = 0; i < list.length(); i++)
                                if (list.getJSONObject(i).getString("profileId").equals(profile)) {
                                    send(x, 200, list.getJSONObject(i));
                                    return;
                                }
                            throw new HttpFailure(404, "PROFILE_NOT_FOUND");
                        }
                    }
                    throw new HttpFailure(503, "HARNESS_NOT_READY");
                }
            }
        } catch (HttpFailure e) {
            status = e.status;
            result = httpError(requestId, e.code, status == 503);
        } catch (StoreException e) {
            status = e.code.endsWith("NOT_FOUND") ? 404 : e.code.endsWith("FORBIDDEN") ? 403 : e.code.equals("FILE_EXPIRED") || e.code.equals("WAIT_EXPIRED") ? 410 : e.code.equals("HARNESS_BUSY")||e.code.equals("FILE_BUSY")||e.code.equals("FILE_QUOTA_EXCEEDED") ? 429 : e.code.contains("CONFLICT")||e.code.equals("RUN_TERMINAL")||e.code.equals("APPROVAL_ARGUMENTS_CHANGED") ? 409 : e.code.startsWith("STORE_") ? 503 : 400;
            result = httpError(requestId, e.code, status == 429 || status == 503);
        } catch (IllegalArgumentException e) {
            status = e.getMessage().equals("PAYLOAD_TOO_LARGE") ? 413 : 400;
            result = httpError(requestId, e.getMessage(), false);
        } catch (Exception e) {
            result = httpError(requestId, "INTERNAL_ERROR", false);
        }
        send(x, status, result);
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

    private static JSONObject httpError(String id, String code, boolean retryable) {
        return new JSONObject().put("requestId", id).put("errorCode", code).put("retryable", retryable);
    }

    private static final class HttpFailure extends RuntimeException {
        final int status;
        final String code;

        HttpFailure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
