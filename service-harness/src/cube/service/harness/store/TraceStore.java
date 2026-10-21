/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 只读、有界的诊断投影：脱敏 trace 导出与追加式反馈。
 *
 * <p>导出内容默认不落原文：结构、身份/版本摘要、状态、attempt、usage、
 * 各阶段时间与文件 ready/delete、提交时间保留，模型/工具的输入输出
 * 内容键与字符串一律替换为 SHA-256 摘要（键为 keySha256:，字符串值为
 * redactedSha256）。本类不参与恢复与任何权威提交，仅消费既有账本；
 * 导出大小、事件/轮次/调用条数超限即明确拒绝，不静默截断。
 */
public final class TraceStore {
    /**
     * 共享的 RunStore，提供事务边界与 run 行访问。
     */
    private final JdbcRunStore store;

    /**
     * 导出保留期（毫秒）：距 run 最后权威更新超过该值即拒绝导出。
     */
    private final long retentionMillis;

    /**
     * 单个导出包的最大字节数，超限拒绝。
     */
    private final int maxBytes;

    /**
     * trace 导出总开关（trace.enabled），默认 false。
     */
    public final boolean enabled;

    /**
     * 反馈入口独立开关（feedback.enabled），默认 false，不依赖 trace 开关。
     */
    private final boolean feedbackEnabled;

    /**
     * 从配置构造 TraceStore 并校验参数范围。
     *
     * @param store 共享的 RunStore 实例
     * @param p     配置属性，读取 trace.enabled、feedback.enabled、
     *              trace.retention.seconds、trace.export.max.bytes
     * @throws IllegalArgumentException INVALID_TRACE_CONFIG（保留期或
     *                                  导出大小超出允许范围）
     */
    public TraceStore(JdbcRunStore store, Properties p) {
        this.store = store;
        enabled = Boolean.parseBoolean(p.getProperty("trace.enabled", "false"));
        feedbackEnabled = Boolean.parseBoolean(p.getProperty("feedback.enabled", "false"));
        long seconds = Long.parseLong(p.getProperty("trace.retention.seconds", "604800"));
        maxBytes = Integer.parseInt(p.getProperty("trace.export.max.bytes", "262144"));
        if (seconds < 60 || seconds > 31536000 || maxBytes < 4096 || maxBytes > 262144) {
            throw new IllegalArgumentException("INVALID_TRACE_CONFIG");
        }
        retentionMillis = seconds * 1000;
    }

    /**
     * 提交一条 run 反馈（positive/negative + 可选原因）。
     *
     * <p>feedbackId 由 (tenant, subject, run, key) 哈希得出：同键同内容
     * 幂等返回，同键异内容报冲突；每 run 最多 256 条，不覆盖历史，
     * 不改变 run 版本。原因最长 4096 字符，仅持久化 reason 字段。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param run     run ID
     * @param key     幂等键
     * @param body    反馈 JSON，必须包含 rating（positive/negative），可含 reason
     * @return 包含 feedbackId 与 accepted=true 的 JSON
     * @throws StoreException FEEDBACK_DISABLED（反馈开关关闭）、
     *                        INVALID_FEEDBACK（rating 非法或原因超长）、
     *                        IDEMPOTENCY_CONFLICT（同键异内容）、FEEDBACK_QUOTA_EXCEEDED（超过 256 条）
     */
    public JSONObject feedback(String tenant, String subject, String run, String key, JSONObject body) {
        if (!feedbackEnabled) {
            throw new StoreException("FEEDBACK_DISABLED");
        }
        String rating = body.getString("rating");
        if (!(rating.equals("positive") || rating.equals("negative")) || body.optString("reason").length() > 4096) {
            throw new StoreException("INVALID_FEEDBACK");
        }
        String digest = hash(body), fid = hash(new JSONArray().put(id(tenant)).put(id(subject)).put(id(run)).put(id(key)));
        return store.tx(c -> {
            store.run(c, tenant, subject, run, true);
            JSONObject old = first(rows(c, "SELECT payload_hash FROM harness_feedback WHERE feedback_id=?", fid));
            if (old != null && !old.getString("payload_hash").equals(digest)) {
                throw new StoreException("IDEMPOTENCY_CONFLICT");
            }
            if (old == null) {
                if (first(rows(c, "SELECT COUNT(*) AS n FROM harness_feedback WHERE run_id=?", run)).getInt("n") >= 256) {
                    throw new StoreException("FEEDBACK_QUOTA_EXCEEDED");
                }
                update(c, "INSERT INTO harness_feedback VALUES (?,?,?,?,?,?,?,?)", fid, run, tenant, subject, digest, rating, canonical(new JSONObject().put("reason", body.optString("reason"))), store.now(c));
            }
            return new JSONObject().put("feedbackId", fid).put("accepted", true);
        });
    }

    /**
     * 递归脱敏：对象键替换为 keySha256: 摘要，字符串值替换为
     * {"redactedSha256": 摘要}，数字/布尔/null 原样保留。
     *
     * <p>内容默认不离开数据库：仅为 mock 输出保留 JSON 形状与摘要承诺。
     */
    private static Object redact(Object v) {
        if (v instanceof JSONObject) {
            JSONObject out = new JSONObject(), o = (JSONObject) v;
            for (String k : o.keySet()) {
                out.put("keySha256:" + hash(k), redact(o.get(k)));
            }
            return out;
        }
        if (v instanceof JSONArray) {
            JSONArray out = new JSONArray(), a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                out.put(redact(a.get(i)));
            }
            return out;
        }
        if (v instanceof String) {
            return new JSONObject().put("redactedSha256", hash(v));
        }
        return v;
    }

    /**
     * 对查询行中的 payload_json / result_json 两列做脱敏与摘要加工。
     *
     * <p>每列额外输出 &lt;列名&gt;_sha256 原文摘要，原列替换为脱敏结构；
     * payload_json 含 argumentsJson 时按 StrictJson 规范化后计算
     * argumentsHash，含 tool 时透传 tool 名（工具名非内容）。
     *
     * @param rows 查询行数组（被就地修改）
     * @return 加工后的同一数组
     */
    private static JSONArray contentRows(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            for (String k : new String[]{"payload_json", "result_json"}) {
                if (!row.has(k) || row.isNull(k)) {
                    continue;
                }
                String raw = row.getString(k);
                if (k.equals("payload_json")) {
                    JSONObject payload = new JSONObject(raw);
                    if (payload.has("argumentsJson")) {
                        row.put("argumentsHash", hash(cube.service.harness.protocol.StrictJson.object(payload.getString("argumentsJson").getBytes(StandardCharsets.UTF_8))));
                    }
                    if (payload.has("tool")) {
                        row.put("tool", payload.getString("tool"));
                    }
                }
                row.put(k + "_sha256", hash(new JSONObject(raw))).put(k, redact(new JSONObject(raw)));
            }
        }
        return rows;
    }

    /**
     * 导出一个 run 的脱敏状态 trace 包。
     *
     * <p>保留期内有效；事件上限 1024、rounds/calls 各 256，超限明确拒绝；
     * 事件缺投影时 replayComplete=false（schema7 前事件无投影，回放拒绝）。
     * 包尾附 metrics（计数与各阶段耗时，空值不填造）与整包 digest，
     * 序列化后超过 maxBytes 同样拒绝。
     *
     * @param tenant  租户标识
     * @param subject 主体标识
     * @param run     run ID
     * @return 脱敏 trace 包 JSON（schemaVersion=1）
     * @throws StoreException TRACE_DISABLED（导出开关关闭）、
     *                        TRACE_EXPIRED（超过保留期）、TRACE_EXPORT_TOO_LARGE（条数或体积超限）
     */
    public JSONObject export(String tenant, String subject, String run) {
        if (!enabled) {
            throw new StoreException("TRACE_DISABLED");
        }
        return store.tx(c -> {
            // run 行锁将本次一致性读与 coordinator/file/control 事务排序。
            JSONObject r = store.run(c, tenant, subject, run, true);
            if (store.now(c) - r.getLong("updated_at") >= retentionMillis) {
                throw new StoreException("TRACE_EXPIRED");
            }
            JSONArray events = rows(c, "SELECT event_seq,run_version,event_type,occurred_at,projection_json FROM harness_event WHERE run_id=? ORDER BY event_seq LIMIT 1025", run);
            if (events.length() > 1024) {
                throw new StoreException("TRACE_EXPORT_TOO_LARGE");
            }
            boolean complete = true;
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                if (e.isNull("projection_json")) {
                    complete = false;
                } else {
                    e.put("projection", new JSONObject(e.getString("projection_json")));
                }
                e.remove("projection_json");
            }
            JSONArray rounds = contentRows(rows(c, "SELECT intent_id,attempt,intent_hash,origin_fence,status,payload_json,result_json,result_hash FROM harness_round WHERE run_id=? ORDER BY intent_id,attempt LIMIT 257", run));
            JSONArray calls = contentRows(rows(c, "SELECT intent_id,attempt,intent_hash,origin_fence,status,payload_json,result_json,result_hash FROM harness_tool_call WHERE run_id=? ORDER BY intent_id,attempt LIMIT 257", run));
            if (rounds.length() > 256 || calls.length() > 256) {
                throw new StoreException("TRACE_EXPORT_TOO_LARGE");
            }
            JSONObject pinned = first(rows(c, "SELECT digest,config_json FROM harness_run_config WHERE run_id=?", run));
            if (pinned != null) {
                JSONObject fixed = new JSONObject(pinned.getString("config_json"));
                pinned.remove("config_json");
                for (String kind : new String[]{"task", "model", "runtime"}) {
                    JSONObject definition = fixed.getJSONObject(kind);
                    pinned.put(kind, new JSONObject().put("id", definition.getString("id")).put("version", definition.getString("version")).put("digest", hash(definition)));
                }
            }
            JSONObject result = first(rows(c, "SELECT result_hash,committed_at FROM harness_result WHERE run_id=?", run));
            JSONObject out = new JSONObject().put("schemaVersion", 1).put("kind", "harness-redacted-state-trace").put("runId", run).put("redaction", "all-content-strings-sha256").put("replayComplete", complete).put("snapshot", snapshot(r).put("usageSettlement", r.getString("usage_settlement"))).put("config", pinned == null ? JSONObject.NULL : pinned).put("events", events).put("rounds", rounds).put("calls", calls).put("usage", rows(c, "SELECT round_id,attempt,estimated_input,estimated_output,actual_input,actual_output,revision FROM harness_usage WHERE run_id=? ORDER BY round_id,attempt", run)).put("modelJobs", rows(c, "SELECT job_id,round_id,attempt,model_id,profile_digest,backend_id,worker_id,status,actual_finished,cancel_requested,created_at,dispatched_at,finished_at,cancelled_at FROM harness_model_job WHERE run_id=? ORDER BY created_at,job_id", run)).put("toolJobs", rows(c, "SELECT job_id,call_id,attempt,release_digest,tool_name,worker_id,status,actual_finished,cancel_requested,effect_status,created_at,dispatched_at,finished_at,cancelled_at,cleanup_after,cleanup_done FROM harness_tool_job WHERE run_id=? ORDER BY created_at,job_id", run)).put("inferences", rows(c, "SELECT inference_id,job_id,wire_attempt,backend_id,status,actual_finished,cancel_requested,error_code,created_at,dispatched_at,finished_at,cancelled_at FROM harness_inference WHERE job_id IN (SELECT job_id FROM harness_model_job WHERE run_id=?) ORDER BY created_at,inference_id", run)).put("files", rows(c, "SELECT file_id,kind,size_bytes,sha256,status,created_at,ready_at,expires_at,deleted_at,attempts FROM harness_file WHERE run_id=? ORDER BY created_at,file_id", run)).put("feedback", rows(c, "SELECT feedback_id,payload_hash,rating,created_at FROM harness_feedback WHERE run_id=? ORDER BY created_at,feedback_id", run)).put("resultCommitment", result == null ? JSONObject.NULL : result).put("timingCoverage", "event timestamps, queue creation, file ready/delete, commit; unavailable stage timestamps are not inferred");
            JSONObject metrics = new JSONObject().put("rounds", rounds.length()).put("calls", calls.length());
            for (String kind : new String[]{"modelJobs", "toolJobs", "inferences"}) {
                JSONArray list = out.getJSONArray(kind), timings = new JSONArray();
                int active = 0, queued = 0;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject j = list.getJSONObject(i);
                    if (j.getInt("actual_finished") == 0) {
                        active++;
                    }
                    if (j.getString("status").equals("queued")) {
                        queued++;
                    }
                    JSONObject time = new JSONObject().put("id", j.getString(kind.equals("inferences") ? "inference_id" : "job_id"));
                    for (String field : new String[]{"queue", "execution", "cancelToPhysical"}) {
                        String a = field.equals("queue") ? "created_at" : field.equals("execution") ? "dispatched_at" : "cancelled_at";
                        String b = field.equals("queue") ? "dispatched_at" : "finished_at";
                        time.put(field + "Millis", j.isNull(a) || j.isNull(b) ? JSONObject.NULL : Math.max(0, j.getLong(b) - j.getLong(a)));
                    }
                    timings.put(time);
                }
                metrics.put(kind, new JSONObject().put("total", list.length()).put("unsettledPhysical", active).put("queued", queued).put("timings", timings));
            }
            out.put("metrics", metrics);
            out.put("digest", hash(out));
            if (canonical(out).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new StoreException("TRACE_EXPORT_TOO_LARGE");
            }
            return out;
        });
    }
}
