/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.runtime.RuntimeSupport;
import org.json.JSONArray;
import org.json.JSONObject;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * Harness 库中的提供方元数据存储：数据源配置、访问授权、管理请求幂等记录，
 * 以及媒体调用的执行意图、事实与物理回执。
 *
 * <p>本类只保存结构化元数据与规范化 JSON，不保存媒体字节。表结构由
 * {@code StoreBootstrap} 的版本化定义统一创建和严格校验，这里不执行 DDL。</p>
 *
 * <p>所有读写都在 {@code JdbcRunStore.tx} 的短事务内完成。MySQL 下写事务先经
 * {@code serial} 对单例行加排他锁，把并发写串行化，使容量预留与幂等判定不会
 * 交错；SQLite 由事务本身提供串行化，无需额外加锁。事务提交失败时由
 * {@code JdbcRunStore} 抛出存储层错误码，此处不再逐一声明。</p>
 */
public final class ProviderStore {

    /** Confirmed successful facts for one authenticated run, never model-authored output. */
    public JSONArray completionEvidence(String tenant, String subject, String run) {
        return store.tx(c -> {
            store.run(c, tenant, subject, run, true);
            JSONArray found = rows(c, "SELECT f.fact_kind,f.fact_id,f.value_json,j.definition_json,j.arguments_json,t.result_json FROM harness_provider_fact f "
                    + "JOIN harness_tool_job j ON f.run_id=j.run_id AND f.job_id=j.job_id "
                    + "JOIN harness_tool_call t ON t.run_id=j.run_id AND t.intent_id=j.call_id AND t.attempt=j.attempt "
                    + "WHERE f.run_id=? AND j.tenant=? AND j.subject_id=? AND j.actual_finished=1 "
                    + "AND j.effect_status='completed' AND t.status='confirmed' "
                    + "AND f.fact_kind IN ('data-page','data-set','analysis') ORDER BY f.fact_kind,f.fact_id",
                    run, tenant, subject);
            JSONArray out = new JSONArray();
            for (int i = 0; i < found.length(); i++) {
                JSONObject row = found.getJSONObject(i);
                // Also inspect normalized call status: confirmed errors cannot establish a fact.
                if (!new JSONObject(row.getString("result_json")).optString("status").equals("success")) continue;
                JSONObject fact = new JSONObject().put("kind", row.getString("fact_kind")).put("id", row.getString("fact_id"))
                        .put("operation", new JSONObject(row.getString("definition_json")).getJSONObject("tool").getString("brokerOperation"))
                        .put("value", new JSONObject(row.getString("value_json")))
                        .put("arguments", new JSONObject(row.getString("arguments_json")));
                if (row.getString("fact_kind").equals("analysis")) fact.put("result", new JSONObject(new JSONObject(row.getString("result_json")).getString("dataJson")));
                out.put(fact);
            }
            return out;
        });
    }

    /**
     * 底层运行存储，提供事务边界、run 行读取与租约校验能力。
     */
    private final JdbcRunStore store;

    /**
     * 是否使用 MySQL；为真时写事务需要显式加单例行锁。
     */
    private final boolean mysql;

    /**
     * 构造方法。
     *
     * <p>DDL 只由 StoreBootstrap 的版本化、严格校验的定义负责，本类不建表。</p>
     *
     * @param store 底层运行存储。
     * @param mysql 是否使用 MySQL。
     */
    public ProviderStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.mysql = mysql;
    }

    /**
     * MySQL 下对单例行加排他锁，把并发写事务串行化。
     *
     * <p>SQLite 的事务本身已串行化，因此不执行该查询。必须在同一事务内、
     * 且在读取待判定的数据之前调用。</p>
     *
     * @param c 事务内连接。
     * @throws Exception SQL 执行失败。
     */
    private void serial(java.sql.Connection c) throws Exception {
        if (mysql) {
            rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1 FOR UPDATE");
        }
    }

    /**
     * 读取指定租户下的数据源配置。
     *
     * <p>引用格式为 {@code id@version}。配置以规范化 JSON 保存，读取时还原为新
     * 对象，调用方修改返回值不会影响库内内容。</p>
     *
     * @param tenant 租户标识。
     * @param ref 数据源引用，形如 {@code id@version}。
     * @return 数据源配置对象。
     * @throws StoreException CONNECTOR_CONFIG_INVALID：引用格式不合法；
     *                        DATA_SOURCE_FORBIDDEN：租户下不存在该版本的数据源。
     */
    public JSONObject source(String tenant, String ref) {
        String[] parts = ref.split("@", -1);
        if (parts.length != 2) {
            throw new StoreException("CONNECTOR_CONFIG_INVALID");
        }
        return store.tx(c -> {
            JSONObject row = first(rows(c, "SELECT config_json FROM harness_data_source WHERE tenant=? AND source_id=? AND source_version=?", tenant, parts[0], parts[1]));
            if (row == null) {
                throw new StoreException("DATA_SOURCE_FORBIDDEN");
            }
            return new JSONObject(row.getString("config_json"));
        });
    }

    /**
     * 校验指定主体是否有权访问某数据源。
     *
     * <p>以 tenant、subject 和完整 sourceRef 查授权行，只有 {@code allowed=1}
     * 才放行；无授权行与显式拒绝按同一错误处理。</p>
     *
     * @param tenant 租户标识。
     * @param subject 主体标识。
     * @param ref 数据源引用，形如 {@code id@version}。
     * @throws StoreException DATA_SOURCE_FORBIDDEN：未授权或显式拒绝。
     */
    public void authorize(String tenant, String subject, String ref) {
        boolean ok = store.tx(c -> {
            JSONObject row = first(rows(c, "SELECT allowed FROM harness_source_grant WHERE tenant=? AND subject_id=? AND source_ref=?", tenant, subject, ref));
            return row != null && row.getInt("allowed") == 1;
        });
        if (!ok) {
            throw new StoreException("DATA_SOURCE_FORBIDDEN");
        }
    }

    /**
     * 写入不可变的数据源配置。
     *
     * <p>同一 {@code id@version} 只允许写入一次：已存在且语义摘要一致时视为幂等
     * 重放，直接返回既有引用和摘要；摘要不同则冲突，不覆盖旧配置。摘要按
     * {@code canonical} 规范化后计算，键序与数字写法不影响身份。</p>
     *
     * @param tenant 租户标识。
     * @param id 数据源标识。
     * @param version 配置版本。
     * @param value 数据源配置对象。
     * @return 含 sourceRef 与 digest 的结果。
     * @throws StoreException IMMUTABLE_SOURCE_CONFLICT：同版本已存在不同内容。
     */
    public JSONObject putSource(String tenant, String id, String version, JSONObject value) {
        RuntimeSupport.id(id);
        RuntimeSupport.id(version);
        return store.tx(c -> {
            serial(c);
            JSONObject old = first(rows(c, "SELECT digest FROM harness_data_source WHERE tenant=? AND source_id=? AND source_version=?", tenant, id, version));
            String digest = hash(value);
            if (old != null && !old.getString("digest").equals(digest)) {
                throw new StoreException("IMMUTABLE_SOURCE_CONFLICT");
            }
            if (old == null) {
                update(c, "INSERT INTO harness_data_source VALUES (?,?,?,?,?)", tenant, id, version, digest, canonical(value));
            }
            return new JSONObject().put("sourceRef", id + "@" + version).put("digest", digest);
        });
    }

    /**
     * 本地写入主体对数据源的授权，覆盖同一组合的旧授权。
     *
     * <p>先校验数据源在租户下存在，再在写事务内以「先删后插」整行替换，
     * 便于在同一把锁下重复执行。</p>
     *
     * @param tenant 租户标识。
     * @param subject 主体标识。
     * @param ref 数据源引用，形如 {@code id@version}。
     * @param allowed 是否放行。
     * @return 含 sourceRef、subject 与 allowed 的授权结果。
     * @throws StoreException DATA_SOURCE_FORBIDDEN：数据源不存在。
     */
    public JSONObject grant(String tenant, String subject, String ref, boolean allowed) {
        RuntimeSupport.id(subject);
        source(tenant, ref);
        return store.tx(c -> {
            serial(c);
            update(c, "DELETE FROM harness_source_grant WHERE tenant=? AND subject_id=? AND source_ref=?", tenant, subject, ref);
            update(c, "INSERT INTO harness_source_grant VALUES (?,?,?,?)", tenant, subject, ref, allowed ? 1 : 0);
            return new JSONObject().put("sourceRef", ref).put("subject", subject).put("allowed", allowed);
        });
    }

    /**
     * 带幂等记录的远程授权写入。
     *
     * <p>以 tenant、actor、key 定位管理请求：已存在且请求摘要一致时直接回放上次
     * 响应，不重复写授权；摘要不一致说明同一幂等键复用于不同请求，直接冲突。
     * 调用方需事先完成管理身份鉴别。</p>
     *
     * @param tenant 租户标识。
     * @param actor 发起请求的管理主体。
     * @param key 管理请求幂等键。
     * @param requestHash 请求内容的语义摘要。
     * @param subject 被授权主体。
     * @param ref 数据源引用，形如 {@code id@version}。
     * @param allowed 是否放行。
     * @return 含 sourceRef、subject 与 allowed 的授权结果。
     * @throws StoreException IDEMPOTENCY_CONFLICT：同一幂等键对应不同请求；
     *                        DATA_SOURCE_FORBIDDEN：数据源不存在。
     */
    public JSONObject grantRemote(String tenant, String actor, String key, String requestHash, String subject, String ref, boolean allowed) {
        RuntimeSupport.id(subject);
        source(tenant, ref);
        return store.tx(c -> {
            serial(c);
            JSONObject prior = first(rows(c, "SELECT request_hash,response_json FROM harness_admin_request WHERE tenant=? AND subject_id=? AND request_key=?", tenant, actor, key));
            if (prior != null) {
                if (!prior.getString("request_hash").equals(requestHash)) {
                    throw new StoreException("IDEMPOTENCY_CONFLICT");
                }
                return new JSONObject(prior.getString("response_json"));
            }
            update(c, "DELETE FROM harness_source_grant WHERE tenant=? AND subject_id=? AND source_ref=?", tenant, subject, ref);
            update(c, "INSERT INTO harness_source_grant VALUES (?,?,?,?)", tenant, subject, ref, allowed ? 1 : 0);
            JSONObject result = new JSONObject().put("sourceRef", ref).put("subject", subject).put("allowed", allowed);
            update(c, "INSERT INTO harness_admin_request VALUES (?,?,?,?,?)", tenant, actor, key, requestHash, canonical(result));
            return result;
        });
    }

    /**
     * 查询或登记管理请求的执行结果。
     *
     * <p>调用方已完成管理身份鉴别。{@code result} 为 null 时只做查询：已有记录
     * 且摘要一致则回放响应，返回 null 表示首次请求；摘要不一致视为幂等冲突。
     * {@code result} 非 null 时在确认首次执行后登记结果并返回该结果。</p>
     *
     * @param tenant 租户标识。
     * @param subject 发起请求的管理主体。
     * @param key 管理请求幂等键。
     * @param requestHash 请求内容的语义摘要。
     * @param result 本次执行结果，null 表示只查询不登记。
     * @return 已登记的响应、本次结果，或 null（首次请求且只查询）。
     * @throws StoreException IDEMPOTENCY_CONFLICT：同一幂等键对应不同请求。
     */
    public JSONObject replay(String tenant, String subject, String key, String requestHash, JSONObject result) {
        return store.tx(c -> {
            serial(c);
            JSONObject old = first(rows(c, "SELECT * FROM harness_admin_request WHERE tenant=? AND subject_id=? AND request_key=?", tenant, subject, key));
            if (old != null) {
                if (!old.getString("request_hash").equals(requestHash)) {
                    throw new StoreException("IDEMPOTENCY_CONFLICT");
                }
                return new JSONObject(old.getString("response_json"));
            }
            if (result == null) {
                return null;
            }
            update(c, "INSERT INTO harness_admin_request VALUES (?,?,?,?,?)", tenant, subject, key, requestHash, canonical(result));
            return result;
        });
    }

    /**
     * 在事务内校验作业仍归当前执行者所有且运行未被终止。
     *
     * <p>作业已请求取消、run 已被撤销、owner/fence 与租约不匹配，或 run 不处于
     * running 状态时，均不允许继续写入事实或预留容量。</p>
     *
     * @param c 事务内连接。
     * @param job 持久化的工具作业行。
     * @throws Exception 校验失败或 SQL 执行失败。
     * @throws StoreException TOOL_CANCELLED：作业已取消或 run 已非运行状态；
     *                        STALE_OWNER：owner/fence 不匹配或租约已过期。
     */
    private void owner(java.sql.Connection c, JSONObject job) throws Exception {
        JSONObject fresh = first(rows(c, "SELECT cancel_requested FROM harness_tool_job WHERE job_id=?", job.getString("job_id")));
        if (fresh == null || fresh.getInt("cancel_requested") != 0 || first(rows(c, "SELECT run_id FROM harness_tool_revocation WHERE run_id=?", job.getString("run_id"))) != null) {
            throw new StoreException("TOOL_CANCELLED");
        }
        JSONObject run = store.run(c, job.getString("tenant"), job.getString("subject_id"), job.getString("run_id"), true);
        store.owner(c, run, new JdbcRunStore.Lease(job.getString("tenant"), job.getString("subject_id"), job.getString("run_id"), run.getString("owner_id"), job.getLong("origin_fence")));
        if (!run.getString("state").equals("running")) {
            throw new StoreException("TOOL_CANCELLED");
        }
    }

    /**
     * 幂等读写一次提供方事实。
     *
     * <p>{@code value} 非 null 时是写入：先校验作业归属，事实不存在则插入，已存在
     * 但内容不同则冲突，内容相同则等价于重放。{@code value} 为 null 时是读取，
     * 要求事实已存在；{@code confirmed} 为真时还要求对应工具调用已确认、且结果
     * 状态为 success，防止引用未经确认的媒体产物。</p>
     *
     * @param job 持久化的工具作业行。
     * @param kind 事实类别。
     * @param id 事实标识。
     * @param value 待写入的事实对象，null 表示只读取。
     * @param confirmed 读取时是否要求工具调用已确认成功。
     * @return 写入或读取到的事实对象。
     * @throws StoreException PROVIDER_FACT_CONFLICT：同标识已存在不同内容；
     *                        MEDIA_REF_FORBIDDEN：事实不存在或未确认成功；
     *                        TOOL_CANCELLED：作业已取消。
     */
    public JSONObject fact(JSONObject job, String kind, String id, JSONObject value, boolean confirmed) {
        return store.tx(c -> {
            if (value != null) {
                owner(c, job);
            }
            JSONObject row = first(rows(c, "SELECT * FROM harness_provider_fact WHERE run_id=? AND fact_kind=? AND fact_id=?", job.getString("run_id"), kind, id));
            if (row == null && value != null) {
                update(c, "INSERT INTO harness_provider_fact VALUES (?,?,?,?,?)", job.getString("run_id"), job.getString("job_id"), kind, id, canonical(value));
                return value;
            }
            if (row == null) {
                throw new StoreException("MEDIA_REF_FORBIDDEN");
            }
            if (value != null && !canonical(value).equals(row.getString("value_json"))) {
                throw new StoreException("PROVIDER_FACT_CONFLICT");
            }
            if (confirmed) {
                JSONObject call = first(rows(c, "SELECT t.status,t.result_json FROM harness_tool_call t JOIN harness_tool_job j ON t.run_id=j.run_id AND t.intent_id=j.call_id AND t.attempt=j.attempt WHERE j.job_id=?", row.getString("job_id")));
                if (call == null || !call.getString("status").equals("confirmed") || !new JSONObject(call.getString("result_json")).optString("status").equals("success")) {
                    throw new StoreException("MEDIA_REF_FORBIDDEN");
                }
            }
            return new JSONObject(row.getString("value_json"));
        });
    }

    /**
     * 原子预留一路物理媒体容量。
     *
     * <p>在事务内校验作业归属并加写锁，若同一 run/job 已有 execution 事实则拒绝
     * 重复启动。候选实例按「已占用槽位 / capacity」负载比选最小者，且需 ready
     * 且仍有空位；专项检测额外要求就绪租约有效、图像余量大于 0，并匹配请求的
     * profile 与切片要求。已占用槽位只统计尚无 receipt 的 execution。</p>
     *
     * <p>物理结果未知的执行不会释放槽位，直到收到实际完成回执，避免自动重发
     * 造成超发。</p>
     *
     * @param job 持久化的工具作业行。
     * @param units 可用的媒体提供方实例列表。
     * @param unit 目标 Unit 名称。
     * @param request 原始调用请求，会复制后写入 execution 事实。
     * @return 含 instanceId、requestId、serviceSn 的执行意图。
     * @throws StoreException CAPABILITY_EXECUTION_ALREADY_STARTED：同一作业已开始执行；
     *                        CAPABILITY_NOT_READY：无满足条件的可用实例；
     *                        TOOL_CANCELLED：作业已取消。
     */
    public JSONObject reserve(JSONObject job, JSONArray units, String unit, JSONObject request) {
        return store.tx(c -> {
            owner(c, job);
            serial(c);
            JSONObject old = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='execution' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (old != null) {
                throw new StoreException("CAPABILITY_EXECUTION_ALREADY_STARTED");
            }
            JSONObject selected = null;
            boolean compatible = false;
            double load = Double.MAX_VALUE;
            for (int i = 0; i < units.length(); i++) {
                JSONObject candidate = units.getJSONObject(i);
                if (!candidate.getString("unit").equals(unit) || !candidate.getBoolean("ready")) {
                    continue;
                }
                if ("SpecializedDetection".equals(unit)) {
                    JSONObject option = request.getJSONObject("option");
                    if (!cube.aigc.SpecializedDetectionCapabilities.ready(candidate) || !cube.aigc.SpecializedDetectionCapabilities.supports(candidate, option.getString("profile"), option.optBoolean("sliced"))) {
                        continue;
                    }
                }
                compatible = true;
                if ("SpecializedDetection".equals(unit) && candidate.optInt("availableImages") <= 0) continue;
                JSONArray active = rows(c, "SELECT a.value_json FROM harness_provider_fact a LEFT JOIN harness_provider_fact b ON a.run_id=b.run_id AND a.fact_id=b.fact_id AND b.fact_kind='receipt' WHERE a.fact_kind='execution' AND b.fact_id IS NULL");
                int count = 0;
                for (int j = 0; j < active.length(); j++) {
                    JSONObject occupied = new JSONObject(active.getJSONObject(j).getString("value_json"));
                    if (occupied.getString("instanceId").equals(candidate.getString("instanceId"))
                            && occupied.optString("unit", unit).equals(unit)) {
                        count++;
                    }
                }
                int capacity = candidate.getInt("capacity");
                if (capacity > count && (double) count / capacity < load) {
                    selected = candidate;
                    load = (double) count / capacity;
                }
            }
            if (selected == null) {
                throw new StoreException(compatible ? "CAPABILITY_CAPACITY_UNAVAILABLE" : "CAPABILITY_NOT_READY");
            }
            JSONObject execution = new JSONObject(request.toString()).put("unit", unit).put("instanceId", selected.getString("instanceId")).put("requestId", job.getString("job_id")).put("serviceSn", cell.util.Utils.generateSerialNumber());
            update(c, "INSERT INTO harness_provider_fact VALUES (?,?,'execution',?,?)", job.getString("run_id"), job.getString("job_id"), job.getString("job_id"), canonical(execution));
            return execution;
        });
    }

    /**
     * 登记物理执行回执。
     *
     * <p>只有 {@code actualFinished=true} 的回执才落库；未完成的回执直接忽略，
     * 使结果未知的执行继续占用槽位。回执的 instanceId 与 requestId 必须与已登记的
     * execution 意图一致；重复回执内容一致时幂等通过，内容不同则冲突。</p>
     *
     * <p>取消或租约丢失后到达的回执仍可登记，但不会改变已确定的执行答案。</p>
     *
     * @param job 持久化的工具作业行。
     * @param receipt 物理回执对象。
     * @throws StoreException CAPABILITY_RECEIPT_FORBIDDEN：无对应意图或标识不匹配；
     *                        CAPABILITY_RECEIPT_CONFLICT：重复回执内容不一致。
     */
    public void receipt(JSONObject job, JSONObject receipt) {
        if (!receipt.optBoolean("actualFinished")) {
            return;
        }
        store.tx(c -> {
            serial(c);
            JSONObject intent = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='execution' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (intent == null) {
                throw new StoreException("CAPABILITY_RECEIPT_FORBIDDEN");
            }
            JSONObject expected = new JSONObject(intent.getString("value_json"));
            if (!expected.getString("instanceId").equals(receipt.getString("instanceId")) || !expected.getString("requestId").equals(receipt.getString("requestId"))) {
                throw new StoreException("CAPABILITY_RECEIPT_FORBIDDEN");
            }
            JSONObject old = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='receipt' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (old == null) {
                update(c, "INSERT INTO harness_provider_fact VALUES (?,?,'receipt',?,?)", job.getString("run_id"), job.getString("job_id"), job.getString("job_id"), canonical(receipt));
                JSONObject timing = new JSONObject().put("recordedAt", store.now(c)).put("receiptSha256", hash(receipt))
                        .put("success", receipt.optBoolean("success")).put("unsent", receipt.optBoolean("unsent"));
                update(c, "INSERT INTO harness_provider_fact VALUES (?,?,'receipt-metadata',?,?)", job.getString("run_id"), job.getString("job_id"), job.getString("job_id"), canonical(timing));
            } else if (!old.getString("value_json").equals(canonical(receipt))) {
                throw new StoreException("CAPABILITY_RECEIPT_CONFLICT");
            }
            return null;
        });
    }

    /** Trusted bridge callback before physical send. A storage failure prevents dispatch. */
    public void mediaDispatch(JSONObject job, JSONObject progress) {
        store.tx(c -> {
            serial(c);
            JSONObject row = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='execution' AND fact_id=?", job.getString("run_id"), job.getString("job_id")));
            if (row == null) throw new StoreException("CAPABILITY_RECEIPT_FORBIDDEN");
            JSONObject intent = new JSONObject(row.getString("value_json"));
            if (!intent.getString("requestId").equals(progress.getString("requestId")) || !intent.getString("instanceId").equals(progress.getString("instanceId"))) throw new StoreException("CAPABILITY_RECEIPT_FORBIDDEN");
            String id = job.getString("job_id") + ":physical-dispatch";
            JSONObject previous = first(rows(c, "SELECT value_json FROM harness_provider_fact WHERE run_id=? AND fact_kind='media-phase' AND fact_id=?", job.getString("run_id"), id));
            if (previous == null) update(c, "INSERT INTO harness_provider_fact VALUES (?,?,'media-phase',?,?)", job.getString("run_id"), job.getString("job_id"), id, canonical(progress));
            else if (!canonical(progress).equals(previous.getString("value_json"))) throw new StoreException("CAPABILITY_RECEIPT_CONFLICT");
            return null;
        });
    }

    /**
     * 判断本次作业是否已收到物理完成回执。
     *
     * @param job 持久化的工具作业行。
     * @return 已存在 receipt 事实时返回 {@code true}。
     */
    public boolean finished(JSONObject job) {
        return store.tx(c -> first(rows(c, "SELECT fact_id FROM harness_provider_fact WHERE run_id=? AND fact_kind='receipt' AND fact_id=?", job.getString("run_id"), job.getString("job_id"))) != null);
    }
}
