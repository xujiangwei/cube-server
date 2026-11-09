/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import cell.core.talk.PrimitiveInputStream;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cube.aigc.completion.CompletionProvider;
import cube.auth.AuthTokenProvider;
import cube.core.AbstractModule;
import cube.core.Kernel;
import cube.core.Module;
import cube.monitor.ServiceSnapshot;
import cube.plugin.PluginSystem;
import cube.service.harness.execution.ExecutionRuntime;
import cube.service.harness.execution.JettyExchange;
import cube.service.harness.execution.ModelProtocol;
import cube.service.harness.execution.ToolProtocol;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreBootstrap;
import cube.service.harness.store.StoreConfig;
import cube.service.harness.store.StoreException;
import org.eclipse.jetty.server.Server;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Harness 内核模块，是 worker 报文、dispatcher 业务报文与文件流的总入口。
 *
 * <p>三类报文入口：
 * <ul>
 * <li>worker 原始 JSON：按报文形态分派到 v1 基础协议注册表、协议 2/3 模型 worker
 * 或 tool/sandbox runner；</li>
 * <li>dispatcher 业务报文（ActionDialect）：经有界业务执行池处理；</li>
 * <li>文件 SHM 流：按流名（transferId）转业务侧接收。</li>
 * </ul>
 *
 * <p>健康/运维 HTTP 监听独立于上述报文入口，只暴露 /healthz、/readyz
 * 与 /harness-admin/v1/admission，其余路径一律 404，业务报文没有 HTTP 回退。
 * 执行未启用时监听由 JDK HttpServer 承载，只提供 scripted 通信探针能力；
 * 执行启用时改由 Jetty 承载同一组健康/运维入口。
 *
 * <p>start 与 stop 由 Kernel 调用且彼此互斥；启动任一环节失败只记录原因码并回滚，
 * 不向 Kernel 抛出异常。
 */
public final class HarnessService extends AbstractModule {

    /**
     * transport 会话 ID 到客户端地址的映射，仅供监控快照补充 worker 地址。
     * <p>容量上限 512 条；达到上限且该会话尚未记录时，先淘汰一个任意条目再写入。
     * 会话断开时由 disconnect 移除对应条目。
     */
    private final Map<Long, String> monitoringEndpoints = new ConcurrentHashMap<>();

    /**
     * Cellet 路由目标与 Kernel 模块名，取值 "Harness"；Cellet 构造、模块安装
     * 与 Python worker 侧 speak 的报文目标名统一使用该值。
     */
    public static final String NAME = "Harness";

    /**
     * 私有配置；构造时未注入时由 start 按默认路径 config/harness.properties 加载，
     * 该路径可用 -Dharness.config 覆盖。
     */
    private HarnessConfig config;

    /**
     * v1 基础协议注册表（最多 64 个 worker），与执行侧模型/runner 注册表相互独立。
     */
    private WorkerRegistry registry;

    /**
     * worker 请求池（harness-cell：2 线程 / 队列 64），定长守护线程 + 有界队列。
     */
    private ThreadPoolExecutor executor;

    /**
     * 健康/运维 HTTP 池（harness-http：2 线程 / 队列 32）。
     * <p>仅在执行未启用、监听由 JDK HttpServer 承载时创建；执行启用走 Jetty，此字段为 null，
     * 监控快照中按未启动的池上报。
     */
    private ThreadPoolExecutor httpExecutor;

    /**
     * 业务执行池（harness-business：4 线程 / 队列 32），定长守护线程 + 有界队列。
     */
    private ThreadPoolExecutor businessExecutor;

    /**
     * 业务分发器，持有文件传输协调器；stop 时先于线程池 drain 关闭。
     */
    private HarnessBusiness business;

    /**
     * 未启用执行时的 JDK HttpServer（仅健康/运维入口）；与 executionHttp 互斥。
     */
    private HttpServer http;

    /**
     * 启用执行时的 Jetty 服务器（仅健康/运维入口）；与 http 互斥。
     */
    private Server executionHttp;

    /**
     * 启动失败原因码；启动成功为 null，失败时固定为 HARNESS_CONFIGURATION_ERROR。
     * <p>目前仅在内部记录，未通过健康/运维接口对外暴露。
     */
    private volatile String startupError;

    /**
     * RunStore；仅 store.enabled=true 时创建，启用执行前必须就绪。
     */
    private JdbcRunStore store;

    /**
     * 存储配置及其 MySQL 连接池；启动失败回滚和停止时均释放。
     */
    private StoreConfig storeConfig;

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
     * 记录 transport 会话对应的客户端地址，供监控快照补充 worker 地址。
     * <p>映射达到 512 条且该会话尚未记录时，先淘汰一个任意条目再写入。
     *
     * @param context 新建的 Cell transport 会话上下文
     */
    public void observeTransport(TalkContext context) {
        long sessionId = context.getSessionId();
        if (monitoringEndpoints.size() >= 512 && !monitoringEndpoints.containsKey(sessionId)) {
            Iterator<Long> keys = monitoringEndpoints.keySet().iterator();
            if (keys.hasNext()) {
                monitoringEndpoints.remove(keys.next());
            }
        }
        String address = context.getSessionHost() + ":" + context.getSessionPort();
        monitoringEndpoints.put(sessionId, address);
    }

    /**
     * 创建定长、守护线程、有界队列的线程池；队列满时按 AbortPolicy 拒绝。
     *
     * @param name     线程名（池内所有线程共用）
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
                new ArrayBlockingQueue<>(capacity),
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
        Properties values = config.values;
        StringBuilder out = new StringBuilder();
        out.append("host=").append(config.host());
        out.append(" port=").append(config.port());
        out.append(" leaseMillis=").append(config.leaseMillis());
        out.append(" store=").append(store != null);
        if (store != null) {
            out.append(" dialect=").append(values.getProperty("store.dialect", ""));
            out.append(" database=").append(values.getProperty("store.database", ""));
        }
        out.append(" execution=").append(execution != null);
        if (execution != null) {
            out.append(" accepting=").append(execution.accepting());
            out.append(" maxActive=").append(values.getProperty("execution.max.active", "128"));
            out.append(" permissions=").append(values.getProperty("execution.permissions", "").replace(",", "|"));
        }
        out.append(" workers=").append(workerCount(values));
        out.append(" files=").append(values.getProperty("files.enabled", "false"));
        out.append(" trace=").append(values.getProperty("trace.enabled", "false"));
        out.append(" feedback=").append(values.getProperty("feedback.enabled", "false"));
        out.append(" inferenceNode=").append(values.getProperty("inference.node.id", ""));
        return out.toString();
    }

    /**
     * 统计授权的 Python worker 数（worker.&lt;id&gt;.role），只计数不打印 workerId。
     *
     * @param values 配置键值
     * @return worker.&lt;id&gt;.role 形式的键个数
     */
    private static int workerCount(Properties values) {
        int count = 0;
        for (String name : values.stringPropertyNames()) {
            if (name.startsWith("worker.") && name.endsWith(".role")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 启动模块（Kernel 调用，synchronized 与 stop 互斥）：
     * 加载配置 → store.enabled 时自检数据库并建 RunStore → 建 v1 注册表 →
     * execution.enabled 时构建 ExecutionRuntime（要求 store 已就绪）→
     * 建 worker 请求池与业务执行池、业务分发器 →
     * 起健康/运维监听（执行启用走 Jetty；否则 JDK HttpServer 并另建 harness-http 池）→
     * 启动 execution。任一环节失败只记录 startupError 并回滚 stop，不向 Kernel 抛出异常。
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
            // 接受业务请求前自动补齐任务账本结构；失败仅停止 Harness。
            if (Boolean.parseBoolean(config.values.getProperty("store.enabled", "false"))) {
                storeConfig = new StoreConfig(config.values);
                StoreBootstrap.selfCheck(storeConfig);
                store = new JdbcRunStore(storeConfig);
            }

            registry = new WorkerRegistry(config.leaseMillis());
            if (Boolean.parseBoolean(config.values.getProperty("execution.enabled", "false"))) {
                if (store == null) {
                    throw new IllegalArgumentException("STORE_REQUIRED");
                }
                execution = new ExecutionRuntime(
                        store,
                        config.values,
                        config.leaseMillis(),
                        () -> {
                            Kernel kernel = getKernel();
                            Object module = kernel == null ? null : kernel.getModule("AIGC");
                            if (module instanceof CompletionProvider) {
                                return (CompletionProvider) module;
                            }
                            return null;
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
     * 先关业务分发器、Jetty/JDK HTTP 监听，再有界 drain 三个线程池候选
     * （优雅等待 3 秒，超时 shutdownNow 后再等 3 秒；httpExecutor 可能为 null，跳过），
     * 最后关闭 execution、清空地址映射与注册表、释放存储引用。
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
                // 停止阶段不再因 Jetty 关闭异常中断其余资源的回收。
            }
            executionHttp = null;
        }
        if (http != null) {
            http.stop(1);
            http = null;
        }
        for (ThreadPoolExecutor poolExecutor : Arrays.asList(executor, httpExecutor, businessExecutor)) {
            if (poolExecutor != null) {
                poolExecutor.shutdown();
                try {
                    if (!poolExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                        poolExecutor.shutdownNow();
                        poolExecutor.awaitTermination(3, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    poolExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        business = null;
        monitoringEndpoints.clear();
        if (execution != null) {
            execution.close();
            execution = null;
        }
        if (registry != null) {
            registry.clear();
        }
        store = null;
        if (storeConfig != null) {
            storeConfig.close();
            storeConfig = null;
        }
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
        monitoringEndpoints.remove(transport);
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
     * Kernel 定时钩子：刷新 v1 注册表快照，snapshot 内部顺带清理过期租约条目。
     *
     * @param module 本模块；本实现未使用
     * @param kernel 所属 Kernel；本实现未使用
     */
    @Override
    public void onTick(Module module, Kernel kernel) {
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
     * <li>带 runnerType：tool/sandbox runner 协议，校验协议版本与凭据后交 execution.runners；</li>
     * <li>protocolVersion 2/3：模型协议，校验契约与凭据后交 execution.workers；</li>
     * <li>其余：v1 基础协议，契约校验 + 凭据 + workerId/role 白名单后交注册表。</li>
     * </ul>
     * <p>只有 v1 响应会统一附带 protocolVersion=1、requestId 与 ok=true；
     * 协议 2/3 与 runner 分支直接返回各自执行侧给出的响应。
     * 无论成败，finally 中都写一条信封级结果日志。
     *
     * @param raw       worker 请求原始字节（单 STRING Stuff 载荷）
     * @param transport Cell transport 会话标识，与 worker 注册/传输绑定
     * @param valid     会话有效性探针，执行期间持续校验
     * @return 响应 JSON；所有异常在本方法内转换为错误响应
     * @throws IllegalArgumentException 协议不匹配（PROTOCOL_MISMATCH）、凭据无效（UNAUTHORIZED）、
     *                                  worker 未授权（FORBIDDEN）、runner 未启用（RUNNER_DISABLED）、
     *                                  服务未就绪（HARNESS_NOT_READY）、载荷超限（PAYLOAD_TOO_LARGE）等；
     *                                  这些异常都在本方法内被捕获并转为错误响应，不会传播给调用方
     */
    public JSONObject process(byte[] raw, long transport, BooleanSupplier valid) {
        String id = "";
        int protocol = 1;
        String runnerType = null;
        JSONObject request = null;
        JSONObject response = null;
        Exception unexpected = null;
        try {
            request = StrictJson.object(raw);
            if (request.has("runnerType")) {
                runnerType = request.optString("runnerType", "");
            }
            if (!started.get()) {
                throw new IllegalArgumentException("HARNESS_NOT_READY");
            }
            int declaredVersion = request.optInt("protocolVersion", 0);
            if (declaredVersion == 2 || declaredVersion == 3) {
                protocol = request.getInt("protocolVersion");
            }
            if (request.opt("requestId") instanceof String
                    && request.getString("requestId").length() <= 128) {
                id = request.getString("requestId");
            }
            if (runnerType != null) {
                Object version = request.opt("protocolVersion");
                if (!(version instanceof Number)
                        || ((Number) version).doubleValue() != ToolProtocol.VERSION) {
                    throw new IllegalArgumentException("PROTOCOL_MISMATCH");
                }
                ToolProtocol.parse("runner-request", raw);
                if (!equal(
                        config.values.getProperty("worker.credential"),
                        request.getString("credential"))) {
                    throw new IllegalArgumentException("UNAUTHORIZED");
                }
                if (execution == null || execution.runners == null) {
                    throw new IllegalArgumentException("RUNNER_DISABLED");
                }
                response = execution.runners.apply(request, transport, valid);
                return response;
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
                response = execution.workers.apply(request, transport, valid);
                return response;
            }
            if (request.has("protocolVersion")
                    && (!(request.get("protocolVersion") instanceof Number)
                    || ((Number) request.get("protocolVersion")).doubleValue() != 1)) {
                throw new IllegalArgumentException("PROTOCOL_MISMATCH");
            }
            byte[] schemaBytes = Contract.resource("schemas/worker-request.json");
            JSONObject schema = new JSONObject(new String(schemaBytes, StandardCharsets.UTF_8));
            Contract.validate(request, schema);
            if (!equal(
                    config.values.getProperty("worker.credential"),
                    request.getString("credential"))) {
                throw new IllegalArgumentException("UNAUTHORIZED");
            }
            if (!config.authorizedWorker(request.getString("workerId"), request.getString("role"))) {
                throw new IllegalArgumentException("FORBIDDEN");
            }
            response = registry.apply(request, transport, valid)
                    .put("protocolVersion", 1)
                    .put("requestId", id)
                    .put("ok", true);
            return response;
        } catch (StoreException e) {
            response = workerError(id, e.code, protocol, runnerType);
            return response;
        } catch (IllegalArgumentException e) {
            response = workerError(id, e.getMessage(), protocol, runnerType);
            return response;
        } catch (Exception e) {
            unexpected = e;
            response = workerError(id, "INTERNAL_ERROR", protocol, runnerType);
            return response;
        } finally {
            WorkerRegistrationLog.outcome(request, response, config, transport, unexpected);
        }
    }

    /**
     * 组装 worker 侧错误响应：非 runner 请求附带 protocolVersion，
     * runner 请求同时附带 runnerType 与该家族当前协议版本。
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
        return response.put("runnerType", runnerType)
                .put("protocolVersion", ToolProtocol.VERSION);
    }

    /**
     * 记录信封级拒绝：请求 JSON 尚不可得（如非法信封、会话过期、业务池已满）时的结果日志。
     *
     * @param code      服务端错误码，如 INVALID_ENVELOPE、HARNESS_NOT_READY、
     *                  STALE_SESSION、HARNESS_BUSY
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
     * 生成面向控制台的监控快照：从 capabilities 中筛出安全的 worker 字段，
     * 补充客户端地址与描述符中的 model/backendId，并按容量账单统计各 worker 占用数。
     *
     * <p>worker 字段按白名单逐项复制，描述符、凭据与任意 worker 载荷一律不外传；
     * 地址来自 observeTransport 记录的 transport 映射。
     * 快照含 workers、infrastructureReady、runsReady、accepting、capacity 与 pools，
     * 执行启用时再追加 jobs。
     *
     * @return 监控快照 JSON
     */
    @Override
    public JSONObject monitoringSnapshot() {
        JSONObject caps = capabilities();
        JSONArray all = caps.getJSONArray("registeredWorkers");
        JSONArray workers = new JSONArray();
        // Never forward descriptors, credentials or arbitrary worker payloads into reports.
        String[] safe = {
                "workerId", "instanceId", "role", "runnerType", "registered", "ready",
                "healthy", "draining", "leaseRemainingMs", "capacity", "occupied", "available"
        };
        for (int i = 0; i < all.length(); ++i) {
            JSONObject source = all.getJSONObject(i);
            JSONObject worker = new JSONObject();
            for (String key : safe) {
                if (source.has(key)) {
                    worker.put(key, source.get(key));
                }
            }
            long transportId = source.optLong("transportId", -1L);
            worker.put("address", monitoringEndpoints.get(transportId));
            JSONObject descriptor = source.optJSONObject("descriptor");
            if (descriptor != null) {
                worker.put("model", descriptor.optString("model", ""));
                worker.put("backendId", descriptor.optString("backendId", ""));
                JSONObject capacity = caps.optJSONObject("capacity");
                JSONArray occupations = capacity == null ? null : capacity.optJSONArray("workers");
                long occupied = 0;
                if (occupations != null) {
                    for (int j = 0; j < occupations.length(); ++j) {
                        JSONObject entry = occupations.getJSONObject(j);
                        if (source.getString("workerId").equals(entry.getString("worker_id"))) {
                            occupied = entry.getLong("occupied");
                        }
                    }
                }
                worker.put("occupied", occupied);
            }
            workers.put(worker);
        }
        JSONObject result = new JSONObject();
        result.put("workers", workers);
        result.put("infrastructureReady", caps.getBoolean("infrastructureReady"));
        result.put("runsReady", caps.getBoolean("runsReady"));
        result.put("accepting", caps.opt("accepting") == null
                ? JSONObject.NULL : caps.get("accepting"));
        result.put("capacity", caps.optJSONObject("capacity") == null
                ? JSONObject.NULL : caps.getJSONObject("capacity"));
        JSONArray pools = new JSONArray();
        pools.put(ServiceSnapshot.pool("Harness worker", executor));
        pools.put(ServiceSnapshot.pool("Harness business", businessExecutor));
        pools.put(ServiceSnapshot.pool("Harness admin", httpExecutor));
        result.put("pools", pools);
        if (execution != null) {
            result.put("jobs", execution.monitoringSnapshot());
        }
        return result;
    }

    /**
     * 组装能力快照，供监控快照与就绪判定使用。
     *
     * <p>执行启用时汇总模型 worker 与 runner 的注册快照，报告 protocolVersion=3、
     * runnerTypes、infrastructureReady、runsReady、accepting、execution、capacity 与 files；
     * 执行未启用时报告 protocolVersion=1、v1 注册表快照、
     * runsReady=false 与 execution=scripted。
     *
     * @return 能力快照 JSON
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
            JSONObject result = new JSONObject();
            result.put("service", NAME);
            result.put("protocolVersion", 3);
            result.put("runnerTypes", execution.runners == null
                    ? new JSONArray() : new JSONArray().put("tool").put("sandbox"));
            result.put("registeredWorkers", all);
            result.put("infrastructureReady", true);
            result.put("runsReady", execution.accepting() && execution.modelReady());
            result.put("accepting", execution.accepting());
            result.put("execution", execution.runners == null
                    ? "resource-loop" : "trusted-tool-loop");
            result.put("capacity", execution.workers.capacity());
            result.put("files", execution.files == null
                    ? JSONObject.NULL : execution.files.metrics());
            return result;
        }
        JSONObject result = new JSONObject();
        result.put("service", NAME);
        result.put("protocolVersion", 1);
        result.put("registeredWorkers", registry.snapshot());
        result.put("infrastructureReady", registry.ready());
        result.put("runsReady", false);
        result.put("execution", "scripted");
        return result;
    }

    /**
     * 读全量请求体，超过 StrictJson.MAX_BYTES 即拒绝。
     *
     * @param in 请求体输入流
     * @return 请求体全部字节
     * @throws IOException              读取失败
     * @throws IllegalArgumentException PAYLOAD_TOO_LARGE：请求体超过严格 JSON 上限
     */
    private static byte[] body(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n = in.read(b);
        while (n != -1) {
            if (out.size() + n > StrictJson.MAX_BYTES) {
                throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");
            }
            out.write(b, 0, n);
            n = in.read(b);
        }
        return out.toByteArray();
    }

    /**
     * 用 Kernel 中的 Auth 模块认证 Bearer 令牌；Auth 缺失或未启动时按不可用处理。
     *
     * @param code 请求携带的 Bearer 令牌原文
     * @return 认证后的身份
     * @throws HarnessBusiness.Failure 401/UNAUTHORIZED 或 503/AUTH_UNAVAILABLE
     *                                 （见 HarnessIdentity.authenticate）
     */
    HarnessIdentity authenticate(String code) {
        Kernel kernel = getKernel();
        Module auth = kernel == null ? null : kernel.getModule("Auth");
        AuthTokenProvider provider = null;
        if (auth instanceof AuthTokenProvider && auth.isStarted()) {
            provider = (AuthTokenProvider) auth;
        }
        Set<String> permissions = HarnessIdentity.permissions(config.values);
        return HarnessIdentity.authenticate(provider, code, permissions);
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
    public JSONObject processBusiness(ActionDialect request,
                                      TalkContext context,
                                      Function<String, OutputStream> opener) {
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
    public void receiveFile(TalkContext context, PrimitiveInputStream stream) {
        HarnessBusiness current = business;
        if (current != null && current.transfers != null) {
            current.transfers.receive(context.getSessionId(), stream);
        } else {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 业务不可用，关闭流失败也无需向上回报。
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
     * @throws IOException 读写 HTTP 交换失败（由容器记录）
     */
    private void handleHttp(HttpExchange x) throws IOException {
        JSONObject response;
        try {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();
            if (path.equals("/healthz") || path.equals("/readyz")) {
                if (!method.equals("GET")) {
                    throw new HarnessBusiness.Failure(405, "METHOD_NOT_ALLOWED");
                }
                boolean ready;
                if (!started.get()) {
                    ready = false;
                } else if (path.equals("/healthz")) {
                    ready = true;
                } else if (execution != null) {
                    ready = execution.accepting() && execution.modelReady();
                } else {
                    ready = registry.ready();
                }
                JSONObject payload = new JSONObject();
                payload.put("service", NAME);
                payload.put("ready", ready);
                payload.put("runsReady", execution != null
                        && execution.accepting() && execution.modelReady());
                response = HarnessBusiness.reply(ready ? 200 : 503, payload);
            } else if (path.startsWith("/harness-admin/v1/")) {
                String credential = config.values.getProperty("admission.admin.credential", "");
                String authorization = Objects.toString(
                        x.getRequestHeaders().getFirst("Authorization"), "");
                if (credential.length() < 24
                        || !equal("Bearer " + credential, authorization)) {
                    throw new HarnessBusiness.Failure(403, "FORBIDDEN");
                }
                if (!path.equals("/harness-admin/v1/admission")) {
                    throw new HarnessBusiness.Failure(404, "NOT_FOUND");
                }
                if (execution == null) {
                    throw new HarnessBusiness.Failure(503, "HARNESS_NOT_READY");
                }
                // null 表示 GET 查询当前接单状态；非 null 表示 POST 切换该状态。
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
