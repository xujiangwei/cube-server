/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.protocol.Contract;
import cube.service.harness.protocol.StrictJson;
import cube.service.harness.runtime.ProfileRegistry;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.JdbcRunStore;
import cube.service.harness.store.StoreException;
import cube.service.harness.store.ToolJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;

import static cube.service.harness.store.JdbcRunStore.canonical;
import static cube.service.harness.store.JdbcRunStore.hash;

/**
 * 服务端只读工具 Broker。
 *
 * <p>子进程代码（SKILL 脚本、runner）既拿不到授权（grant），也拿不到后端 URL 与凭证；
 * 一切上游访问都发生在服务端 JVM 内，由本类经受信提供方完成。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>每次调用以 HMAC-SHA256 授权（grant）绑定 run/call/attempt/release、参数摘要、
 *       租户/主体、原 fence、worker/runtime，TTL 60 秒；受理与上游派发前各复核一次；</li>
 *   <li>提供方查询经有界线程池（2 线程 + 32 队列）与全局信号量（34）双重封顶，
 *       池满以 {@code BROKER_BUSY} 拒绝；</li>
 *   <li>结果先落库（{@code brokerFinish}）再释放信号量；DB 提交失败只重试提交内存中的
 *       同一结果，绝不重复上游操作；</li>
 *   <li>构造时把本节点原有 dispatched 窗口转为 unknown，避免 JVM 重启后重发可能已发送的
 *       上游请求；多个 Java 节点必须使用不同且重启后稳定的 {@code broker.node.id}；</li>
 *   <li>普通工具查询与 sandbox worker 执行走两条互斥路径，作业不可跨类型领取。</li>
 * </ul>
 */
public final class ToolBroker implements AutoCloseable {

    /**
     * 持久化作业存储；分发器与 Broker 共享同一账本。
     */
    public final ToolJobStore jobs;

    /**
     * 受信身份；为 null 表示不绑定单一身份（按作业行判定租户/主体）。
     */
    private final ProfileRegistry.Identity identity;

    /**
     * 派发前校验用的权限集合，构造时排序并包为不可变视图。
     */
    private final Set<String> permissions;

    /**
     * 授权签名密钥（runner.grant.key），至少 32 字符；缺失即拒绝启动。
     */
    private final String key;

    /**
     * 本 Broker 节点标识（broker.node.id），须稳定且部署实例唯一。
     */
    private final String node;

    /**
     * 部署侧提供方注册表，按 operation 路由作业。
     */
    private final QueryProviders providers;

    /**
     * 文件服务，可后置注入；为 null 表示未启用受控文件访问。
     */
    private cube.service.harness.files.FileService files;

    /**
     * 后置注入文件服务。
     *
     * @param value 文件服务实例；传 null 表示关闭文件访问
     * @return 本 Broker 实例，便于链式调用
     */
    public ToolBroker files(cube.service.harness.files.FileService value) {
        files = value;
        return this;
    }

    /**
     * 提供方查询线程池：固定 2 线程 + 32 容量有界队列，拒绝策略为 AbortPolicy。
     */
    private final ThreadPoolExecutor queries;

    /**
     * 单线程守护调度器，以 200ms 固定周期驱动 flush 对账。
     */
    private final ScheduledExecutorService reconciliation =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "harness-broker-reconcile");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * 全局执行/待提交信号量（34 = 2 个查询线程 + 32 个排队项）。
     */
    private final Semaphore permits = new Semaphore(34);

    /**
     * 恢复查询线程池：单线程 + 8 容量队列，仅承担独立执行的清理对账。
     */
    private final ExecutorService recoveryQueries =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    r -> {
                        Thread t = new Thread(r, "harness-provider-recovery");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    /**
     * 最近一次恢复扫描时间（毫秒）；5 秒内不重复扫描。
     */
    private long lastRecovery;

    /**
     * 正在恢复中的 job_id 集合，防止同一作业被重复投递到恢复池。
     */
    private final Set<String> recovering = ConcurrentHashMap.newKeySet();

    /**
     * 已完成待落库的查询结果：job_id → {response, unknown}。
     */
    private final Map<String, JSONObject> pending = new ConcurrentHashMap<>();

    /**
     * 以受信身份构造 Broker。
     *
     * @param jobs     持久化作业存储
     * @param identity 受信身份，其权限集合参与派发校验
     * @param p        部署私有配置（runner.grant.key、broker.node.id 等）
     * @throws Exception 提供方注册失败或配置非法时向上传播
     */
    public ToolBroker(ToolJobStore jobs, ProfileRegistry.Identity identity, Properties p)
            throws Exception {
        this(jobs, identity, identity.permissions, p);
    }

    /**
     * 以显式权限集合构造 Broker（不绑定单一身份）。
     *
     * @param jobs        持久化作业存储
     * @param permissions 派发校验用的权限集合
     * @param p           部署私有配置
     * @throws Exception 提供方注册失败或配置非法时向上传播
     */
    public ToolBroker(ToolJobStore jobs, Set<String> permissions, Properties p) throws Exception {
        this(jobs, null, permissions, p);
    }

    /**
     * Broker 主构造器：完成契约自检、密钥校验、提供方注册与恢复窗口转换。
     *
     * @param jobs        持久化作业存储
     * @param identity    受信身份，可为 null
     * @param permissions 派发校验用的权限集合
     * @param p           部署私有配置
     * @throws Exception                提供方注册失败时向上传播
     * @throws IllegalArgumentException 授权密钥不足 32 字符时抛出，
     *                                  错误码为 RUNNER_GRANT_KEY_REQUIRED
     */
    private ToolBroker(ToolJobStore jobs, ProfileRegistry.Identity identity, Set<String> permissions,
                       Properties p) throws Exception {
        ToolProtocol.verify();
        this.jobs = jobs;
        this.identity = identity;
        this.permissions = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(permissions));
        key = p.getProperty("runner.grant.key", "");
        node = RuntimeSupport.id(p.getProperty("broker.node.id", ""));
        if (key.length() < 32) {
            throw new IllegalArgumentException("RUNNER_GRANT_KEY_REQUIRED");
        }
        providers = new QueryProviders(p);
        queries =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<Runnable>(32),
                        r -> {
                            Thread t = new Thread(r, "harness-broker-query");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        jobs.brokerRestart(node);
        reconciliation.scheduleWithFixedDelay(this::flush, 0, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * 对授权内容计算 HMAC-SHA256 签名。
     *
     * @param value 待签名内容（授权 claims 的规范化 JSON 文本）
     * @return 小写十六进制签名字符串
     * @throws IllegalStateException 密钥或算法异常时包装抛出
     */
    private String sign(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : m.doFinal(value.getBytes(StandardCharsets.UTF_8))) {
                out.append(String.format("%02x", b & 255));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // 仅用于离线 N08 夹具的兼容入口。

    /**
     * 返回 MockConsole 测试提供方的参数 schema，仅供离线夹具兼容使用。
     *
     * @return 参数 schema 对象
     */
    public static JSONObject parameters() {
        return MockQueryProvider.parameters();
    }

    /**
     * 返回 MockConsole 测试提供方的输出 schema，仅供离线夹具兼容使用。
     *
     * @return 输出 schema 对象
     */
    public static JSONObject outputSchema() {
        return MockQueryProvider.outputSchema();
    }

    /**
     * 按作业行解析其登记的提供方。
     *
     * @param job 作业行，须含 definition_json，其中 tool.brokerOperation 指向已注册 operation
     * @return 对应的 QueryProvider 实例
     * @throws StoreException 该 operation 未注册时由注册表抛出，
     *                        错误码为 TOOL_PROVIDER_UNAVAILABLE
     */
    private QueryProvider provider(JSONObject job) {
        return providers.get(
                new JSONObject(job.getString("definition_json"))
                        .getJSONObject("tool")
                        .getString("brokerOperation"));
    }

    /**
     * 对工具参数执行双重校验：先按提供方 inputSchema 做契约校验，再走提供方自定义校验。
     *
     * @param args     原始参数对象，会被 Contract.validate 就地校验
     * @param provider 目标提供方，其 inputSchema 为校验依据
     */
    private void arguments(JSONObject args, QueryProvider provider) {
        Contract.validate(args, provider.inputSchema());
        provider.validate(args);
    }

    /**
     * 登记一次工具调用作业：对工具定义做全部静态校验后写入持久队列。
     *
     * <p>校验项：提供方类型与 runner 类型互斥一致、runtime 模式/认证/能力、
     * effect 为 read-only、权限在授予集合内、工具 schema 与提供方注册 schema 逐字节一致。
     *
     * @param l          run 租约，绑定作业的租户/主体/run
     * @param intent     call intent 行，其 payload_json 含 argumentsJson
     * @param definition 工具定义（tool、runtime、parameters、resultSchema）
     * @return 登记后的作业行
     * @throws StoreException 出现以下情况时抛出：PROVIDER_EXECUTION_OWNER_INVALID
     *                        （sandbox 声明与提供方类型不一致）、TOOL_PROFILE_FORBIDDEN
     *                        （runtime 模式、认证、能力、effect 或权限不满足）、
     *                        PROVIDER_PROTOCOL_MISMATCH（runnerType 不一致）、
     *                        TOOL_REGISTRY_SCHEMA_MISMATCH（schema 与注册不一致）
     */
    public JSONObject enqueue(JdbcRunStore.Lease l, JSONObject intent, JSONObject definition) {
        JSONObject tool = definition.getJSONObject("tool"),
                runtime = definition.getJSONObject("runtime");
        QueryProvider provider = providers.get(tool.getString("brokerOperation"));
        if ((ToolProtocol.SANDBOX.equals(provider.runnerType())) != (provider instanceof WorkerExecutionProvider)) {
            throw new StoreException("PROVIDER_EXECUTION_OWNER_INVALID");
        }
        if (!runtime.getString("mode").equals("trusted-python-v1")
                || !runtime.getString("certification").equals("offline-trusted-runner")
                || !RuntimeSupport.strings(runtime.getJSONArray("capabilities")).contains("tool.execute")
                || !tool.getString("effect").equals("read-only")
                || !tool.getString("permission").equals(provider.permission())
                || !permissions.contains(provider.permission())) {
            throw new StoreException("TOOL_PROFILE_FORBIDDEN");
        }
        if (!tool.getString("runnerType").equals(provider.runnerType())) {
            throw new StoreException("PROVIDER_PROTOCOL_MISMATCH");
        }
        if (!canonical(provider.inputSchema()).equals(canonical(definition.getJSONObject("parameters")))
                || !canonical(provider.resultSchema())
                .equals(canonical(definition.getJSONObject("resultSchema")))) {
            throw new StoreException("TOOL_REGISTRY_SCHEMA_MISMATCH");
        }
        JSONObject args =
                StrictJson.object(
                        new JSONObject(intent.getString("payload_json"))
                                .getString("argumentsJson")
                                .getBytes(StandardCharsets.UTF_8));
        arguments(args, provider);
        return jobs.enqueue(l, intent, definition);
    }

    /**
     * 组装下发 runner 的作业线报，并附带 HMAC 签名的执行授权（grant）。
     *
     * <p>线报内仅含作业标识、参数摘要与执行配额（超时上限 30s、输出上限 32 KiB、
     * 工作区上限 10 MiB/256 项且与 runtime 配额取小），不含后端地址与凭证；
     * grant claims 的 TTL 为 60 秒。
     *
     * @param j        作业行
     * @param worker   目标 workerId
     * @param runtime  目标 runtimeId
     * @param instance 目标实例标识
     * @return 作业线报对象，末位带 grant（bodyJson + signature）
     */
    public JSONObject wire(JSONObject j, String worker, String runtime, String instance) {
        JSONObject d = new JSONObject(j.getString("definition_json"));
        JSONObject w =
                new JSONObject()
                        .put("runnerType", provider(j).runnerType())
                        .put("jobId", j.getString("job_id"))
                        .put("runId", j.getString("run_id"))
                        .put("callId", j.getString("call_id"))
                        .put("attempt", j.getInt("attempt"))
                        .put("originFence", j.getLong("origin_fence"))
                        .put("intentHash", j.getString("intent_hash"))
                        .put("tenantId", j.getString("tenant"))
                        .put("subjectId", j.getString("subject_id"))
                        .put("release", j.getString("release_digest"))
                        .put("tool", j.getString("tool_name"))
                        .put("argumentsJson", j.getString("arguments_json"))
                        .put("argumentsHash", j.getString("arguments_hash"))
                        .put("bundleJson", d.getString("bundleJson"))
                        .put("workerId", worker)
                        .put("instanceId", instance)
                        .put("runtimeId", runtime)
                        .put(
                                "timeoutMillis",
                                Math.min(
                                        30000,
                                        d.optInt("timeoutMillis", provider(j).independentExecution() ? 30000 : 10000)))
                        .put("maxOutputBytes", 32768)
                        .put(
                                "maxWorkspaceBytes",
                                Math.min(10485760, d.getJSONObject("runtime").getLong("maxWorkspaceBytes")))
                        .put(
                                "maxWorkspaceFiles",
                                Math.min(256, d.getJSONObject("runtime").getInt("maxWorkspaceFiles")));
        if (provider(j) instanceof WorkerExecutionProvider) {
            w.put("executionProfile", ((WorkerExecutionProvider) provider(j)).executionProfile());
        }
        w.put("jobHash", hash(w));
        JSONObject a = new JSONObject(j.getString("arguments_json"));
        JSONObject claims =
                new JSONObject()
                        .put("jobId", j.getString("job_id"))
                        .put("jobHash", w.getString("jobHash"))
                        .put("runId", j.getString("run_id"))
                        .put("callId", j.getString("call_id"))
                        .put("attempt", j.getInt("attempt"))
                        .put("release", j.getString("release_digest"))
                        .put("tool", j.getString("tool_name"))
                        .put("argumentsHash", j.getString("arguments_hash"))
                        .put("tenantId", j.getString("tenant"))
                        .put("subjectId", j.getString("subject_id"))
                        .put("originFence", j.getLong("origin_fence"))
                        .put("workerId", worker)
                        .put("runtimeId", runtime)
                        .put("expiresAt", System.currentTimeMillis() + 60000);
        String body = canonical(claims);
        w.put("grant", new JSONObject().put("bodyJson", body).put("signature", sign(body)));
        ToolProtocol.parse("runner-job", RuntimeSupport.bytes(w));
        return w;
    }

    /**
     * 复核 runner 回传的授权：与线报内授权一致、签名有效、未过期且作业仍有效。
     *
     * @param j     作业行
     * @param grant runner 回传的授权对象（bodyJson + signature）
     * @throws StoreException GRANT_INVALID（与线报授权不一致或签名不符）、
     *                        GRANT_REVOKED_OR_STALE（已过期、fence 不符、身份或权限不符、
     *                        已取消/吊销，或 run 快照状态不是 running）
     */
    public void authorize(JSONObject j, JSONObject grant) {
        JSONObject stored = new JSONObject(j.getString("wire_json")).getJSONObject("grant");
        if (!canonical(stored).equals(canonical(grant))
                || !java.security.MessageDigest.isEqual(
                sign(grant.getString("bodyJson")).getBytes(StandardCharsets.US_ASCII),
                grant.getString("signature").getBytes(StandardCharsets.US_ASCII))) {
            throw new StoreException("GRANT_INVALID");
        }
        JSONObject claims =
                StrictJson.object(grant.getString("bodyJson").getBytes(StandardCharsets.UTF_8));
        JSONObject run = jobs.currentRun(j);
        if (claims.getLong("expiresAt") < System.currentTimeMillis()
                || run.getLong("fence") != j.getLong("origin_fence")
                || (identity != null && !identity.tenant.equals(j.getString("tenant")))
                || (identity != null && !identity.subject.equals(j.getString("subject_id")))
                || !permissions.contains(provider(j).permission())
                || j.getInt("cancel_requested") != 0
                || jobs.revoked(j)
                || !run.getJSONObject("snapshot").getString("state").equals("running")) {
            throw new StoreException("GRANT_REVOKED_OR_STALE");
        }
        arguments(new JSONObject(j.getString("arguments_json")), provider(j));
    }

    /**
     * 通用查询入口：按目标与字段选择封装参数后转交 {@link #invokeArguments}。
     *
     * @param j               作业行
     * @param grant           执行授权
     * @param selectedTargets 选定的目标集合
     * @param selectedFields  选定的字段集合
     * @return 调用结果；已完成为 finished + dataJson，未决为 pending
     */
    public JSONObject query(
            JSONObject j, JSONObject grant, JSONArray selectedTargets, JSONArray selectedFields) {
        return invokeArguments(
                j, grant, new JSONObject().put("targets", selectedTargets).put("fields", selectedFields));
    }

    /**
     * 参数化调用入口：复核授权后按参数摘要查持久账本，未决则经有界队列触发一次查询。
     *
     * <p>已有 finished 记录直接回放；已有 unknown 记录以 BROKER_OUTCOME_UNKNOWN 拒绝；
     * 全新作业先占信号量再入队，队列等待后、触达上游前会再次复核授权。
     *
     * @param j     作业行
     * @param grant 执行授权
     * @param args  本次调用参数，其摘要须与作业 arguments_hash 一致
     * @return 已完成时返回 finished + dataJson；进行中返回 pending
     * @throws StoreException PYTHON_WORKER_EXECUTION_REQUIRED（sandbox 作业必须走 worker 路径）、
     *                        TOOL_NOT_ACCEPTED（作业未被受理）、BROKER_ARGUMENT_CONFLICT
     *                        （参数摘要不一致）、BROKER_BUSY（信号量耗尽）、
     *                        BROKER_OUTCOME_UNKNOWN（已存在未知结果）；授权失败见
     *                        {@link #authorize}
     */
    public JSONObject invokeArguments(JSONObject j, JSONObject grant, JSONObject args) {
        if (provider(j) instanceof WorkerExecutionProvider) {
            throw new StoreException("PYTHON_WORKER_EXECUTION_REQUIRED");
        }
        authorize(j, grant);
        if (!j.getString("status").equals("accepted")) {
            throw new StoreException("TOOL_NOT_ACCEPTED");
        }
        if (!hash(args).equals(j.getString("arguments_hash"))) {
            throw new StoreException("BROKER_ARGUMENT_CONFLICT");
        }
        JSONObject old = jobs.brokerExchange(j.getString("job_id"), hash(args), node, false);
        if (old == null) {
            if (!permits.tryAcquire()) {
                throw new StoreException("BROKER_BUSY");
            }
            try {
                old = jobs.brokerExchange(j.getString("job_id"), hash(args), node, true);
                if (old.getBoolean("new")) {
                    queries.execute(() -> invoke(j, args));
                } else {
                    permits.release();
                }
            } catch (RuntimeException e) {
                permits.release();
                throw e;
            }
        }
        if (old.getString("status").equals("unknown")) {
            throw new StoreException("BROKER_OUTCOME_UNKNOWN");
        }
        if (old.getString("status").equals("finished")) {
            return new JSONObject()
                    .put("status", "finished")
                    .put("dataJson", old.getString("response_json"));
        }
        return new JSONObject().put("status", "pending");
    }

    /**
     * 查询线程体：复核授权后调用提供方完成一次上游查询，结果入 pending 并触发落库。
     *
     * <p>授权在队列等待之后、触达上游之前再次复核；非 StoreException 一律记为 unknown，
     * 不宣称未观察到的结果。
     *
     * @param j    作业行
     * @param args 调用参数
     */
    private void invoke(JSONObject j, JSONObject args) {
        JSONObject response;
        boolean unknown = false;
        try {
            // 队列等待后、触达上游前再次复核吊销状态。
            authorize(
                    jobs.job(j.getString("job_id")),
                    new JSONObject(j.getString("wire_json")).getJSONObject("grant"));
            response = provider(j).query(new ProviderInvocation(j, jobs, files), args);
            validateData(j, response);
        } catch (Exception e) {
            unknown = !(e instanceof StoreException);
            response =
                    ToolJobStore.error(
                            e instanceof StoreException ? ((StoreException) e).code : "BROKER_OUTCOME_UNKNOWN");
        }
        pending.put(
                j.getString("job_id"), new JSONObject().put("response", response).put("unknown", unknown));
        flush();
    }

    /**
     * 解析作业对应的 sandbox worker 提供方。
     *
     * @param j 作业行
     * @return sandbox 类型的 WorkerExecutionProvider
     * @throws StoreException 提供方不是 WorkerExecutionProvider 或 runnerType 不是 sandbox 时抛出，
     *                        错误码为 PROVIDER_EXECUTION_OWNER_INVALID
     */
    private WorkerExecutionProvider workerProvider(JSONObject j) {
        QueryProvider p = provider(j);
        if (!(p instanceof WorkerExecutionProvider) || !ToolProtocol.SANDBOX.equals(p.runnerType())) {
            throw new StoreException("PROVIDER_EXECUTION_OWNER_INVALID");
        }
        return (WorkerExecutionProvider) p;
    }

    /**
     * 返回 worker 作业在持久账本中的归属键。
     *
     * @param j 作业行
     * @return 归属键，形如 {@code python:<runtimeId>}
     */
    private String workerOwner(JSONObject j) {
        return "python:" + j.getString("runtime_id");
    }

    /**
     * 持久化的"仅一次"执行许可；重放防止由 Python journal 负责。
     *
     * @param j     作业行
     * @param grant 执行授权
     * @return status 为 execute 表示许可首次下发可执行；为 finished 表示已有持久结果可回放
     * @throws StoreException PROVIDER_EXECUTION_OWNER_INVALID（非 sandbox worker 提供方）、
     *                        TOOL_NOT_ACCEPTED（作业未被受理）；授权失败见 {@link #authorize}
     */
    public JSONObject prepareWorker(JSONObject j, JSONObject grant) {
        workerProvider(j);
        authorize(j, grant);
        if (!j.getString("status").equals("accepted")) {
            throw new StoreException("TOOL_NOT_ACCEPTED");
        }
        JSONObject saved =
                jobs.brokerExchange(
                        j.getString("job_id"), j.getString("arguments_hash"), workerOwner(j), true);
        return new JSONObject()
                .put("status", saved.getString("status").equals("dispatched") ? "execute" : "finished");
    }

    /**
     * 受理 worker 的清理/结果回执；取消、过期或 JVM 重启后回执仍然可受理。
     *
     * <p>已有 finished 记录时按回执摘要对账（不一致即冲突）；否则转换输出、校验并落库。
     * 回执携带错误码时直接以该错误码收尾，不再调用提供方。
     *
     * @param j       作业行
     * @param receipt worker 回执，经 sandbox-receipt 契约校验
     * @return status 为 finished 并附带归一化后的 dataJson
     * @throws StoreException PROVIDER_EXECUTION_OWNER_INVALID（非 sandbox worker 提供方）、
     *                        TOOL_NOT_ACCEPTED（作业未被受理）、TOOL_RESULT_CONFLICT
     *                        （回执摘要与已存结果不一致）、SANDBOX_NOT_DISPATCHED
     *                        （未派发却带回执输出）；授权失败见 {@link #authorize}
     * @throws Exception      回执解析或提供方处理失败时向上传播
     */
    public synchronized JSONObject completeWorker(JSONObject j, JSONObject receipt) throws Exception {
        WorkerExecutionProvider p = workerProvider(j);
        ToolProtocol.parse("sandbox-receipt", RuntimeSupport.bytes(receipt));
        String receiptHash = hash(receipt);
        JSONObject saved =
                jobs.brokerExchange(
                        j.getString("job_id"), j.getString("arguments_hash"), workerOwner(j), false);
        if (saved != null && saved.getString("status").equals("finished")) {
            JSONObject wrapper = new JSONObject(saved.getString("response_json"));
            if (!receiptHash.equals(wrapper.getString("receiptHash"))) {
                throw new StoreException("TOOL_RESULT_CONFLICT");
            }
            return new JSONObject()
                    .put("status", "finished")
                    .put("dataJson", canonical(wrapper.getJSONObject("data")));
        }
        if (receipt.has("output") && saved == null) {
            throw new StoreException("SANDBOX_NOT_DISPATCHED");
        }
        if (saved == null) {
            jobs.brokerExchange(
                    j.getString("job_id"), j.getString("arguments_hash"), workerOwner(j), true);
        }
        JSONObject data;
        if (receipt.has("errorCode")) {
            data = ToolJobStore.error(receipt.getString("errorCode"));
        } else {
            try {
                data = p.complete(new ProviderInvocation(j, jobs, files), receipt.getJSONObject("output"));
                validateData(j, data);
            } catch (StoreException e) {
                data = ToolJobStore.error(e.code);
            }
        }
        jobs.workerFinish(
                j.getString("job_id"),
                workerOwner(j),
                new JSONObject().put("receiptHash", receiptHash).put("data", data));
        return new JSONObject().put("status", "finished").put("dataJson", canonical(data));
    }

    /**
     * 周期对账：先做恢复扫描，再把 pending 中的查询结果提交落库。
     *
     * <p>DB 提交失败只重试提交内存中的同一结果，绝不重复上游操作；结果与信号量成对释放。
     */
    private synchronized void flush() {
        try {
            recoverExternal();
        } catch (RuntimeException ignored) {
        }
        for (Map.Entry<String, JSONObject> e : pending.entrySet()) {
            try {
                jobs.brokerFinish(
                        e.getKey(),
                        node,
                        e.getValue().getJSONObject("response"),
                        e.getValue().getBoolean("unknown"));
                if (pending.remove(e.getKey(), e.getValue())) {
                    permits.release();
                }
            } catch (RuntimeException ignored) {
                /* 仅重试 DB 提交，不重复上游操作。 */
            }
        }
    }

    /**
     * 恢复扫描：最多每 5 秒一次，处理本节点未决 broker 作业（最多 8 条）。
     *
     * <p>仅独立执行的提供方参与恢复；已在恢复中的作业跳过；reconcile 只做清理/探测，
     * 失败即保留 unknown 等待下次扫描。
     */
    private void recoverExternal() {
        long now = System.nanoTime() / 1000000;
        if (now - lastRecovery < 5000) {
            return;
        }
        lastRecovery = now;
        JSONArray rows = jobs.unresolvedBroker(node);
        for (int i = 0; i < 8; i++) {
            if (i >= rows.length()) {
                break;
            }
            JSONObject job = rows.getJSONObject(i);
            String id = job.getString("job_id");
            try {
                QueryProvider provider = provider(job);
                if (provider instanceof WorkerExecutionProvider
                        || !provider.independentExecution()
                        || !recovering.add(id)) {
                    continue;
                }
                try {
                    recoveryQueries.execute(
                            () -> {
                                try {
                                    if (provider.reconcile(new ProviderInvocation(job, jobs, files))) {
                                        jobs.brokerReconciled(id, node);
                                    }
                                } catch (Exception ignored) {
                                    /* 保留 unknown，仅重试清理。 */
                                } finally {
                                    recovering.remove(id);
                                }
                            });
                } catch (RuntimeException e) {
                    recovering.remove(id);
                }
            } catch (RuntimeException ignored) {
                /* 缺失或已吊销的扩展无法证明清理完成。 */
            }
        }
    }

    /**
     * 对提供方返回数据执行双重校验：先按 resultSchema 契约校验，再走提供方自定义校验。
     *
     * @param j    作业行，其 definition_json.resultSchema 为契约依据
     * @param data 待校验的结果数据
     */
    private void validateData(JSONObject j, JSONObject data) {
        JSONObject definition = new JSONObject(j.getString("definition_json")),
                args = new JSONObject(j.getString("arguments_json"));
        Contract.validate(data, definition.getJSONObject("resultSchema"));
        provider(j).validateResult(args, data);
    }

    /**
     * 受理 runner 上报的工具执行结果。
     *
     * <p>归一化 tool-result 后校验：无效输出保存 {@code TOOL_OUTPUT_INVALID} 并照常 ACK
     * （坏输出不进入模型）；成功结果必须与 Broker 持久证据逐字节一致，否则
     * {@code TOOL_FACT_NOT_PROVEN}；已撤销作业保存 {@code TOOL_REVOKED}。
     *
     * @param j      作业行
     * @param report runner 上报，经 runner-report 契约校验
     * @return 落库后的 report 行摘要
     */
    public JSONObject report(JSONObject j, JSONObject report) {
        ToolProtocol.parse("runner-report", RuntimeSupport.bytes(report));
        JSONObject normalized;
        try {
            normalized =
                    Contract.parse(
                            "tool-result", report.getString("resultJson").getBytes(StandardCharsets.UTF_8));
            if (normalized.getJSONArray("artifacts").length() != 0) {
                throw new IllegalArgumentException();
            }
            if (!report.getBoolean("actualFinished")
                    && (!normalized.getString("status").equals("unknown")
                    || !report.getString("effectStatus").equals("unknown"))) {
                throw new IllegalArgumentException();
            }
            if (normalized.getString("status").equals("success")) {
                if (!report.getBoolean("actualFinished")
                        || !report.getString("effectStatus").equals("completed")
                        || normalized.has("errorCode")) {
                    throw new IllegalArgumentException();
                }
                validateData(
                        j,
                        StrictJson.object(normalized.getString("dataJson").getBytes(StandardCharsets.UTF_8)));
                { // 每个成功的工具结果都必须与 Broker 持久证据一致。
                    JSONObject evidence =
                            jobs.brokerExchange(
                                    j.getString("job_id"),
                                    j.getString("arguments_hash"),
                                    provider(j) instanceof WorkerExecutionProvider ? workerOwner(j) : node,
                                    false);
                    JSONObject fact =
                            evidence != null && evidence.getString("status").equals("finished")
                                    ? new JSONObject(evidence.getString("response_json"))
                                    : null;
                    if (fact != null && provider(j) instanceof WorkerExecutionProvider) {
                        fact = fact.getJSONObject("data");
                    }
                    if (evidence == null
                            || !evidence.getString("status").equals("finished")
                            || !canonical(fact)
                            .equals(canonical(new JSONObject(normalized.getString("dataJson"))))) {
                        throw new IllegalArgumentException("TOOL_FACT_NOT_PROVEN");
                    }
                }
            } else if (normalized.has("dataJson")
                    || !normalized.optString("errorCode").matches("[A-Z0-9_]{1,128}")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            normalized = ToolJobStore.error("TOOL_OUTPUT_INVALID");
            if (!report.getBoolean("actualFinished")) {
                normalized.put("status", "unknown");
            }
        }
        if (report.getBoolean("actualFinished") && jobs.revoked(j)) {
            normalized = ToolJobStore.error("TOOL_REVOKED");
        }
        return jobs.report(j, report, normalized, provider(j).independentExecution());
    }

    /**
     * 关闭 Broker：关闭提供方与各线程池，并尽力把 pending 结果落库。
     */
    @Override
    public void close() {
        providers.close();
        queries.shutdownNow();
        recoveryQueries.shutdownNow();
        reconciliation.shutdownNow();
        flush();
    }
}
