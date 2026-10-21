/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 模型作业的持久受理与物理后端预留：传输过期绝不释放槽位。
 *
 * <p>模型作业经权威 round intent 派生（run/round/attempt），崩溃后可从已提交
 * intent 重建；backend 行锁把容量检查串行化到同一 backend 的所有 JVM 与
 * worker。Unit 忙时推理留在持久队列中，迟到有效 completed 仍结算一次。
 */
public final class ModelJobStore {

    /**
     * 共享的权威账本入口；所有读写都经由其短事务执行。
     */
    private final JdbcRunStore store;

    /**
     * MySQL 下的行锁后缀；非 MySQL 为空串，用于串行化跨 JVM 的容量检查。
     */
    private final String lock;

    /**
     * 构造模型作业存储。
     *
     * @param store 共享的 JdbcRunStore 事务入口
     * @param mysql 是否 MySQL 数据库；决定读关键行是否追加 FOR UPDATE 行锁
     */
    public ModelJobStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.lock = mysql ? " FOR UPDATE" : "";
    }

    /**
     * 登记或核对模型后端身份与容量。
     *
     * <p>网关后端（descriptor 含 type）额外冻结 routing_hash：旧行缺省时补齐，
     * 不一致则拒绝，保证在途 drain/对账沿用冻结的 v2 worker 身份。
     *
     * @param backend    后端 ID
     * @param descriptor 后端描述 JSON
     * @param capacity   共享物理引擎的推理容量
     * @throws StoreException BACKEND_IDENTITY_CONFLICT：容量变化或摘要与已登记身份不一致
     */
    public void backend(String backend, JSONObject descriptor, int capacity) {
        store.tx(
                c -> {
                    JSONObject old =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_model_backend WHERE backend_id=?"
                                                    + lock,
                                            id(backend)));
                    String digest = hash(descriptor);
                    boolean gateway = descriptor.has("type");
                    if (old == null) {
                        update(
                                c,
                                "INSERT INTO harness_model_backend (backend_id,descriptor_hash,capacity,routing_hash) VALUES (?,?,?,?)",
                                backend,
                                digest,
                                capacity,
                                gateway ? digest : null);
                    } else {
                        if (old.getInt("capacity") != capacity) {
                            throw new StoreException("BACKEND_IDENTITY_CONFLICT");
                        }
                        if (gateway) {
                            // 保留冻结的 v2 worker 身份，供在途 drain/对账继续使用。
                            if (old.isNull("routing_hash")) {
                                update(
                                        c,
                                        "UPDATE harness_model_backend SET routing_hash=? WHERE backend_id=?",
                                        digest,
                                        backend);
                            } else if (!old.getString("routing_hash").equals(digest)) {
                                throw new StoreException("BACKEND_IDENTITY_CONFLICT");
                            }
                        } else if (!old.getString("descriptor_hash").equals(digest)) {
                            throw new StoreException("BACKEND_IDENTITY_CONFLICT");
                        }
                    }
                    return null;
                });
    }

    /**
     * 把已确认的模型轮次意图入队为持久作业；入队边界崩溃后可从已提交 intent 重建。
     *
     * @param lease  当前租约
     * @param intent 权威 harness_round 行
     * @param model  模型描述（id、adapterDigest 等）
     * @return 新建或已存在的 harness_model_job 行
     * @throws StoreException INTENT_EVIDENCE_MISMATCH：权威 intent 缺失或与传入内容不一致
     * @throws StoreException MODEL_REQUEST_TOO_LARGE：规范化请求超过 48000 字节
     */
    public JSONObject enqueue(Lease lease, JSONObject intent, JSONObject model) {
        return store.tx(
                c -> {
                    JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
                    store.owner(c, r, lease);
                    String round = intent.getString("intent_id");
                    int attempt = intent.getInt("attempt");
                    JSONObject authoritative =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_round WHERE run_id=? AND intent_id=? AND attempt=?",
                                            lease.runId,
                                            round,
                                            attempt));
                    if (authoritative == null
                            || !canonical(authoritative).equals(canonical(intent))) {
                        throw new StoreException("INTENT_EVIDENCE_MISMATCH");
                    }
                    String job = hash(new JSONArray().put(lease.runId).put(round).put(attempt));
                    JSONObject old =
                            first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?", job));
                    if (old != null) {
                        return old;
                    }
                    JSONObject payload = new JSONObject(intent.getString("payload_json"));
                    String request = canonical(payload.getJSONObject("request"));
                    if (request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48000) {
                        throw new StoreException("MODEL_REQUEST_TOO_LARGE");
                    }
                    update(
                            c,
                            "INSERT INTO harness_model_job (job_id,run_id,tenant,subject_id,round_id,attempt,origin_fence,intent_hash,model_id,profile_digest,request_hash,request_json,backend_id,worker_id,instance_id,session_id,status,actual_finished,cancel_requested,outcome_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,'queued',0,0,NULL,?)",
                            job,
                            lease.runId,
                            lease.tenant,
                            lease.subject,
                            round,
                            attempt,
                            intent.getLong("origin_fence"),
                            intent.getString("intent_hash"),
                            model.getString("id"),
                            model.optString(
                                    "adapterDigest",
                                    cube.service.harness.execution.ModelProfiles.adapterDigest(
                                            "scripted-v1")),
                            hash(payload.getJSONObject("request")),
                            request,
                            store.now(c));
                    return first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?", job));
                });
    }

    /**
     * 领取下一个模型作业；backend 行锁把容量检查串行化到共享该 backend 的所有 JVM 与 worker。
     *
     * <p>已有 assigned 作业按原样重放；protocolVersion=2 时受 backend 物理容量
     * 与 worker 自身容量双重约束；仅领取所属 run 处于 running 的 queued 作业，
     * 认领冲突抛错。
     *
     * @param worker         worker ID
     * @param instance       实例 ID
     * @param session        会话 ID
     * @param descriptor     worker 描述（backendId、model、profileDigest、protocolVersion）
     * @param workerCapacity 该 worker 的模型处理容量
     * @param healthy        worker 自报健康；不健康则不派发
     * @return wire 作业对象；无合适作业时返回 null
     * @throws StoreException BACKEND_UNKNOWN：backend 未登记
     * @throws StoreException JOB_CLAIM_CONFLICT：抢占 queued 作业的 UPDATE 未命中
     */
    public JSONObject poll(
            String worker,
            String instance,
            String session,
            JSONObject descriptor,
            int workerCapacity,
            boolean healthy) {
        return store.tx(
                c -> {
                    String backend = descriptor.getString("backendId");
                    JSONObject b =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_model_backend WHERE backend_id=?"
                                                    + lock,
                                            backend));
                    if (b == null) {
                        throw new StoreException("BACKEND_UNKNOWN");
                    }
                    JSONObject existing =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_model_job WHERE worker_id=? AND instance_id=? AND actual_finished=0 AND status='assigned' ORDER BY created_at LIMIT 1",
                                            worker,
                                            instance));
                    if (existing != null) {
                        return wire(existing);
                    }
                    if (!healthy) {
                        return null;
                    }
                    int occupied =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_model_job WHERE backend_id=? AND status<>'queued' AND actual_finished=0",
                                            backend))
                                    .getInt("n");
                    int own =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_model_job WHERE worker_id=? AND actual_finished=0 AND status<>'queued'",
                                            worker))
                                    .getInt("n");
                    if ((descriptor.optInt("protocolVersion", 2) == 2
                            && occupied >= b.getInt("capacity"))
                            || own >= workerCapacity) {
                        return null;
                    }
                    JSONObject job =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_model_job WHERE status='queued' AND cancel_requested=0 AND model_id=? AND profile_digest=? AND run_id IN (SELECT run_id FROM harness_run WHERE state='running') ORDER BY created_at,job_id LIMIT 1"
                                                    + lock,
                                            descriptor.getString("model"),
                                            descriptor.getString("profileDigest")));
                    if (job == null) {
                        return null;
                    }
                    if (update(
                            c,
                            "UPDATE harness_model_job SET backend_id=?,worker_id=?,instance_id=?,session_id=?,execution_protocol=?,status='assigned' WHERE job_id=? AND status='queued'",
                            backend,
                            worker,
                            instance,
                            session,
                            descriptor.optInt("protocolVersion", 2),
                            job.getString("job_id"))
                            != 1) {
                        throw new StoreException("JOB_CLAIM_CONFLICT");
                    }
                    job.put("worker_id", worker)
                            .put("instance_id", instance)
                            .put("session_id", session)
                            .put("execution_protocol", descriptor.optInt("protocolVersion", 2));
                    update(
                            c,
                            "UPDATE harness_model_job SET dispatched_at=? WHERE job_id=?",
                            store.now(c),
                            job.getString("job_id"));
                    return wire(job);
                });
    }

    /**
     * 把作业行投影为发给 worker 的 wire 对象。
     *
     * @param j harness_model_job 行
     * @return 含 jobId/runId/roundId/attempt/originFence/intentHash/requestHash/requestJson/model/workerId/instanceId/sessionId 的对象
     */
    private static JSONObject wire(JSONObject j) {
        JSONObject result =
                new JSONObject()
                        .put("jobId", j.getString("job_id"))
                        .put("runId", j.getString("run_id"))
                        .put("roundId", j.getString("round_id"))
                        .put("attempt", j.getInt("attempt"))
                        .put("originFence", j.getLong("origin_fence"))
                        .put("intentHash", j.getString("intent_hash"))
                        .put("requestHash", j.getString("request_hash"))
                        .put("requestJson", j.getString("request_json"))
                        .put("model", j.getString("model_id"))
                        .put("workerId", j.getString("worker_id"))
                        .put("instanceId", j.getString("instance_id"))
                        .put("sessionId", j.getString("session_id"));
        return result;
    }

    /**
     * 记录 worker 上报的作业结果；重复未知必须一致。
     *
     * <p>已实际结束时按 outcome 幂等回放，冲突抛错；unknown 被隔离，后续持久
     * completed 仍可结算，但 protocol=3 时 Java 已认证的未决观察直接忽略。
     *
     * @param worker worker ID
     * @param report 含 jobId/instanceId/requestHash/outcomeJson/actualFinished 的报告
     * @throws StoreException JOB_EVIDENCE_MISMATCH：作业不存在或归属/实例/请求摘要不匹配
     * @throws StoreException JOB_RESULT_CONFLICT：重复报告内容与已存结果不一致
     */
    public void report(String worker, JSONObject report) {
        store.tx(
                c -> {
                    JSONObject j =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_model_job WHERE job_id=?" + lock,
                                            report.getString("jobId")));
                    if (j == null
                            || !worker.equals(j.optString("worker_id"))
                            || !j.getString("instance_id").equals(report.getString("instanceId"))
                            || !j.getString("request_hash").equals(report.getString("requestHash"))) {
                        throw new StoreException("JOB_EVIDENCE_MISMATCH");
                    }
                    String result =
                            canonical(
                                    cube.service.harness.execution.ModelProtocol.outcome(
                                            report.getString("outcomeJson"),
                                            report.getBoolean("actualFinished")));
                    if (j.getInt("actual_finished") == 1) {
                        if (!j.getString("outcome_json").equals(result)
                                || !report.getBoolean("actualFinished")) {
                            throw new StoreException("JOB_RESULT_CONFLICT");
                        }
                        return null;
                    }
                    // unknown 被隔离：后续持久 completed 可以结算；重复 unknown 必须一致。
                    if (j.getString("status").equals("unknown")
                            && !report.getBoolean("actualFinished")
                            && !j.getString("outcome_json").equals(result)) {
                        if (j.getInt("execution_protocol") == 3) {
                            return null; // Java 已认证这一未决观察。
                        }
                        throw new StoreException("JOB_RESULT_CONFLICT");
                    }
                    update(
                            c,
                            "UPDATE harness_model_job SET status=?,actual_finished=?,outcome_json=? WHERE job_id=?",
                            report.getBoolean("actualFinished") ? "finished" : "unknown",
                            report.getBoolean("actualFinished") ? 1 : 0,
                            result,
                            j.getString("job_id"));
                    if (report.getBoolean("actualFinished")) {
                        update(
                                c,
                                "UPDATE harness_model_job SET finished_at=? WHERE job_id=?",
                                store.now(c),
                                j.getString("job_id"));
                    }
                    return null;
                });
    }

    /**
     * 按作业 ID 读取模型作业行。
     *
     * @param id 作业 ID
     * @return harness_model_job 行；不存在返回 null
     */
    public JSONObject jobById(String id) {
        return store.tx(c -> first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?", id)));
    }

    /**
     * 按 run/round 读取首次尝试（attempt=1）的模型作业行。
     *
     * @param run   run ID
     * @param round 轮次（intent）ID
     * @return harness_model_job 行；不存在返回 null
     */
    public JSONObject job(String run, String round) {
        return store.tx(
                c ->
                        first(
                                rows(
                                        c,
                                        "SELECT * FROM harness_model_job WHERE run_id=? AND round_id=? AND attempt=1",
                                        run,
                                        round)));
    }

    /**
     * 请求取消模型作业；queued 作业尚未派发，以 MODEL_NOT_DISPATCHED 终结。
     *
     * @param lease 当前租约
     * @param job   作业 ID
     */
    public void cancel(Lease lease, String job) {
        store.tx(
                c -> {
                    JSONObject r = store.run(c, lease.tenant, lease.subject, lease.runId, true);
                    store.owner(c, r, lease);
                    update(
                            c,
                            "UPDATE harness_model_job SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE job_id=? AND run_id=?",
                            store.now(c),
                            job,
                            lease.runId);
                    JSONObject outcome =
                            new JSONObject()
                                    .put("status", "error")
                                    .put("errorCode", "MODEL_NOT_DISPATCHED")
                                    .put("audit", new JSONArray())
                                    .put("cancellationObserved", true)
                                    .put(
                                            "usage",
                                            new JSONObject().put("input", 0).put("output", 0));
                    update(
                            c,
                            "UPDATE harness_model_job SET status='finished',actual_finished=1,outcome_json=? WHERE job_id=? AND run_id=? AND status='queued'",
                            canonical(outcome),
                            job,
                            lease.runId);
                    return null;
                });
    }

    /**
     * 列出某 worker 待处理的取消请求。
     *
     * @param worker worker ID
     * @return 作业 ID 数组（cancel_requested=1 且未实际结束）
     */
    public JSONArray cancellations(String worker) {
        return store.tx(
                c -> {
                    JSONArray result = new JSONArray(),
                            list =
                                    rows(
                                            c,
                                            "SELECT job_id FROM harness_model_job WHERE worker_id=? AND cancel_requested=1 AND actual_finished=0",
                                            worker);
                    for (int i = 0; i < list.length(); i++) {
                        result.put(list.getJSONObject(i).getString("job_id"));
                    }
                    return result;
                });
    }

    /**
     * 汇总各 backend 的物理占用与各 worker 的占用。
     *
     * @return 含 backends（每行附带 occupied 计数）与 workers（按 worker 分组计数）的对象
     */
    public JSONObject capacity() {
        return store.tx(
                c ->
                        new JSONObject()
                                .put(
                                        "backends",
                                        rows(
                                                c,
                                                "SELECT b.*, (SELECT COUNT(*) FROM harness_model_job j WHERE j.backend_id=b.backend_id AND j.status<>'queued' AND j.actual_finished=0 AND j.execution_protocol=2) + (SELECT COUNT(*) FROM harness_inference i WHERE i.backend_id=b.backend_id AND i.status<>'queued' AND i.actual_finished=0) AS occupied FROM harness_model_backend b"))
                                .put(
                                        "workers",
                                        rows(
                                                c,
                                                "SELECT worker_id,COUNT(*) AS occupied FROM harness_model_job WHERE status<>'queued' AND actual_finished=0 GROUP BY worker_id")));
    }

    /**
     * 列出可运行的 run（无租户过滤）。
     *
     * @param limit 返回上限
     * @return harness_run 的 run_id/tenant/subject_id 行数组
     */
    public JSONArray runnable(int limit) {
        return runnable(null, limit);
    }

    /**
     * 列出可运行的 run（无租户过滤，带偏移）。
     *
     * @param limit  返回上限
     * @param offset 结果偏移
     * @return harness_run 的 run_id/tenant/subject_id 行数组
     */
    public JSONArray runnable(int limit, long offset) {
        return runnable(null, limit, offset);
    }

    /**
     * 列出某租户下可运行的 run。
     *
     * @param tenant 租户 ID
     * @param limit  返回上限
     * @return harness_run 的 run_id/tenant/subject_id 行数组
     */
    public JSONArray runnable(String tenant, int limit) {
        return runnable(tenant, limit, 0);
    }

    /**
     * 列出可运行的 run：活跃状态，或存在已结束但未确认/未结算 model、tool 作业的 run。
     *
     * @param tenant 租户 ID；为 null 时不按租户过滤
     * @param limit  返回上限（实际裁剪到 1..256）
     * @param offset 结果偏移
     * @return harness_run 的 run_id/tenant/subject_id 行数组
     */
    private JSONArray runnable(String tenant, int limit, long offset) {
        return store.tx(
                c ->
                        rows(
                                c,
                                "SELECT r.run_id,r.tenant,r.subject_id FROM harness_run r WHERE " + (tenant == null ? "" : "r.tenant=? AND ") + "(r.state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling') OR EXISTS (SELECT j.job_id FROM harness_model_job j JOIN harness_round q ON q.run_id=j.run_id AND q.intent_id=j.round_id AND q.attempt=j.attempt JOIN harness_usage u ON u.run_id=q.run_id AND u.round_id=q.intent_id AND u.attempt=q.attempt WHERE j.run_id=r.run_id AND j.actual_finished=1 AND (q.status<>'confirmed' OR (u.actual_input IS NULL AND j.outcome_json LIKE '%\"usage\":%'))) OR EXISTS (SELECT j.job_id FROM harness_tool_job j JOIN harness_tool_call q ON q.run_id=j.run_id AND q.intent_id=j.call_id AND q.attempt=j.attempt WHERE j.run_id=r.run_id AND j.actual_finished=1 AND q.status<>'confirmed')) ORDER BY r.created_at,r.run_id LIMIT "
                                        + Math.max(1, Math.min(256, limit)) + " OFFSET " + Math.max(0, offset),
                                tenant == null ? new Object[0] : new Object[]{id(tenant)}));
    }

    /**
     * 统计某租户下处于活跃状态的 run 数。
     *
     * @param tenant 租户 ID
     * @return queued/running/reconciling/waiting_input/waiting_approval/cancelling 状态的 run 计数
     */
    public int active(String tenant) {
        return store.tx(
                c ->
                        first(
                                rows(
                                        c,
                                        "SELECT COUNT(*) AS n FROM harness_run WHERE tenant=? AND state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')",
                                        tenant))
                                .getInt("n"));
    }

    /**
     * 读取租约对应 run 的创建时间。
     *
     * @param lease 当前租约
     * @return created_at（毫秒时间戳）
     */
    public long created(Lease lease) {
        return store.tx(
                c ->
                        store.run(c, lease.tenant, lease.subject, lease.runId, false)
                                .getLong("created_at"));
    }

    /**
     * 物理未知与未对账回执必须阻止运维 drain。
     *
     * @return 含 activeRuns/unsettledModelJobs/unsettledToolJobs/unknownRuns/drained 的对象
     */
    public JSONObject drainStatus() {
        return drainStatus(null);
    }

    /**
     * 物理未知与未对账回执必须阻止运维 drain（可按租户过滤）。
     *
     * @param tenant 租户 ID；为 null 时统计全部
     * @return 含 activeRuns/unsettledModelJobs/unsettledToolJobs/unknownRuns/drained 的对象；
     * drained 仅当四类计数全为 0
     */
    public JSONObject drainStatus(String tenant) {
        return store.tx(
                c -> {
                    int active =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_run WHERE " + (tenant == null ? "" : "tenant=? AND ") + "state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')",
                                            tenant == null ? new Object[0] : new Object[]{tenant}))
                                    .getInt("n");
                    int model =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_model_job j JOIN harness_round q ON q.run_id=j.run_id AND q.intent_id=j.round_id AND q.attempt=j.attempt WHERE " + (tenant == null ? "" : "j.tenant=? AND ") + "(j.actual_finished=0 OR q.status<>'confirmed')",
                                            tenant == null ? new Object[0] : new Object[]{tenant}))
                                    .getInt("n");
                    int tool =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_tool_job j JOIN harness_tool_call q ON q.run_id=j.run_id AND q.intent_id=j.call_id AND q.attempt=j.attempt WHERE " + (tenant == null ? "" : "j.tenant=? AND ") + "(j.actual_finished=0 OR q.status<>'confirmed')",
                                            tenant == null ? new Object[0] : new Object[]{tenant}))
                                    .getInt("n");
                    int unknown =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_run WHERE " + (tenant == null ? "" : "tenant=? AND ") + "execution_settlement='unknown'",
                                            tenant == null ? new Object[0] : new Object[]{tenant}))
                                    .getInt("n");
                    return new JSONObject()
                            .put("activeRuns", active)
                            .put("unsettledModelJobs", model)
                            .put("unsettledToolJobs", tool)
                            .put("unknownRuns", unknown)
                            .put("drained", active == 0 && model == 0 && tool == 0 && unknown == 0);
                });
    }

    /**
     * 读取指定 run 的创建时间。
     *
     * @param tenant  租户 ID
     * @param subject 主体 ID
     * @param run     run ID
     * @return created_at（毫秒时间戳）
     */
    public long created(String tenant, String subject, String run) {
        return store.tx(c -> store.run(c, tenant, subject, run, false).getLong("created_at"));
    }

    /**
     * 判断 run 是否已超过生命周期。
     *
     * @param tenant   租户 ID
     * @param subject  主体 ID
     * @param run      run ID
     * @param lifetime 生命周期毫秒数
     * @return 当前时间减去 created_at 大于 lifetime 时返回 true
     */
    public boolean expired(String tenant, String subject, String run, long lifetime) {
        return store.tx(
                c ->
                        store.now(c)
                                - store.run(c, tenant, subject, run, false)
                                .getLong("created_at")
                                > lifetime);
    }
}
