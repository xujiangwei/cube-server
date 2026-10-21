/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * N11 控制面存取：等待（wait）、resume、审批（approval）与取消（cancel）。
 *
 * <p>全部为短权威事务：事务内只做数据库读写与事件发射，不调用 worker、
 * 不发起网络请求。控制请求按幂等键（receipt）先查回放再校验版本与终态；
 * 取消与撤销（model/tool job 的 cancel_requested）在同一事务内原子生效，
 * 晚到结果只能结算、不能重开终态。
 */
public final class ControlStore {
    /**
     * 共享的 RunStore，提供事务边界、run 行访问与事件发射。
     */
    private final JdbcRunStore store;

    /**
     * 行锁后缀：MySQL 为 " FOR UPDATE"，SQLite 为空串（依赖 BEGIN IMMEDIATE）。
     */
    private final String lock;

    /**
     * 文件存储注入；resume 携带附件时用于绑定校验，可为 null（文件能力关闭）。
     */
    private FileStore files;

    /**
     * 注入文件存储（resume 的附件绑定需要）。
     *
     * @param files FileStore 实例
     * @return 本实例，便于链式调用
     */
    public ControlStore files(FileStore files) {
        this.files = files;
        return this;
    }

    /**
     * 绑定共享 RunStore 与方言标志。
     *
     * @param store 共享的 RunStore 实例
     * @param mysql true 为 MySQL（行锁可用），false 为 SQLite
     */
    public ControlStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        lock = mysql ? " FOR UPDATE" : "";
    }

    /**
     * 判断 run 状态是否为终态。
     *
     * @param s run 状态串
     * @return s 属于 succeeded/failed/cancelled/timed_out 时返回 true
     */
    private boolean terminal(String s) {
        return Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(s);
    }

    /**
     * 查询 run 当前待处理的控制等待（最近一条 pending）。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param run     run ID
     * @return 等待的视图 JSON（waitId/kind/expiresAt/argumentsHash/details）；无等待时返回 null
     */
    public JSONObject current(String tenant, String subject, String run) {
        return store.tx(
                c -> {
                    store.run(c, tenant, subject, run, false);
                    JSONObject w =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM harness_wait WHERE run_id=? AND status='pending' ORDER BY created_at DESC LIMIT 1",
                                            run));
                    return w == null ? null : view(w);
                });
    }

    /**
     * 把等待行投影为对外视图。
     *
     * @param w harness_wait 行
     * @return 包含 waitId、kind、expiresAt、argumentsHash 与解析后 details 的 JSON
     */
    private JSONObject view(JSONObject w) {
        return new JSONObject()
                .put("waitId", w.getString("wait_id"))
                .put("kind", w.getString("kind"))
                .put("expiresAt", w.getLong("expires_at"))
                .put("argumentsHash", w.getString("arguments_hash"))
                .put("details", new JSONObject(w.getString("payload_json")));
    }

    /**
     * 创建一个控制等待，run 随即进入 waiting_input/waiting_approval。
     *
     * <p>approval 类的参数摘要取 payload 的 argumentsHash 字段（调用方
     * 固定），input 类对 payload 整体哈希。wait_id 由 (runId, kind,
     * payload) 哈希得出，已存在即视为已消费。
     *
     * @param l        调用方租约
     * @param expected 期望的当前 run 版本
     * @param kind     等待类型，仅接受 "input" 或 "approval"
     * @param payload  等待负载 JSON
     * @param expires  截止时间戳（毫秒）
     * @throws StoreException INVALID_WAIT_KIND（非法等待类型）、
     *                        WAIT_EXPIRED（run 非运行中或截止时间已过）、
     *                        WAIT_ALREADY_CONSUMED（同一 wait_id 已存在）、
     *                        STALE_OWNER（租约失效）、VERSION_CONFLICT（版本不匹配）
     */
    public void waitFor(Lease l, long expected, String kind, JSONObject payload, long expires) {
        if (!Arrays.asList("input", "approval").contains(kind)) {
            throw new StoreException("INVALID_WAIT_KIND");
        }
        store.tx(
                c -> {
                    JSONObject r = store.run(c, l.tenant, l.subject, l.runId, true);
                    store.owner(c, r, l);
                    version(r, expected);
                    if (!r.getString("state").equals("running") || expires <= store.now(c)) {
                        throw new StoreException("WAIT_EXPIRED");
                    }
                    String wait = hash(new JSONArray().put(l.runId).put(kind).put(payload));
                    JSONObject prior = first(rows(c, "SELECT * FROM harness_wait WHERE wait_id=?", wait));
                    if (prior != null) {
                        throw new StoreException("WAIT_ALREADY_CONSUMED");
                    }
                    String ah = kind.equals("approval") ? payload.getString("argumentsHash") : hash(payload);
                    update(
                            c,
                            "INSERT INTO harness_wait VALUES (?,?,?,'pending',?,?,?,?)",
                            wait,
                            l.runId,
                            kind,
                            canonical(payload),
                            ah,
                            expires,
                            store.now(c));
                    String state = kind.equals("input") ? "waiting_input" : "waiting_approval";
                    update(c, "UPDATE harness_run SET state=? WHERE run_id=?", state, l.runId);
                    r.put("state", state);
                    store.emit(
                            c,
                            r,
                            "run." + state,
                            new JSONObject()
                                    .put("waitId", wait)
                                    .put("expiresAt", expires)
                                    .put("details", payload),
                            "control");
                    return null;
                });
    }

    /**
     * 判断某绑定是否已被某个 resumed/approved 的等待消费过。
     *
     * @param run     run ID
     * @param kind    等待类型（input/approval）
     * @param binding 待查绑定值（与等待 payload 的 binding 字段比对）
     * @return 已有已完结等待携带该 binding 时返回 true
     */
    public boolean handled(String run, String kind, String binding) {
        return store.tx(
                c -> {
                    JSONArray all =
                            rows(
                                    c,
                                    "SELECT payload_json FROM harness_wait WHERE run_id=? AND kind=? AND status IN ('resumed','approved')",
                                    run,
                                    kind);
                    for (int i = 0; i < all.length(); i++) {
                        if (binding.equals(
                                new JSONObject(all.getJSONObject(i).getString("payload_json"))
                                        .optString("binding"))) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /**
     * 应用一个控制操作：resume / approval / cancel。
     *
     * <p>鉴权在事务外；事务内先按幂等键查回放（同键异载荷报冲突），
     * 再校验 expectedVersion 与终态。cancel 将 run 置 cancelling 并撤销
     * 未完成的 model/tool job；resume/approval 校验等待状态、截止时间，
     * approval 还要求审批人非任务属主且参数摘要未变；resume 的附件经
     * FileStore 绑定。最终发射 control.&lt;operation&gt; 事件并落回执。
     *
     * @param tenant    租户标识
     * @param subject   任务属主主体标识
     * @param actor     操作者标识（审批时须与 subject 不同）
     * @param run       run ID
     * @param key       幂等键
     * @param operation 操作类型，仅接受 resume/approval/cancel
     * @param waitId    目标等待 ID
     * @param payload   操作负载 JSON（含 expectedVersion 等字段）
     * @param approver  操作者是否具备审批人身份
     * @return 控制事件 JSON（幂等重放时为已存储的回放响应）
     * @throws StoreException INVALID_CONTROL（非法操作）、
     *                        IDEMPOTENCY_CONFLICT（同键异载荷）、RUN_TERMINAL（run 已终态）、
     *                        CONTROL_FORBIDDEN（run 已被撤销）、WAIT_NOT_FOUND、WAIT_STATE_CONFLICT、
     *                        WAIT_EXPIRED、APPROVER_FORBIDDEN（审批人是属主或无审批身份）、
     *                        APPROVAL_ARGUMENTS_CHANGED（参数摘要与固定值不符）、
     *                        FILES_DISABLED（携带附件但文件能力关闭）
     */
    public JSONObject apply(
            String tenant,
            String subject,
            String actor,
            String run,
            String key,
            String operation,
            String waitId,
            JSONObject payload,
            boolean approver) {
        if (!Arrays.asList("resume", "approval", "cancel").contains(operation)) {
            throw new StoreException("INVALID_CONTROL");
        }
        String
                receipt = hash(new JSONArray().put(tenant).put(actor).put(run).put(operation).put(id(key))),
                digest = hash(new JSONObject().put("waitId", waitId).put("payload", payload));
        return store.tx(
                c -> {
                    JSONObject r = store.run(c, tenant, subject, run, true);
                    // 鉴权在外部完成；回放先于 expectedVersion 与终态检查。
                    JSONObject old =
                            first(rows(c, "SELECT * FROM harness_control_receipt WHERE receipt_id=?", receipt));
                    if (old != null) {
                        if (!digest.equals(old.getString("payload_hash"))) {
                            throw new StoreException("IDEMPOTENCY_CONFLICT");
                        }
                        return new JSONObject(old.getString("response_json"));
                    }
                    version(r, payload.getLong("expectedVersion"));
                    if (terminal(r.getString("state"))) {
                        throw new StoreException("RUN_TERMINAL");
                    }
                    if (!operation.equals("cancel")
                            && first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", run))
                            != null) {
                        throw new StoreException("CONTROL_FORBIDDEN");
                    }
                    if (operation.equals("cancel")) {
                        update(c, "UPDATE harness_run SET state='cancelling' WHERE run_id=?", run);
                        r.put("state", "cancelling");
                        update(
                                c,
                                "UPDATE harness_wait SET status='cancelled' WHERE run_id=? AND status='pending'",
                                run);
                        // 撤销与取消回执原子生效，新的 broker 派发无法混入。
                        update(
                                c,
                                "UPDATE harness_model_job SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE run_id=? AND actual_finished=0",
                                store.now(c),
                                run);
                        update(
                                c,
                                "UPDATE harness_tool_job SET cancel_requested=1,cancelled_at=COALESCE(cancelled_at,?) WHERE run_id=? AND actual_finished=0",
                                store.now(c),
                                run);
                    } else {
                        JSONObject w =
                                first(
                                        rows(
                                                c,
                                                "SELECT * FROM harness_wait WHERE wait_id=? AND run_id=?" + lock,
                                                id(waitId),
                                                run));
                        if (w == null) {
                            throw new StoreException("WAIT_NOT_FOUND");
                        }
                        String kind = operation.equals("resume") ? "input" : "approval";
                        if (!w.getString("kind").equals(kind)
                                || !w.getString("status").equals("pending")
                                || !r.getString("state").equals("waiting_" + kind)) {
                            throw new StoreException("WAIT_STATE_CONFLICT");
                        }
                        if (w.getLong("expires_at") <= store.now(c)) {
                            throw new StoreException("WAIT_EXPIRED");
                        }
                        if (operation.equals("approval")) {
                            if (!approver || actor.equals(subject)) {
                                throw new StoreException("APPROVER_FORBIDDEN");
                            }
                            if (!w.getString("arguments_hash").equals(payload.getString("argumentsHash"))) {
                                throw new StoreException("APPROVAL_ARGUMENTS_CHANGED");
                            }
                        }
                        if (operation.equals("resume")) {
                            JSONArray attachments = payload.getJSONObject("input").getJSONArray("attachmentIds");
                            if (files == null && attachments.length() > 0) {
                                throw new StoreException("FILES_DISABLED");
                            }
                            if (files != null) {
                                files.bind(c, tenant, subject, run, attachments);
                            }
                        }
                        String status =
                                operation.equals("resume")
                                        ? "resumed"
                                        : payload.getString("decision").equals("approve") ? "approved" : "rejected";
                        update(
                                c,
                                "UPDATE harness_wait SET status=? WHERE wait_id=? AND status='pending'",
                                status,
                                waitId);
                        String state = status.equals("rejected") ? "cancelling" : "running";
                        update(c, "UPDATE harness_run SET state=? WHERE run_id=?", state, run);
                        r.put("state", state);
                    }
                    JSONObject response =
                            store.emit(
                                    c,
                                    r,
                                    "control." + operation,
                                    new JSONObject()
                                            .put("actor", actor)
                                            .put("waitId", waitId)
                                            .put("payload", payload),
                                    "control");
                    update(
                            c,
                            "INSERT INTO harness_control_receipt VALUES (?,?,?,?)",
                            receipt,
                            digest,
                            canonical(response),
                            store.now(c));
                    return response;
                });
    }

    /**
     * 汇总 run 历史上通过 resume 提交的全部 user 输入。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param run     run ID
     * @return 按事件顺序排列的 input JSON 数组
     */
    public JSONArray inputs(String tenant, String subject, String run) {
        return store.tx(
                c -> {
                    store.run(c, tenant, subject, run, false);
                    JSONArray out = new JSONArray();
                    JSONArray events =
                            rows(
                                    c,
                                    "SELECT data_json FROM harness_event WHERE run_id=? AND event_type='control.resume' ORDER BY event_seq",
                                    run);
                    for (int i = 0; i < events.length(); i++) {
                        out.put(
                                new JSONObject(events.getJSONObject(i).getString("data_json"))
                                        .getJSONObject("payload")
                                        .getJSONObject("input"));
                    }
                    return out;
                });
    }

    /**
     * 读取数据库时钟（毫秒）。
     *
     * @return 当前数据库时间戳
     */
    public long now() {
        return store.tx(store::now);
    }

    /**
     * 取 run 最近一次取消/审批事件的发生时间，作为取消生效时刻。
     *
     * @param run run ID
     * @return control.cancel 或 control.approval 最新事件的时间戳；
     * 无事件时返回当前数据库时间
     */
    public long cancelTime(String run) {
        return store.tx(
                c -> {
                    JSONObject r =
                            first(
                                    rows(
                                            c,
                                            "SELECT occurred_at FROM harness_event WHERE run_id=? AND event_type IN ('control.cancel','control.approval') ORDER BY event_seq DESC LIMIT 1",
                                            run));
                    return r == null ? store.now(c) : r.getLong("occurred_at");
                });
    }
}
