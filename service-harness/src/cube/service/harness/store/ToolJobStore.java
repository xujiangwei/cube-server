/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.execution.ToolProtocol;
import org.json.JSONArray;
import org.json.JSONObject;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 工具作业的持久受理与证据账本：会话、超时与观察 ACK 都不能证明执行已结束。
 *
 * <p>作业经有界队列（256）进入持久化派发；Runner 领取后先落本地 journal 再请求
 * durable accepted，传输重送不增加 attempt。撤销（revoke）与取消
 * （cancel_requested）只记录义务，不替代物理执行证据；queued 作业尚未到达
 * worker、无副作用，可直接终结。broker_protocol 的 5/6 整数编码仅用于
 * schema7 存储兼容，不是公开的 Runner 版本。
 */
public final class ToolJobStore {
    /**
     * 共享的权威账本入口；所有读写都经由其短事务执行。
     */
    private final JdbcRunStore store;

    /**
     * MySQL 下的行锁后缀；非 MySQL 为空串，用于串行化跨 JVM 的受理与认领。
     */
    private final String lock;

    /**
     * 作业线格式工厂：把持久作业行转换为发给 Runner 的 wire 报文。
     */
    public interface WireFactory {
        /**
         * 构造 wire 报文。
         *
         * @param job      持久化后的 harness_tool_job 行
         * @param worker   领取该作业的 worker ID
         * @param runtime  领取该作业的 runtime ID
         * @param instance 领取该作业的实例 ID
         * @return wire 报文 JSON；必须包含 jobHash 键
         */
        JSONObject create(JSONObject job, String worker, String runtime, String instance);
    }

    /**
     * 构造工具作业存储。
     *
     * @param store 共享的 JdbcRunStore 事务入口
     * @param mysql 是否 MySQL 数据库；决定读关键行是否追加 FOR UPDATE 行锁
     */
    public ToolJobStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        lock = mysql ? " FOR UPDATE" : "";
    }

    // 保持 schema7 既有数据库上的整数编码稳定。它不是公开的 Runner 版本。

    /**
     * 把 Runner 类型映射为 schema7 的整数存储编码。
     *
     * @param runnerType Runner 类型（ToolProtocol.TOOL 或 ToolProtocol.SANDBOX）
     * @return tool 对应 5，sandbox 对应 6
     * @throws StoreException RUNNER_TYPE_INVALID：类型不在受支持集合内
     */
    private static int storageCode(String runnerType) {
        if (ToolProtocol.TOOL.equals(runnerType)) {
            return 5;
        }
        if (ToolProtocol.SANDBOX.equals(runnerType)) {
            return 6;
        }
        throw new StoreException("RUNNER_TYPE_INVALID");
    }

    /**
     * 从作业行反推 Runner 类型。
     *
     * @param job 含 broker_protocol 列的 harness_tool_job 行
     * @return 5 映射为 tool，6 映射为 sandbox
     * @throws StoreException RUNNER_TYPE_INVALID：broker_protocol 不是受支持的编码
     */
    public static String runnerType(JSONObject job) {
        switch (job.getInt("broker_protocol")) {
            case 5:
                return ToolProtocol.TOOL;
            case 6:
                return ToolProtocol.SANDBOX;
            default:
                throw new StoreException("RUNNER_TYPE_INVALID");
        }
    }

    /**
     * 把已确认的工具调用意图入队为持久作业。
     *
     * <p>先核对权威 intent 行（摘要必须一致），作业 ID 由
     * run/intent/attempt 派生故天然幂等；入队前以单例行锁串行化跨 JVM 的
     * 有界受理，未结束作业满 256 拒绝。
     *
     * @param lease      当前租约
     * @param intent     权威 harness_tool_call 行
     * @param definition 工具定义（release、tool.runnerType 等）
     * @return 新建或已存在的 harness_tool_job 行
     * @throws StoreException INTENT_EVIDENCE_MISMATCH：权威 intent 缺失或与传入内容不一致
     * @throws StoreException TOOL_QUEUE_FULL：未结束作业数达到 256 上限
     */
    public JSONObject enqueue(Lease lease, JSONObject intent, JSONObject definition) {
        return store.tx(
                c -> {
                    JSONObject run = store.run(c, lease.tenant, lease.subject, lease.runId, true);
                    store.owner(c, run, lease);
                    JSONObject authoritative =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_tool_call WHERE run_id=? AND intent_id=? AND attempt=?",
                                            lease.runId,
                                            intent.getString("intent_id"),
                                            intent.getInt("attempt")));
                    if (authoritative == null || !canonical(authoritative).equals(canonical(intent))) {
                        throw new StoreException("INTENT_EVIDENCE_MISMATCH");
                    }
                    String job =
                            hash(
                                    new JSONArray()
                                            .put(lease.runId)
                                            .put(intent.getString("intent_id"))
                                            .put(intent.getInt("attempt")));
                    JSONObject old = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", job));
                    if (old != null) {
                        return old;
                    }
                    // 跨 JVM（含 unknown 预留）串行化有界受理。
                    if (!lock.isEmpty()) {
                        rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1 FOR UPDATE");
                    }
                    if (first(rows(c, "SELECT COUNT(*) AS n FROM harness_tool_job WHERE actual_finished=0"))
                            .getInt("n")
                            >= 256) {
                        throw new StoreException("TOOL_QUEUE_FULL");
                    }
                    JSONObject call = new JSONObject(intent.getString("payload_json"));
                    JSONObject arguments =
                            cube.service.harness.protocol.StrictJson.object(
                                    call.getString("argumentsJson")
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    update(
                            c,
                            "INSERT INTO harness_tool_job (job_id,run_id,tenant,subject_id,call_id,attempt,origin_fence,intent_hash,release_digest,tool_name,definition_json,arguments_json,arguments_hash,broker_protocol,status,actual_finished,cancel_requested,effect_status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'queued',0,0,'none',?)",
                            job,
                            lease.runId,
                            lease.tenant,
                            lease.subject,
                            intent.getString("intent_id"),
                            intent.getInt("attempt"),
                            intent.getLong("origin_fence"),
                            intent.getString("intent_hash"),
                            definition.getString("release"),
                            call.getString("tool"),
                            canonical(definition),
                            canonical(arguments),
                            hash(arguments),
                            storageCode(definition.getJSONObject("tool").getString("runnerType")),
                            store.now(c));
                    return first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", job));
                });
    }

    /**
     * 登记或核对 Runner 身份：workerId 持久绑定原 runtimeId 与容量。
     *
     * @param worker   worker ID
     * @param runtime  runtime ID
     * @param capacity 该 worker 的处理容量
     * @throws StoreException RUNNER_IDENTITY_CONFLICT：已登记且 runtimeId 或容量不一致
     */
    public void identity(String worker, String runtime, int capacity) {
        store.tx(
                c -> {
                    JSONObject old =
                            first(
                                    rows(
                                            c, "SELECT * FROM harness_runner_identity WHERE worker_id=?" + lock, worker));
                    if (old == null) {
                        update(
                                c, "INSERT INTO harness_runner_identity VALUES (?,?,?)", worker, runtime, capacity);
                    } else if (!runtime.equals(old.getString("runtime_id"))
                            || capacity != old.getInt("capacity")) {
                        throw new StoreException("RUNNER_IDENTITY_CONFLICT");
                    }
                    return null;
                });
    }

    /**
     * 领取下一个工具作业（默认 tool 类型）。
     *
     * @param worker   worker ID
     * @param runtime  runtime ID
     * @param instance 实例 ID
     * @param healthy  worker 自报健康；不健康则不派发
     * @param factory  wire 报文工厂
     * @return wire 报文；无合适作业时返回 null
     */
    public JSONObject poll(
            String worker, String runtime, String instance, boolean healthy, WireFactory factory) {
        return poll(worker, runtime, instance, healthy, ToolProtocol.TOOL, factory);
    }

    /**
     * 按 Runner 类型领取下一个工具作业。
     *
     * <p>身份不符、不健康或存在未对账 unknown 作业时拒绝派发；已 assigned 的
     * 作业按 wire_json 重放（重送不重复派发）；达到自身容量上限时空手返回；
     * 仅领取所属 run 处于 running 的 queued 作业，认领冲突抛错。
     *
     * @param worker     worker ID
     * @param runtime    runtime ID
     * @param instance   实例 ID
     * @param healthy    worker 自报健康；不健康则不派发
     * @param runnerType Runner 类型（tool 或 sandbox）
     * @param factory    wire 报文工厂
     * @return wire 报文；无合适作业时返回 null
     * @throws StoreException RUNNER_IDENTITY_CONFLICT：身份未登记或 runtime 不匹配
     * @throws StoreException TOOL_CLAIM_CONFLICT：抢占 queued 作业的 UPDATE 未命中
     */
    public JSONObject poll(
            String worker,
            String runtime,
            String instance,
            boolean healthy,
            String runnerType,
            WireFactory factory) {
        final int runnerCode = storageCode(runnerType);
        return store.tx(
                c -> {
                    JSONObject identity =
                            first(
                                    rows(
                                            c, "SELECT * FROM harness_runner_identity WHERE worker_id=?" + lock, worker));
                    if (identity == null || !runtime.equals(identity.getString("runtime_id"))) {
                        throw new StoreException("RUNNER_IDENTITY_CONFLICT");
                    }
                    if (!healthy
                            || first(
                            rows(
                                    c,
                                    "SELECT job_id FROM harness_tool_job WHERE worker_id=? AND status='unknown' AND actual_finished=0 LIMIT 1",
                                    worker))
                            != null) {
                        return null;
                    }
                    JSONObject existing =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_tool_job WHERE worker_id=? AND runtime_id=? AND broker_protocol=? AND status='assigned' AND cancel_requested=0 ORDER BY created_at LIMIT 1",
                                            worker,
                                            runtime,
                                            runnerCode));
                    if (existing != null) {
                        return new JSONObject(existing.getString("wire_json"));
                    }
                    if (!healthy
                            || first(
                            rows(
                                    c,
                                    "SELECT COUNT(*) AS n FROM harness_tool_job WHERE worker_id=? AND actual_finished=0 AND status<>'queued'",
                                    worker))
                            .getInt("n")
                            >= identity.getInt("capacity")) {
                        return null;
                    }
                    JSONObject job =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_tool_job WHERE status='queued' AND broker_protocol=?"
                                                    + " AND cancel_requested=0 AND EXISTS (SELECT run_id FROM harness_run r WHERE r.run_id=harness_tool_job.run_id AND r.state='running') ORDER BY created_at,job_id LIMIT 1"
                                                    + lock,
                                            runnerCode));
                    if (job == null) {
                        return null;
                    }
                    JSONObject wire = factory.create(job, worker, runtime, instance);
                    if (update(
                            c,
                            "UPDATE harness_tool_job SET worker_id=?,runtime_id=?,instance_id=?,job_hash=?,wire_json=?,status='assigned' WHERE job_id=? AND status='queued'",
                            worker,
                            runtime,
                            instance,
                            wire.getString("jobHash"),
                            canonical(wire),
                            job.getString("job_id"))
                            != 1) {
                        throw new StoreException("TOOL_CLAIM_CONFLICT");
                    }
                    update(
                            c,
                            "UPDATE harness_tool_job SET dispatched_at=? WHERE job_id=?",
                            store.now(c),
                            job.getString("job_id"));
                    return wire;
                });
    }

    /**
     * 按 run/call/attempt 查找工具作业行。
     *
     * @param run     run ID
     * @param call    调用（intent）ID
     * @param attempt attempt 序号
     * @return 匹配的 harness_tool_job 行；不存在返回 null
     */
    public JSONObject find(String run, String call, int attempt) {
        return store.tx(
                c ->
                        first(
                                rows(
                                        c,
                                        "SELECT * FROM harness_tool_job WHERE run_id=? AND call_id=? AND attempt=?",
                                        run,
                                        call,
                                        attempt)));
    }

    /**
     * 按作业 ID 读取工具作业行。
     *
     * @param id 作业 ID
     * @return harness_tool_job 行
     * @throws StoreException TOOL_JOB_NOT_FOUND：作业不存在
     */
    public JSONObject job(String id) {
        return store.tx(
                c -> {
                    JSONObject j = first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", id));
                    if (j == null) {
                        throw new StoreException("TOOL_JOB_NOT_FOUND");
                    }
                    return j;
                });
    }

    /**
     * 登记到期的过程文件清理，并返回当前可清理的作业摘要。
     *
     * <p>仅对已确认（intent confirmed）、已实际结束且效果非 unknown 的作业
     * 设置 cleanup_after；返回受 32 条上限约束的待清理清单。
     *
     * @param worker  worker ID
     * @param runtime runtime ID
     * @param grace   距离当时的宽限毫秒数
     * @return 含 jobId/jobHash/runtimeId/resultHash 的待清理作业数组
     */
    public JSONArray cleanups(String worker, String runtime, long grace) {
        return store.tx(
                c -> {
                    long now = store.now(c);
                    update(
                            c,
                            "UPDATE harness_tool_job SET cleanup_after=? WHERE worker_id=? AND runtime_id=? AND job_hash IS NOT NULL AND report_hash IS NOT NULL AND actual_finished=1 AND effect_status<>'unknown' AND cleanup_after=0 AND EXISTS (SELECT intent_id FROM harness_tool_call q WHERE q.run_id=harness_tool_job.run_id AND q.intent_id=harness_tool_job.call_id AND q.attempt=harness_tool_job.attempt AND q.status='confirmed')",
                            now + grace,
                            worker,
                            runtime);
                    JSONArray rows =
                            rows(
                                    c,
                                    "SELECT * FROM harness_tool_job WHERE worker_id=? AND runtime_id=? AND actual_finished=1 AND effect_status<>'unknown' AND cleanup_after>0 AND cleanup_after<=? AND cleanup_done=0 LIMIT 32",
                                    worker,
                                    runtime,
                                    now),
                            out = new JSONArray();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject j = rows.getJSONObject(i);
                        out.put(
                                new JSONObject()
                                        .put("jobId", j.getString("job_id"))
                                        .put("jobHash", j.getString("job_hash"))
                                        .put("runtimeId", runtime)
                                        .put("resultHash", j.getString("report_hash")));
                    }
                    return out;
                });
    }

    /**
     * 确认一次过程文件清理已完成。
     *
     * @param worker     worker ID
     * @param runtime    runtime ID
     * @param job        作业 ID
     * @param hash       作业线格式摘要（job_hash）
     * @param reportHash 结果摘要（report_hash）
     * @throws StoreException CLEANUP_EVIDENCE_MISMATCH：归属、摘要、结束状态或清理窗口校验失败
     */
    public void cleaned(String worker, String runtime, String job, String hash, String reportHash) {
        store.tx(
                c -> {
                    JSONObject j =
                            first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?" + lock, job));
                    if (j == null
                            || !worker.equals(j.optString("worker_id"))
                            || !runtime.equals(j.optString("runtime_id"))
                            || !hash.equals(j.optString("job_hash"))
                            || !reportHash.equals(j.optString("report_hash"))
                            || j.getInt("actual_finished") != 1
                            || j.getLong("cleanup_after") == 0
                            || j.getLong("cleanup_after") > store.now(c)) {
                        throw new StoreException("CLEANUP_EVIDENCE_MISMATCH");
                    }
                    update(c, "UPDATE harness_tool_job SET cleanup_done=1 WHERE job_id=?", job);
                    return null;
                });
    }

    /**
     * 读取并校验作业的归属证据（worker/runtime/job_hash 必须匹配）。
     *
     * @param worker  worker ID
     * @param runtime runtime ID
     * @param job     作业 ID
     * @param digest  调用方声明的作业摘要
     * @return harness_tool_job 行
     * @throws StoreException TOOL_EVIDENCE_MISMATCH：作业不存在或归属/摘要不匹配
     */
    public JSONObject evidence(String worker, String runtime, String job, String digest) {
        JSONObject j = job(job);
        if (!worker.equals(j.optString("worker_id"))
                || !runtime.equals(j.optString("runtime_id"))
                || !digest.equals(j.optString("job_hash"))) {
            throw new StoreException("TOOL_EVIDENCE_MISMATCH");
        }
        return j;
    }

    /**
     * Runner 确认接受作业：accepted 与上游派发前重新授权。
     *
     * @param job    作业 ID
     * @param digest 调用方声明的作业摘要
     * @return harness_tool_job 行
     * @throws StoreException TOOL_EVIDENCE_MISMATCH：作业不存在或摘要不匹配
     * @throws StoreException TOOL_REVOKED：作业已取消或所属 run 已撤销
     */
    public JSONObject accepted(String job, String digest) {
        return store.tx(
                c -> {
                    JSONObject j =
                            first(rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?" + lock, job));
                    if (j == null || !digest.equals(j.optString("job_hash"))) {
                        throw new StoreException("TOOL_EVIDENCE_MISMATCH");
                    }
                    if (j.getInt("cancel_requested") != 0 || revoked(c, j)) {
                        throw new StoreException("TOOL_REVOKED");
                    }
                    if (j.getString("status").equals("assigned")) {
                        update(c, "UPDATE harness_tool_job SET status='accepted' WHERE job_id=?", job);
                    }
                    return j;
                });
    }

    /**
     * 记录工具作业结果（非独立 broker 场景的便捷重载）。
     *
     * @param job    权威 harness_tool_job 行
     * @param report Runner 报告
     * @param result 工具结果 JSON
     * @return 更新后的 harness_tool_job 行
     */
    public JSONObject report(JSONObject job, JSONObject report, JSONObject result) {
        return report(job, report, result, false);
    }

    /**
     * 记录工具作业结果；传输重送不增加 attempt。
     *
     * <p>已实际结束时按 report_hash 幂等回放，冲突抛错；效果未知而
     * independent 且 broker 交换未完成时，作业保持 unknown 并记为
     * BROKER_OUTCOME_UNKNOWN——结束观察不构成物理结束证据。
     *
     * @param job         权威 harness_tool_job 行
     * @param report      Runner 报告（actualFinished、effectStatus 等）
     * @param result      工具结果 JSON
     * @param independent 是否为独立 broker 执行（需等待 broker 交换完成才能终结）
     * @return 更新后的 harness_tool_job 行
     * @throws StoreException TOOL_RESULT_CONFLICT：重复报告的内容与已存结果不一致
     */
    public JSONObject report(
            JSONObject job, JSONObject report, JSONObject result, boolean independent) {
        return store.tx(
                c -> {
                    JSONObject j =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_tool_job WHERE job_id=?" + lock,
                                            job.getString("job_id")));
                    String hash = hash(report);
                    if (j.getInt("actual_finished") == 1) {
                        if (!hash.equals(j.optString("report_hash"))) {
                            throw new StoreException("TOOL_RESULT_CONFLICT");
                        }
                        return j;
                    }
                    if (j.getString("status").equals("unknown")
                            && !report.getBoolean("actualFinished")
                            && !hash.equals(j.optString("report_hash"))) {
                        throw new StoreException("TOOL_RESULT_CONFLICT");
                    }
                    boolean ended = report.getBoolean("actualFinished");
                    JSONObject evidence =
                            first(
                                    rows(
                                            c,
                                            "SELECT status FROM harness_broker_exchange WHERE job_id=?",
                                            j.getString("job_id")));
                    boolean externalPending =
                            independent && evidence != null && !evidence.getString("status").equals("finished");
                    ended = ended && !externalPending;
                    JSONObject savedResult =
                            externalPending ? error("BROKER_OUTCOME_UNKNOWN").put("status", "unknown") : result;
                    update(
                            c,
                            "UPDATE harness_tool_job SET status=?,actual_finished=?,effect_status=?,result_json=?,report_hash=?,runner_finished=? WHERE job_id=?",
                            ended ? "finished" : "unknown",
                            ended ? 1 : 0,
                            externalPending ? "unknown" : report.getString("effectStatus"),
                            canonical(savedResult),
                            hash,
                            report.getBoolean("actualFinished") ? 1 : 0,
                            j.getString("job_id"));
                    if (ended) {
                        update(
                                c,
                                "UPDATE harness_tool_job SET finished_at=? WHERE job_id=?",
                                store.now(c),
                                j.getString("job_id"));
                    }
                    return first(
                            rows(c, "SELECT * FROM harness_tool_job WHERE job_id=?", j.getString("job_id")));
                });
    }

    /**
     * 判断作业所属 run 是否已撤销（事务内版本，供行锁串行读取）。
     *
     * @param c 事务连接
     * @param j harness_tool_job 行
     * @return 存在对应撤销记录时返回 true
     */
    private boolean revoked(java.sql.Connection c, JSONObject j) throws Exception {
        return first(
                rows(
                        c,
                        "SELECT run_id FROM harness_tool_revocation WHERE run_id=? AND tenant=? AND subject_id=?",
                        j.getString("run_id"),
                        j.getString("tenant"),
                        j.getString("subject_id")))
                != null;
    }

    /**
     * 判断作业所属 run 是否已撤销。
     *
     * @param j harness_tool_job 行
     * @return 存在对应撤销记录时返回 true
     */
    public boolean revoked(JSONObject j) {
        return store.tx(c -> revoked(c, j));
    }

    /**
     * 读取作业所属 run 的当前权威快照。
     *
     * @param j harness_tool_job 行（至少含 tenant/subject_id/run_id）
     * @return run 快照（状态、usage、result 等）
     */
    public JSONObject currentRun(JSONObject j) {
        return store.read(j.getString("tenant"), j.getString("subject_id"), j.getString("run_id"));
    }

    /**
     * 请求取消单个作业；queued 作业尚未到达 worker、无副作用，直接终结。
     *
     * @param l   当前租约
     * @param job 作业 ID
     * @throws StoreException TOOL_JOB_NOT_FOUND：作业不存在或不属于该 run
     */
    public void cancel(Lease l, String job) {
        store.tx(
                c -> {
                    store.owner(c, store.run(c, l.tenant, l.subject, l.runId, true), l);
                    JSONObject j =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_tool_job WHERE job_id=? AND run_id=?" + lock,
                                            job,
                                            l.runId));
                    if (j == null) {
                        throw new StoreException("TOOL_JOB_NOT_FOUND");
                    }
                    update(
                            c,
                            "UPDATE harness_tool_job SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE job_id=?",
                            store.now(c),
                            job);
                    if (j.getString("status").equals("queued")) {
                        update(
                                c,
                                "UPDATE harness_tool_job SET status='finished',actual_finished=1,effect_status='none',result_json=? WHERE job_id=?",
                                canonical(error("TOOL_CANCELLED")),
                                job);
                    }
                    return null;
                });
    }

    /**
     * 撤销整个 run 的工具执行：记录撤销义务并终结所有 queued 作业。
     *
     * <p>撤销行只落一次；未结束作业统一打上取消标记；queued 作业直接以
     * TOOL_REVOKED 终结，其余作业的物理结果仍由后续证据结算。
     *
     * @param tenant  租户 ID
     * @param subject 主体 ID
     * @param run     run ID
     * @param reason  撤销原因
     */
    public void revoke(String tenant, String subject, String run, String reason) {
        store.tx(
                c -> {
                    store.run(c, tenant, subject, run, true);
                    if (first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                            == null) {
                        update(
                                c,
                                "INSERT INTO harness_tool_revocation VALUES (?,?,?,?)",
                                run,
                                tenant,
                                subject,
                                reason);
                    }
                    update(
                            c,
                            "UPDATE harness_tool_job SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE run_id=? AND actual_finished=0",
                            store.now(c),
                            run);
                    // queued 作业尚未到达 worker，没有副作用。
                    update(
                            c,
                            "UPDATE harness_tool_job SET status='finished',actual_finished=1,effect_status='none',result_json=? WHERE run_id=? AND status='queued'",
                            canonical(error("TOOL_REVOKED")),
                            run);
                    return null;
                });
    }

    /**
     * 列出某 worker 待处理的取消请求（最多 64 条）。
     *
     * @param worker worker ID
     * @return 作业 ID 数组（cancel_requested=1 且未实际结束）
     */
    public JSONArray cancellations(String worker) {
        return store.tx(
                c ->
                        rows(
                                c,
                                "SELECT job_id FROM harness_tool_job WHERE worker_id=? AND cancel_requested=1 AND actual_finished=0 LIMIT 64",
                                worker));
    }

    /**
     * 统计某 worker 当前占用的处理槽。
     *
     * @param worker worker ID
     * @return 未结束且非 queued 的作业数
     */
    public int occupied(String worker) {
        return store.tx(
                c ->
                        first(
                                rows(
                                        c,
                                        "SELECT COUNT(*) AS n FROM harness_tool_job WHERE worker_id=? AND actual_finished=0 AND status<>'queued'",
                                        worker))
                                .getInt("n"));
    }

    /**
     * 判断 worker 是否健康：不存在未对账的 unknown 作业。
     *
     * @param worker worker ID
     * @return 没有 unknown 且未实际结束的作业时返回 true
     */
    public boolean healthy(String worker) {
        return store.tx(
                c ->
                        first(
                                rows(
                                        c,
                                        "SELECT job_id FROM harness_tool_job WHERE worker_id=? AND status='unknown' AND actual_finished=0 LIMIT 1",
                                        worker))
                                == null);
    }

    /**
     * 创建或读取 broker 交换行，按 (job, request_hash) 幂等。
     *
     * <p>先用作业行锁串行化首次提交；同一 node 前缀为 python: 的拥有者
     * 之间不允许易主，防止重发可能已发送的上游请求。
     *
     * @param job         作业 ID
     * @param requestHash 上游请求摘要
     * @param node        发起节点 ID
     * @param create      不存在时是否创建
     * @return 交换行（含 new 标记）；不存在且不创建时返回 null
     * @throws StoreException BROKER_ARGUMENT_CONFLICT：已存在交换行但 request_hash 不同
     * @throws StoreException BROKER_OWNER_CONFLICT：python: 前缀节点争用同一交换行
     */
    public JSONObject brokerExchange(String job, String requestHash, String node, boolean create) {
        return store.tx(
                c -> {
                    // 交换行尚不存在时，由作业行锁串行化首次提交。
                    rows(c, "SELECT job_id FROM harness_tool_job WHERE job_id=?" + lock, job);
                    JSONObject old =
                            first(rows(c, "SELECT * FROM harness_broker_exchange WHERE job_id=?", job));
                    if (old != null) {
                        if (!requestHash.equals(old.getString("request_hash"))) {
                            throw new StoreException("BROKER_ARGUMENT_CONFLICT");
                        }
                        if ((node.startsWith("python:") || old.getString("owner_id").startsWith("python:"))
                                && !node.equals(old.getString("owner_id"))) {
                            throw new StoreException("BROKER_OWNER_CONFLICT");
                        }
                        return old.put("new", false);
                    }
                    if (!create) {
                        return null;
                    }
                    update(
                            c,
                            "INSERT INTO harness_broker_exchange VALUES (?,?,?,'dispatched',NULL)",
                            job,
                            requestHash,
                            node);
                    return new JSONObject().put("status", "dispatched").put("new", true);
                });
    }

    /**
     * 记录 broker 上游交换的最终结果。
     *
     * <p>unknown 只结束观察；finished 且作业此前被 Runner 标记结束时，
     * 说明 Runner 早于 broker 结束，按 TOOL_RUNNER_ENDED_EARLY 终结。
     *
     * @param job      作业 ID
     * @param node     拥有者节点 ID
     * @param response 上游响应 JSON
     * @param unknown  结果是否无法确认
     */
    public void brokerFinish(String job, String node, JSONObject response, boolean unknown) {
        store.tx(
                c -> {
                    rows(c, "SELECT job_id FROM harness_tool_job WHERE job_id=?" + lock, job);
                    int changed =
                            update(
                                    c,
                                    "UPDATE harness_broker_exchange SET status=?,response_json=? WHERE job_id=? AND owner_id=? AND status='dispatched'",
                                    unknown ? "unknown" : "finished",
                                    canonical(response),
                                    job,
                                    node);
                    if (changed == 1 && !unknown) {
                        update(
                                c,
                                "UPDATE harness_tool_job SET status='finished',actual_finished=1,effect_status='none',result_json=? WHERE job_id=? AND status='unknown' AND runner_finished=1",
                                canonical(error("TOOL_RUNNER_ENDED_EARLY")),
                                job);
                    }
                    update(
                            c,
                            "UPDATE harness_tool_job SET finished_at=COALESCE(finished_at,?) WHERE job_id=? AND actual_finished=1",
                            store.now(c),
                            job);
                    return null;
                });
    }

    /**
     * Python 运行时拥有物理执行；Java 重启绝不接管这份回执。
     *
     * @param job     作业 ID
     * @param owner   回执所有者节点 ID
     * @param receipt 回执 JSON
     * @throws StoreException BROKER_OWNER_CONFLICT：交换行不存在或所有者不匹配
     * @throws StoreException TOOL_RESULT_CONFLICT：已结束交换行的回执内容不一致
     */
    public void workerFinish(String job, String owner, JSONObject receipt) {
        store.tx(
                c -> {
                    rows(c, "SELECT job_id FROM harness_tool_job WHERE job_id=?" + lock, job);
                    JSONObject old =
                            first(rows(c, "SELECT * FROM harness_broker_exchange WHERE job_id=?", job));
                    if (old == null || !owner.equals(old.getString("owner_id"))) {
                        throw new StoreException("BROKER_OWNER_CONFLICT");
                    }
                    if (old.getString("status").equals("finished")) {
                        if (!canonical(receipt).equals(old.getString("response_json"))) {
                            throw new StoreException("TOOL_RESULT_CONFLICT");
                        }
                    } else {
                        update(
                                c,
                                "UPDATE harness_broker_exchange SET status='finished',response_json=? WHERE job_id=? AND owner_id=?",
                                canonical(receipt),
                                job,
                                owner);
                    }
                    return null;
                });
    }

    /**
     * 列出某节点名下仍未知的 broker 交换作业（最多 8 条）。
     *
     * @param node 拥有者节点 ID
     * @return 关联的 harness_tool_job 行数组
     */
    public JSONArray unresolvedBroker(String node) {
        return store.tx(
                c ->
                        rows(
                                c,
                                "SELECT j.* FROM harness_tool_job j JOIN harness_broker_exchange b ON b.job_id=j.job_id WHERE b.owner_id=? AND b.status='unknown' AND j.actual_finished=0 LIMIT 8",
                                node));
    }

    /**
     * 用 PROVIDER_RESULT_UNAVAILABLE 结算一个未知的 broker 交换。
     *
     * @param job  作业 ID
     * @param node 拥有者节点 ID
     */
    public void brokerReconciled(String job, String node) {
        store.tx(
                c -> {
                    rows(c, "SELECT job_id FROM harness_tool_job WHERE job_id=?" + lock, job);
                    int changed =
                            update(
                                    c,
                                    "UPDATE harness_broker_exchange SET status='finished',response_json=? WHERE job_id=? AND owner_id=? AND status='unknown'",
                                    canonical(error("PROVIDER_RESULT_UNAVAILABLE")),
                                    job,
                                    node);
                    if (changed == 1) {
                        update(
                                c,
                                "UPDATE harness_tool_job SET status='finished',actual_finished=1,effect_status='none',finished_at=?,result_json=? WHERE job_id=? AND status='unknown' AND runner_finished=1",
                                store.now(c),
                                canonical(error("PROVIDER_RESULT_UNAVAILABLE")),
                                job);
                    }
                    return null;
                });
    }

    /**
     * broker 节点重启：仅把本节点原 dispatched 窗口转为 unknown，避免重发。
     *
     * @param node 拥有者节点 ID
     */
    public void brokerRestart(String node) {
        store.tx(
                c -> {
                    update(
                            c,
                            "UPDATE harness_broker_exchange SET status='unknown',response_json=? WHERE owner_id=? AND status='dispatched'",
                            canonical(error("BROKER_OUTCOME_UNKNOWN")),
                            node);
                    return null;
                });
    }

    /**
     * 构造标准错误结果对象。
     *
     * @param code 错误码（如 TOOL_CANCELLED、BROKER_OUTCOME_UNKNOWN）
     * @return 含 status=error、空 artifacts 与 errorCode 的 JSON 对象
     */
    public static JSONObject error(String code) {
        return new JSONObject()
                .put("status", "error")
                .put("artifacts", new JSONArray())
                .put("errorCode", code);
    }
}
