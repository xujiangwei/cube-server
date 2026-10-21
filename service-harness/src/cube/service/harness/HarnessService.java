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
 * Harness 内核模块：run 准入等待 profile 与协调器就绪后开放。
 * <p>职责分三条报文入口：worker 原始 JSON（v1 基础协议、协议 2/3 模型、tool/sandbox runner）、
 * dispatcher 业务报文（ActionDialect，经有界业务执行池）、文件 SHM 流。
 * 健康/运维 HTTP 监听只暴露 /healthz、/readyz 与 /harness-admin/v1/admission，
 * 业务路径在此监听器一律 404。执行未启用时只提供 scripted 通信探针能力。
 */
public final class HarnessService extends AbstractModule {

    /**
     * Cellet 路由目标与模块名，与 Python 侧 HarnessService.NAME 保持一致。
     */
    public static final String NAME = "Harness";

    /**
     * 私有配置；start 时按默认路径惰性加载，除非构造时已注入。
     */
    private HarnessConfig config;

    /**
     * v1 基础协议注册表（最多 64 个 worker），与执行侧注册表相互独立。
     */
    private WorkerRegistry registry;

    /**
     * 三个定长有界线程池：worker 请求池、健康/运维 HTTP 池、业务执行池；队列满即拒绝。
     */
    private ThreadPoolExecutor executor, httpExecutor, businessExecutor;

    /**
     * 业务分发器，持有文件传输协调器；stop 时随之关闭。
     */
    private HarnessBusiness business;

    /**
     * 未启用执行时的 JDK HttpServer（仅健康/运维入口）；与 executionHttp 互斥。
     */
    private HttpServer http;

    /**
     * 启用执行时的 Jetty 服务器（仅健康/运维入口）；与 http 互斥。
     */
    private org.eclipse.jetty.server.Server executionHttp;

    /**
     * 启动失败原因码；启动成功为 null；失败时固定为 HARNESS_CONFIGURATION_ERROR。
     */
    private volatile String startupError;

    /**
     * RunStore；仅 store.enabled=true 时创建，执行启用前必须就绪。
     */
    private JdbcRunStore store;

    /**
     * 执行运行时；仅 execution.enabled=true 时创建，为 null 时只提供 scripted 能力。
     */
    private ExecutionRuntime execution;

    /**
     * 无参构造：配置留空，由 start 时按默认路径加载。
     */
    public HarnessService() {
    }

    /**
     * 注入预加载配置的构造，供测试与合部署复用。
     *
     * @param config 私有配置；为 null 时 start 仍按默认路径加载
     */
    public HarnessService(HarnessConfig config) {
        this.config = config;
    }

    /**
     * 创建定长、守护线程、有界队列的线程池；队列满时按 AbortPolicy 拒绝。
     *
     * @param name     线程名前（所有线程共用的前缀）
     * @param size     核心与最大线程数（定长）
     * @param capacity 有界任务队列容量
     * @return 配置好的线程池
     */
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
     * 启动摘要。只输出非敏感项：凭据、令牌一律不进入日志。
     * 覆盖监听地址、租约、存储方言与库名、执行开关与参数、授权 worker 计数、
     * 文件/trace/feedback 开关及推理节点 ID。
     *
     * @return 单行启动摘要字符串
     */
    private String startupSummary() {
        java.util.Properties v = config.values;
        StringBuilder out = new StringBuilder();
        out.append("host=").append(config.host()).append(" port=").append(config.port())
                .append(" leaseMillis=").append(config.leaseMillis());
        out.append(" store=").append(store != null);
        if (store != null) {
            out.append(" dialect=").append(config.values.getProperty("store.dialect", ""))
                    .append(" database=").append(config.values.getProperty("store.database", ""));
        }
        out.append(" execution=").append(execution != null);
        if (execution != null) {
            out.append(" accepting=").append(execution.accepting())
                    .append(" maxActive=").append(v.getProperty("execution.max.active", "128"))
                    .append(" permissions=").append(v.getProperty("execution.permissions", "").replace(",", "|"));
        }
        out.append(" workers=").append(workerCount(v));
        out.append(" files=").append(v.getProperty("files.enabled", "false"));
        out.append(" trace=").append(v.getProperty("trace.enabled", "false"))
                .append(" feedback=").append(v.getProperty("feedback.enabled", "false"));
        out.append(" inferenceNode=").append(v.getProperty("inference.node.id", ""));
        return out.toString();
    }

    /**
     * 统计授权的 Python worker 数（worker.<id>.role），只计数不打印 workerId。
     *
     * @param v 配置键值
     * @return worker.<id>.role 形式的键个数
     */
    private static int workerCount(java.util.Properties v) {
        int count = 0;
        for (String name : v.stringPropertyNames()) {
            if (name.startsWith("worker.") && name.endsWith(".role")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 启动模块（Kernel 调用，synchronized 与 stop 互斥）：
     * 加载配置 → 可选建 RunStore（仅校验不迁移）→ 建注册表 →
     * execution.enabled 时构建 ExecutionRuntime（store 必须就绪）→
     * 建三个线程池与业务分发器 → 起健康/运维监听（执行启用走 Jetty，否则 JDK HttpServer）→
     * 启动 execution。任一失败只记录 startupError 并回滚 stop，不向 Kernel 抛出。
     */
    @Override
    public synchronized void start() {
        if (started.get()) {
            return;
        }
        try {
            if (config == null) {
                config = HarnessConfig.load();
            }
            // N03 为可选且 fail-closed；DDL 只在显式迁移 CLI 中提供。
            if (Boolean.parseBoolean(config.values.getProperty("store.enabled", "false"))) {
                store = new JdbcRunStore(new StoreConfig(config.values));
            }

            registry = new WorkerRegistry(config.leaseMillis());
            if (Boolean.parseBoolean(config.values.getProperty("execution.enabled", "false"))) {
                if (store == null) {
                    throw new IllegalArgumentException("STORE_REQUIRED");
                }
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
            if (execution != null) {
                execution.start();
            }
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

    /**
     * 停止模块（Kernel 调用，synchronized 与 start 互斥）：
     * 先关业务/传输与 HTTP 监听，再有界 drain 三个线程池（优雅 3 秒，
     * 超时 shutdownNow 后再等 3 秒），最后关闭 execution、清空注册表与存储引用。
     * drain 期间被中断时恢复线程中断标记。
     */
    @Override
    public synchronized void stop() {
        boolean running = started.getAndSet(false);
        if (running) {
            Logger.i(
                    HarnessService.class,
                    "#stop - Harness stopping: "
                            + "execution="
                            + (execution != null)
                            + " http="
                            + (http != null || executionHttp != null));
        }
        // Stop new work and close streams before draining requests that use the runtime.
        if (business != null) {
            business.close();
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
        for (ThreadPoolExecutor pool : Arrays.asList(executor, httpExecutor, businessExecutor)) {
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
        }
        business = null;
        if (execution != null) {
            execution.close();
            execution = null;
        }
        if (registry != null) {
            registry.clear();
        }
        store = null;
    }

    /**
     * @return 当前 RunStore；未启用存储时为 null
     */
    public JdbcRunStore runStore() {
        return store;
    }

    /**
     * @return worker 请求执行池（harness-cell）；尚未启动时为 null
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * transport 断开回调：清理该连接上的在途传输、注册表条目，
     * 以及执行侧 model/runner 记录；不断开其他连接的会话。
     *
     * @param transport 断开的 Cell transport 会话标识
     */
    public void disconnect(long transport) {
        HarnessBusiness current = business;
        if (current != null && current.transfers != null) {
            current.transfers.disconnect(transport);
        }
        if (registry != null) {
            registry.disconnect(transport);
        }
        if (execution != null) {
            execution.workers.disconnect(transport);
            if (execution.runners != null) {
                execution.runners.disconnect(transport);
            }
        }
    }

    /**
     * @return 恒为 null——Harness 不使用插件系统
     */
    @Override
    public <T extends PluginSystem> T getPluginSystem() {
        return null;
    }

    /**
     * Kernel 定时钩子：刷新注册表快照，顺带清理过期租约条目。
     *
     * @param module 本模块
     * @param kernel 所属 Kernel
     */
    @Override
    public void onTick(cube.core.Module module, Kernel kernel) {
        if (registry != null) {
            registry.snapshot();
        }
    }

    /**
     * 常量时间比较两段 UTF-8 字节，避免凭据校验的时序侧信道。
     *
     * @param a 第一段文本
     * @param b 第二段文本
     * @return 两段文本字节完全一致返回 true，否则 false
     */
    static boolean equal(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 处理 worker 请求的便捷重载：会话有效性按恒真处理。
     *
     * @param raw       worker 请求原始字节（单 STRING Stuff 载荷）
     * @param transport Cell transport 会话标识
     * @return 响应 JSON；异常也转换为带错误码的响应，不向外抛出
     */
    public JSONObject process(byte[] raw, long transport) {
        return process(raw, transport, () -> true);
    }

    /**
     * worker 报文主入口，按报文形态分派：
     * <ul>
     * <li>带 runnerType：tool/sandbox runner 协议，凭据校验后交 execution.runners</li>
     * <li>protocolVersion 2/3：模型协议，凭据校验后交 execution.workers</li>
     * <li>其余：v1 基础协议，契约校验 + 凭据 + workerId/role 白名单后交注册表</li>
     * </ul>
     * 响应统一附带协议版本与 requestId；无论成败最后都写一条结果日志。
     *
     * @param raw       worker 请求原始字节（单 STRING Stuff 载荷）
     * @param transport Cell transport 会话标识，与 worker 注册/传输绑定
     * @param valid     会话有效性探针，执行期间持续校验
     * @return 响应 JSON；所有异常在本方法内转换为错误响应
     * @throws IllegalArgumentException 协议不匹配（PROTOCOL_MISMATCH）、凭据无效（UNAUTHORIZED）、
     *                                  worker 未授权（FORBIDDEN）、runner 未启用（RUNNER_DISABLED）、服务未就绪（HARNESS_NOT_READY）、
     *                                  载荷超限（PAYLOAD_TOO_LARGE）等；上述异常被本方法捕获并转为错误响应，不会传播给调用方
     */
    public JSONObject process(
            byte[] raw, long transport, java.util.function.BooleanSupplier valid) {
        String id = "";
        int protocol = 1;
        String runnerType = null;
        JSONObject request = null, response = null;
        Exception unexpected = null;
        try {
            request = StrictJson.object(raw);
            if (request.has("runnerType")) {
                runnerType = request.optString("runnerType", "");
            }
            if (!started.get()) {
                throw new IllegalArgumentException("HARNESS_NOT_READY");
            }
            if (request.optInt("protocolVersion", 0) == 2
                    || request.optInt("protocolVersion", 0) == 3) {
                protocol = request.getInt("protocolVersion");
            }
            if (request.opt("requestId") instanceof String
                    && request.getString("requestId").length() <= 128) {
                id = request.getString("requestId");
            }
            if (runnerType != null) {
                cube.service.harness.execution.ToolProtocol.parse("runner-request", raw);
                if (!equal(
                        config.values.getProperty("worker.credential"),
                        request.getString("credential"))) {
                    throw new IllegalArgumentException("UNAUTHORIZED");
                }
                if (execution == null || execution.runners == null) {
                    throw new IllegalArgumentException("RUNNER_DISABLED");
                }
                return response = execution.runners.apply(request, transport, valid);
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
                        request.getString("credential"))) {
                    throw new IllegalArgumentException("UNAUTHORIZED");
                }
                return response = execution.workers.apply(request, transport, valid);
            }
            if (request.has("protocolVersion")
                    && (!(request.get("protocolVersion") instanceof Number)
                    || ((Number) request.get("protocolVersion")).doubleValue() != 1)) {
                throw new IllegalArgumentException("PROTOCOL_MISMATCH");
            }
            Contract.validate(
                    request,
                    new JSONObject(
                            new String(
                                    Contract.resource("schemas/worker-request.json"),
                                    StandardCharsets.UTF_8)));
            if (!equal(
                    config.values.getProperty("worker.credential"),
                    request.getString("credential"))) {
                throw new IllegalArgumentException("UNAUTHORIZED");
            }
            if (!config.authorizedWorker(request.getString("workerId"), request.getString("role"))) {
                throw new IllegalArgumentException("FORBIDDEN");
            }
            return response = registry.apply(request, transport, valid)
                    .put("protocolVersion", 1)
                    .put("requestId", id)
                    .put("ok", true);
        } catch (StoreException e) {
            return response = workerError(id, e.code, protocol, runnerType);
        } catch (IllegalArgumentException e) {
            return response = workerError(id, e.getMessage(), protocol, runnerType);
        } catch (Exception e) {
            unexpected = e;
            return response = workerError(id, "INTERNAL_ERROR", protocol, runnerType);
        } finally {
            WorkerRegistrationLog.outcome(request, response, config, transport, unexpected);
        }
    }

    /**
     * 组装 worker 侧错误响应：非 runner 请求附带 protocolVersion，
     * runner 请求改带 runnerType（不使用协议版本协商）。
     *
     * @param id         requestId，解析失败时为空串
     * @param code       服务端错误码
     * @param protocol   请求解析出的协议版本，默认 1
     * @param runnerType runner 类型；null 表示非 runner 请求
     * @return 错误响应 JSON
     */
    private static JSONObject workerError(String id, String code, int protocol, String runnerType) {
        JSONObject response = error(id, code);
        if (runnerType == null) {
            return response.put("protocolVersion", protocol);
        }
        response.remove("protocolVersion");
        return response.put("runnerType", runnerType);
    }

    /**
     * 记录信封级拒绝：请求 JSON 尚不可得（如非法信封、业务池已满）时的结果日志。
     *
     * @param code      服务端错误码，如 INVALID_ENVELOPE、HARNESS_BUSY
     * @param transport 发起请求的 Cell transport 会话标识
     */
    public static void logWorkerRejection(String code, long transport) {
        WorkerRegistrationLog.outcome(null, error("", code), null, transport, null);
    }

    /**
     * 组装 v1 错误响应骨架。
     *
     * @param id   requestId，无法安全取得时为空串
     * @param code 服务端错误码
     * @return {@code {protocolVersion:1, requestId, ok:false, errorCode}} 响应
     */
    public static JSONObject error(String id, String code) {
        return new JSONObject()
                .put("protocolVersion", 1)
                .put("requestId", id)
                .put("ok", false)
                .put("errorCode", code);
    }

    /**
     * @return capabilities 响应 JSON：执行未启用时报告 scripted 基础设施就绪度
     * （registeredWorkers/infrastructureReady/runsReady=false/execution=scripted）；
     * 执行启用时追加 runnerTypes、accepting、runsReady、capacity 与 files 指标
     */
    JSONObject capabilities() {
        if (execution != null) {
            JSONArray all = execution.workers.snapshot();
            if (execution.runners != null) {
                JSONArray runners = execution.runners.snapshot();
                for (int i = 0; i < runners.length(); i++) {
                    all.put(runners.get(i));
                }
            }
            return new JSONObject()
                    .put("service", NAME)
                    .put("protocolVersion", 3)
                    .put("runnerTypes", execution.runners == null ? new JSONArray()
                            : new JSONArray().put("tool").put("sandbox"))
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

    /**
     * 读全量请求体，超过 StrictJson.MAX_BYTES 即拒绝。
     *
     * @param in 请求体输入流
     * @return 请求体全部字节
     * @throws IOException              读取失败
     * @throws IllegalArgumentException 413/PAYLOAD_TOO_LARGE：体量超过严格 JSON 上限
     */
    private static byte[] body(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > StrictJson.MAX_BYTES) {
                throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");
            }
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 用 Kernel 中的 Auth 模块认证 Bearer 令牌；Auth 缺失或未启动时按不可用处理。
     *
     * @param code 请求携带的 Bearer 令牌原文
     * @return 认证后的身份
     * @throws HarnessBusiness.Failure 401/UNAUTHORIZED 或 503/AUTH_UNAVAILABLE（见 HarnessIdentity.authenticate）
     */
    HarnessIdentity authenticate(String code) {
        cube.core.Module auth = getKernel() == null ? null : getKernel().getModule("Auth");
        return HarnessIdentity.authenticate(auth instanceof cube.auth.AuthTokenProvider && auth.isStarted()
                ? (cube.auth.AuthTokenProvider) auth : null, code, HarnessIdentity.permissions(config.values));
    }

    /**
     * @return 业务执行池（harness-business）；尚未启动时为 null
     */
    public ExecutorService businessExecutor() {
        return businessExecutor;
    }

    /**
     * dispatcher 业务报文处理入口：服务未就绪直接 503，否则转业务分发器。
     *
     * @param request 业务请求，须含 sn 与 _performer
     * @param context TalkContext，提供 sessionId 与会话有效性
     * @param opener  SHM 流打开器，供下载传输写回字节流
     * @return 统一响应结构 {@code {status,headers,body}}
     */
    public JSONObject processBusiness(cell.core.talk.dialect.ActionDialect request,
                                      cell.core.talk.TalkContext context, java.util.function.Function<String, OutputStream> opener) {
        HarnessBusiness current = business;
        if (!started.get() || current == null) {
            return HarnessBusiness.error(503, "HARNESS_NOT_READY");
        }
        return current.handle(request, context.getSessionId(), context::isValid, opener);
    }

    /**
     * 文件上传流入口：按流名（transferId）转业务侧接收；业务不可用时直接关闭流。
     *
     * @param context TalkContext
     * @param stream  客户端经 SHM 送达的字节流
     */
    public void receiveFile(cell.core.talk.TalkContext context, cell.core.talk.PrimitiveInputStream stream) {
        HarnessBusiness current = business;
        if (current != null && current.transfers != null) {
            current.transfers.receive(context.getSessionId(), stream);
        } else {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 健康/运维 HTTP 处理器：只暴露 /healthz、/readyz 与 /harness-admin/v1/admission。
     * /healthz 仅证明 HTTP 已启动；/readyz 还要求接单且 model/runner 就绪。
     * 管理入口须带独立管理凭据的 Bearer，GET 查询、POST 切换接单状态；
     * 其余路径一律 404，业务路径无 HTTP 回退。
     *
     * @param x HTTP 交换，提供请求 URI、方法与头
     * @throws IOException 读取请求体失败（由容器记录）
     */
    private void handleHttp(HttpExchange x) throws IOException {
        JSONObject response;
        try {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            if (path.equals("/healthz") || path.equals("/readyz")) {
                if (!method.equals("GET")) {
                    throw new HarnessBusiness.Failure(405, "METHOD_NOT_ALLOWED");
                }
                boolean ready = started.get() && (path.equals("/healthz") || (execution != null
                        ? execution.accepting() && execution.workers.ready() : registry.ready()));
                response = HarnessBusiness.reply(ready ? 200 : 503, new JSONObject().put("service", NAME)
                        .put("ready", ready).put("runsReady", execution != null && execution.accepting() && execution.workers.ready()));
            } else if (path.startsWith("/harness-admin/v1/")) {
                String credential = config.values.getProperty("admission.admin.credential", "");
                if (credential.length() < 24 || !equal("Bearer " + credential,
                        Objects.toString(x.getRequestHeaders().getFirst("Authorization"), ""))) {
                    throw new HarnessBusiness.Failure(403, "FORBIDDEN");
                }
                if (!path.equals("/harness-admin/v1/admission")) {
                    throw new HarnessBusiness.Failure(404, "NOT_FOUND");
                }
                if (execution == null) {
                    throw new HarnessBusiness.Failure(503, "HARNESS_NOT_READY");
                }
                Boolean accepting = null;
                if (method.equals("POST")) {
                    JSONObject value = StrictJson.object(body(x.getRequestBody()));
                    if (value.length() != 1 || !(value.opt("accepting") instanceof Boolean)) {
                        throw new HarnessBusiness.Failure(400, "INVALID_ADMISSION_CONTROL");
                    }
                    accepting = value.getBoolean("accepting");
                } else if (!method.equals("GET")) {
                    throw new HarnessBusiness.Failure(405, "METHOD_NOT_ALLOWED");
                }
                response = HarnessBusiness.reply(200, execution.admission(accepting));
            } else {
                throw new HarnessBusiness.Failure(404, "NOT_FOUND");
            }
        } catch (Exception e) {
            response = HarnessBusiness.failure(e);
        }
        send(x, response.getInt("status"), response.getJSONObject("body"));
    }

    /**
     * 发送 JSON 响应：固定 Content-Type 与 no-store，写完即关闭交换。
     *
     * @param x      HTTP 交换
     * @param status HTTP 状态码
     * @param result 响应体 JSON
     * @throws IOException 写出失败
     */
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
