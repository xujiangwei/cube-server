/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.WorkerRegistry;
import cube.service.harness.store.ModelJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * 模型 worker 的拉取传输：instance/session 护栏 + 描述符登记 + 就绪/容量聚合。
 *
 * <p>只有可信配置授予容量：describe 时按 worker.&lt;id&gt;.* 白名单校验角色、模型、adapter
 * 能力与 backend 容量，未授权一律拒绝。protocolVersion=2 为 scripted 执行，
 * protocolVersion=3 走 Java 推理网关（inference_* 动作、usage 账本以 Java 为准）。
 * actual_finished 的 outcome 先过 ModelProtocol 语义校验，再经推理账本 reconcile 对账，
 * 覆盖 worker 对物理结束与用量的自行断言。
 */
public final class ModelDispatcher {

    /**
     * worker 会话注册表：租约、instance/session 护栏与快照。
     */
    private final WorkerRegistry sessions;

    /**
     * 模型作业账本：backend 行、poll、report、cancellations。
     */
    private final ModelJobStore jobs;

    /**
     * 可信配置：worker.&lt;id&gt;.role/model/backend 与 backend.&lt;id&gt;.capacity/type。
     */
    private final Properties config;

    /**
     * 推理网关；v2 scripted 部署下可为 null（协议 3 动作会拒绝）。
     */
    private final InferenceGateway inference;

    /**
     * workerId → 最新登记的描述符（describe 时深拷贝入库）。
     */
    private final Map<String, JSONObject> descriptions = new HashMap<>();

    /**
     * workerId → 健康标记（heartbeat 的 healthy 字段，缺省 true）。
     */
    private final Map<String, Boolean> health = new HashMap<>();

    /**
     * 无推理网关的构造：仅支持 protocolVersion=2 scripted 执行。
     *
     * @param jobs   模型作业账本
     * @param config 可信配置
     * @param lease  会话租约时长（毫秒）
     */
    public ModelDispatcher(ModelJobStore jobs, Properties config, long lease) {
        this(jobs, config, lease, null);
    }

    /**
     * 完整构造：传入推理网关后同时支持 protocolVersion=3。
     *
     * @param jobs      模型作业账本
     * @param config    可信配置
     * @param lease     会话租约时长（毫秒）
     * @param inference 推理网关；可为 null（禁用协议 3）
     */
    public ModelDispatcher(ModelJobStore jobs, Properties config, long lease, InferenceGateway inference) {
        this.jobs = jobs;
        this.config = config;
        this.sessions = new WorkerRegistry(lease);
        this.inference = inference;
    }

    /**
     * 受理一次 worker 请求：登记/心跳/摘除/拉取/上报/推理动作的统一入口。
     *
     * <p>describe 先按白名单校验并登记描述符；normalized 去掉 descriptor/report/healthy 与
     * 推理字段后映射为 register/drain/unregister/heartbeat 交会话注册表。响应 ready 综合
     * 会话就绪、健康标记与后端就绪（协议 3 查推理网关，v2 要求 scripted 模型）。
     * actual_finished 核对 job 归属后经账本对账；poll 仅在 ready 时下发作业。
     *
     * @param request   worker 请求（action、workerId、protocolVersion、requestId 等）
     * @param transport 传输层观测值（如 RTT），交会话注册表
     * @param valid     传输凭据/时效的判定函数
     * @return 响应对象（ready、job、inference、cancellations、protocolVersion 等）
     * @throws IllegalArgumentException FORBIDDEN、MODEL_PROFILE_MISMATCH、BACKEND_REQUIRED、
     *                                  BACKEND_CAPACITY_MISMATCH、INFERENCE_REQUIRED、
     *                                  STALE_SESSION、PROTOCOL_MISMATCH、JOB_EVIDENCE_MISMATCH
     *                                  及 ModelProtocol/InferenceGateway 转出的错误码
     */
    public synchronized JSONObject apply(JSONObject request, long transport, java.util.function.BooleanSupplier valid) {
        String action = request.getString("action"), worker = request.getString("workerId");
        int protocol = request.getInt("protocolVersion");
        JSONObject descriptor = descriptions.get(worker);
        if (action.equals("describe")) {
            descriptor = new JSONObject(request.getJSONObject("descriptor").toString());
            String prefix = "worker." + worker + ".";
            if (!"model".equals(config.getProperty(prefix + "role")) || !descriptor.getString("model").equals(config.getProperty(prefix + "model"))) {
                throw new IllegalArgumentException("FORBIDDEN");
            }
            String model = descriptor.getString("model");
            String adapter = protocol == 3 ? (descriptor.getBoolean("nativeTools") ? "openai-tools-v2" : "openai-json-v2")
                    : "scripted-v1";
            if (!adapter.equals(descriptor.getString("adapter"))
                    || !"observe-only".equals(descriptor.getString("cancellation"))
                    || !"transport-bytes-v1".equals(descriptor.getString("budget"))) {
                throw new IllegalArgumentException("MODEL_PROFILE_MISMATCH");
            }
            String backend = config.getProperty(prefix + "backend");
            if (backend == null) {
                throw new IllegalArgumentException("BACKEND_REQUIRED");
            }
            int capacity = Integer.parseInt(config.getProperty("backend." + backend + ".capacity", "0"));
            if (capacity < 1 || capacity > 32) {
                throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
            }
            if (protocol == 2) {
                if (!backend.equals(descriptor.getString("backendId")) || descriptor.getInt("capacity") != capacity
                        || descriptor.getBoolean("nativeTools") != model.equals("Baize2")) {
                    throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
                }
                jobs.backend(backend, descriptor, capacity);
            } else {
                if (inference == null) {
                    throw new IllegalArgumentException("INFERENCE_REQUIRED");
                }
                jobs.backend(backend,
                        new JSONObject().put("backendId", backend)
                                .put("type", config.getProperty("backend." + backend + ".type", "unit")),
                        capacity);
                descriptor.put("backendId", backend).put("capacity", capacity).put("protocolVersion", 3);
            }
        }
        JSONObject normalized = new JSONObject(request.toString());
        normalized.remove("descriptor");
        normalized.remove("report");
        normalized.remove("healthy");
        for (String k : Arrays.asList("jobId", "jobInstanceId", "wireAttempt", "requestHash", "requestJson", "after")) {
            normalized.remove(k);
        }
        normalized.put("action",
                action.equals("describe") ? "register"
                        : action.equals("drain") ? "drain"
                        : action.equals("unregister") ? "unregister"
                        : "heartbeat");
        JSONObject response = sessions.apply(normalized, transport, valid);
        if (action.equals("describe")) {
            descriptions.put(worker, new JSONObject(descriptor.toString()));
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("STALE_SESSION");
        }
        if (descriptor.optInt("protocolVersion", 2) != protocol) {
            throw new IllegalArgumentException("PROTOCOL_MISMATCH");
        }
        health.put(worker, request.optBoolean("healthy", true));
        response.put("ready", response.getBoolean("ready") && health.get(worker)
                && (protocol == 3 ? inference.ready(descriptor) : descriptor.getString("model").equals("scripted")));
        if (action.equals("actual_finished")) {
            JSONObject report = new JSONObject(request.getJSONObject("report").toString());
            if (protocol == 3) {
                JSONObject bound = jobs.jobById(report.getString("jobId"));
                if (bound == null || !worker.equals(bound.optString("worker_id")) || !report.getString("instanceId").equals(bound.optString("instance_id"))) {
                    throw new IllegalArgumentException("JOB_EVIDENCE_MISMATCH");
                }
                JSONObject outcome = ModelProtocol.outcome(report.getString("outcomeJson"), report.getBoolean("actualFinished"));
                outcome = inference.ledger.reconcile(report.getString("jobId"), outcome);
                report.put("outcomeJson", outcome.toString()).put("actualFinished", !outcome.getString("status").equals("unknown"));
            }
            jobs.report(worker, report);
        }
        if (action.startsWith("inference_")) {
            response.put("inference", inference.apply(request));
        }
        if (action.equals("poll")) {
            JSONObject job = jobs.poll(worker, request.getString("instanceId"), request.getString("sessionId"), descriptor, 1, response.getBoolean("ready"));
            if (job != null) {
                response.put("job", job);
            }
        }
        return response.put("protocolVersion", protocol).put("requestId", request.getString("requestId")).put("ok", true)
                .put("cancellations", jobs.cancellations(worker));
    }

    /**
     * 生成 worker 快照：在会话快照上叠加健康标记与后端就绪状态。
     *
     * @return worker 数组，每项含 healthy、ready 与最新 descriptor
     */
    public synchronized JSONArray snapshot() {
        JSONArray out = sessions.snapshot();
        for (int i = 0; i < out.length(); i++) {
            JSONObject w = out.getJSONObject(i);
            boolean healthy = Boolean.TRUE.equals(health.get(w.getString("workerId")));
            JSONObject d = descriptions.get(w.getString("workerId"));
            boolean backendReady = d.optInt("protocolVersion", 2) == 3 ? inference.ready(d) : d.getString("model").equals("scripted");
            w.put("healthy", healthy).put("ready", w.getBoolean("ready") && healthy && backendReady).put("descriptor", d);
        }
        return out;
    }

    /**
     * 注销指定会话（按会话 ID）。
     *
     * @param id 会话标识
     */
    public synchronized void disconnect(long id) {
        sessions.disconnect(id);
    }

    /**
     * 是否存在就绪 worker（任一 ready 即真）。
     *
     * @return 存在就绪 worker 时为 true
     */
    public synchronized boolean ready() {
        JSONArray list = snapshot();
        for (int i = 0; i < list.length(); i++) {
            if (list.getJSONObject(i).getBoolean("ready")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 聚合跨 backend 的容量视图：把各后端上报的路由容量公平摊到可执行工作上。
     *
     * <p>先按 executable() 降序排序（有可执行工作的 backend 优先认领共享组），再两轮认领：
     * 第一轮只认领空闲路由（受物理空闲上限约束），第二轮补认领 registered 路由。
     * 共享组经 CapacityBudget 去重；unit 后端无分组时按 processing 与 available 线性取小，
     * 非 unit 无分组时取最大值兜底。最终每 backend 输出 processingSlots、processingAvailable、
     * inferenceUnits、inferenceAvailable、readyUnits、available、executableConcurrency。
     *
     * @return 含 backends 明细与 registeredUnits/available/executableConcurrency 汇总的容量对象
     */
    public synchronized JSONObject capacity() {
        JSONObject view = jobs.capacity();
        JSONArray workers = snapshot();
        JSONArray backends = view.getJSONArray("backends");
        JSONArray occupied = view.getJSONArray("workers");
        Set<String> busy = new HashSet<>();
        for (int n = 0; n < occupied.length(); n++) {
            busy.add(occupied.getJSONObject(n).getString("worker_id"));
        }
        List<CapacityView> views = new ArrayList<>();
        for (int n = 0; n < backends.length(); n++) {
            JSONObject backend = backends.getJSONObject(n);
            String id = backend.getString("backend_id");
            CapacityView row = new CapacityView(backend);
            views.add(row);
            for (int j = 0; j < workers.length(); j++) {
                JSONObject worker = workers.getJSONObject(j);
                JSONObject d = worker.getJSONObject("descriptor");
                if (!d.getString("backendId").equals(id) || !worker.getBoolean("ready")) {
                    continue;
                }
                row.ready++;
                boolean idle = !busy.contains(worker.getString("workerId"));
                if (idle) {
                    row.idle++;
                }
                String model = d.getString("model");
                int[] processing = row.processing.computeIfAbsent(model, key -> new int[2]);
                processing[0]++;
                if (idle) {
                    processing[1]++;
                }
                if (d.optInt("protocolVersion", 2) == 3) {
                    if (!row.models.containsKey(model)) {
                        row.models.put(model, inference.capacity(d));
                    }
                } else {
                    row.legacy = true;
                }
            }
        }
        // 先把共享组分配给有可执行工作的 backend，避免忙别名在别处报告可用槽时
        // 仍把上报并发全部消耗殆尽。
        views.sort((a, b) -> Integer.compare(b.executable(), a.executable()));
        int available = 0, total = 0;
        CapacityBudget budgets = new CapacityBudget();
        for (CapacityView row : views) {
            for (JSONObject capacity : row.models.values()) {
                JSONArray groups = CapacityBudget.entries(capacity);
                if (groups != null) {
                    budgets.observe(groups);
                }
            }
        }
        for (CapacityView row : views) {
            JSONObject backend = row.backend;
            int physical = Math.max(0, backend.getInt("capacity") - backend.getInt("occupied"));
            int live = row.legacy ? physical : 0;
            int registered = row.legacy ? row.ready : 0;
            List<String> modelOrder = new ArrayList<>(row.models.keySet());
            modelOrder.sort((a, b) -> Integer.compare(row.modelAvailable(b), row.modelAvailable(a)));
            for (String model : modelOrder) {
                JSONObject capacity = row.models.get(model);
                int[] processing = row.processing.get(model);
                JSONArray groups = CapacityBudget.entries(capacity);
                if (groups != null) {
                    int free = budgets.claim(groups, true, Math.max(0, Math.min(processing[1], physical - live)));
                    live += free;
                    registered += free;
                    row.assigned.put(model, free);
                } else if (config.getProperty("backend." + backend.getString("backend_id") + ".type", "unit").equals("unit")) {
                    live += Math.min(processing[1], capacity.getInt("available"));
                    registered += Math.min(processing[0], capacity.getInt("registered"));
                } else {
                    live = Math.max(live, capacity.getInt("available"));
                    registered = Math.max(registered, capacity.getInt("registered"));
                }
            }
            row.live = live;
            row.registered = registered;
        }
        // 仅在每个 backend 的可用路由都预留到各自份额后，再分配剩余的 registered 容量；
        // 一个忙模型不能掩盖另一个 backend 的空闲路由。
        for (CapacityView row : views) {
            JSONObject backend = row.backend;
            int physical = Math.max(0, backend.getInt("capacity") - backend.getInt("occupied"));
            int live = row.live, registered = row.registered;
            for (String model : row.models.keySet()) {
                JSONArray groups = CapacityBudget.entries(row.models.get(model));
                if (groups != null) {
                    registered += budgets.claim(groups, false,
                            Math.max(0, Math.min(row.processing.get(model)[0] - row.assigned.getOrDefault(model, 0),
                                    backend.getInt("capacity") - registered)));
                }
            }
            int free = Math.min(row.idle, Math.min(physical, live));
            int concurrency = Math.min(row.ready, Math.min(backend.getInt("capacity"), registered));
            backend.put("processingSlots", row.ready).put("processingAvailable", row.idle).put("inferenceUnits", registered)
                    .put("inferenceAvailable", Math.min(physical, live)).put("readyUnits", row.ready)
                    .put("available", free).put("executableConcurrency", concurrency);
            available += free;
            total += concurrency;
        }
        view.remove("workers");
        return view.put("registeredUnits", workers.length()).put("available", available).put("executableConcurrency", total);
    }

    /**
     * 单个 backend 的容量聚合行：workers 视图、模型容量、处理中计数与两轮认领结果。
     */
    private static final class CapacityView {

        /**
         * jobs.capacity() 中的 backend 行（backend_id、capacity、occupied）。
         */
        final JSONObject backend;

        /**
         * model → 后端容量描述符（按首次出现顺序保留）。
         */
        final Map<String, JSONObject> models = new LinkedHashMap<>();

        /**
         * model → [处理中 worker 数, 空闲 worker 数]。
         */
        final Map<String, int[]> processing = new HashMap<>();

        /**
         * model → 第一轮已认领的空闲路由数（第二轮补领时扣除）。
         */
        final Map<String, Integer> assigned = new HashMap<>();

        /**
         * 就绪 worker 数。
         */
        int ready;

        /**
         * 空闲（未在处理）的就绪 worker 数。
         */
        int idle;

        /**
         * 第一轮后的可用推理单元数。
         */
        int live;

        /**
         * 第一轮后的已注册推理单元数。
         */
        int registered;

        /**
         * 是否存在 protocolVersion&lt;3 的 worker（scripted 容量路径）。
         */
        boolean legacy;

        /**
         * 用 backend 行构造聚合行。
         *
         * @param backend jobs.capacity() 中的 backend 行
         */
        CapacityView(JSONObject backend) {
            this.backend = backend;
        }

        /**
         * 计算模型的可执行空闲量：处理中空闲数与后端可用量的较小者。
         *
         * @param model 模型名
         * @return 可执行空闲量
         */
        int modelAvailable(String model) {
            return Math.min(processing.get(model)[1], models.get(model).getInt("available"));
        }

        /**
         * 计算本 backend 当前可执行的工作量：legacy 用 worker 空闲数，否则按模型累加，受物理余量封顶。
         *
         * @return 可执行工作量（不超过 capacity - occupied）
         */
        int executable() {
            int free = legacy ? idle : 0;
            for (String model : models.keySet()) {
                free += modelAvailable(model);
            }
            return Math.min(free, Math.max(0, backend.getInt("capacity") - backend.getInt("occupied")));
        }
    }
}
