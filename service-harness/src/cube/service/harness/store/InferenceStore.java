/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.aigc.usage.UsageContext;
import cube.aigc.usage.UsageWriter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 推理副作用与完成证据的权威账本：Java 独立于 Python 处理流程持有推理记录。
 *
 * <p>推理调用记录（harness_inference）在派发前落库，发送窗口内崩溃也不会导致重发；
 * worker 对物理完成与 usage 的断言一律忽略，只有 Java 后端证据可以结算。
 * 与模型作业（harness_model_job）按 execution_protocol=3 绑定，同一 attempt 重复提交
 * 按 request_hash 幂等回放，摘要不同返回冲突。
 */
public final class InferenceStore {
    /**
     * 共享的权威账本入口；所有读写都经由其短事务执行。
     */
    private final JdbcRunStore store;

    private final UsageWriter accounting;

    public UsageContext accountingContext(String inference) {
        return store.tx(c -> {
            JSONObject r = first(rows(c, "SELECT r.tenant,r.subject_id,r.run_id FROM harness_inference i JOIN harness_model_job j ON j.job_id=i.job_id JOIN harness_run r ON r.run_id=j.run_id WHERE i.inference_id=?", inference));
            if (r == null) throw new StoreException("INFERENCE_NOT_FOUND");
            return UsageContext.run(r.getString("tenant"), Long.parseLong(r.getString("subject_id")), r.getString("run_id"), inference);
        });
    }

    public void closeAccounting() {
        accounting.close();
    }

    /**
     * MySQL 下的行锁后缀；非 MySQL 为空串，用于串行化跨 JVM 的容量检查。
     */
    private final String lock;

    /**
     * 构造推理存储。
     *
     * @param store 共享的 JdbcRunStore 事务入口
     * @param mysql 是否 MySQL 数据库；决定读关键行是否追加 FOR UPDATE 行锁
     */
    public InferenceStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.lock = mysql ? " FOR UPDATE" : "";
        this.accounting = new cube.aigc.usage.UsageWriter(store.accounting(),
                java.nio.file.Paths.get("storage", "ai-usage-outbox", store.accountingIdentity()));
    }

    /**
     * 读取并校验模型作业行：worker/实例身份与 execution_protocol=3 必须匹配。
     *
     * @param c        事务连接
     * @param job      作业 ID
     * @param worker   声明归属的 worker ID
     * @param instance 声明归属的实例 ID
     * @return 匹配的 harness_model_job 行
     * @throws StoreException JOB_EVIDENCE_MISMATCH：作业不存在、worker/实例不匹配或协议版本不是 3
     */
    private JSONObject job(java.sql.Connection c, String job, String worker, String instance)
            throws Exception {
        JSONObject j = first(rows(c, "SELECT * FROM harness_model_job WHERE job_id=?" + lock, job));
        if (j == null
                || !worker.equals(j.optString("worker_id"))
                || !instance.equals(j.optString("instance_id"))
                || j.getInt("execution_protocol") != 3) {
            throw new StoreException("JOB_EVIDENCE_MISMATCH");
        }
        return j;
    }

    /**
     * 提交一次推理执行：按 (job, wire_attempt) 绑定唯一执行。
     *
     * <p>先校验请求摘要与体积（≤128 KiB），再在事务内校验作业证据、可受理性
     * （原实例未漂移、作业未结束、未取消）与 profile 匹配（attempt 上限、模型、
     * max_tokens 与 outputReserve）；attempt&gt;1 时上一 attempt 必须已携响应结束。
     *
     * @param job              模型作业 ID
     * @param worker           worker ID
     * @param originalInstance 作业认领时的原始实例 ID
     * @param currentInstance  当前提交方实例 ID；与 originalInstance 不一致拒绝受理
     * @param attempt          wire attempt 序号（从 1 开始）
     * @param requestHash      请求规范化摘要
     * @param request          推理请求 JSON
     * @return 新建或已存在的 harness_inference 行
     * @throws StoreException INFERENCE_REQUEST_INVALID：请求摘要不匹配或超过 128 KiB
     * @throws StoreException INFERENCE_CONFLICT：同一 (job, attempt) 已存在且 request_hash 不同
     * @throws StoreException INFERENCE_NOT_ADMISSIBLE：实例漂移、作业已实际结束或已请求取消
     * @throws StoreException INFERENCE_PROFILE_MISMATCH：attempt 越界、模型不一致或 max_tokens 不匹配
     * @throws StoreException INFERENCE_PREVIOUS_UNRESOLVED：上一 attempt 未携响应实际结束
     */
    public JSONObject submit(
            String job,
            String worker,
            String originalInstance,
            String currentInstance,
            int attempt,
            String requestHash,
            JSONObject request) {
        if (!hash(request).equals(requestHash)
                || canonical(request).getBytes(StandardCharsets.UTF_8).length > 131072) {
            throw new StoreException("INFERENCE_REQUEST_INVALID");
        }
        return store.tx(
                c -> {
                    JSONObject j = job(c, job, worker, originalInstance);
                    JSONObject old =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_inference WHERE job_id=? AND wire_attempt=?",
                                            job,
                                            attempt));
                    if (old != null) {
                        if (!old.getString("request_hash").equals(requestHash)) {
                            throw new StoreException("INFERENCE_CONFLICT");
                        }
                        return old;
                    }
                    if (!originalInstance.equals(currentInstance)
                            || j.getInt("actual_finished") != 0
                            || j.getInt("cancel_requested") != 0) {
                        throw new StoreException("INFERENCE_NOT_ADMISSIBLE");
                    }
                    JSONObject input = new JSONObject(j.getString("request_json"));
                    // 允许至多一次格式修复重试：attempt 上限取顶层与 generation.maxRepairs 的较小值。
                    int repairs =
                            Math.min(
                                    1,
                                    Math.min(
                                            input.optInt("maxRepairs", 0),
                                            input.optJSONObject("generation") == null
                                                    ? 0
                                                    : input.getJSONObject("generation").optInt("maxRepairs", 0)));
                    if (attempt < 1
                            || attempt > 1 + repairs
                            || !j.getString("model_id").equals(request.optString("model"))
                            || request.optLong("max_tokens", request.optLong("max_completion_tokens", 0))
                            != input.getLong("outputReserve")) {
                        throw new StoreException("INFERENCE_PROFILE_MISMATCH");
                    }
                    if (attempt > 1) {
                        JSONObject previous =
                                first(
                                        rows(
                                                c,
                                                "SELECT * FROM harness_inference WHERE job_id=? AND wire_attempt=?",
                                                job,
                                                attempt - 1));
                        if (previous == null
                                || previous.getInt("actual_finished") != 1
                                || previous.isNull("response_json")) {
                            throw new StoreException("INFERENCE_PREVIOUS_UNRESOLVED");
                        }
                    }
                    String inference = hash(new JSONArray().put(job).put(attempt));
                    update(
                            c,
                            "INSERT INTO harness_inference (inference_id,job_id,wire_attempt,backend_id,request_hash,request_json,status,actual_finished,cancel_requested,response_json,error_code,unit_id,node_id,owner_id,created_at) VALUES (?,?,?,?,?,?,'queued',0,0,NULL,NULL,NULL,NULL,NULL,?)",
                            inference,
                            job,
                            attempt,
                            j.getString("backend_id"),
                            requestHash,
                            canonical(request),
                            store.now(c));
                    return first(rows(c, "SELECT * FROM harness_inference WHERE inference_id=?", inference));
                });
    }

    /**
     * 列出待派发的推理队列（最多 32 条，按创建时间与 ID 排序）。
     *
     * @return harness_inference 行数组，仅含 status='queued' 的记录
     */
    public JSONArray queued() {
        return store.tx(
                c ->
                        rows(
                                c,
                                "SELECT * FROM harness_inference WHERE status='queued' ORDER BY created_at,inference_id LIMIT 32"));
    }

    /**
     * 派发前提交预留：发送窗口内崩溃绝不会造成重发。
     *
     * <p>在行锁保护下统计 backend 物理占用（未结束的推理记录加 execution_protocol=2 的
     * 模型作业），达到 capacity 即拒绝；作业或推理已请求取消时直接置为
     * MODEL_NOT_DISPATCHED 结束；否则原子抢占为 dispatched。
     *
     * @param inference 推理 ID
     * @param node      认领节点 ID
     * @param owner     认领所有者 ID
     * @return 是否成功认领并派发
     */
    public boolean claim(String inference, String node, String owner) {
        return store.tx(
                c -> {
                    JSONObject i =
                            first(
                                    rows(
                                            c, "SELECT * FROM harness_inference WHERE inference_id=?" + lock, inference));
                    if (i == null || !i.getString("status").equals("queued")) {
                        return false;
                    }
                    String backend = i.getString("backend_id");
                    JSONObject b =
                            first(
                                    rows(
                                            c, "SELECT * FROM harness_model_backend WHERE backend_id=?" + lock, backend));
                    int occupied =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_inference WHERE backend_id=? AND status<>'queued' AND actual_finished=0",
                                            backend))
                                    .getInt("n");
                    occupied +=
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n FROM harness_model_job WHERE backend_id=? AND execution_protocol=2 AND status<>'queued' AND actual_finished=0",
                                            backend))
                                    .getInt("n");
                    if (occupied >= b.getInt("capacity")) {
                        return false;
                    }
                    JSONObject j =
                            first(
                                    rows(
                                            c,
                                            "SELECT cancel_requested FROM harness_model_job WHERE job_id=?",
                                            i.getString("job_id")));
                    if (j.getInt("cancel_requested") != 0 || i.getInt("cancel_requested") != 0) {
                        update(
                                c,
                                "UPDATE harness_inference SET status='finished',actual_finished=1,error_code='MODEL_NOT_DISPATCHED',cancel_requested=1 WHERE inference_id=?",
                                inference);
                        return false;
                    }
                    if (!accounting.healthy()) throw new StoreException("AI_USAGE_TEMPORARILY_UNAVAILABLE");
                    JSONObject subject = first(rows(c, "SELECT r.tenant,r.subject_id,r.run_id,j.worker_id,j.model_id FROM harness_model_job j JOIN harness_run r ON r.run_id=j.run_id WHERE j.job_id=?", i.getString("job_id")));
                    cube.aigc.usage.UsageContext identity = cube.aigc.usage.UsageContext.run(subject.getString("tenant"), Long.parseLong(subject.getString("subject_id")), subject.getString("run_id"), inference);
                    accounting.ledger.begin(c, identity, inference, subject.getString("model_id"), subject.optString("worker_id", ""), store.now(c));
                    return update(
                            c,
                            "UPDATE harness_inference SET status='dispatched',node_id=?,owner_id=?,dispatched_at=? WHERE inference_id=? AND status='queued'",
                            node,
                            owner,
                            store.now(c),
                            inference)
                            == 1;
                });
    }

    /**
     * JVM 重启后把本节点上属于其他所有者的已派发推理隔离为 unknown。
     *
     * <p>物理执行无法确认，保留容量隔离，禁止自动重发新推理。
     *
     * @param node  节点 ID
     * @param owner 当前所有者 ID；仅改写 owner_id 不同的记录
     */
    public void recover(String node, String owner) {
        store.tx(
                c -> {
                    update(
                            c,
                            "UPDATE harness_inference SET status='unknown',error_code='INFERENCE_JVM_RESTARTED' WHERE node_id=? AND owner_id<>? AND status='dispatched' AND actual_finished=0",
                            node,
                            owner);
                    return null;
                });
    }

    /**
     * 追加一条推理增量事件，序号按当前事件数顺延。
     *
     * @param inference 推理 ID
     * @param event     事件 JSON
     * @throws StoreException MODEL_OUTPUT_TOO_LARGE：单事件超过 64 KiB，或总量超过 4096 条/1 MiB；
     *                        推理已实际结束时静默忽略
     */
    public void append(String inference, JSONObject event) {
        String raw = canonical(event);
        if (raw.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new StoreException("MODEL_OUTPUT_TOO_LARGE");
        }
        store.tx(
                c -> {
                    JSONObject i =
                            first(
                                    rows(
                                            c,
                                            "SELECT actual_finished FROM harness_inference WHERE inference_id=?" + lock,
                                            inference));
                    if (i == null || i.getInt("actual_finished") == 1) {
                        return null;
                    }
                    JSONObject totals =
                            first(
                                    rows(
                                            c,
                                            "SELECT COUNT(*) AS n,COALESCE(SUM(LENGTH(event_json)),0) AS bytes FROM harness_inference_event WHERE inference_id=?",
                                            inference));
                    if (totals.getInt("n") >= 4096 || totals.getLong("bytes") + raw.length() > 1048576) {
                        throw new StoreException("MODEL_OUTPUT_TOO_LARGE");
                    }
                    update(
                            c,
                            "INSERT INTO harness_inference_event VALUES (?,?,?)",
                            inference,
                            totals.getLong("n") + 1,
                            raw);
                    return null;
                });
    }

    /**
     * 记录推理的最终响应或错误码。
     *
     * <p>响应超过 64 KiB 时整体丢弃并记为 MODEL_OUTPUT_TOO_LARGE；actualFinished=false
     * 仅结束观察，记录为 unknown 且不写 finished_at。
     *
     * @param inference      推理 ID
     * @param response       最终响应 JSON；可为 null
     * @param error          错误码；可为 null
     * @param actualFinished 是否构成物理推理结束证据
     * @param unit           执行推理的 Unit ID
     * @throws StoreException INFERENCE_NOT_FOUND：推理记录不存在
     */
    public void finish(
            String inference, JSONObject response, String error, boolean actualFinished, String unit) {
        finish(inference, response, error, actualFinished, unit, response);
    }

    public void finish(String inference, JSONObject response, String error, boolean actualFinished, String unit, JSONObject usageEvidence) {
        long[] tokens = cube.aigc.usage.UsageTokens.read(usageEvidence);
        String status = "MODEL_NOT_DISPATCHED".equals(error) ? "not_dispatched" : error == null ? "succeeded" : actualFinished ? "failed" : "unknown";
        JSONObject accountingEvidence = cube.aigc.usage.UsageLedger.evidence(inference, tokens == null ? 1 : actualFinished ? 3 : 2, status,
                tokens == null ? "unknown" : "actual", tokens == null ? null : tokens[0], tokens == null ? null : tokens[1], System.currentTimeMillis());
        JSONObject invocation;
        try {
            invocation = store.tx(c -> first(rows(c, "SELECT started_at FROM harness_ai_usage_call WHERE meter_id=?", inference)));
        } catch (RuntimeException unavailable) {
            accounting.finish(accountingEvidence);
            throw unavailable;
        }
        if (invocation != null) accounting.finish(accountingEvidence);
        String raw = response == null ? null : canonical(response);
        if (raw != null && raw.getBytes(StandardCharsets.UTF_8).length > 65536) {
            raw = null;
            error = "MODEL_OUTPUT_TOO_LARGE";
        }
        final String result = raw, code = error;
        store.tx(
                c -> {
                    JSONObject old =
                            first(
                                    rows(
                                            c, "SELECT * FROM harness_inference WHERE inference_id=?" + lock, inference));
                    if (old == null) {
                        throw new StoreException("INFERENCE_NOT_FOUND");
                    }
                    if (old.getInt("actual_finished") == 1) {
                        return null;
                    }
                    update(
                            c,
                            "UPDATE harness_inference SET status=?,actual_finished=?,response_json=?,error_code=?,unit_id=? WHERE inference_id=?",
                            actualFinished ? "finished" : "unknown",
                            actualFinished ? 1 : 0,
                            result,
                            code,
                            unit,
                            inference);
                    if (actualFinished) {
                        update(
                                c,
                                "UPDATE harness_inference SET finished_at=? WHERE inference_id=?",
                                store.now(c),
                                inference);
                    }
                    return null;
                });
    }

    /**
     * 按游标分页读取推理事件，可选地同时请求取消。
     *
     * <p>cursor 必须落在 [0, 当前最大事件序号] 内；每页最多 16 条且不超过 128 KiB；
     * 事件追平后才附带最终响应。
     *
     * @param job              模型作业 ID
     * @param worker           worker ID
     * @param originalInstance 原始实例 ID
     * @param attempt          wire attempt 序号
     * @param after            事件游标（不含）
     * @param cancel           是否同时请求取消；queued 状态直接置为 MODEL_NOT_DISPATCHED
     * @return 含 inferenceId/status/events/nextCursor/hasMore 等键的分页对象
     * @throws StoreException INFERENCE_NOT_FOUND：推理记录不存在
     * @throws StoreException INVALID_INFERENCE_CURSOR：游标为负或超过当前事件序号
     */
    public JSONObject page(
            String job, String worker, String originalInstance, int attempt, long after, boolean cancel) {
        return store.tx(
                c -> {
                    job(c, job, worker, originalInstance);
                    JSONObject i =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_inference WHERE job_id=? AND wire_attempt=?" + lock,
                                            job,
                                            attempt));
                    if (i == null) {
                        throw new StoreException("INFERENCE_NOT_FOUND");
                    }
                    String id = i.getString("inference_id");
                    if (cancel) {
                        update(
                                c,
                                "UPDATE harness_inference SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE inference_id=?",
                                store.now(c),
                                id);
                        if (i.getString("status").equals("queued")) {
                            update(
                                    c,
                                    "UPDATE harness_inference SET status='finished',actual_finished=1,error_code='MODEL_NOT_DISPATCHED' WHERE inference_id=?",
                                    id);
                            i.put("status", "finished")
                                    .put("actual_finished", 1)
                                    .put("error_code", "MODEL_NOT_DISPATCHED");
                        }
                        i.put("cancel_requested", 1);
                    }
                    long end =
                            first(
                                    rows(
                                            c,
                                            "SELECT COALESCE(MAX(event_seq),0) AS n FROM harness_inference_event WHERE inference_id=?",
                                            id))
                                    .getLong("n");
                    if (after < 0 || after > end) {
                        throw new StoreException("INVALID_INFERENCE_CURSOR");
                    }
                    JSONArray events =
                            rows(
                                    c,
                                    "SELECT event_seq,event_json FROM harness_inference_event WHERE inference_id=? AND event_seq>? ORDER BY event_seq LIMIT 16",
                                    id,
                                    after);
                    JSONArray page = new JSONArray();
                    long cursor = after;
                    int bytes = 0;
                    for (int n = 0; n < events.length(); n++) {
                        JSONObject e = events.getJSONObject(n);
                        String value = e.getString("event_json");
                        bytes += value.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > 131072) {
                            break;
                        }
                        cursor = e.getLong("event_seq");
                        page.put(new JSONObject().put("sequence", cursor).put("data", new JSONObject(value)));
                    }
                    JSONObject out =
                            new JSONObject()
                                    .put("inferenceId", id)
                                    .put("status", i.getString("status"))
                                    .put("actualFinished", i.getInt("actual_finished") == 1)
                                    .put("cancelRequested", i.getInt("cancel_requested") == 1)
                                    .put("events", page)
                                    .put("nextCursor", cursor)
                                    .put("hasMore", cursor < end);
                    if (!i.isNull("error_code")) {
                        out.put("errorCode", i.getString("error_code"));
                    }
                    if (!i.isNull("response_json") && cursor == end) {
                        out.put("response", new JSONObject(i.getString("response_json")));
                    }
                    return out;
                });
    }

    /**
     * 忽略 worker 对物理完成与 usage 的断言；只有 Java 后端证据可以结算。
     *
     * <p>逐 attempt 汇总响应摘要与真实 usage；任一 attempt 未实际结束即整体 unknown，
     * 缺失 usage 的记录令汇总降级为不声明 usage，绝不估算 token。
     *
     * @param job     模型作业 ID
     * @param outcome 调用方（Python 侧）声明的结果对象，仅保留其非 usage 字段与审计轨迹
     * @return 附加 audit 与（可确知时）usage 汇总的结果对象
     * @throws StoreException INFERENCE_EVIDENCE_REQUIRED：无推理记录却声明 success，或缺响应却声明 success
     */
    public JSONObject reconcile(String job, JSONObject outcome) {
        return store.tx(
                c -> {
                    JSONArray attempts =
                            rows(c, "SELECT * FROM harness_inference WHERE job_id=? ORDER BY wire_attempt", job);
                    if (attempts.length() == 0 && outcome.getString("status").equals("success")) {
                        throw new StoreException("INFERENCE_EVIDENCE_REQUIRED");
                    }
                    long input = 0, output = 0;
                    boolean known = attempts.length() > 0;
                    JSONArray audit = new JSONArray();
                    for (int n = 0; n < attempts.length(); n++) {
                        JSONObject i = attempts.getJSONObject(n);
                        if (i.getInt("actual_finished") == 0) {
                            return new JSONObject()
                                    .put("status", "unknown")
                                    .put("errorCode", "MODEL_COMPLETION_UNKNOWN")
                                    .put("audit", new JSONArray())
                                    .put("cancellationObserved", outcome.getBoolean("cancellationObserved"));
                        }
                        JSONObject record =
                                new JSONObject()
                                        .put("attempt", i.getInt("wire_attempt"))
                                        .put("requestHash", i.getString("request_hash"));
                        if (!i.isNull("response_json")) {
                            JSONObject response = new JSONObject(i.getString("response_json"));
                            record
                                    .put("responseHash", hash(response))
                                    .put(
                                            "finishReason",
                                            response.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
                            JSONObject u = response.optJSONObject("usage");
                            if (u == null) {
                                known = false;
                            } else {
                                input = Math.addExact(input, u.getLong("prompt_tokens"));
                                output = Math.addExact(output, u.getLong("completion_tokens"));
                                record.put(
                                        "usage",
                                        new JSONObject()
                                                .put("input", u.getLong("prompt_tokens"))
                                                .put("output", u.getLong("completion_tokens")));
                            }
                        } else {
                            known = false;
                            if (outcome.getString("status").equals("success")) {
                                throw new StoreException("INFERENCE_EVIDENCE_REQUIRED");
                            }
                        }
                        audit.put(record);
                    }
                    JSONObject result = new JSONObject(outcome.toString()).put("audit", audit);
                    result.remove("usage");
                    if (known) {
                        result.put("usage", new JSONObject().put("input", input).put("output", output));
                    }
                    return result;
                });
    }
}
