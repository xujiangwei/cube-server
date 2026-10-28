/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import com.sun.net.httpserver.HttpExchange;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 执行编排门面：按可信请求身份隔离数据，后台执行从持久账本恢复任务归属。
 *
 * <p>启动时装配全部组件（ModelProtocol.verify 先行核对契约），运行期提供 run 创建、准入、
 * 读取、事件回放与 SSE。身份一律来自 Cube Auth 校验后的 token（tenant/subject），不从请求
 * 载荷自报。准入为当前 JVM 的内存开关（execution.accepting），关闭后新任务返回
 * HARNESS_DRAINING，既有创建键仍可幂等重放；满额返回 HARNESS_BUSY，同样先尝试重放。
 */
public final class ExecutionRuntime implements AutoCloseable {

    /**
     * 模型作业账本：backend 行、poll/report 与未决作业计数。
     */
    public final ModelJobStore jobs;

    /**
     * 模型 worker 拉取传输（描述符登记、会话护栏、容量聚合）。
     */
    public final ModelDispatcher workers;

    /**
     * run 编排器：以租约/fence/checkpoint 推进资源循环。
     */
    public final RunCoordinator coordinator;

    /**
     * Java 推理网关：Cell v3 推理受理、派发与 usage 账本。
     */
    public final InferenceGateway inference;

    /**
     * 可信工具 broker；execution.tools.enabled=false 时为 null。
     */
    public final ToolBroker broker;

    /**
     * tool runner 拉取传输；broker 未启用时为 null。
     */
    public final RunnerDispatcher runners;

    /**
     * 服务器执行配置与租户业务策略解析及 run 创建。
     */
    public final ProfileRegistry profiles;

    /**
     * 平台授予的工具权限集合（execution.permissions，已规范化，不可变）。
     */
    public final Set<String> permissions;

    /**
     * need_input 等待与控制操作（resume/approval/cancel）存储。
     */
    public final ControlStore controls;

    /**
     * trace 事件存储（读取、导出与回放的事实来源）。
     */
    public final TraceStore traces;

    /**
     * 文件服务；files.enabled=false 时为 null。
     */
    public final cube.service.harness.files.FileService files;

    /**
     * profile/release 目录存储（私有）。
     */
    private final CatalogStore catalog;

    private final TenantTemplates tenantTemplates;

    public final ExecutionProfiles deployment;

    /**
     * run 权威账本（私有）：findCreate/read/events/subject/admissionLimit。
     */
    private final JdbcRunStore store;
    public JSONObject monitoringSnapshot() { return store.monitoringSnapshot(); }

    /**
     * 单租户活跃 run 准入上限（execution.max.active，取值 1..256）。
     */
    private final int maxActive;

    /**
     * 当前 JVM 是否接受新 run；volatile，管理接口可切换，重启恢复配置值。
     */
    private volatile boolean accepting;

    /**
     * SSE 并发连接信号量（上限 2），stream 结束或异常时释放。
     */
    private final Semaphore streams = new Semaphore(2);

    /**
     * 使用无 Unit 提供方的配置构造运行时（等同显式传入返回 null 的工厂）。
     *
     * @param store run 权威账本
     * @param p     Harness 配置
     * @param lease 会话租约时长（毫秒）
     * @throws Exception 装配失败（契约核对、配置非法或存储初始化）
     */
    public ExecutionRuntime(JdbcRunStore store, Properties p, long lease) throws Exception {
        this(store, p, lease, () -> null);
    }

    /**
     * 完整构造：核对契约并装配存储、权限、文件、profile、仓库与全部执行组件。
     *
     * <p>权限取自 execution.permissions（缺省 skill.list,skill.activate,resource.list,
     * resource.read,resource.search），逐项规范化；maxActive 越界拒绝
     * （INVALID_ADMISSION_LIMIT）并写入账本；execution.repository 为必填。
     * broker/runners 仅在 execution.tools.enabled=true 时装配。
     *
     * @param store    run 权威账本
     * @param p        Harness 配置
     * @param lease    会话租约时长（毫秒）
     * @param provider 同 JVM CompletionProvider 工厂；无 Unit 部署时可返回 null
     * @throws Exception                存储/目录初始化失败
     * @throws IllegalArgumentException INVALID_ADMISSION_LIMIT（maxActive 越界）或
     *                                  execution.repository 缺失（NPE 携带该键名）
     */
    public ExecutionRuntime(
            JdbcRunStore store,
            Properties p,
            long lease,
            java.util.function.Supplier<cube.aigc.completion.CompletionProvider> provider)
            throws Exception {
        ModelProtocol.verify();
        this.store = store;
        traces = new TraceStore(store, p);
        StoreConfig sc = new StoreConfig(p);
        catalog = new CatalogStore(store, sc.mysql());
        deployment = ExecutionProfiles.load(p);
        tenantTemplates = TenantTemplates.load(Paths.get(p.getProperty("execution.tenant.templates", "./config/harness-tenant")));
        Set<String> configured = new TreeSet<>();
        for (String value : p.getProperty("execution.permissions",
                "skill.list,skill.activate,resource.list,resource.read,resource.search").split(",")) {
            if (!value.trim().isEmpty()) {
                configured.add(RuntimeSupport.id(value.trim()));
            }
        }
        permissions = Collections.unmodifiableSet(configured);
        files =
                Boolean.parseBoolean(p.getProperty("files.enabled", "false"))
                        ? new cube.service.harness.files.FileService(store, p, null)
                        : null;
        profiles = new ProfileRegistry(catalog, files == null ? null : files.ledger, deployment);
        controls = new ControlStore(store, sc.mysql()).files(files == null ? null : files.ledger);
        maxActive = Integer.parseInt(p.getProperty("execution.max.active", "128"));
        if (maxActive < 1 || maxActive > 256) {
            throw new IllegalArgumentException("INVALID_ADMISSION_LIMIT");
        }
        store.admissionLimit(maxActive);
        accepting = Boolean.parseBoolean(p.getProperty("execution.accepting", "true"));
        Path path =
                Paths.get(
                        Objects.requireNonNull(
                                p.getProperty("execution.repository"), "execution.repository"));
        PackageRepository repo =
                new PackageRepository(
                        catalog,
                        new AssetFiles(
                                path,
                                Long.parseLong(
                                        p.getProperty(
                                                "execution.repository.max.bytes", "67108864")),
                                p.getProperty(
                                        "execution.repository.namespace", "harness-resources")),
                        Collections.emptyMap(),
                        true,
                        Boolean.parseBoolean(p.getProperty("execution.tools.enabled", "false")));
        jobs = new ModelJobStore(store, sc.mysql());
        inference = new InferenceGateway(new InferenceStore(store, sc.mysql()), p, provider);
        workers = new ModelDispatcher(jobs, p, lease, inference);
        broker =
                Boolean.parseBoolean(p.getProperty("execution.tools.enabled", "false"))
                        ? new ToolBroker(new ToolJobStore(store, sc.mysql()), permissions, p)
                        .files(files)
                        : null;
        runners = broker == null ? null : new RunnerDispatcher(broker, p, lease);
        coordinator =
                new RunCoordinator(
                        store,
                        jobs,
                        catalog,
                        permissions,
                        new ResourceTools(catalog, repo).files(files),
                        broker)
                        .controls(controls, files, p);
    }

    /**
     * 启动文件服务、推理网关与协调器（存储侧无后台线程，随构造就绪）。
     */
    public void start() {
        if (files != null) {
            files.start();
        }
        inference.start();
        coordinator.start();
    }

    /** Called only after the business request has passed trusted authentication and validation. */
    public void ensureTenant(ProfileRegistry.Identity identity) {
        catalog.ensureTenant(identity.tenant, tenantTemplates);
    }

    /**
     * 创建 run：先做持久化创建重放，再做过载准入，最后交 profile 目录受理。
     *
     * <p>关闭接单或达到 maxActive 时，同键请求仍先返回既有创建结果（幂等重放），
     * 仅新键被拒绝。
     *
     * @param identity 调用者身份（tenant/subject 来自可信 token）
     * @param key      幂等创建键（Idempotency-Key）
     * @param request  创建请求（input 与可选模型、执行器和能力选择）
     * @return 既有或新建的 run 创建结果
     * @throws StoreException HARNESS_DRAINING（关闭接单且非重放）或 HARNESS_BUSY（满额且非重放）
     */
    public synchronized JSONObject create(ProfileRegistry.Identity identity, String key, JSONObject request) {
        cube.service.harness.protocol.Contract.parse("create-run", RuntimeSupport.bytes(request));
        ensureTenant(identity);
        JSONObject priorCreation = store.findCreate(identity.tenant, identity.subject, key, "server-default", request);
        if (priorCreation != null) return priorCreation;
        if (!accepting) {
            JSONObject prior =
                    store.findCreate(
                            identity.tenant,
                            identity.subject,
                            key,
                            "server-default",
                            request);
            if (prior != null) {
                return prior;
            }
            throw new StoreException("HARNESS_DRAINING");
        }
        // 持久化创建重放先于准入判断（含已满额时）：同键请求在 catalog/store 内仍可重放。
        if (jobs.active(identity.tenant) >= maxActive) {
            JSONObject prior =
                    store.findCreate(
                            identity.tenant,
                            identity.subject,
                            key,
                            "server-default",
                            request);
            if (prior != null) {
                return prior;
            }
            throw new StoreException("HARNESS_BUSY");
        }
        // Readiness is a point-in-time admission observation, not a reservation. Read its
        // database-backed worker state before the creation write transaction (SQLite).
        Set<String> readyModels = new HashSet<>();
        for (JSONObject model : deployment.list("model")) if (workers.ready(model)) readyModels.add(model.getString("id") + "@" + model.getString("version"));
        JSONArray readyRunners = runners == null ? new JSONArray() : runners.snapshot();
        return profiles.createRun(identity, key, request, pin -> requireReady(pin, readyModels, readyRunners));
    }

    /**
     * 读取当前接单开关状态。
     *
     * @return 接受新 run 为 true
     */
    public boolean modelReady() {
        for (JSONObject model : deployment.list("model")) if (workers.ready(model)) {
            for (JSONObject runtime : deployment.list("runtime")) {
                try {
                    deployment.select(new JSONObject().put("model", model.getString("id") + "@" + model.getString("version"))
                            .put("runtime", runtime.getString("id") + "@" + runtime.getString("version")));
                    if (runtimeAvailability(runtime, Collections.emptySet()).getBoolean("ready")) return true;
                } catch (cube.service.harness.store.StoreException incompatible) { }
            }
        }
        return false;
    }

    private JSONObject runtimeAvailability(JSONObject runtime, Set<String> requiredTypes) {
        return runtimeAvailability(runtime, requiredTypes, runners == null ? new JSONArray() : runners.snapshot());
    }

    private JSONObject runtimeAvailability(JSONObject runtime, Set<String> requiredTypes, JSONArray nodes) {
        if (runtime.getString("mode").equals("resources-only")) return new JSONObject().put("ready", requiredTypes.isEmpty())
                .put("capacity", maxActive).put("available", maxActive).put("reason", requiredTypes.isEmpty() ? "" : "RUNTIME_NOT_READY");
        if (runners == null) return new JSONObject().put("ready", false).put("capacity", 0).put("available", 0).put("reason", "TOOLS_DISABLED");
        Set<String> readyTypes = new TreeSet<>();
        int capacity = 0, available = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (!node.getBoolean("ready") || (!requiredTypes.isEmpty() && !requiredTypes.contains(node.getString("runnerType")))) continue;
            readyTypes.add(node.getString("runnerType"));
            capacity += node.getInt("capacity"); available += node.getInt("available");
        }
        boolean ready = !readyTypes.isEmpty() && readyTypes.containsAll(requiredTypes);
        return new JSONObject().put("ready", ready).put("capacity", capacity).put("available", available).put("reason", ready ? "" : "RUNTIME_NOT_READY");
    }

    private void requireReady(JSONObject pin, Set<String> readyModels, JSONArray readyRunners) {
        JSONObject model = pin.getJSONObject("model");
        if (!readyModels.contains(model.getString("id") + "@" + model.getString("version"))) throw new StoreException("MODEL_NOT_READY");
        Set<String> requiredTypes = RuntimeSupport.strings(pin.getJSONArray("requiredRunnerTypes"));
        if (!runtimeAvailability(pin.getJSONObject("runtime"), requiredTypes, readyRunners).getBoolean("ready")) throw new StoreException("RUNTIME_NOT_READY");
        Set<String> tools = RuntimeSupport.strings(pin.getJSONObject("selection").getJSONArray("tools"));
        JSONArray releases = pin.getJSONArray("releases");
        for (int i = 0; i < releases.length(); i++) {
            JSONArray declared = releases.getJSONObject(i).getJSONObject("manifest").getJSONArray("tools");
            for (int j = 0; j < declared.length(); j++) {
                JSONObject tool = declared.getJSONObject(j);
                if (tools.contains(tool.getString("name")) && tool.getString("kind").equals("executable")
                        && (broker == null || !broker.supports(tool))) throw new StoreException("RUNTIME_NOT_READY");
            }
        }
    }

    /** Authenticated public capability catalog with live readiness and bounded capacity. */
    public JSONObject taskProfiles(ProfileRegistry.Identity identity) {
        ensureTenant(identity);
        JSONObject out = profiles.describe(identity);
        JSONArray models = out.getJSONArray("models"), runtimes = out.getJSONArray("runtimes"), tools = out.getJSONArray("tools");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            JSONObject status = workers.availability(deployment.resolve("model", model.getString("id") + "@" + model.getString("version")));
            for (String key : status.keySet()) model.put(key, status.get(key));
        }
        for (int i = 0; i < runtimes.length(); i++) {
            JSONObject runtime = runtimes.getJSONObject(i);
            JSONObject status = runtimeAvailability(runtime, Collections.emptySet());
            for (String key : status.keySet()) runtime.put(key, status.get(key));
        }
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            int capacity = 0, available = 0; boolean ready = false;
            for (int j = 0; j < runtimes.length(); j++) {
                JSONObject runtime = runtimes.getJSONObject(j);
                boolean compatible = false;
                JSONArray refs = tool.getJSONArray("compatibleRuntimes");
                for (int k = 0; k < refs.length(); k++) if (refs.getJSONObject(k).getString("id").equals(runtime.getString("id"))
                        && refs.getJSONObject(k).getString("version").equals(runtime.getString("version"))) compatible = true;
                if (!compatible) continue;
                JSONObject status = runtimeAvailability(runtime, tool.has("runnerType") ? Collections.singleton(tool.getString("runnerType")) : Collections.emptySet());
                ready |= status.getBoolean("ready"); capacity = Math.max(capacity, status.getInt("capacity")); available = Math.max(available, status.getInt("available"));
            }
            if (tool.getString("kind").equals("executable")) {
                JSONObject release = catalog.release(identity.tenant, tool.getString("release"));
                JSONArray declared = new JSONObject(release.getString("manifest_json")).getJSONArray("tools");
                for (int j = 0; j < declared.length(); j++) if (declared.getJSONObject(j).getString("name").equals(tool.getString("name"))
                        && (broker == null || !broker.supports(declared.getJSONObject(j)))) ready = false;
            }
            tool.put("ready", ready).put("capacity", ready ? capacity : 0).put("available", ready ? available : 0).put("reason", ready ? "" : "RUNTIME_NOT_READY");
        }
        return out;
    }

    public boolean accepting() {
        return accepting;
    }

    /**
     * 查询/切换接单开关，并返回 drain 状态汇总。
     *
     * <p>开关仅属于当前 JVM，重启恢复配置值，不跨 JVM 广播；计数覆盖账本全部租户。
     *
     * @param enabled 目标开关；null 表示仅查询不切换
     * @return drainStatus 叠加 accepting 与 scope 说明的状态对象
     */
    public synchronized JSONObject admission(Boolean enabled) {
        if (enabled != null) {
            accepting = enabled;
        }
        return jobs.drainStatus()
                .put("accepting", accepting)
                .put("scope", "this-jvm; ledger counts cover all tenants");
    }

    /**
     * 仅供已获授权的同域审批人或 trace 读取者解析任务所有者。
     *
     * @param caller 调用者身份（须为授权审批人或 reader）
     * @param run    run 标识
     * @return 以账本记载所有者为 subject 的身份对象
     */
    public ProfileRegistry.Identity owner(ProfileRegistry.Identity caller, String run) {
        return new ProfileRegistry.Identity(caller.tenant, store.subject(caller.tenant, run), permissions);
    }

    /**
     * 读取 run 的快照、结算、用量与结果，并附带待处理交互（如有）。
     *
     * @param identity 调用者身份
     * @param run      run 标识
     * @return snapshot/usageSettlement/usage/result，及可选的 interaction
     */
    public JSONObject read(ProfileRegistry.Identity identity, String run) {
        JSONObject row = store.read(identity.tenant, identity.subject, RuntimeSupport.id(run));
        JSONObject result =
                new JSONObject()
                        .put("snapshot", row.getJSONObject("snapshot"))
                        .put("usageSettlement", row.getString("usageSettlement"))
                        .put("usage", row.getJSONObject("usage"))
                        .put("result", row.get("result"));
        JSONObject wait = controls.current(identity.tenant, identity.subject, run);
        if (wait != null) {
            result.put("interaction", wait);
        }
        return result;
    }

    /**
     * 应用一次控制操作（resume/approval/cancel 等）到指定 run。
     *
     * @param identity  调用者身份
     * @param run       run 标识
     * @param key       幂等键
     * @param operation 操作名
     * @param wait      等待语义（如 blocking）
     * @param payload   操作载荷
     * @param actor     操作者标识
     * @param approver  调用者是否为同域审批人
     * @return 控制操作结果
     */
    public JSONObject control(
            ProfileRegistry.Identity identity,
            String run,
            String key,
            String operation,
            String wait,
            JSONObject payload,
            String actor,
            boolean approver) {
        return controls.apply(
                identity.tenant,
                identity.subject,
                actor,
                RuntimeSupport.id(run),
                key,
                operation,
                wait,
                payload,
                approver);
    }

    /**
     * 分页读取 run 事件，并返回下一页游标。
     *
     * @param identity 调用者身份
     * @param run      run 标识
     * @param after    事件游标（从该序号之后开始）
     * @param limit    单页上限
     * @return events 数组与 nextCursor（本页最大 eventSeq，无事件时维持入参）
     */
    public JSONObject events(ProfileRegistry.Identity identity, String run, long after, int limit) {
        JSONArray events = store.events(identity.tenant, identity.subject, run, after, limit);
        long cursor = after;
        if (events.length() > 0) {
            cursor = events.getJSONObject(events.length() - 1).getLong("eventSeq");
        }
        return new JSONObject().put("events", events).put("nextCursor", cursor);
    }

    /**
     * 以 SSE 写出有限回放页：内存与时长均有界，客户端按最后完整收到的事件 ID 重连。
     *
     * <p>并发连接受信号量限制（2），超限抛 HARNESS_BUSY；仅支持非阻塞 HTTP 实现
     * （JettyExchange），否则抛 SSE_REQUIRES_NONBLOCKING_HTTP。信号量在流结束或
     * 启动失败时释放。
     *
     * @param x    HTTP 交换（必须为 JettyExchange）
     * @param page 含 events 数组的回放页
     * @throws IOException    写入失败
     * @throws StoreException HARNESS_BUSY（连接数已满）或
     *                        SSE_REQUIRES_NONBLOCKING_HTTP（非非阻塞 HTTP 实现）
     */
    public void stream(HttpExchange x, JSONObject page) throws IOException {
        if (!streams.tryAcquire()) {
            throw new StoreException("HARNESS_BUSY");
        }
        try {
            if (!(x instanceof JettyExchange)) {
                throw new StoreException("SSE_REQUIRES_NONBLOCKING_HTTP");
            }
            ((JettyExchange) x).stream(page, streams::release);
        } catch (IOException | RuntimeException e) {
            streams.release();
            throw e;
        }
    }

    /**
     * 关闭协调器、推理网关、broker 与文件服务（按依赖逆序，未启用组件跳过）。
     */
    @Override
    public void close() {
        coordinator.close();
        inference.close();
        if (broker != null) {
            broker.close();
        }
        if (files != null) {
            files.close();
        }
    }
}
