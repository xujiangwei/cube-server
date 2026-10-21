/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1 基础协议注册表：register/heartbeat/echo/drain/unregister 的登记、租约与状态查询。
 * <p>所有状态迁移都在 synchronized 方法内串行完成；身份围栏由 transport 会话与
 * 租约 nonce 共同保证——旧连接、旧实例、旧 nonce 一律按 STALE_SESSION 拒绝。
 * 注册上限 64 条；空闲租约（默认 6 秒，可配置）过期自动移除；
 * drain 后 ready=false 并拒绝新 echo；断开回调只删该 transport 的记录。
 */
public final class WorkerRegistry {
    /**
     * 注册表条目：worker 身份（id/instance/role）、能力、租约 nonce、
     * 绑定的 transport 与租约截止时间，以及 drain 状态。
     */
    private static final class Worker {
        /**
         * workerId，同一 JVM 内唯一。
         */
        String id;

        /**
         * instanceId，每个 Python 进程随机生成，重连保持不变。
         */
        String instance;

        /**
         * 角色（model/runner），以 Java 配置为准，客户端不可修改。
         */
        String role;

        /**
         * 租约 nonce，register 成功时随机生成，即 sessionId。
         */
        String nonce;

        /**
         * 绑定的 Cell transport 会话标识。
         */
        long transport;

        /**
         * 租约截止时间（单调毫秒，见 now）。
         */
        long deadline;

        /**
         * drain 标记：true 后拒绝新 echo，ready=false。
         */
        boolean draining;

        /**
         * 能力数组；长度 0 表示无能力，registered 但 not ready。
         */
        JSONArray capabilities;

        /**
         * 从 register 请求构造条目并生成租约 nonce。
         *
         * @param request   register 请求 JSON，读取 workerId/instanceId/role/capabilities
         * @param transport 绑定的 Cell transport 会话标识
         * @param deadline  初始租约截止时间（单调毫秒）
         */
        Worker(JSONObject request, long transport, long deadline) {
            id = request.getString("workerId");
            instance = request.getString("instanceId");
            role = request.getString("role");
            capabilities = request.getJSONArray("capabilities");
            nonce = UUID.randomUUID().toString();
            this.transport = transport;
            this.deadline = deadline;
        }
    }

    /**
     * workerId → Worker；全部访问均在 synchronized 方法内，普通字段无需 volatile。
     */
    private final Map<String, Worker> workers = new HashMap<>();

    /**
     * 租约时长（毫秒），每次成功交互后顺延。
     */
    private final long leaseMillis;

    /**
     * @param leaseMillis 租约时长（毫秒），来自私有配置 lease.millis
     */
    public WorkerRegistry(long leaseMillis) {
        this.leaseMillis = leaseMillis;
    }

    /**
     * @return 基于 System.nanoTime 的单调毫秒时钟，不受系统时间调整影响
     */
    private long now() {
        return System.nanoTime() / 1000000;
    }

    /**
     * 移除已过租约的条目（deadline 不晚于当前时间）。
     */
    private void expire() {
        long time = now();
        workers.values().removeIf(w -> w.deadline <= time);
    }

    /**
     * 处理 worker 请求的便捷重载：会话有效性按恒真处理。
     *
     * @param request   worker 请求 JSON
     * @param transport Cell transport 会话标识
     * @return 响应 JSON
     */
    public synchronized JSONObject apply(JSONObject request, long transport) {
        return apply(request, transport, () -> true);
    }

    /**
     * 处理一次 worker 请求（synchronized，全部迁移串行）：
     * register 拒绝 sessionId/payload 字段，同 workerId 的另一 transport 或另一 instanceId
     * 按 DUPLICATE_WORKER 拒绝，空表按 REGISTRY_FULL 拒绝；非 register 动作须命中
     * 同 transport、同 instanceId 与同 nonce，否则 STALE_SESSION；
     * role/capabilities 与登记不一致按 IDENTITY_MISMATCH 拒绝；
     * 非 echo 动作携带 payload 或 echo 时 drain/无能力按 SCHEMA_INVALID/WORKER_NOT_READY 拒绝。
     * 成功即顺延租约；unregister 在构造响应后摘除记录。
     *
     * @param request   worker 请求 JSON，含 action/workerId/instanceId/role/capabilities
     * @param transport Cell transport 会话标识
     * @param valid     会话有效性探针，失效即按 STALE_SESSION 拒绝
     * @return 响应 JSON：sessionId（租约 nonce）、leaseMillis、registered、ready；
     * echo 附加 payload 回显
     * @throws IllegalArgumentException SCHEMA_INVALID、DUPLICATE_WORKER、REGISTRY_FULL、
     *                                  STALE_SESSION、IDENTITY_MISMATCH、WORKER_NOT_READY（错误码即消息）
     */
    public synchronized JSONObject apply(JSONObject request, long transport, java.util.function.BooleanSupplier valid) {
        if (!valid.getAsBoolean()) {
            fail("STALE_SESSION");
        }
        expire();
        String id = request.getString("workerId"), action = request.getString("action");
        Worker w = workers.get(id);
        if (action.equals("register")) {
            if (request.has("sessionId") || request.has("payload")) {
                fail("SCHEMA_INVALID");
            }
            if (w != null && (w.transport != transport || !w.instance.equals(request.getString("instanceId")))) {
                fail("DUPLICATE_WORKER");
            }
            if (w == null) {
                if (workers.size() >= 64) {
                    fail("REGISTRY_FULL");
                }
                w = new Worker(request, transport, now() + leaseMillis);
                workers.put(id, w);
            }
        } else {
            if (w == null || w.transport != transport || !w.instance.equals(request.getString("instanceId")) ||
                    !w.nonce.equals(request.optString("sessionId"))) {
                fail("STALE_SESSION");
            }
        }
        if (!w.role.equals(request.getString("role")) || !w.capabilities.toString().equals(request.getJSONArray("capabilities").toString())) {
            fail("IDENTITY_MISMATCH");
        }
        if (!action.equals("echo") && request.has("payload")) {
            fail("SCHEMA_INVALID");
        }
        if (action.equals("echo") && (!request.has("payload") || w.draining || w.capabilities.length() == 0)) {
            fail("WORKER_NOT_READY");
        }
        if (action.equals("drain")) {
            w.draining = true;
        }
        w.deadline = now() + leaseMillis;
        JSONObject response = new JSONObject().put("sessionId", w.nonce).put("leaseMillis", leaseMillis)
                .put("registered", !action.equals("unregister")).put("ready", !w.draining && w.capabilities.length() > 0 && !action.equals("unregister"));
        if (action.equals("echo")) {
            response.put("payload", request.getJSONObject("payload"));
        }
        if (action.equals("unregister")) {
            workers.remove(id);
        }
        return response;
    }

    /**
     * transport 断开回调：移除该连接上的全部条目，不影响其他连接的会话。
     *
     * @param transport 断开的 Cell transport 会话标识
     */
    public synchronized void disconnect(long transport) {
        workers.values().removeIf(w -> w.transport == transport);
    }

    /**
     * 清空注册表（停机路径调用）。
     */
    public synchronized void clear() {
        workers.clear();
    }

    /**
     * 生成注册表快照：先清理过期条目，再逐条输出
     * workerId/instanceId/role/registered/ready/capabilities。
     *
     * @return 快照 JSONArray，顺序按 HashMap 迭代序，不保证稳定
     */
    public synchronized JSONArray snapshot() {
        expire();
        JSONArray result = new JSONArray();
        for (Worker w : workers.values()) {
            result.put(new JSONObject().put("workerId", w.id).put("instanceId", w.instance)
                    .put("role", w.role).put("registered", true).put("ready", !w.draining && w.capabilities.length() > 0).put("capabilities", w.capabilities));
        }
        return result;
    }

    /**
     * 基础设施就绪判断：至少一个未 drain 且有能力的 model，
     * 且至少一个未 drain 且有能力的 runner。
     *
     * @return model 与 runner 均就绪返回 true，否则 false
     */
    public synchronized boolean ready() {
        expire();
        boolean model = false, runner = false;
        for (Worker w : workers.values()) {
            if (!w.draining && w.capabilities.length() > 0) {
                if (w.role.equals("model")) {
                    model = true;
                }
                if (w.role.equals("runner")) {
                    runner = true;
                }
            }
        }
        return model && runner;
    }

    /**
     * 以错误码拒绝请求：错误码同时作为 IllegalArgumentException 的消息。
     *
     * @param code 服务端错误码，如 STALE_SESSION、IDENTITY_MISMATCH
     * @throws IllegalArgumentException 恒抛出，消息为 code
     */
    private static void fail(String code) {
        throw new IllegalArgumentException(code);
    }
}
