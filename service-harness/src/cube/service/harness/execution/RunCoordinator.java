/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cell.util.log.Logger;
import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.ContextEngine;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.ResourceTools;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.CatalogStore;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.ModelJobStore;
import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * run 的持久步进状态机：以租约/围栏/checkpoint 推进资源循环。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>扫描与步进都发生在单一守护线程（harness-coordinator）上，API/Cell 线程不做推理，
 *       也不做轮询休眠；</li>
 *   <li>每一步持有独立的不可变身份（{@link RunStep}），不在共享服务上切换当前用户；</li>
 *   <li>状态机：queued → running；waiting_input / waiting_approval 经控制面恢复；
 *       终态为 succeeded / failed / cancelled / timed_out；</li>
 *   <li>租约：按 run 缓存 {@link JdbcRunStore.Lease}，活动步骤由独立线程每两秒续租十秒；
 *       STALE_OWNER / LEASE_BUSY / VERSION_CONFLICT 时丢弃缓存租约重取；</li>
 *   <li>对账先行：任何新 intent 之前先对账全部历史 round；回调只写证据，从不写状态；</li>
 *   <li>限额来自服务器 executionLimits 与租户业务配额的交集（maxRounds / maxTools / maxRepairs /
 *       maxLifetimeMillis），缺省时使用内置默认值；</li>
 *   <li>取消有宽限期（cancelGrace）：超时且物理结果仍未知时以 unknown 收尾，保留隔离，
 *       禁止自动补执行。</li>
 * </ul>
 */
public final class RunCoordinator implements AutoCloseable {

    private static final long RUN_LEASE_MILLIS = 10000;
    private static final long LEASE_RENEW_MILLIS = 2000;

    /**
     * run 持久存储：快照、intent、租约与结算的唯一事实来源。
     */
    private final JdbcRunStore store;

    /**
     * 模型作业存储：可运行扫描、过期判定与 round 对账入队。
     */
    private final ModelJobStore jobs;

    /**
     * 目录存储：查询 task/model pin（固定发布绑定）。
     */
    private final CatalogStore catalog;

    /**
     * 受信身份；为 null 表示多租户模式，逐行从作业取身份。
     */
    private final ProfileRegistry.Identity identity;

    /**
     * 多租户模式下使用的权限集合，构造时排序并包为不可变视图。
     */
    private final java.util.Set<String> permissions;

    /**
     * 资源工具注册表：工具定义查询与受控调用。
     */
    private final ResourceTools resources;

    /**
     * 工具 Broker；为 null 表示未启用工具执行（仅内置文件工具可用）。
     */
    private final ToolBroker broker;

    /**
     * 上下文引擎：上下文组装与 checkpoint。
     */
    private final ContextEngine contexts;

    /**
     * 本协调器实例的租约持有者标识，形如 coordinator-&lt;随机 UUID&gt;。
     */
    private final String owner = "coordinator-" + UUID.randomUUID();

    /**
     * runId 到已获取租约的缓存；续租、释放与终态清理都经此表。
     */
    private final Map<String, JdbcRunStore.Lease> leases = new HashMap<>();

    /**
     * 单线程守护调度器，以 100ms 固定周期驱动 scan。
     */
    private final ScheduledExecutorService executor;

    /**
     * 慢查询或上下文组装期间仍须续租；不得与执行步骤共用单线程。
     */
    private final ScheduledExecutorService leaseExecutor;

    /**
     * 仅续租正在执行的步骤；所有业务写入仍由数据库校验 owner/fence。
     */
    private volatile JdbcRunStore.Lease activeLease;

    private String lastDiagnosticKey;
    private long lastDiagnosticNanos;

    /**
     * 最近一次诊断错误码，仅运维可见，不参与协议。
     */
    private volatile String lastError;

    /**
     * 多租户扫描页偏移；本批不满一页时归零，防止等待中的任务占满首批。
     */
    private long scanOffset;

    /**
     * 控制面存储（等待输入、审批、取消时间），可后置注入。
     */
    private cube.service.harness.store.ControlStore controls;

    /**
     * 文件服务，可后置注入；为 null 表示未启用受控文件访问。
     */
    private cube.service.harness.files.FileService files;

    /**
     * 需要人工审批的工具名集合，来自 control.approval.tools 配置。
     */
    private java.util.Set<String> approvalTools = java.util.Collections.emptySet();

    /**
     * 等待输入/审批的最长毫秒数，范围 1000..86400000。
     */
    private long waitMillis = 300000;

    /**
     * 取消宽限毫秒数，范围 1000..60000；超时且结果未知按 unknown 收尾。
     */
    private long cancelGrace = 10000;

    /**
     * 后置注入控制面存储、文件服务与审批工具配置。
     *
     * @param controls 控制面存储，可为 null 表示不启用等待/审批
     * @param files    文件服务，可为 null 表示不启用文件访问
     * @param p        部署私有配置，读取 control.approval.tools、control.wait.millis、
     *                 control.cancel.grace.millis
     * @return 本协调器实例，便于链式调用
     * @throws IllegalArgumentException 等待或宽严时间越界时抛出，错误码为 INVALID_CONTROL_CONFIG
     */
    public RunCoordinator controls(
            cube.service.harness.store.ControlStore controls,
            cube.service.harness.files.FileService files,
            java.util.Properties p) {
        this.controls = controls;
        this.files = files;
        approvalTools =
                new java.util.HashSet<>(
                        java.util.Arrays.asList(p.getProperty("control.approval.tools", "").split(",")));
        waitMillis = Long.parseLong(p.getProperty("control.wait.millis", "300000"));
        cancelGrace = Long.parseLong(p.getProperty("control.cancel.grace.millis", "10000"));
        if (waitMillis < 1000 || waitMillis > 86400000 || cancelGrace < 1000 || cancelGrace > 60000) {
            throw new IllegalArgumentException("INVALID_CONTROL_CONFIG");
        }
        return this;
    }

    /**
     * 以受信身份构造协调器（不启用工具 Broker）。
     *
     * @param store     run 持久存储
     * @param jobs      模型作业存储
     * @param catalog   目录存储
     * @param identity  受信身份
     * @param resources 资源工具注册表
     */
    public RunCoordinator(
            JdbcRunStore store,
            ModelJobStore jobs,
            CatalogStore catalog,
            ProfileRegistry.Identity identity,
            ResourceTools resources) {
        this(store, jobs, catalog, identity, resources, null);
    }

    /**
     * 以受信身份构造协调器。
     *
     * @param store     run 持久存储
     * @param jobs      模型作业存储
     * @param catalog   目录存储
     * @param identity  受信身份
     * @param resources 资源工具注册表
     * @param broker    工具 Broker，可为 null
     */
    public RunCoordinator(
            JdbcRunStore store,
            ModelJobStore jobs,
            CatalogStore catalog,
            ProfileRegistry.Identity identity,
            ResourceTools resources,
            ToolBroker broker) {
        this(store, jobs, catalog, identity, identity.permissions, resources, broker);
    }

    /**
     * 以显式权限集合构造协调器（不绑定单一身份，多租户模式）。
     *
     * @param store       run 持久存储
     * @param jobs        模型作业存储
     * @param catalog     目录存储
     * @param permissions 逐行步进时使用的权限集合
     * @param resources   资源工具注册表
     * @param broker      工具 Broker，可为 null
     */
    public RunCoordinator(JdbcRunStore store, ModelJobStore jobs, CatalogStore catalog,
                          java.util.Set<String> permissions, ResourceTools resources, ToolBroker broker) {
        this(store, jobs, catalog, null, permissions, resources, broker);
    }

    /**
     * 协调器主构造器：固化依赖并创建上下文引擎与扫描线程。
     *
     * @param store       run 持久存储
     * @param jobs        模型作业存储
     * @param catalog     目录存储
     * @param identity    受信身份，可为 null
     * @param permissions 权限集合，包为不可变视图
     * @param resources   资源工具注册表
     * @param broker      工具 Broker，可为 null
     */
    private RunCoordinator(JdbcRunStore store, ModelJobStore jobs, CatalogStore catalog,
                           ProfileRegistry.Identity identity, java.util.Set<String> permissions,
                           ResourceTools resources, ToolBroker broker) {
        this.broker = broker;
        this.store = store;
        this.jobs = jobs;
        this.catalog = catalog;
        this.identity = identity;
        this.permissions = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(permissions));
        this.resources = resources;
        this.contexts = new ContextEngine(catalog, resources);
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "harness-coordinator");
                            t.setDaemon(true);
                            return t;
                        });
        leaseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harness-coordinator-lease");
            t.setDaemon(true);
            return t;
        });
        leaseExecutor.scheduleWithFixedDelay(this::renewActiveLease,
                LEASE_RENEW_MILLIS, LEASE_RENEW_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * 续租不能复活过期租约，也不能覆盖其他 JVM 已取得的新 fence。
     */
    private void renewActiveLease() {
        JdbcRunStore.Lease lease = activeLease;
        if (lease == null) return;
        try {
            store.renew(lease, RUN_LEASE_MILLIS);
        } catch (StoreException e) {
            if (activeLease == lease) lastError = e.code;
        } catch (Exception e) {
            if (activeLease == lease) lastError = "COORDINATOR_LEASE_RENEW_FAILED";
        }
    }

    /**
     * 只记录状态诊断；相同错误限频，避免持续重试淹没日志。
     */
    private void recordFailure(String run, String code) {
        lastError = code;
        String key = run + ":" + code;
        long now = System.nanoTime();
        if (!key.equals(lastDiagnosticKey) || now - lastDiagnosticNanos >= TimeUnit.SECONDS.toNanos(30)) {
            Logger.w(RunCoordinator.class, "#scan - runId=" + run + " errorCode=" + code);
            lastDiagnosticKey = key;
            lastDiagnosticNanos = now;
        }
    }

    /**
     * 启动扫描循环：立即执行一次，之后每 100ms 一轮。
     */
    public void start() {
        executor.scheduleWithFixedDelay(this::scan, 0, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * 读取最近一次诊断错误码。
     *
     * @return 错误码；无故障时为 null
     */
    public String lastError() {
        return lastError;
    }

    /**
     * 扫描一轮可运行作业并逐步推进。
     *
     * <p>多租户模式按 scanOffset 轮换取页；绑定身份时只处理本主体的作业。
     * 单步失败按类别记录：可重试的租约冲突丢弃缓存租约，其余记 COORDINATOR_STEP_FAILED；
     * 存储不可用记 COORDINATOR_STORE_UNAVAILABLE。
     */
    private void scan() {
        try {
            // 多租户总任务数可超过单租户接单上限，轮换扫描页以免等待中的任务占满首批。
            JSONArray batch = identity == null ? jobs.runnable(256, scanOffset) : jobs.runnable(identity.tenant, 256);
            scanOffset = batch.length() < 256 ? 0 : scanOffset + batch.length();
            for (int i = 0; i < batch.length(); i++) {
                JSONObject row = batch.getJSONObject(i);
                if (identity != null && !identity.subject.equals(row.getString("subject_id"))) {
                    continue;
                }
                try {
                    step(identity == null ? new ProfileRegistry.Identity(row.getString("tenant"),
                            row.getString("subject_id"), permissions) : identity, row.getString("run_id"));
                } catch (StoreException e) {
                    recordFailure(row.getString("run_id"), e.code);
                    if (Arrays.asList("STALE_OWNER", "LEASE_BUSY", "VERSION_CONFLICT").contains(e.code)) {
                        leases.remove(row.getString("run_id"));
                    }
                } catch (Exception e) {
                    recordFailure(row.getString("run_id"), "COORDINATOR_STEP_FAILED");
                }
            }
        } catch (Exception e) {
            recordFailure("scan", "COORDINATOR_STORE_UNAVAILABLE");
        }
    }

    /**
     * 以绑定身份推进指定 run 一步（外部入口）。
     *
     * @param run run 标识
     * @throws Exception             存储或步进失败时向上传播
     * @throws IllegalStateException 未绑定受信身份时抛出，错误信息为 TRUSTED_IDENTITY_REQUIRED
     */
    public synchronized void step(String run) throws Exception {
        if (identity == null) {
            throw new IllegalStateException("TRUSTED_IDENTITY_REQUIRED");
        }
        step(identity, run);
    }

    /**
     * 以指定身份推进 run 一步。
     *
     * @param identity 本步使用的不可变身份
     * @param run      run 标识
     * @throws Exception 存储或步进失败时向上传播
     */
    public synchronized void step(ProfileRegistry.Identity identity, String run) throws Exception {
        activeLease = leases.get(run);
        try {
            new RunStep(identity).step(run);
        } finally {
            activeLease = null;
        }
    }

    // 每一步持有独立的不可变身份；不在共享服务上切换当前用户。

    /**
     * 单步执行体：以固定身份读取快照、对账历史 intent 并按需写入新 intent。
     */
    private final class RunStep {
        /**
         * 本步使用的不可变身份，含租户、主体与权限。
         */
        private final ProfileRegistry.Identity identity;

        /**
         * 以指定身份创建单步执行体。
         *
         * @param identity 本步使用的不可变身份
         */
        RunStep(ProfileRegistry.Identity identity) {
            this.identity = identity;
        }

        /**
         * 读取 run 快照的版本号。
         *
         * @param run run 标识
         * @return 快照 version 字段值
         */
        private long version(String run) {
            return store
                    .readSnapshot(identity.tenant, identity.subject, run)
                    .getLong("version");
        }

        /**
         * 读取 run 快照。
         *
         * @param run run 标识
         * @return 快照 JSON 对象
         */
        private JSONObject state(String run) {
            return store.readSnapshot(identity.tenant, identity.subject, run);
        }

        /**
         * 读取指定类型的 intent 行。
         *
         * @param run  run 标识
         * @param kind intent 类型，round 或 call
         * @return intent 行数组
         */
        private JSONArray intents(String run, String kind) {
            return store.intents(identity.tenant, identity.subject, run, kind);
        }

        /**
         * 确认一个 intent：把结果写入对应 intent 行。
         *
         * @param l      run 租约
         * @param kind   intent 类型，round 或 call
         * @param row    intent 行
         * @param result 确认结果载荷
         */
        private void confirm(JdbcRunStore.Lease l, String kind, JSONObject row, JSONObject result) {
            store.confirmIntent(
                    l,
                    version(l.runId),
                    kind,
                    row.getString("intent_id"),
                    row.getInt("attempt"),
                    row.getLong("origin_fence"),
                    row.getString("intent_hash"),
                    result);
        }

        /**
         * 收尾 run：写入终态快照与执行结算，并释放缓存租约。
         *
         * <p>succeeded 且启用文件服务时附带产物清单；产物投递失败中的非存储类错误
         * 降级为 failed + ARTIFACT_DELIVERY_FAILED，存储类错误（STORE_* 或 FILE_BUSY）
         * 原样抛出以保留隔离语义。
         *
         * @param l          run 租约
         * @param state      目标状态，如 succeeded / failed / cancelled / timed_out
         * @param settlement 执行结算方式，confirmed 或 unknown
         * @param result     结果载荷，可能被产物清单或错误码替换
         */
        private void finish(JdbcRunStore.Lease l, String state, String settlement, JSONObject result) {
            if (state.equals("succeeded") && files != null) {
                try {
                    files.checkDelivery(identity, l.runId);
                    result.put("artifacts", files.artifacts(identity, l.runId));
                } catch (Exception e) {
                    if (e instanceof StoreException
                            && (((StoreException) e).code.startsWith("STORE_")
                            || ((StoreException) e).code.equals("FILE_BUSY"))) {
                        throw (StoreException) e;
                    }
                    state = "failed";
                    result = new JSONObject().put("errorCode", "ARTIFACT_DELIVERY_FAILED");
                }
            }
            store.finish(l, version(l.runId), state, settlement, result, new JSONArray());
            leases.remove(l.runId);
        }

        /**
         * 以 failed/confirmed 加错误码收尾 run。
         *
         * @param l    run 租约
         * @param code 错误码
         */
        private void fail(JdbcRunStore.Lease l, String code) {
            finish(l, "failed", "confirmed", new JSONObject().put("errorCode", code));
        }

        /**
         * 处理单个 call intent：broker 作业对账、内置文件工具直调或资源工具调用。
         *
         * <p>错误分类：可重试的存储类错误（STORE_*、STALE_OWNER、VERSION_CONFLICT、
         * TOOL_QUEUE_FULL、FILE_BUSY）原样上抛；其余归一化为 status=error 加错误码
         * （提供方错误码或 TOOL_OUTPUT_INVALID）。
         *
         * @param l        run 租约
         * @param row      call intent 行
         * @param expired  是否已超过 run 生命周期
         * @param terminal 是否处于终态或取消中（决定按取消收尾）
         * @throws Exception 存储类错误或工具调用异常上抛/转换
         */
        private void tool(JdbcRunStore.Lease l, JSONObject row, boolean expired, boolean terminal)
                throws Exception {
            JSONObject call = new JSONObject(row.getString("payload_json")),
                    result = new JSONObject().put("artifacts", new JSONArray());
            try {
                JSONObject job =
                        broker == null
                                ? null
                                : broker.jobs.find(l.runId, row.getString("intent_id"), row.getInt("attempt"));
                if (job == null && !terminal && !expired && broker != null) {
                    JSONObject definition = resources.executable(identity, l.runId, call.getString("tool"));
                    if (definition != null) {
                        job = broker.enqueue(l, row, definition);
                    }
                }
                if (job != null) {
                    if (job.getInt("actual_finished") == 0) {
                        if (expired || terminal) {
                            broker.jobs.cancel(l, job.getString("job_id"));
                        }
                        if (!terminal && (expired || job.getString("status").equals("unknown"))) {
                            finish(
                                    l,
                                    expired ? "timed_out" : "failed",
                                    "unknown",
                                    new JSONObject()
                                            .put(
                                                    "errorCode", expired ? "RUN_LIFETIME_EXCEEDED" : "TOOL_EXECUTION_UNKNOWN"));
                        }
                        return;
                    }
                    if (job.getString("effect_status").equals("unknown")) {
                        if (!terminal) {
                            finish(
                                    l, "failed", "unknown", new JSONObject().put("errorCode", "TOOL_EFFECT_UNKNOWN"));
                        }
                        return;
                    }
                    confirm(l, "call", row, new JSONObject(job.getString("result_json")));
                    return;
                }
                if (terminal || expired) {
                    confirm(l, "call", row, cube.service.harness.store.ToolJobStore.error("TOOL_CANCELLED"));
                    return;
                }
                JSONObject arguments =
                        StrictJson.object(
                                call.getString("argumentsJson").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String name = call.getString("tool");
                if (files != null
                        && java.util.Arrays.asList("input.read_text", "artifact.write_text").contains(name)) {
                    JSONObject definition = null;
                    JSONArray available = resources.tools(identity, l.runId);
                    for (int i = 0; i < available.length(); i++) {
                        if (available.getJSONObject(i).getString("name").equals(name)) {
                            definition = available.getJSONObject(i);
                        }
                    }
                    if (definition == null) {
                        throw new StoreException("RESOURCE_FORBIDDEN");
                    }
                    Contract.validate(arguments, definition.getJSONObject("parameters"));
                    Object value = files.invoke(l, row.getString("intent_id"), name, arguments);
                    result.put("status", "success").put("dataJson", JdbcRunStore.canonical(value));
                    if (name.equals("artifact.write_text")) {
                        result.put("artifacts", new JSONArray().put(files.reference((JSONObject) value)));
                    }
                    Contract.parse("tool-result", RuntimeSupport.bytes(result));
                    confirm(l, "call", row, result);
                    if (name.equals("input.read_text")) {
                        files.consumed(l.runId, row.getString("intent_id"), arguments.getString("inputId"));
                    }
                    return;
                }
                Object value = resources.invoke(identity, l.runId, name, arguments, l);
                result.put("status", "success").put("dataJson", JdbcRunStore.canonical(value));
                Contract.parse("tool-result", RuntimeSupport.bytes(result));
            } catch (Exception e) {
                if (e instanceof StoreException
                        && Arrays.asList(
                                "STORE_UNAVAILABLE",
                                "STORE_COMMIT_OUTCOME_UNKNOWN",
                                "STALE_OWNER",
                                "VERSION_CONFLICT",
                                "TOOL_QUEUE_FULL",
                                "FILE_BUSY")
                        .contains(((StoreException) e).code)) {
                    throw e;
                }
                result =
                        new JSONObject()
                                .put("status", "error")
                                .put("artifacts", new JSONArray())
                                .put(
                                        "errorCode",
                                        e instanceof StoreException ? ((StoreException) e).code : "TOOL_OUTPUT_INVALID");
            }
            confirm(l, "call", row, result);
        }

        /**
         * 推进 run 一步的主状态机。
         *
         * <p>顺序：读快照与限额 → 等待类状态处理 → 租约获取/续租 → queued 转 running →
         * 历史 round 对账 → 未确认 call 处理 → 终态/取消/过期/修复限额 → 组装上下文与
         * checkpoint → pending 提案（含审批）→ final/need_input → 轮次上限 → 写入新 round intent。
         *
         * @param run run 标识
         * @throws Exception 存储访问或状态转换失败时向上传播
         */
        private void step(String run) throws Exception {
            JSONObject read = store.read(identity.tenant, identity.subject, run),
                    s = read.getJSONObject("snapshot");
            String state = s.getString("state");
            boolean cancelling = state.equals("cancelling");
            boolean terminal =
                    Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(state);
            if (terminal
                    && s.getString("executionSettlement").equals("confirmed")
                    && read.getString("usageSettlement").equals("settled")) {
                return;
            }
            JSONObject pin = catalog.pinned(identity.tenant, identity.subject, run),
                    task = cube.service.harness.runtime.ExecutionProfiles.execution(pin),
                    model = pin.getJSONObject("model");
            JSONObject limits = task.optJSONObject("executionLimits");
            if (limits == null) {
                limits =
                        new JSONObject()
                                .put("maxRounds", 8)
                                .put("maxTools", 32)
                                .put("maxRepairs", 2)
                                .put("maxLifetimeMillis", 300000);
            }
            boolean expired =
                    jobs.expired(identity.tenant, identity.subject, run, limits.getLong("maxLifetimeMillis"));
            if (Arrays.asList("waiting_input", "waiting_approval").contains(state) && !expired) {
                JSONObject wait =
                        controls == null ? null : controls.current(identity.tenant, identity.subject, run);
                if (wait == null || wait.getLong("expiresAt") > controls.now()) {
                    return;
                }
                expired = true;
            }
            JdbcRunStore.Lease l = leases.get(run);
            if (l == null) {
                l = store.acquire(identity.tenant, identity.subject, run, owner, s.getLong("version"), RUN_LEASE_MILLIS);
                leases.put(run, l);
            } else {
                store.renew(l, RUN_LEASE_MILLIS);
            }
            activeLease = l;
            if (state.equals("queued")) {
                store.transition(l, version(run), "running", new JSONObject());
            }
            if (Arrays.asList("waiting_input", "waiting_approval").contains(state)) {
                if (expired) {
                    finish(
                            l,
                            "timed_out",
                            "confirmed",
                            new JSONObject().put("errorCode", "RUN_LIFETIME_EXCEEDED"));
                } else {
                    store.release(l, version(run));
                    leases.remove(run);
                }
                return;
            }
            JSONArray rounds = intents(run, "round"), calls = intents(run, "call");
            int repairs = 0;
            // 任何新 intent 之前先对账全部历史 round；回调只写证据，从不写状态。
            for (int i = 0; i < rounds.length(); i++) {
                JSONObject r = rounds.getJSONObject(i), j = jobs.enqueue(l, r, model);
                if (!j.isNull("outcome_json")) {
                    JSONObject o = new JSONObject(j.getString("outcome_json"));
                    repairs += Math.max(0, o.getJSONArray("audit").length() - 1);
                }
                if (!r.getString("status").equals("confirmed")) {
                    if (j.getInt("actual_finished") == 0) {
                        if (expired || cancelling || terminal) {
                            jobs.cancel(l, j.getString("job_id"));
                            if (!terminal
                                    && (expired
                                    || (cancelling
                                    && controls != null
                                    && controls.now() - controls.cancelTime(run) >= cancelGrace))) {
                                finish(
                                        l,
                                        expired ? "timed_out" : "cancelled",
                                        "unknown",
                                        new JSONObject()
                                                .put(
                                                        "errorCode",
                                                        expired ? "RUN_LIFETIME_EXCEEDED" : "CANCEL_OUTCOME_UNKNOWN"));
                            }
                        }
                        return;
                    }
                    JSONObject o = new JSONObject(j.getString("outcome_json"));
                    if (!o.getString("status").equals("success")) {
                        // 保存已校验的失败证据；失败的 round 不再构建下一轮模型上下文。
                        confirm(l, "round", r, new JSONObject().put("errorCode", o.getString("errorCode")));
                        if (o.has("usage")) {
                            settle(l, r, j, o);
                        }
                        if (!terminal && !cancelling) {
                            finish(
                                    l,
                                    "failed",
                                    "confirmed",
                                    new JSONObject().put("errorCode", o.getString("errorCode")));
                        }
                        return;
                    }
                    confirm(l, "round", r, o.getJSONObject("decision"));
                    // The confirmation committed. Continue reconciliation in this step instead
                    // of scanning and re-reading the same run just to settle usage and finish.
                    r.put("status", "confirmed").put("result_json", o.getJSONObject("decision").toString());
                }
                JSONObject o = new JSONObject(j.getString("outcome_json"));
                if (o.has("usage")) {
                    settle(l, r, j, o);
                }
            }
            for (int i = 0; i < calls.length(); i++) {
                if (!calls.getJSONObject(i).getString("status").equals("confirmed")) {
                    tool(l, calls.getJSONObject(i), expired, terminal || cancelling);
                    if (cancelling
                            && controls != null
                            && !state(run).getString("executionSettlement").equals("confirmed")
                            && controls.now() - controls.cancelTime(run) >= cancelGrace) {
                        finish(
                                l,
                                "cancelled",
                                "unknown",
                                new JSONObject().put("errorCode", "CANCEL_OUTCOME_UNKNOWN"));
                    }
                    return;
                }
            }
            if (terminal) {
                store.release(l, version(run));
                leases.remove(run);
                return;
            }
            if (cancelling) {
                finish(l, "cancelled", "confirmed", new JSONObject().put("errorCode", "CANCELLED"));
                return;
            }
            if (expired) {
                finish(
                        l, "timed_out", "confirmed", new JSONObject().put("errorCode", "RUN_LIFETIME_EXCEEDED"));
                return;
            }
            if (repairs > limits.getInt("maxRepairs")) {
                fail(l, "REPAIR_LIMIT_EXCEEDED");
                return;
            }
            if (rounds.length() > 0) {
                JSONObject last = new JSONObject(rounds.getJSONObject(rounds.length() - 1).getString("result_json"));
                if (last.getString("kind").equals("final")) {
                    // No further model input is needed. Keep the authorization check that
                    // context assembly would perform, without rebuilding/checkpointing it.
                    if (!identity.permissions.containsAll(RuntimeSupport.strings(pin.getJSONArray("permissions")))) {
                        fail(l, "CONTEXT_AUTHORIZATION_CHANGED");
                        return;
                    }
                    finish(l, "succeeded", "confirmed", last);
                    return;
                }
            }
            JSONObject snapshot;
            try {
                snapshot = contexts.assemble(identity, run, new JSONArray());
            } catch (StoreException e) {
                fail(l, e.code.split(":")[0]);
                return;
            }
            contexts.checkpoint(l, version(run), snapshot);
            JSONArray pending = snapshot.getJSONArray("pending");
            if (pending.length() > 0) {
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject p = pending.getJSONObject(i);
                    if (!p.getString("kind").equals("proposal")) {
                        throw new StoreException("CONTEXT_PENDING_EXECUTION");
                    }
                    if (calls.length() + pending.length() > limits.getInt("maxTools")) {
                        fail(l, "TOOL_LIMIT_EXCEEDED");
                        return;
                    }
                    JSONObject call = p.getJSONObject("call");
                    if (controls != null && approvalTools.contains(call.getString("tool"))) {
                        JSONObject args =
                                StrictJson.object(
                                        call.getString("argumentsJson")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        JSONArray available = resources.tools(identity, run);
                        JSONObject definition = null;
                        for (int t = 0; t < available.length(); t++) {
                            if (available.getJSONObject(t).getString("name").equals(call.getString("tool"))) {
                                definition = available.getJSONObject(t);
                            }
                        }
                        if (definition == null) {
                            throw new StoreException("RESOURCE_FORBIDDEN");
                        }
                        Contract.validate(args, definition.getJSONObject("parameters"));
                        String binding =
                                JdbcRunStore.hash(
                                        new JSONObject().put("call", call).put("definition", definition).put("pin", pin));
                        if (!controls.handled(run, "approval", binding)) {
                            controls.waitFor(
                                    l,
                                    version(run),
                                    "approval",
                                    new JSONObject()
                                            .put("binding", binding)
                                            .put("tool", call.getString("tool"))
                                            .put("arguments", args)
                                            .put("argumentsHash", JdbcRunStore.hash(args))
                                            .put("configDigest", JdbcRunStore.hash(pin)),
                                    Math.min(
                                            jobs.created(l) + limits.getLong("maxLifetimeMillis"),
                                            controls.now() + waitMillis));
                            store.release(l, version(run));
                            leases.remove(run);
                            return;
                        }
                    }
                    store.intent(l, version(run), "call", call.getString("proposalId"), 1, call, 0, 0);
                }
                return;
            }
            if (rounds.length() > 0) {
                // round ID 为定宽格式，持久顺序与字典序一致。
                JSONObject last =
                        new JSONObject(rounds.getJSONObject(rounds.length() - 1).getString("result_json"));
                if (last.getString("kind").equals("need_input")) {
                    String binding = rounds.getJSONObject(rounds.length() - 1).getString("intent_id");
                    if (controls != null && controls.handled(run, "input", binding)) {
                        // 持久化的恢复事件将作为新的用户消息附于下方。
                    } else {
                        if (controls != null) {
                            controls.waitFor(
                                    l,
                                    version(run),
                                    "input",
                                    new JSONObject()
                                            .put("binding", binding)
                                            .put("question", last.getString("question"))
                                            .put("checkpointDigest", JdbcRunStore.hash(snapshot)),
                                    Math.min(
                                            jobs.created(l) + limits.getLong("maxLifetimeMillis"),
                                            controls.now() + waitMillis));
                        } else {
                            store.transition(l, version(run), "waiting_input", last);
                        }
                        store.release(l, version(run));
                        leases.remove(run);
                        return;
                    }
                }
            }
            if (rounds.length() >= limits.getInt("maxRounds")) {
                fail(l, "ROUND_LIMIT_EXCEEDED");
                return;
            }
            JSONObject request = ContextEngine.modelInput(snapshot);
            request.put("maxRepairs", Math.max(0, Math.min(1, limits.getInt("maxRepairs") - repairs)));
            String round = String.format(java.util.Locale.ROOT, "round-%04d", rounds.length() + 1);
            store.intent(
                    l,
                    version(run),
                    "round",
                    round,
                    1,
                    new JSONObject().put("contextDigest", JdbcRunStore.hash(snapshot)).put("request", request),
                    snapshot.getLong("inputTokens"),
                    snapshot.getLong("outputReserve"));
            // Queue immediately after the intent commits; a crash between the two
            // transactions is still recovered by the historical-round loop above.
            JSONArray durableRounds = intents(run, "round");
            jobs.enqueue(l, durableRounds.getJSONObject(durableRounds.length() - 1), model);
        }

        /**
         * 结算一轮模型推理的用量账。
         *
         * @param l run 租约
         * @param r round intent 行
         * @param j 对应模型作业行（证据来源）
         * @param o 含 usage 字段的 round 结果
         */
        private void settle(JdbcRunStore.Lease l, JSONObject r, JSONObject j, JSONObject o) {
            JSONObject usage = o.getJSONObject("usage");
            store.settleUsage(
                    l,
                    version(l.runId),
                    new JSONObject()
                            .put("roundId", r.getString("intent_id"))
                            .put("attempt", r.getInt("attempt"))
                            .put("evidenceId", j.getString("job_id"))
                            .put("expectedRevision", 0)
                            .put("input", usage.getLong("input"))
                            .put("output", usage.getLong("output")));
        }

    }

    /**
     * 关闭协调器：停止扫描与续租线程，分别等待至多 3 秒退出。
     */
    @Override
    public void close() {
        executor.shutdownNow();
        activeLease = null;
        leaseExecutor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
            leaseExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
