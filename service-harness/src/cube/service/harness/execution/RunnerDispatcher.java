/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.service.harness.store.StoreException;
import cube.service.harness.store.ToolJobStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 按传输通道（Cell 长连接）维度的 runner 注册表与作业分发入口。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>物理容量预留由共享持久存储（{@link ToolJobStore}）负责，本类只维护会话状态：
 *       登记、租约、健康、drain 与按 worker 的作业拉取；</li>
 *   <li>worker 按 workerId 注册并绑定 transport、instanceId、runtimeId、capacity 与 runnerType；
 *       重连（新 instanceId）必须重新 describe，旧会话的 sessionId 一律失效；</li>
 *   <li>每次 {@link #apply} 刷新租约截止时间（lease 毫秒），过期 worker 在下次访问时移除；
 *       传输断开按 transport 批量清理；</li>
 *   <li>注册表上限 64 个 worker，超出以 {@code REGISTRY_FULL} 拒绝；</li>
 *   <li>健康为复合判定：worker 自报 healthy 且持久存储侧 {@code jobs.healthy(id)} 为真；</li>
 *   <li>全部响应统一经 {@link ToolProtocol#parse} 校验后才返回，线报不合法即拒绝。</li>
 * </ul>
 */
public final class RunnerDispatcher {
    /**
     * 单个已注册 runner 的会话状态。
     *
     * <p>仅由 {@link RunnerDispatcher} 在持有外部锁时读写，字段一经登记不再变更身份部分
     * （id/transport/instance/runtime/capacity/runnerType/nonce），只刷新 deadline、
     * draining 与 healthy。
     */
    private static final class Worker {
        /**
         * workerId，与部署配置 worker.&lt;id&gt;.role=runner 授权绑定。
         */
        String id;

        /**
         * 实例标识（instanceId）；同 workerId 重连会获得新值，旧会话随之失效。
         */
        String instance;

        /**
         * 会话随机标识，作为 sessionId 下发给 runner 并要求回带校验。
         */
        String nonce;

        /**
         * 运行时标识（runtimeId），持久绑定该 worker。
         */
        String runtime;

        /**
         * runner 类型：tool 或 sandbox，决定可领取的作业类型。
         */
        String runnerType;

        /**
         * 传输通道标识，断开时按此值批量清理。
         */
        long transport;

        /**
         * 租约截止时间戳（毫秒，now() 基准），过期即视为离线。
         */
        long deadline;

        /**
         * 登记的处理容量，与部署配置及描述符一致。
         */
        int capacity;

        /**
         * 是否正在排水；排水中的 worker 不再拉取新作业。
         */
        boolean draining;

        /**
         * 健康标记：runner 自报与持久存储侧健康的复合结果。
         */
        boolean healthy;
    }

    /**
     * workerId 到会话状态的映射；仅由本类的同步方法访问。
     */
    private final Map<String, Worker> workers = new HashMap<>();

    /**
     * 工具 Broker，提供线报组装、授权与证据查询。
     */
    private final ToolBroker broker;

    /**
     * 持久化作业存储，与 {@link #broker} 共享同一实例。
     */
    private final ToolJobStore jobs;

    /**
     * 部署私有配置，读取 worker 角色、容量与沙箱证书摘要等。
     */
    private final Properties config;

    /**
     * 租约时长（毫秒），每次 apply 刷新 worker 的截止时间。
     */
    private final long lease;

    /**
     * 构造分发器。
     *
     * @param broker 工具 Broker，其 jobs 存储被复用为作业账本
     * @param config 部署私有配置
     * @param lease  租约时长（毫秒）
     */
    public RunnerDispatcher(ToolBroker broker, Properties config, long lease) {
        this.broker = broker;
        this.jobs = broker.jobs;
        this.config = config;
        this.lease = lease;
    }

    /**
     * 返回当前毫秒时间戳。
     *
     * @return 基于 System.nanoTime 换算的毫秒数，单调递增，不受系统时钟回拨影响
     */
    private long now() {
        return System.nanoTime() / 1000000;
    }

    /**
     * 移除租约已过期的 worker，过期即视为离线，不再参与作业分发。
     */
    private void expire() {
        workers.values().removeIf(w -> w.deadline <= now());
    }

    /**
     * 处理一次 runner 请求（describe/注册、poll、accepted、status、result、drain 等）。
     *
     * <p>同步方法，与 {@link #disconnect}、 {@link #snapshot} 共用同一把锁。
     * 主要流程：会话有效性判定 → describe 登记或旧会话校验 → 刷新租约与健康 →
     * 按动作分发（拉取作业、授权、沙箱回执、结果上报）→ 组装并校验响应。
     *
     * @param r         runner 请求对象，须含 workerId、action、requestId 等字段
     * @param transport 传输通道标识，用于会话绑定与断开清理
     * @param valid     调用方注入的会话有效性判定，返回 false 时以 STALE_SESSION 拒绝
     * @return runner 响应对象，含 sessionId、租约、取消清单、作业/状态/结果等字段
     * @throws StoreException 出现以下情况时抛出：STALE_SESSION（会话无效或过期）、
     *                        FORBIDDEN（workerId 未授权 runner 角色）、SCHEMA_INVALID
     *                        （describe 携带 sessionId）、RUNNER_CAPACITY_MISMATCH（容量与配置不符）、
     *                        SANDBOX_WORKER_NOT_CERTIFIED（沙箱 worker 档案或证书摘要不符）、
     *                        DUPLICATE_WORKER（同 workerId 被其他实例/传输占用）、
     *                        REGISTRY_FULL（注册表已满）、PROTOCOL_MISMATCH（runnerType 不一致）、
     *                        SANDBOX_RECEIPT_RETRY（沙箱回执处理失败）
     */
    public synchronized JSONObject apply(
            JSONObject r, long transport, java.util.function.BooleanSupplier valid) {
        if (r.getInt("protocolVersion") != ToolProtocol.VERSION) {
            throw new StoreException("PROTOCOL_MISMATCH");
        }
        if (!valid.getAsBoolean()) {
            throw new StoreException("STALE_SESSION");
        }
        expire();
        String id = r.getString("workerId"), action = r.getString("action");
        if (!"runner".equals(config.getProperty("worker." + id + ".role"))) {
            throw new StoreException("FORBIDDEN");
        }
        Worker w = workers.get(id);
        if (action.equals("describe")) {
            // 登记/重注册：运行时能力、容量与沙箱认证须符合部署配置。
            if (r.has("sessionId")) {
                throw new StoreException("SCHEMA_INVALID");
            }
            JSONObject d = r.getJSONObject("descriptor");
            if (!d.getString("runtime").equals("trusted-python-v1")) {
                throw new StoreException("PROTOCOL_MISMATCH");
            }
            int capacity = Integer.parseInt(config.getProperty("worker." + id + ".capacity", "1"));
            if (capacity != d.getInt("capacity")) {
                throw new StoreException("RUNNER_CAPACITY_MISMATCH");
            }
            if (ToolProtocol.SANDBOX.equals(r.getString("runnerType"))) {
                String prefix = "worker." + id + ".sandbox.";
                if (capacity != 1
                        || !d.getString("executionProfile").equals("linux-firecracker-python-v1")
                        || !d.getString("sandboxProfileDigest")
                        .equals(config.getProperty(prefix + "profile.sha256", ""))
                        || !d.getString("certificateDigest")
                        .equals(config.getProperty(prefix + "certificate.sha256", ""))) {
                    throw new StoreException("SANDBOX_WORKER_NOT_CERTIFIED");
                }
            }
            // 同 workerId 已被其他传输或实例占用时拒绝，避免绕开未决作业换实例。
            if (w != null && (w.transport != transport || !w.instance.equals(r.getString("instanceId")))) {
                throw new StoreException("DUPLICATE_WORKER");
            }
            jobs.identity(id, d.getString("runtimeId"), capacity);
            if (w == null) {
                if (workers.size() >= 64) {
                    throw new StoreException("REGISTRY_FULL");
                }
                w = new Worker();
                w.id = id;
                w.transport = transport;
                w.instance = r.getString("instanceId");
                w.runtime = d.getString("runtimeId");
                w.capacity = capacity;
                w.runnerType = r.getString("runnerType");
                w.nonce = UUID.randomUUID().toString();
                workers.put(id, w);
            }
        } else if (w == null
                || w.transport != transport
                || !w.instance.equals(r.getString("instanceId"))
                || !w.nonce.equals(r.optString("sessionId"))) {
            // 非 describe 动作必须来自已登记且会话匹配的连接。
            throw new StoreException("STALE_SESSION");
        }
        if (!w.runnerType.equals(r.getString("runnerType"))) {
            throw new StoreException("PROTOCOL_MISMATCH");
        }
        w.deadline = now() + lease;
        w.healthy = r.optBoolean("healthy", true) && jobs.healthy(id);
        if (action.equals("drain")) {
            w.draining = true;
        }
        JSONObject out =
                new JSONObject()
                        .put("protocolVersion", ToolProtocol.VERSION)
                        .put("runnerType", w.runnerType)
                        .put("requestId", r.getString("requestId"))
                        .put("ok", true)
                        .put("sessionId", w.nonce)
                        .put("leaseMillis", lease)
                        .put("registered", !action.equals("unregister"))
                        .put("ready", !w.draining && w.healthy);
        JSONArray cancel = new JSONArray(), rows = jobs.cancellations(id);
        for (int i = 0; i < rows.length(); i++) {
            cancel.put(rows.getJSONObject(i).getString("job_id"));
        }
        out.put("cancellations", cancel);
        if (Boolean.parseBoolean(config.getProperty("files.enabled", "false"))
                && Boolean.parseBoolean(config.getProperty("cleanup.enabled", "true"))) {
            out.put(
                    "cleanup",
                    jobs.cleanups(
                            id,
                            w.runtime,
                            Long.parseLong(config.getProperty("cleanup.temp.grace.seconds", "60")) * 1000));
        }
        if (action.equals("cleanup_ack")) {
            jobs.cleaned(
                    id, w.runtime, r.getString("jobId"), r.getString("jobHash"), r.getString("resultHash"));
        }
        if (action.equals("poll") && !w.draining) {
            JSONObject job = jobs.poll(id, w.runtime, w.instance, w.healthy, w.runnerType, broker::wire);
            if (job != null) {
                out.put("job", job);
            }
        }
        if (action.equals("accepted")
                || action.equals("status")
                || action.equals("sandbox_prepare")
                || action.equals("sandbox_complete")
                || action.equals("broker_invoke")) {
            JSONObject j = jobs.evidence(id, w.runtime, r.getString("jobId"), r.getString("jobHash"));
            if (action.equals("accepted")) {
                // accepted：核对回传 grant 与线报内授权一致后才登记受理。
                broker.authorize(j, new JSONObject(j.getString("wire_json")).getJSONObject("grant"));
                jobs.accepted(j.getString("job_id"), j.getString("job_hash"));
                out.put("status", "accepted");
            } else if (action.equals("status")) {
                out.put("status", j.getString("status"))
                        .put("actualFinished", j.getInt("actual_finished") == 1);
            } else if (action.equals("sandbox_prepare") || action.equals("sandbox_complete")) {
                // 沙箱阶段：仅 sandbox 类型 worker 可推进，回执转换失败统一为可重试错误码。
                if (!ToolProtocol.SANDBOX.equals(w.runnerType) || !ToolProtocol.SANDBOX.equals(ToolJobStore.runnerType(j))) {
                    throw new StoreException("PROTOCOL_MISMATCH");
                }
                JSONObject result;
                try {
                    result =
                            action.equals("sandbox_prepare")
                                    ? broker.prepareWorker(j, r.getJSONObject("grant"))
                                    : broker.completeWorker(
                                    j,
                                    cube.service.harness.protocol.StrictJson.object(
                                            r.getString("receiptJson")
                                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                } catch (StoreException e) {
                    throw e;
                } catch (Exception e) {
                    throw new StoreException("SANDBOX_RECEIPT_RETRY");
                }
                for (String k : result.keySet()) {
                    out.put(k, result.get(k));
                }
            } else {
                // broker_invoke：普通工具经 Broker 复核授权后调用提供方。
                JSONObject result = broker.invokeArguments(
                        j,
                        r.getJSONObject("grant"),
                        cube.service.harness.protocol.StrictJson.object(
                                r.getString("argumentsJson")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                for (String k : result.keySet()) {
                    out.put(k, result.get(k));
                }
            }
        }
        if (action.equals("result")) {
            JSONObject report = r.getJSONObject("report"),
                    j = jobs.evidence(id, w.runtime, report.getString("jobId"), report.getString("jobHash"));
            JSONObject saved = broker.report(j, report);
            out.put("resultHash", saved.getString("report_hash"))
                    .put("actualFinished", saved.getInt("actual_finished") == 1);
        }
        w.healthy = w.healthy && jobs.healthy(id);
        out.put("ready", !w.draining && w.healthy);
        if (action.equals("unregister")) {
            workers.remove(id);
        }
        ToolProtocol.parse("runner-response", cube.service.harness.runtime.RuntimeSupport.bytes(out));
        return out;
    }

    /**
     * 传输通道断开时移除其上的全部 worker 会话。
     *
     * @param transport 断开的传输通道标识
     */
    public synchronized void disconnect(long transport) {
        workers.values().removeIf(w -> w.transport == transport);
    }

    /**
     * 返回当前注册 runner 的快照，用于能力查询与运维展示。
     *
     * @return 每个 worker 一项的 JSON 数组，含 workerId、instanceId、runtimeId、runnerType、
     * ready、capacity、occupied 与 available（available 为不健康或排水的 worker 恒为 0）
     */
    public synchronized JSONArray snapshot() {
        expire();
        JSONArray out = new JSONArray();
        for (Worker w : workers.values()) {
            int used = jobs.occupied(w.id);
            boolean healthy = w.healthy && jobs.healthy(w.id);
            out.put(
                    new JSONObject()
                            .put("workerId", w.id)
                            .put("instanceId", w.instance)
                            .put("runtimeId", w.runtime)
                            .put("role", "runner").put("transportId", w.transport)
                            .put("protocolVersion", ToolProtocol.VERSION)
                            .put("runnerType", w.runnerType)
                            .put("healthy", healthy).put("draining", w.draining).put("leaseRemainingMs", Math.max(0, w.deadline - now()))
                            .put("ready", !w.draining && healthy)
                            .put("capacity", w.capacity)
                            .put("occupied", used)
                            .put("available", healthy && !w.draining ? Math.max(0, w.capacity - used) : 0));
        }
        return out;
    }
}
