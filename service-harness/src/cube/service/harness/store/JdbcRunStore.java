/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * 权威 Harness 账本。每个变更方法都拥有一个短数据库事务；事务内不允许网络发送、
 * 模型调用或 trace 依赖。所有 run 访问都要求由服务提供的可信 tenant 与 subject。
 *
 * <p>并发模型：run 行 version 作乐观锁，租约（owner/fence/lease_until）作写所有权，
 * MySQL 关键行追加 FOR UPDATE、SQLite 以 BEGIN IMMEDIATE 取写锁；跨副本的准入与
 * 容量用 harness_store_identity 单例行串行化。事件一经提交即不可改，增量事件按
 * 单调 event_seq 分页；终态 run 的最终结果不因迟到证据改写。
 */
public final class JdbcRunStore {

    /**
     * 存储连接配置：方言、目标专用库与库身份。
     */
    private final StoreConfig config;

    /**
     * 事务边界故障钩子；仅隔离测试注入，生产使用无钩子构造器（空实现）。
     */
    private final Faults faults;

    /**
     * 活跃/等待输入类 run 的准入上限；volatile 以便运行期调整。
     * 默认 Integer.MAX_VALUE（不设限），setter 只接受 1..256。
     */
    private volatile int admissionLimit = Integer.MAX_VALUE;

    /**
     * 调整准入上限。
     *
     * @param limit 新的准入上限，取值 1..256
     * @throws StoreException INVALID_ADMISSION_LIMIT：limit 小于 1 或大于 256
     */
    public void admissionLimit(int limit) {
        if (limit < 1 || limit > 256) {
            throw new StoreException("INVALID_ADMISSION_LIMIT");
        }
        admissionLimit = limit;
    }

    /**
     * 事务边界故障钩子：在提交前后两个 stage 边界回调，供隔离测试注入故障。
     */
    public interface Faults {
        /**
         * 到达指定事务边界。
         *
         * @param boundary 边界名；当前仅使用 "beforeCommit"（提交前，抛错将回滚）
         *                 与 "afterCommit"（提交后，抛错表示提交结果未知）
         */
        void at(String boundary);
    }

    /**
     * 事务工作单元：在一个短事务内执行的读写闭包。
     */
    interface Work<T> {
        /**
         * 执行工作单元。
         *
         * @param c 事务内连接
         * @return 工作结果；允许为 null
         * @throws Exception 业务异常（如 StoreException）或 SQL 异常
         */
        T run(Connection c) throws Exception;
    }

    /**
     * 接收方效果：与 inbox 落库同事务执行的同库效果。
     */
    public interface InboxEffect {
        /**
         * 应用接收方效果。
         *
         * @param transaction 事务内连接
         * @throws Exception 效果执行失败
         */
        void apply(Connection transaction) throws Exception;
    }

    /**
     * run 租约值对象：写所有权与 fence 代次的不可变快照。
     */
    public static final class Lease {
        /**
         * 租户 ID；构造时按存储标识格式校验。
         */
        public final String tenant;

        /**
         * 主体 ID；构造时按存储标识格式校验。
         */
        public final String subject;

        /**
         * run ID；构造时按存储标识格式校验。
         */
        public final String runId;

        /**
         * 所有者 ID；构造时按存储标识格式校验。
         */
        public final String owner;

        /**
         * fence 代次；每次 acquire 单调递增，用于作废旧所有者的迟到回调。
         */
        public final long fence;

        /**
         * 构造租约；各标识经 {@link #id(String)} 校验格式。
         *
         * @param tenant  租户 ID
         * @param subject 主体 ID
         * @param runId   run ID
         * @param owner   所有者 ID
         * @param fence   fence 代次
         */
        public Lease(String tenant, String subject, String runId, String owner, long fence) {
            this.tenant = id(tenant);
            this.subject = id(subject);
            this.runId = id(runId);
            this.owner = id(owner);
            this.fence = fence;
        }
    }

    /**
     * 使用空故障钩子构造存储；构造时校验 schema 迁移状态。
     *
     * @param config 存储连接配置
     * @throws Exception 连接失败或迁移校验未通过
     */
    public JdbcRunStore(StoreConfig config) throws Exception {
        this(config, boundary -> {
        });
    }

    /**
     * 故障钩子仅供隔离测试使用；生产使用不带钩子的构造器。
     *
     * @param config 存储连接配置
     * @param faults 事务边界钩子实现
     * @throws Exception 连接失败或迁移校验未通过
     */
    public JdbcRunStore(StoreConfig config, Faults faults) throws Exception {
        this.config = config;
        this.faults = faults;
        try (Connection c = config.connect()) {
            StoreMigrations.verify(c, config);
        }
    }

    /**
     * 事务边界：在独立连接上执行工作单元并提交，带瞬时错误重试。
     *
     * <p>stage 边界语义（经 {@link Faults#at(String)} 回调）：
     * <ul>
     * <li>"beforeCommit"：工作单元成功后、提交尝试前触发；抛错则回滚，
     * 尚未计入提交尝试，按原异常类型上抛（StoreException 原样透传）；</li>
     * <li>"afterCommit"：提交成功后触发；抛错时事务已落地、结果丢弃，
     * 因提交已尝试而上抛 STORE_COMMIT_OUTCOME_UNKNOWN；</li>
     * </ul>
     * 未尝试提交的瞬时 SQL 错误（见 {@link #retryable(SQLException)}）按
     * 10ms、20ms、40ms、80ms、160ms 退避重试，最多 5 次；其余异常包装为
     * STORE_UNAVAILABLE（未尝试提交）或 STORE_COMMIT_OUTCOME_UNKNOWN（提交结果未知）。
     *
     * @param work 事务工作单元
     * @param <T>  结果类型
     * @return 工作单元的返回结果
     * @throws StoreException STORE_INTERRUPTED（退避睡眠被中断）、
     *                        STORE_UNAVAILABLE（未提交的基础失败）、
     *                        STORE_COMMIT_OUTCOME_UNKNOWN（提交后失败，结果未知）
     */
    <T> T tx(Work<T> work) {
        for (int attempt = 0; ; attempt++) {
            boolean committed = false, commitAttempted = false;
            try (Connection c = config.connect()) {
                StoreConfig.begin(c, config.mysql());
                try {
                    T result = work.run(c);
                    faults.at("beforeCommit");
                    commitAttempted = true;
                    StoreConfig.end(c, config.mysql(), true);
                    committed = true;
                    faults.at("afterCommit");
                    return result;
                } catch (Exception e) {
                    if (!committed) {
                        try {
                            StoreConfig.end(c, config.mysql(), false);
                        } catch (SQLException rollbackFailure) {
                            e.addSuppressed(rollbackFailure);
                        }
                    }
                    throw e;
                }
            } catch (Exception e) {
                if (!commitAttempted
                        && attempt < 5
                        && e instanceof SQLException
                        && retryable((SQLException) e)) {
                    try {
                        Thread.sleep(10L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new StoreException("STORE_INTERRUPTED", interrupted);
                    }
                    continue;
                }
                if (e instanceof StoreException) {
                    throw (StoreException) e;
                }
                throw new StoreException(
                        commitAttempted ? "STORE_COMMIT_OUTCOME_UNKNOWN" : "STORE_UNAVAILABLE", e);
            }
        }
    }

    /**
     * 判断 SQL 异常是否为可重试的瞬时错误。
     *
     * @param e SQL 异常
     * @return 锁等待/死锁/完整性约束冲突类错误返回 true（错误码 5、6、1213、1205
     * 或 SQLState 以 23 开头）
     */
    private static boolean retryable(SQLException e) {
        return e.getErrorCode() == 5
                || e.getErrorCode() == 6
                || e.getErrorCode() == 1213
                || e.getErrorCode() == 1205
                || (e.getSQLState() != null && e.getSQLState().startsWith("23"));
    }

    /**
     * 校验并原样返回存储标识。
     *
     * @param value 待校验标识（tenant/subject/run/owner 等）
     * @return 原值
     * @throws StoreException INVALID_STORE_ID：为 null 或不匹配 [A-Za-z0-9_.:-]{1,128}
     */
    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new StoreException("INVALID_STORE_ID");
        }
        return value;
    }

    /**
     * 语义 JSON 摘要：对象键序与数字的字面书写不影响身份。
     *
     * <p>键排序后输出；数字经 BigDecimal 去尾零归一；布尔与字符串按 JSON 字面量输出。
     *
     * @param value JSONObject、JSONArray、Number、Boolean、String 或 null
     * @return 规范化 JSON 文本
     * @throws StoreException INVALID_STORE_JSON：出现不受支持的 JSON 值类型
     */
    public static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            StringJoiner out = new StringJoiner(",", "{", "}");
            for (String key : keys) {
                out.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            }
            return out.toString();
        }
        if (value instanceof JSONArray) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                out.add(canonical(array.get(i)));
            }
            return out.toString();
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        throw new StoreException("INVALID_STORE_JSON");
    }

    /**
     * 计算语义摘要的 SHA-256 十六进制串。
     *
     * @param value 任意受 {@link #canonical(Object)} 支持的 JSON 值
     * @return 64 字符小写十六进制摘要
     * @throws StoreException INVALID_STORE_JSON：值类型不受支持
     */
    public static String hash(Object value) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b :
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical(value).getBytes(StandardCharsets.UTF_8))) {
                out.append(String.format("%02x", b & 255));
            }
            return out.toString();
        } catch (Exception e) {
            throw new StoreException("INVALID_STORE_JSON", e);
        }
    }

    /**
     * 规范化 JSON 并施加持久化体积上限。
     *
     * @param value JSON 值
     * @return 规范化文本
     * @throws StoreException STORE_PAYLOAD_TOO_LARGE：UTF-8 编码超过 65536 字节
     */
    private static String json(Object value) {
        String text = canonical(value);
        if (text.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new StoreException("STORE_PAYLOAD_TOO_LARGE");
        }
        return text;
    }

    /**
     * 按位置绑定参数并准备语句。
     *
     * @param c    连接
     * @param sql  SQL 文本（? 占位）
     * @param args 按序绑定的参数
     * @return 预编译语句；调用方负责关闭
     * @throws SQLException 准备失败
     */
    private static PreparedStatement statement(Connection c, String sql, Object... args)
            throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            s.setObject(i + 1, args[i]);
        }
        return s;
    }

    /**
     * 执行写语句。
     *
     * @param c    连接
     * @param sql  SQL 文本（? 占位）
     * @param args 按序绑定的参数
     * @return 受影响行数
     * @throws SQLException 执行失败
     */
    static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = statement(c, sql, args)) {
            return s.executeUpdate();
        }
    }

    /**
     * 执行查询并把结果集转为 JSON 数组；SQL NULL 映射为 JSONObject.NULL。
     *
     * @param c    连接
     * @param sql  SQL 文本（? 占位）
     * @param args 按序绑定的参数
     * @return 行对象数组；无结果时为空数组
     * @throws SQLException 执行失败
     */
    static JSONArray rows(Connection c, String sql, Object... args) throws SQLException {
        JSONArray out = new JSONArray();
        try (PreparedStatement s = statement(c, sql, args);
             ResultSet r = s.executeQuery()) {
            ResultSetMetaData meta = r.getMetaData();
            while (r.next()) {
                JSONObject row = new JSONObject();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(
                            meta.getColumnLabel(i), r.getObject(i) == null ? JSONObject.NULL : r.getObject(i));
                }
                out.put(row);
            }
        }
        return out;
    }

    /**
     * 取结果集首行。
     *
     * @param rows 行数组
     * @return 首行对象；空数组返回 null
     */
    static JSONObject first(JSONArray rows) {
        return rows.length() == 0 ? null : rows.getJSONObject(0);
    }

    /**
     * 读取数据库当前毫秒时间戳。
     *
     * @param c 连接
     * @return 当前时间（epoch 毫秒）；MySQL 取 UNIX_TIMESTAMP(3)*1000，SQLite 由 julianday 换算
     * @throws SQLException 查询失败
     */
    long now(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r =
                     s.executeQuery(
                             config.mysql()
                                     ? "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)"
                                     : "SELECT CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)")) {
            r.next();
            return r.getLong(1);
        }
    }

    /**
     * 按租户与主体读取 run 行，可选行锁。
     *
     * @param c       连接
     * @param tenant  租户 ID
     * @param subject 主体 ID
     * @param runId   run ID
     * @param lock    是否追加 FOR UPDATE（仅 MySQL 生效）
     * @return harness_run 行
     * @throws StoreException RUN_NOT_FOUND：指定租户/主体下 run 不存在
     */
    JSONObject run(Connection c, String tenant, String subject, String runId, boolean lock)
            throws SQLException {
        JSONObject run =
                first(
                        rows(
                                c,
                                "SELECT * FROM harness_run WHERE tenant=? AND subject_id=? AND run_id=?"
                                        + (lock && config.mysql() ? " FOR UPDATE" : ""),
                                id(tenant),
                                id(subject),
                                id(runId)));
        if (run == null) {
            throw new StoreException("RUN_NOT_FOUND");
        }
        return run;
    }

    /**
     * 判断 run 状态是否为终态。
     *
     * @param state run 状态
     * @return succeeded/failed/cancelled/timed_out 之一返回 true
     */
    private static boolean terminal(String state) {
        return Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(state);
    }

    /**
     * 校验当前调用方仍是 run 的租约所有者。
     *
     * @param c     连接
     * @param r     harness_run 行
     * @param lease 调用方持有的租约
     * @throws StoreException STALE_OWNER：owner/fence 不匹配或租约已过期
     */
    void owner(Connection c, JSONObject r, Lease lease) throws SQLException {
        if (!lease.owner.equals(r.optString("owner_id"))
                || lease.fence != r.getLong("fence")
                || r.getLong("lease_until") <= now(c)) {
            throw new StoreException("STALE_OWNER");
        }
    }

    /**
     * 乐观锁版本校验。
     *
     * @param r        harness_run 行
     * @param expected 调用方期望的 version
     * @throws StoreException VERSION_CONFLICT：行内 version 与期望不一致
     */
    static void version(JSONObject r, long expected) {
        if (r.getLong("version") != expected) {
            throw new StoreException("VERSION_CONFLICT");
        }
    }

    /**
     * 把 run 行投影为对外快照。
     *
     * @param row harness_run 行
     * @return 含 runId/version/eventSeq/state/executionSettlement/taskProfileVersion 的对象
     */
    public static JSONObject snapshot(JSONObject row) {
        return new JSONObject()
                .put("runId", row.getString("run_id"))
                .put("version", row.getLong("version"))
                .put("eventSeq", row.getLong("event_seq"))
                .put("state", row.getString("state"))
                .put("executionSettlement", row.getString("execution_settlement"))
                .put("taskProfileVersion", row.getString("profile_version"));
    }

    /**
     * 原子地推进 version、落事件并记录其发送意图。
     *
     * <p>同一事务内：version+1 与 event_seq+1 的 CAS 更新（失败即
     * VERSION_CONFLICT）、事件行（含追加式状态投影）与 outbox 行一起提交；
     * outbox 是发送意图，实际投递由 dispatcher 认领后完成。
     *
     * @param c     连接
     * @param r     harness_run 行（会被就地更新 version/event_seq/updated_at）
     * @param type  事件类型
     * @param data  事件数据
     * @param topic outbox 主题（coordinator/control/model/runner/usage/result/compensation）
     * @return 更新后的 run 快照
     * @throws StoreException VERSION_CONFLICT：version CAS 未命中
     * @throws SQLException   SQL 执行失败
     */
    JSONObject emit(Connection c, JSONObject r, String type, JSONObject data, String topic)
            throws SQLException {
        long v = r.getLong("version"), seq = r.getLong("event_seq") + 1, time = now(c);
        int changed =
                update(
                        c,
                        "UPDATE harness_run SET version=?,event_seq=?,updated_at=? WHERE run_id=? AND tenant=? AND version=?",
                        v + 1,
                        seq,
                        time,
                        r.getString("run_id"),
                        r.getString("tenant"),
                        v);
        if (changed != 1) {
            throw new StoreException("VERSION_CONFLICT");
        }
        r.put("version", v + 1).put("event_seq", seq).put("updated_at", time);
        String payload = json(data);
        // 追加式、无内容的状态投影支持离线回放。它是权威事件的一部分，不是外部 trace sink；
        // 旧事件保持显式不完整。
        JSONObject persisted =
                run(c, r.getString("tenant"), r.getString("subject_id"), r.getString("run_id"), false);
        JSONObject projection =
                snapshot(persisted).put("usageSettlement", persisted.getString("usage_settlement"));
        update(
                c,
                "INSERT INTO harness_event (run_id,event_seq,tenant,run_version,event_type,occurred_at,schema_version,data_json,projection_json) VALUES (?,?,?,?,?,?,?,?,?)",
                r.getString("run_id"),
                seq,
                r.getString("tenant"),
                v + 1,
                id(type),
                time,
                1,
                payload,
                canonical(projection));
        // 显式列保持 schema 的事件表示无歧义。
        JSONObject event =
                new JSONObject()
                        .put("runId", r.getString("run_id"))
                        .put("eventSeq", seq)
                        .put("runVersion", v + 1)
                        .put("type", type)
                        .put("occurredAt", time)
                        .put("schemaVersion", 1)
                        .put("dataJson", payload);
        update(
                c,
                "INSERT INTO harness_outbox VALUES (?,?,?,?,?,?,0,?,NULL,0,0,0)",
                hash(new JSONArray().put(r.getString("run_id")).put(seq)),
                r.getString("run_id"),
                r.getString("tenant"),
                seq,
                id(topic),
                json(event),
                time);
        return snapshot(r);
    }

    /**
     * 派生幂等请求键。
     *
     * @param tenant    租户 ID
     * @param subject   主体 ID
     * @param operation 操作名
     * @param target    目标（run ID 或 "runs" 等）
     * @param key       调用方提供的幂等键
     * @return 五元组的 SHA-256 摘要
     */
    private String requestKey(
            String tenant, String subject, String operation, String target, String key) {
        return hash(
                new JSONArray()
                        .put(id(tenant))
                        .put(id(subject))
                        .put(id(operation))
                        .put(id(target))
                        .put(id(key)));
    }

    /**
     * 查找并校验幂等回放。
     *
     * @param c      连接
     * @param key    请求键
     * @param digest 本次请求的内容摘要
     * @return 已存响应；无记录返回 null
     * @throws StoreException IDEMPOTENCY_CONFLICT：同键已存在但内容摘要不同
     */
    private JSONObject replay(Connection c, String key, String digest) throws SQLException {
        JSONObject old = first(rows(c, "SELECT * FROM harness_request WHERE request_hash=?", key));
        if (old == null) {
            return null;
        }
        if (!digest.equals(old.getString("payload_hash"))) {
            throw new StoreException("IDEMPOTENCY_CONFLICT");
        }
        return new JSONObject(old.getString("response_json"));
    }

    /**
     * 写入幂等回执。
     *
     * @param c        连接
     * @param key      请求键
     * @param tenant   租户 ID
     * @param subject  主体 ID
     * @param op       操作名
     * @param target   目标
     * @param digest   内容摘要
     * @param response 回放响应
     * @throws SQLException SQL 执行失败
     */
    private void receipt(
            Connection c,
            String key,
            String tenant,
            String subject,
            String op,
            String target,
            String digest,
            JSONObject response)
            throws SQLException {
        // 账本不做剪枝：回执存活至整个 run 生命周期且至少 24h。
        update(
                c,
                "INSERT INTO harness_request VALUES (?,?,?,?,?,?,?,?)",
                key,
                tenant,
                subject,
                op,
                target,
                digest,
                json(response),
                now(c) + 86400000L);
    }

    /**
     * 受理新 run（无配置解析的便捷重载）。
     *
     * @param tenant         租户 ID
     * @param subject        主体 ID
     * @param key            幂等键
     * @param profileVersion 任务 profile 版本
     * @param request        受理请求
     * @return run.created 事件快照
     */
    public JSONObject create(
            String tenant, String subject, String key, String profileVersion, JSONObject request) {
        return createResolved(tenant, subject, key, profileVersion, request, null);
    }

    /**
     * 仅按幂等键查找已受理的 run，不做受理。
     *
     * @param tenant         租户 ID
     * @param subject        主体 ID
     * @param key            幂等键
     * @param profileVersion 任务 profile 版本
     * @param request        受理请求
     * @return 已存响应；无记录返回 null
     * @throws StoreException IDEMPOTENCY_CONFLICT：同键已存在但内容摘要不同
     */
    public JSONObject findCreate(
            String tenant, String subject, String key, String profileVersion, JSONObject request) {
        String k = requestKey(tenant, subject, "create", "runs", key);
        String digest =
                hash(new JSONObject().put("profileVersion", profileVersion).put("request", request));
        return tx(c -> replay(c, k, digest));
    }

    /**
     * 受理时解析并冻结配置的回调接口。
     */
    interface ConfigResolver {
        /**
         * 在事务内解析要冻结的配置。
         *
         * @param connection 事务内连接
         * @return 含 task、releases 等键的冻结配置 JSON
         * @throws Exception 解析失败
         */
        JSONObject resolve(java.sql.Connection connection) throws Exception;
    }

    /**
     * 受理新 run，可选地在事务内解析并冻结配置。
     *
     * <p>store identity 行是跨服务副本的准入互斥量；满员时回放仍然合法。
     * 准入上限只统计活跃/等待类 run；冻结配置连同 release 引用与附件绑定
     * 一起落库，最后发 run.created 事件并写幂等回执。
     *
     * @param tenant         租户 ID
     * @param subject        主体 ID
     * @param key            幂等键
     * @param profileVersion 任务 profile 版本（有冻结配置时以配置内版本为准）
     * @param request        受理请求
     * @param resolver       配置解析器；为 null 时不落配置
     * @return run.created 事件快照
     * @throws StoreException HARNESS_BUSY：活跃/等待类 run 达到准入上限
     * @throws StoreException IDEMPOTENCY_CONFLICT：同键已存在但内容摘要不同
     */
    JSONObject createResolved(
            String tenant,
            String subject,
            String key,
            String profileVersion,
            JSONObject request,
            ConfigResolver resolver) {
        id(profileVersion);
        String k = requestKey(tenant, subject, "create", "runs", key);
        String digest =
                hash(new JSONObject().put("profileVersion", profileVersion).put("request", request));
        return tx(
                c -> {
                    // store identity 行是跨服务副本的准入互斥量。满员时回放仍然合法。
                    if (resolver != null && config.mysql()) {
                        rows(c, "SELECT singleton FROM harness_store_identity WHERE singleton=1 FOR UPDATE");
                    }
                    JSONObject old = replay(c, k, digest);
                    if (old != null) {
                        return old;
                    }
                    if (resolver != null
                            && first(
                            rows(
                                    c,
                                    "SELECT COUNT(*) AS n FROM harness_run WHERE tenant=? AND state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')",
                                    tenant))
                            .getLong("n")
                            >= admissionLimit) {
                        throw new StoreException("HARNESS_BUSY");
                    }
                    String runId = UUID.randomUUID().toString();
                    long time = now(c);
                    JSONObject pinned = resolver == null ? null : resolver.resolve(c);
                    update(
                            c,
                            "INSERT INTO harness_run VALUES (?,?,?,?,?,0,0,NULL,0,0,?,?,?,?)",
                            runId,
                            tenant,
                            subject,
                            pinned == null ? profileVersion : pinned.getJSONObject("task").getString("version"),
                            "queued",
                            "pending",
                            "settled",
                            time,
                            time);
                    if (pinned != null) {
                        update(
                                c,
                                "INSERT INTO harness_run_config VALUES (?,?,?,?,?)",
                                runId,
                                tenant,
                                subject,
                                hash(pinned),
                                canonical(pinned));
                        JSONArray releases = pinned.getJSONArray("releases");
                        for (int i = 0; i < releases.length(); i++) {
                            update(
                                    c,
                                    "INSERT INTO harness_release_ref VALUES (?,?,?)",
                                    tenant,
                                    releases.getJSONObject(i).getString("digest"),
                                    runId);
                        }
                        JSONArray attachments = request.getJSONObject("input").getJSONArray("attachmentIds");
                        for (int i = 0; i < attachments.length(); i++) {
                            update(
                                    c,
                                    "INSERT INTO harness_file_binding VALUES (?,?)",
                                    runId,
                                    attachments.getString(i));
                        }
                    }
                    JSONObject response =
                            emit(c, run(c, tenant, subject, runId, true), "run.created", request, "coordinator");
                    receipt(c, k, tenant, subject, "create", "runs", digest, response);
                    return response;
                });
    }

    /**
     * 读取 run 的任务所有者（主体 ID）。
     *
     * <p>已获授权的服务端调用方才可使用；任务所有者查询始终限定域。
     *
     * @param tenant 租户 ID
     * @param runId  run ID
     * @return subject_id
     * @throws StoreException RUN_NOT_FOUND：指定租户下 run 不存在
     */
    public String subject(String tenant, String runId) {
        return tx(c -> {
            JSONObject row = first(rows(c, "SELECT subject_id FROM harness_run WHERE tenant=? AND run_id=?",
                    id(tenant), id(runId)));
            if (row == null) {
                throw new StoreException("RUN_NOT_FOUND");
            }
            return row.getString("subject_id");
        });
    }

    /**
     * 一个事务提供一致的 state/result/usage 快照。
     *
     * @param tenant  租户 ID
     * @param subject 主体 ID
     * @param runId   run ID
     * @return 含 snapshot/owner/fence/leaseUntil/usageSettlement/usage/result 的对象
     * @throws StoreException RUN_NOT_FOUND：指定租户/主体下 run 不存在
     */
    public JSONObject read(String tenant, String subject, String runId) {
        return tx(
                c -> {
                    JSONObject r = run(c, tenant, subject, runId, true);
                    JSONObject result =
                            first(
                                    rows(
                                            c,
                                            "SELECT result_json FROM harness_result WHERE run_id=? AND tenant=?",
                                            runId,
                                            tenant));
                    JSONObject usage =
                            first(
                                    rows(
                                            c,
                                            "SELECT COALESCE(SUM(estimated_input),0) AS estimatedInput,"
                                                    + "COALESCE(SUM(estimated_output),0) AS estimatedOutput,COALESCE(SUM(actual_input),0) AS actualInput,"
                                                    + "COALESCE(SUM(actual_output),0) AS actualOutput, SUM(CASE WHEN actual_input IS NULL THEN 1 ELSE 0 END) AS pending"
                                                    + " FROM harness_usage WHERE run_id=? AND tenant=?",
                                            runId,
                                            tenant));
                    return new JSONObject()
                            .put("snapshot", snapshot(r))
                            .put("owner", r.opt("owner_id"))
                            .put("fence", r.getLong("fence"))
                            .put("leaseUntil", r.getLong("lease_until"))
                            .put("usageSettlement", r.getString("usage_settlement"))
                            .put("usage", usage)
                            .put(
                                    "result",
                                    result == null
                                            ? JSONObject.NULL
                                            : new JSONObject(result.getString("result_json")));
                });
    }

    /**
     * 获取 run 租约：version 乐观锁加 lease_until CAS，成功后 fence 递增。
     *
     * @param tenant          租户 ID
     * @param subject         主体 ID
     * @param runId           run ID
     * @param owner           所有者 ID
     * @param expectedVersion 期望的 run version
     * @param duration        租约时长（毫秒），取值 100..60000
     * @return 新租约
     * @throws StoreException INVALID_LEASE_DURATION：时长越界
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     * @throws StoreException LEASE_BUSY：租约未过期或 CAS 未命中
     */
    public Lease acquire(
            String tenant,
            String subject,
            String runId,
            String owner,
            long expectedVersion,
            long duration) {
        id(owner);
        if (duration < 100 || duration > 60000) {
            throw new StoreException("INVALID_LEASE_DURATION");
        }
        return tx(
                c -> {
                    JSONObject r = run(c, tenant, subject, runId, true);
                    version(r, expectedVersion);
                    long time = now(c);
                    if (r.getLong("lease_until") > time) {
                        throw new StoreException("LEASE_BUSY");
                    }
                    long fence = r.getLong("fence") + 1;
                    if (update(
                            c,
                            "UPDATE harness_run SET owner_id=?,fence=?,lease_until=? WHERE run_id=? AND version=? AND lease_until<=?",
                            owner,
                            fence,
                            time + duration,
                            runId,
                            expectedVersion,
                            time)
                            != 1) {
                        throw new StoreException("LEASE_BUSY");
                    }
                    update(
                            c,
                            "INSERT INTO harness_lease VALUES (?,?,?,?,?)",
                            runId,
                            fence,
                            owner,
                            time,
                            time + duration);
                    r.put("owner_id", owner).put("fence", fence).put("lease_until", time + duration);
                    emit(c, r, "lease.acquired", new JSONObject().put("fence", fence), "coordinator");
                    return new Lease(tenant, subject, runId, owner, fence);
                });
    }

    /**
     * 续租：仅当前所有者可在租约未过期时延长。
     *
     * @param lease    当前租约
     * @param duration 续租时长（毫秒），取值 100..60000
     * @throws StoreException INVALID_LEASE_DURATION：时长越界
     * @throws StoreException STALE_OWNER：所有权校验或 CAS 未命中
     */
    public void renew(Lease lease, long duration) {
        if (duration < 100 || duration > 60000) {
            throw new StoreException("INVALID_LEASE_DURATION");
        }
        tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    long until = now(c) + duration;
                    if (update(
                            c,
                            "UPDATE harness_run SET lease_until=? WHERE run_id=? AND owner_id=? AND fence=? AND lease_until>?",
                            until,
                            lease.runId,
                            lease.owner,
                            lease.fence,
                            now(c))
                            != 1) {
                        throw new StoreException("STALE_OWNER");
                    }
                    update(
                            c,
                            "UPDATE harness_lease SET expires_at=? WHERE run_id=? AND fence=?",
                            until,
                            lease.runId,
                            lease.fence);
                    return null;
                });
    }

    /**
     * 释放租约：lease_until 置 0 并记录租约历史，发 lease.released 事件。
     *
     * @param lease           当前租约
     * @param expectedVersion 期望的 run version
     * @return lease.released 事件快照
     * @throws StoreException STALE_OWNER：所有权校验失败
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     */
    public JSONObject release(Lease lease, long expectedVersion) {
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    version(r, expectedVersion);
                    update(
                            c,
                            "UPDATE harness_run SET lease_until=0 WHERE run_id=? AND fence=?",
                            lease.runId,
                            lease.fence);
                    update(
                            c,
                            "UPDATE harness_lease SET expires_at=? WHERE run_id=? AND fence=?",
                            now(c),
                            lease.runId,
                            lease.fence);
                    return emit(c, r, "lease.released", new JSONObject(), "coordinator");
                });
    }

    /**
     * 状态迁移：仅接受合法状态机边，发 run.&lt;state&gt; 事件。
     *
     * <p>合法边：queued→running/cancelling；running→running/waiting_input/
     * waiting_approval/reconciling/cancelling；waiting_input/waiting_approval
     * →running/cancelling；reconciling→running/reconciling/cancelling；
     * cancelling→cancelling/reconciling。
     *
     * @param lease           当前租约
     * @param expectedVersion 期望的 run version
     * @param state           目标状态
     * @param checkpoint      随事件携带的 checkpoint 数据
     * @return run.&lt;state&gt; 事件快照
     * @throws StoreException STALE_OWNER：所有权校验失败
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     * @throws StoreException INVALID_STATE_TRANSITION：状态机边不合法
     */
    public JSONObject transition(
            Lease lease, long expectedVersion, String state, JSONObject checkpoint) {
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    version(r, expectedVersion);
                    String from = r.getString("state");
                    boolean valid =
                            (from.equals("queued") && Arrays.asList("running", "cancelling").contains(state))
                                    || (from.equals("running")
                                    && Arrays.asList(
                                            "running",
                                            "waiting_input",
                                            "waiting_approval",
                                            "reconciling",
                                            "cancelling")
                                    .contains(state))
                                    || (Arrays.asList("waiting_input", "waiting_approval").contains(from)
                                    && Arrays.asList("running", "cancelling").contains(state))
                                    || (from.equals("reconciling")
                                    && Arrays.asList("running", "reconciling", "cancelling").contains(state))
                                    || (from.equals("cancelling")
                                    && Arrays.asList("cancelling", "reconciling").contains(state));
                    if (!valid) {
                        throw new StoreException("INVALID_STATE_TRANSITION");
                    }
                    update(c, "UPDATE harness_run SET state=? WHERE run_id=?", state, lease.runId);
                    r.put("state", state);
                    return emit(c, r, "run." + state, checkpoint, "coordinator");
                });
    }

    /**
     * 仅持久化命令回执；控制面提供策略/等待/审批语义与执行。
     *
     * @param tenant          租户 ID
     * @param subject         主体 ID
     * @param runId           run ID
     * @param key             幂等键
     * @param operation       cancel/resume/approval/feedback 之一
     * @param expectedVersion 期望的 run version
     * @param payload         命令负载
     * @return control.requested 事件快照
     * @throws StoreException INVALID_CONTROL：操作名不在受支持集合内
     * @throws StoreException IDEMPOTENCY_CONFLICT：同键已存在但内容摘要不同
     * @throws StoreException RUN_TERMINAL：run 已处于终态
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     */
    public JSONObject submitControl(
            String tenant,
            String subject,
            String runId,
            String key,
            String operation,
            long expectedVersion,
            JSONObject payload) {
        if (!Arrays.asList("cancel", "resume", "approval", "feedback").contains(operation)) {
            throw new StoreException("INVALID_CONTROL");
        }
        String k = requestKey(tenant, subject, operation, runId, key);
        String digest =
                hash(new JSONObject().put("expectedVersion", expectedVersion).put("payload", payload));
        return tx(
                c -> {
                    JSONObject r = run(c, tenant, subject, runId, true);
                    JSONObject old = replay(c, k, digest);
                    if (old != null) {
                        return old;
                    }
                    version(r, expectedVersion);
                    if (terminal(r.getString("state"))) {
                        throw new StoreException("RUN_TERMINAL");
                    }
                    JSONObject response =
                            emit(
                                    c,
                                    r,
                                    "control.requested",
                                    new JSONObject()
                                            .put("operation", operation)
                                            .put("commandId", k)
                                            .put("payload", payload),
                                    "control");
                    receipt(c, k, tenant, subject, operation, runId, digest, response);
                    return response;
                });
    }

    /**
     * 把 intent 类型映射到存储表。
     *
     * @param kind round 或 call
     * @return 对应表名（harness_round/harness_tool_call）
     * @throws StoreException INVALID_INTENT_KIND：类型不受支持
     */
    private static String table(String kind) {
        if (kind.equals("round")) {
            return "harness_round";
        }
        if (kind.equals("call")) {
            return "harness_tool_call";
        }
        throw new StoreException("INVALID_INTENT_KIND");
    }

    /**
     * 校验 attempt 序号范围。
     *
     * @param attempt attempt 序号
     * @throws StoreException INVALID_ATTEMPT：小于 1 或大于 1000000
     */
    private static void attempt(int attempt) {
        if (attempt < 1 || attempt > 1000000) {
            throw new StoreException("INVALID_ATTEMPT");
        }
    }

    /**
     * 写入 round/call intent；同 digest 重复提交幂等回放。
     *
     * <p>仅 running 状态可写入；存在未对账的同 intent attempt 时拒绝重试；
     * round 额外落预估 usage 行并把 run 置为 provisional；执行结算统一置 pending。
     *
     * @param lease           当前租约
     * @param expectedVersion 期望的 run version
     * @param kind            round 或 call
     * @param intentId        intent ID
     * @param attempt         attempt 序号
     * @param payload         intent 负载
     * @param estimatedInput  预估输入用量
     * @param estimatedOutput 预估输出用量
     * @return intent 事件快照
     * @throws StoreException INTENT_CONFLICT：同 intent 已存在且摘要不同
     * @throws StoreException INVALID_STATE_TRANSITION：run 非 running
     * @throws StoreException INTENT_RECONCILIATION_REQUIRED：存在未对账的早先 attempt
     * @throws StoreException INVALID_USAGE：预估用量为负或越界
     */
    public JSONObject intent(
            Lease lease,
            long expectedVersion,
            String kind,
            String intentId,
            int attempt,
            JSONObject payload,
            long estimatedInput,
            long estimatedOutput) {
        String table = table(kind);
        id(intentId);
        attempt(attempt);
        nonnegative(estimatedInput, estimatedOutput);
        String digest =
                hash(
                        new JSONObject()
                                .put("payload", payload)
                                .put("estimatedInput", estimatedInput)
                                .put("estimatedOutput", estimatedOutput));
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    JSONObject old =
                            first(
                                    rows(
                                            c,
                                            "SELECT intent_hash FROM "
                                                    + table
                                                    + " WHERE run_id=? AND intent_id=? AND attempt=?",
                                            lease.runId,
                                            intentId,
                                            attempt));
                    if (old != null) {
                        if (!old.getString("intent_hash").equals(digest)) {
                            throw new StoreException("INTENT_CONFLICT");
                        }
                        return snapshot(r);
                    }
                    version(r, expectedVersion);
                    if (!r.getString("state").equals("running")) {
                        throw new StoreException("INVALID_STATE_TRANSITION");
                    }
                    // 未对账的早先 attempt 必须先对账，才允许授权任何重试。
                    JSONObject pending =
                            first(
                                    rows(
                                            c,
                                            "SELECT attempt FROM "
                                                    + table
                                                    + " WHERE run_id=? AND intent_id=? AND status<>'confirmed'",
                                            lease.runId,
                                            intentId));
                    if (pending != null) {
                        throw new StoreException("INTENT_RECONCILIATION_REQUIRED");
                    }
                    update(
                            c,
                            "INSERT INTO " + table + " VALUES (?,?,?,?,?,?,?,'pending',NULL,NULL)",
                            lease.runId,
                            intentId,
                            attempt,
                            lease.tenant,
                            digest,
                            json(payload),
                            lease.fence);
                    if (kind.equals("round")) {
                        update(
                                c,
                                "INSERT INTO harness_usage VALUES (?,?,?,?,?,?,NULL,NULL,0)",
                                lease.runId,
                                intentId,
                                attempt,
                                lease.tenant,
                                estimatedInput,
                                estimatedOutput);
                        update(
                                c,
                                "UPDATE harness_run SET usage_settlement='provisional' WHERE run_id=?",
                                lease.runId);
                    }
                    update(
                            c,
                            "UPDATE harness_run SET execution_settlement='pending' WHERE run_id=?",
                            lease.runId);
                    r.put("execution_settlement", "pending");
                    return emit(
                            c,
                            r,
                            kind + ".intent",
                            new JSONObject()
                                    .put("intentId", intentId)
                                    .put("attempt", attempt)
                                    .put("intentHash", digest)
                                    .put("fencingToken", lease.fence)
                                    .put("payload", payload),
                            kind.equals("round") ? "model" : "runner");
                });
    }

    /**
     * 当前所有者核验历史 intent/attempt 证据；旧回调永远不能推进 run。
     *
     * @param lease           当前租约
     * @param expectedVersion 期望的 run version
     * @param kind            round 或 call
     * @param intentId        intent ID
     * @param attempt         attempt 序号
     * @param originFence     发起该 intent 时的 fence
     * @param intentHash      intent 摘要
     * @param result          结果负载
     * @return confirmed 事件快照
     * @throws StoreException INTENT_EVIDENCE_MISMATCH：intent 不存在或 fence/摘要不匹配
     * @throws StoreException RESULT_CONFLICT：已 confirmed 且结果摘要不同
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     */
    public JSONObject confirmIntent(
            Lease lease,
            long expectedVersion,
            String kind,
            String intentId,
            int attempt,
            long originFence,
            String intentHash,
            JSONObject result) {
        String table = table(kind);
        id(intentId);
        attempt(attempt);
        String resultHash = hash(result);
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    JSONObject intent =
                            first(
                                    rows(
                                            c,
                                            "SELECT * FROM " + table + " WHERE run_id=? AND intent_id=? AND attempt=?",
                                            lease.runId,
                                            intentId,
                                            attempt));
                    if (intent == null
                            || intent.getLong("origin_fence") != originFence
                            || !intent.getString("intent_hash").equals(intentHash)) {
                        throw new StoreException("INTENT_EVIDENCE_MISMATCH");
                    }
                    if (intent.getString("status").equals("confirmed")) {
                        if (!resultHash.equals(intent.getString("result_hash"))) {
                            throw new StoreException("RESULT_CONFLICT");
                        }
                        return snapshot(r);
                    }
                    version(r, expectedVersion);
                    update(
                            c,
                            "UPDATE "
                                    + table
                                    + " SET status='confirmed',result_json=?,result_hash=? WHERE run_id=? AND intent_id=? AND attempt=?",
                            json(result),
                            resultHash,
                            lease.runId,
                            intentId,
                            attempt);
                    String settlement = outstanding(c, lease.runId) ? "pending" : "confirmed";
                    update(
                            c,
                            "UPDATE harness_run SET execution_settlement=? WHERE run_id=?",
                            settlement,
                            lease.runId);
                    r.put("execution_settlement", settlement);
                    return emit(
                            c,
                            r,
                            kind + ".confirmed",
                            new JSONObject()
                                    .put("intentId", intentId)
                                    .put("attempt", attempt)
                                    .put("resultHash", resultHash)
                                    .put("originFence", originFence),
                            "coordinator");
                });
    }

    /**
     * 判断 run 是否仍有未确认的 round 或 tool_call intent。
     *
     * @param c     连接
     * @param runId run ID
     * @return 存在未确认 intent 时返回 true
     * @throws SQLException SQL 执行失败
     */
    private boolean outstanding(Connection c, String runId) throws SQLException {
        return first(
                rows(
                        c,
                        "SELECT intent_id FROM harness_round WHERE run_id=? AND status<>'confirmed'",
                        runId))
                != null
                || first(
                rows(
                        c,
                        "SELECT intent_id FROM harness_tool_call WHERE run_id=? AND status<>'confirmed'",
                        runId))
                != null;
    }

    /**
     * 列出 run 的全部 intent（按 intent_id、attempt 排序）。
     *
     * @param tenant  租户 ID
     * @param subject 主体 ID
     * @param runId   run ID
     * @param kind    round 或 call
     * @return intent 行数组
     * @throws StoreException RUN_NOT_FOUND：指定租户/主体下 run 不存在
     */
    public JSONArray intents(String tenant, String subject, String runId, String kind) {
        String table = table(kind);
        return tx(
                c -> {
                    run(c, tenant, subject, runId, true);
                    return rows(
                            c,
                            "SELECT * FROM " + table + " WHERE run_id=? AND tenant=? ORDER BY intent_id,attempt",
                            runId,
                            tenant);
                });
    }

    /**
     * 校验用量值非负且不越界。
     *
     * @param values 待校验值
     * @throws StoreException INVALID_USAGE：任一值为负或超过 1000000000000
     */
    private static void nonnegative(long... values) {
        for (long value : values) {
            if (value < 0 || value > 1000000000000L) {
                throw new StoreException("INVALID_USAGE");
            }
        }
    }

    /**
     * 从 JSON 对象精确提取整数。
     *
     * @param object JSON 对象
     * @param key    键
     * @return 整数值
     * @throws StoreException INVALID_USAGE：值缺失、非整数或超出 long 精确范围
     */
    private static long whole(JSONObject object, String key) {
        try {
            Object value = object.get(key);
            if (!(value instanceof Number)) {
                throw new ArithmeticException();
            }
            return new BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException e) {
            throw new StoreException("INVALID_USAGE");
        }
    }

    /**
     * 结算单条用量证据：幂等去重、intent 必须已确认、revision 乐观 CAS。
     *
     * @param c        连接
     * @param lease    当前租约
     * @param evidence 用量证据（roundId/evidenceId/attempt/input/output/expectedRevision）
     * @return 是否发生了实际变更（重复证据返回 false）
     * @throws StoreException USAGE_EVIDENCE_CONFLICT：同证据重复提交且摘要不同
     * @throws StoreException USAGE_INTENT_UNCONFIRMED：对应 round intent 未确认
     * @throws StoreException USAGE_VERSION_CONFLICT：revision CAS 未命中
     * @throws StoreException INVALID_USAGE：数值非法
     * @throws StoreException INVALID_ATTEMPT：attempt 越界
     */
    private boolean usage(Connection c, Lease lease, JSONObject evidence) throws SQLException {
        String round = id(evidence.getString("roundId")), key = id(evidence.getString("evidenceId"));
        long rawAttempt = whole(evidence, "attempt");
        if (rawAttempt < 1 || rawAttempt > 1000000) {
            throw new StoreException("INVALID_ATTEMPT");
        }
        int attempt = (int) rawAttempt;
        long input = whole(evidence, "input"),
                output = whole(evidence, "output"),
                revision = whole(evidence, "expectedRevision");
        nonnegative(input, output, revision);
        String digest = hash(evidence);
        JSONObject old =
                first(
                        rows(
                                c,
                                "SELECT evidence_hash FROM harness_usage_adjustment WHERE run_id=? AND round_id=? AND attempt=? AND evidence_id=?",
                                lease.runId,
                                round,
                                attempt,
                                key));
        if (old != null) {
            if (!digest.equals(old.getString("evidence_hash"))) {
                throw new StoreException("USAGE_EVIDENCE_CONFLICT");
            }
            return false;
        }
        JSONObject intent =
                first(
                        rows(
                                c,
                                "SELECT status FROM harness_round WHERE run_id=? AND intent_id=? AND attempt=?",
                                lease.runId,
                                round,
                                attempt));
        if (intent == null || !intent.getString("status").equals("confirmed")) {
            throw new StoreException("USAGE_INTENT_UNCONFIRMED");
        }
        if (update(
                c,
                "UPDATE harness_usage SET actual_input=?,actual_output=?,revision=revision+1 WHERE run_id=? AND tenant=? AND round_id=? AND attempt=? AND revision=?",
                input,
                output,
                lease.runId,
                lease.tenant,
                round,
                attempt,
                revision)
                != 1) {
            throw new StoreException("USAGE_VERSION_CONFLICT");
        }
        update(
                c,
                "INSERT INTO harness_usage_adjustment VALUES (?,?,?,?,?,?,?,?,?)",
                lease.runId,
                round,
                attempt,
                key,
                digest,
                revision,
                input,
                output,
                now(c));
        String settlement =
                first(
                        rows(
                                c,
                                "SELECT round_id FROM harness_usage WHERE run_id=? AND actual_input IS NULL",
                                lease.runId))
                        == null
                        ? "settled"
                        : "provisional";
        update(c, "UPDATE harness_run SET usage_settlement=? WHERE run_id=?", settlement, lease.runId);
        return true;
    }

    /**
     * 迟到的实际用量替换该 attempt 的总额；revision 防止过期纠正。
     *
     * @param lease           当前租约
     * @param expectedVersion 期望的 run version
     * @param evidence        用量证据
     * @return usage.settled 事件快照；重复证据时仅返回当前快照
     * @throws StoreException STALE_OWNER：所有权校验失败
     * @throws StoreException USAGE_EVIDENCE_CONFLICT/USAGE_INTENT_UNCONFIRMED/USAGE_VERSION_CONFLICT
     */
    public JSONObject settleUsage(Lease lease, long expectedVersion, JSONObject evidence) {
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    boolean changed = usage(c, lease, evidence);
                    if (!changed) {
                        return snapshot(r);
                    }
                    version(r, expectedVersion);
                    return emit(
                            c,
                            r,
                            "usage.settled",
                            new JSONObject()
                                    .put("evidenceId", evidence.getString("evidenceId"))
                                    .put("roundId", evidence.getString("roundId"))
                                    .put("attempt", evidence.getInt("attempt")),
                            "usage");
                });
    }

    /**
     * 最终结果、可选实际用量、version、事件与 Outbox 一起提交。
     *
     * @param lease               当前租约
     * @param expectedVersion     期望的 run version
     * @param state               终态（succeeded/failed/cancelled/timed_out）
     * @param executionSettlement confirmed 或 unknown
     * @param result              结果负载
     * @param actualUsage         实际用量证据数组
     * @return 最终结果信封
     * @throws StoreException INVALID_FINAL：状态/结算组合非法（succeeded 必须 confirmed）
     * @throws StoreException FINAL_CONFLICT：已存在结果且摘要不同
     * @throws StoreException EXECUTION_UNSETTLED：声明 confirmed 但仍有未确认 intent
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     */
    public JSONObject finish(
            Lease lease,
            long expectedVersion,
            String state,
            String executionSettlement,
            JSONObject result,
            JSONArray actualUsage) {
        if (!terminal(state)
                || !Arrays.asList("confirmed", "unknown").contains(executionSettlement)
                || (state.equals("succeeded") && !executionSettlement.equals("confirmed"))) {
            throw new StoreException("INVALID_FINAL");
        }
        JSONObject envelope =
                new JSONObject()
                        .put("state", state)
                        .put("executionSettlement", executionSettlement)
                        .put("result", result)
                        .put("usage", actualUsage);
        String digest = hash(envelope);
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    JSONObject old =
                            first(rows(c, "SELECT * FROM harness_result WHERE run_id=?", lease.runId));
                    if (old != null) {
                        if (!digest.equals(old.getString("result_hash"))) {
                            throw new StoreException("FINAL_CONFLICT");
                        }
                        return new JSONObject(old.getString("result_json"));
                    }
                    version(r, expectedVersion);
                    if (executionSettlement.equals("confirmed") && outstanding(c, lease.runId)) {
                        throw new StoreException("EXECUTION_UNSETTLED");
                    }
                    for (int i = 0; i < actualUsage.length(); i++) {
                        usage(c, lease, actualUsage.getJSONObject(i));
                    }
                    update(
                            c,
                            "INSERT INTO harness_result VALUES (?,?,?,?,?,?)",
                            lease.runId,
                            lease.tenant,
                            state,
                            json(envelope),
                            digest,
                            now(c));
                    update(
                            c,
                            "UPDATE harness_run SET state=?,execution_settlement=? WHERE run_id=?",
                            state,
                            executionSettlement,
                            lease.runId);
                    update(
                            c,
                            "UPDATE harness_wait SET status='expired' WHERE run_id=? AND status='pending'",
                            lease.runId);
                    r.put("state", state).put("execution_settlement", executionSettlement);
                    emit(c, r, "run." + state, new JSONObject().put("resultHash", digest), "result");
                    return new JSONObject(json(envelope));
                });
    }

    /**
     * 记录补偿/对账义务，自身绝不重复外部效果。
     *
     * @param lease           当前租约
     * @param expectedVersion 期望的 run version
     * @param key             幂等键
     * @param kind            round 或 call
     * @param intentId        intent ID
     * @param attempt         attempt 序号
     * @param intentHash      intent 摘要
     * @param obligation      补偿义务负载
     * @return compensation.requested 事件快照
     * @throws StoreException INTENT_EVIDENCE_MISMATCH：intent 不存在或摘要不匹配
     * @throws StoreException IDEMPOTENCY_CONFLICT：同键已存在但内容摘要不同
     * @throws StoreException VERSION_CONFLICT：version 不匹配
     */
    public JSONObject requestCompensation(
            Lease lease,
            long expectedVersion,
            String key,
            String kind,
            String intentId,
            int attempt,
            String intentHash,
            JSONObject obligation) {
        String table = table(kind);
        id(intentId);
        attempt(attempt);
        String k = requestKey(lease.tenant, lease.subject, "compensate", lease.runId, key);
        JSONObject payload =
                new JSONObject()
                        .put("kind", kind)
                        .put("intentId", intentId)
                        .put("attempt", attempt)
                        .put("intentHash", intentHash)
                        .put("obligation", obligation);
        String digest = hash(payload);
        return tx(
                c -> {
                    JSONObject r = run(c, lease.tenant, lease.subject, lease.runId, true);
                    owner(c, r, lease);
                    JSONObject old = replay(c, k, digest);
                    if (old != null) {
                        return old;
                    }
                    version(r, expectedVersion);
                    JSONObject intent =
                            first(
                                    rows(
                                            c,
                                            "SELECT intent_hash FROM "
                                                    + table
                                                    + " WHERE run_id=? AND intent_id=? AND attempt=?",
                                            lease.runId,
                                            intentId,
                                            attempt));
                    if (intent == null || !intentHash.equals(intent.getString("intent_hash"))) {
                        throw new StoreException("INTENT_EVIDENCE_MISMATCH");
                    }
                    JSONObject response = emit(c, r, "compensation.requested", payload, "compensation");
                    receipt(c, k, lease.tenant, lease.subject, "compensate", lease.runId, digest, response);
                    return response;
                });
    }

    /**
     * 恢复检查点来自持久 run/intents/commands；trace 被有意排除在外。
     *
     * @param tenant     租户 ID
     * @param afterRunId 分位游标（run_id 之后）；空串表示从头
     * @param limit      返回上限
     * @return 需要恢复关注的 run 摘要行（租约过期或执行/用量未完全结算）
     * @throws StoreException INVALID_PAGE_SIZE：limit 越界
     * @throws StoreException INVALID_STORE_ID：tenant 或 afterRunId 格式非法
     */
    public JSONArray recover(String tenant, String afterRunId, int limit) {
        page(limit);
        id(tenant);
        if (!afterRunId.isEmpty()) {
            id(afterRunId);
        }
        return tx(
                c ->
                        rows(
                                c,
                                "SELECT run_id,subject_id,version,state,fence,lease_until FROM harness_run WHERE tenant=? AND run_id>? AND lease_until<=?"
                                        + " AND (state NOT IN ('succeeded','failed','cancelled','timed_out') OR execution_settlement<>'confirmed' OR usage_settlement<>'settled') ORDER BY run_id LIMIT ?",
                                tenant,
                                afterRunId,
                                now(c),
                                limit));
    }

    /**
     * 校验分页上限。
     *
     * @param limit 页大小
     * @throws StoreException INVALID_PAGE_SIZE：小于 1 或大于 500
     */
    private static void page(int limit) {
        if (limit < 1 || limit > 500) {
            throw new StoreException("INVALID_PAGE_SIZE");
        }
    }

    /**
     * 按游标分页读取 run 事件，并校验序列连续无缺口。
     *
     * @param tenant  租户 ID
     * @param subject 主体 ID
     * @param runId   run ID
     * @param after   事件游标（不含）
     * @param limit   返回上限
     * @return 事件对象数组
     * @throws StoreException INVALID_EVENT_CURSOR：游标为负或超过当前事件序号
     * @throws StoreException EVENT_GAP：检测到事件序号缺口
     * @throws StoreException RUN_NOT_FOUND：指定租户/主体下 run 不存在
     */
    public JSONArray events(String tenant, String subject, String runId, long after, int limit) {
        page(limit);
        if (after < 0) {
            throw new StoreException("INVALID_EVENT_CURSOR");
        }
        return tx(
                c -> {
                    JSONObject r = run(c, tenant, subject, runId, true);
                    if (after > r.getLong("event_seq")) {
                        throw new StoreException("INVALID_EVENT_CURSOR");
                    }
                    JSONArray found =
                            rows(
                                    c,
                                    "SELECT * FROM harness_event WHERE run_id=? AND tenant=? AND event_seq>? ORDER BY event_seq LIMIT ?",
                                    runId,
                                    tenant,
                                    after,
                                    limit);
                    if (after < r.getLong("event_seq")
                            && (found.length() == 0 || found.getJSONObject(0).getLong("event_seq") != after + 1)) {
                        throw new StoreException("EVENT_GAP");
                    }
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < found.length(); i++) {
                        JSONObject e = found.getJSONObject(i);
                        if (e.getLong("event_seq") != after + i + 1) {
                            throw new StoreException("EVENT_GAP");
                        }
                        out.put(
                                new JSONObject()
                                        .put("runId", runId)
                                        .put("eventSeq", e.getLong("event_seq"))
                                        .put("runVersion", e.getLong("run_version"))
                                        .put("type", e.getString("event_type"))
                                        .put("occurredAt", e.getLong("occurred_at"))
                                        .put("schemaVersion", e.getInt("schema_version"))
                                        .put("dataJson", e.getString("data_json")));
                    }
                    return out;
                });
    }

    /**
     * 全局派发认领面向可信的内部 dispatcher；消息仍保持租户边界。
     *
     * @param dispatcher dispatcher ID
     * @param limit      认领上限
     * @param duration   认领租约时长（毫秒），取值 100..60000
     * @return 认领到的 outbox 消息数组（含更新后的 claim 字段）
     * @throws StoreException INVALID_STORE_ID：dispatcher 格式非法
     * @throws StoreException INVALID_PAGE_SIZE：limit 越界
     * @throws StoreException INVALID_LEASE_DURATION：时长越界
     */
    public JSONArray claimOutbox(String dispatcher, int limit, long duration) {
        id(dispatcher);
        page(limit);
        if (duration < 100 || duration > 60000) {
            throw new StoreException("INVALID_LEASE_DURATION");
        }
        return tx(
                c -> {
                    long time = now(c);
                    JSONArray candidates =
                            rows(
                                    c,
                                    "SELECT * FROM harness_outbox WHERE delivered=0 AND available_at<=? AND claim_until<=? ORDER BY available_at,message_id LIMIT ?"
                                            + (config.mysql() ? " FOR UPDATE SKIP LOCKED" : ""),
                                    time,
                                    time,
                                    limit);
                    JSONArray claimed = new JSONArray();
                    for (int i = 0; i < candidates.length(); i++) {
                        JSONObject m = candidates.getJSONObject(i);
                        long fence = m.getLong("claim_fence") + 1;
                        if (update(
                                c,
                                "UPDATE harness_outbox SET claim_owner=?,claim_fence=?,claim_until=?,attempts=attempts+1 WHERE message_id=? AND delivered=0 AND claim_until<=? AND claim_fence=?",
                                dispatcher,
                                fence,
                                time + duration,
                                m.getString("message_id"),
                                time,
                                fence - 1)
                                == 1) {
                            claimed.put(
                                    m.put("claim_owner", dispatcher)
                                            .put("claim_fence", fence)
                                            .put("claim_until", time + duration)
                                            .put("attempts", m.getInt("attempts") + 1));
                        }
                    }
                    return claimed;
                });
    }

    /**
     * 仅在接收方提交其 Inbox/效果之后调用；丢失的 ACK 会故意重投。
     *
     * @param dispatcher dispatcher ID
     * @param messageId  消息 ID
     * @param fence      认领时的 claim_fence
     * @throws StoreException STALE_OUTBOX_CLAIM：认领已过期或 fence 不匹配
     */
    public void ackOutbox(String dispatcher, String messageId, long fence) {
        id(dispatcher);
        id(messageId);
        tx(
                c -> {
                    if (update(
                            c,
                            "UPDATE harness_outbox SET delivered=1 WHERE message_id=? AND delivered=0 AND claim_owner=? AND claim_fence=? AND claim_until>?",
                            messageId,
                            dispatcher,
                            fence,
                            now(c))
                            != 1) {
                        throw new StoreException("STALE_OUTBOX_CLAIM");
                    }
                    return null;
                });
    }

    /**
     * 释放认领并把消息推迟到 available_at 再投。
     *
     * @param dispatcher  dispatcher ID
     * @param messageId   消息 ID
     * @param fence       认领时的 claim_fence
     * @param delayMillis 推迟毫秒数，取值 0..60000
     * @throws StoreException INVALID_RETRY_DELAY：延迟越界
     * @throws StoreException STALE_OUTBOX_CLAIM：认领已过期或 fence 不匹配
     */
    public void retryOutbox(String dispatcher, String messageId, long fence, long delayMillis) {
        id(dispatcher);
        id(messageId);
        if (delayMillis < 0 || delayMillis > 60000) {
            throw new StoreException("INVALID_RETRY_DELAY");
        }
        tx(
                c -> {
                    long time = now(c);
                    if (update(
                            c,
                            "UPDATE harness_outbox SET claim_until=0,available_at=? WHERE message_id=? AND delivered=0 AND claim_owner=? AND claim_fence=? AND claim_until>?",
                            time + delayMillis,
                            messageId,
                            dispatcher,
                            fence,
                            time)
                            != 1) {
                        throw new StoreException("STALE_OUTBOX_CLAIM");
                    }
                    return null;
                });
    }

    /**
     * Inbox 与同库接收方效果共享一个事务；外部效果需要 journal。
     *
     * @param tenant    租户 ID
     * @param consumer  消费者 ID
     * @param messageId 消息 ID
     * @param effect    接收方效果
     * @return 首次消费返回 true；已消费过返回 false
     * @throws StoreException MESSAGE_NOT_FOUND：该租户下消息不存在
     */
    public boolean consume(String tenant, String consumer, String messageId, InboxEffect effect) {
        id(tenant);
        id(consumer);
        id(messageId);
        return tx(
                c -> {
                    JSONObject m =
                            first(
                                    rows(
                                            c,
                                            "SELECT payload_json FROM harness_outbox WHERE tenant=? AND message_id=?"
                                                    + (config.mysql() ? " FOR UPDATE" : ""),
                                            tenant,
                                            messageId));
                    if (m == null) {
                        throw new StoreException("MESSAGE_NOT_FOUND");
                    }
                    if (first(
                            rows(
                                    c,
                                    "SELECT message_id FROM harness_inbox WHERE consumer_id=? AND message_id=?",
                                    consumer,
                                    messageId))
                            != null) {
                        return false;
                    }
                    effect.apply(c);
                    update(
                            c,
                            "INSERT INTO harness_inbox VALUES (?,?,?,?,?)",
                            consumer,
                            messageId,
                            tenant,
                            hash(m.getString("payload_json")),
                            now(c));
                    return true;
                });
    }

    /**
     * 发送器回调：把一条 outbox 消息投给接收方。
     */
    public interface Sender {
        /**
         * 投递消息。
         *
         * @param message outbox 消息行
         * @throws Exception 投递失败；由 dispatch 转重试
         */
        void send(JSONObject message) throws Exception;
    }

    /**
     * 一个有界派发轮次。发送方只允许对持久、去重后的接收方结果 ACK。
     *
     * @param dispatcher dispatcher ID
     * @param limit      本轮认领上限
     * @param sender     发送器实现
     * @return 成功发送并 ACK 的消息数
     */
    public int dispatch(String dispatcher, int limit, Sender sender) {
        JSONArray batch = claimOutbox(dispatcher, limit, 30000);
        int sent = 0;
        for (int i = 0; i < batch.length(); i++) {
            JSONObject m = batch.getJSONObject(i);
            try {
                sender.send(m);
                ackOutbox(dispatcher, m.getString("message_id"), m.getLong("claim_fence"));
                sent++;
            } catch (Exception failure) {
                try {
                    retryOutbox(dispatcher, m.getString("message_id"), m.getLong("claim_fence"), 1000);
                } catch (StoreException stale) {
                    /* 另一个 dispatcher 会回收已过期的认领。 */
                }
            }
        }
        return sent;
    }
}
