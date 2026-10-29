/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.usage;

import cube.core.Storage;
import cube.storage.MySQLStorage;
import cube.storage.StorageType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * 按 meter 逐次记账的持久化账本。
 *
 * <p>一次模型派发对应一条 {@code call} 记录，终态证据以 revision 递增的方式更新它。每次变更与对应的
 * 小时聚合增量在同一个事务里提交，因此明细与汇总不会出现“明细已写、汇总没动”的中间态。</p>
 *
 * <p>并发控制：MySQL 依靠 {@code store} 单行的 {@code FOR UPDATE} 行锁串行化写入；SQLite 使用
 * {@code BEGIN IMMEDIATE} 加 {@code synchronous=FULL} 。带 {@link Connection} 参数的方法要求调用方
 * 已经开启事务，其余入口自行开事务。</p>
 *
 * <p>建表由 {@link #initialize()} 在服务启动时完成；Console 这类读者只查询，绝不可调用它。</p>
 */
public final class UsageLedger {

    /**
     * 连接来源。
     *
     * <p>每次调用返回一条新连接，由调用方负责关闭；抛出的异常表示存储不可达。</p>
     */
    public interface Connections {

        /**
         * 打开一条新连接。
         *
         * @return 新连接，由调用方关闭。
         * @throws SQLException 无法建立连接。
         */
        Connection open() throws SQLException;
    }

    /**
     * 在事务连接上执行的工作单元。
     *
     * @param <T> 返回值类型。
     */
    public interface Work<T> {

        /**
         * 执行工作单元。
         *
         * @param c 已开启事务的连接。
         * @return 工作结果。
         * @throws Exception 任何失败都会触发回滚。
         */
        T run(Connection c) throws Exception;
    }

    /**
     * 连接来源，账本的所有读写都经由它取得连接。
     */
    public final Connections connections;

    /**
     * 表名前缀，已由 {@link UsageSchema#prefix(String)} 校验。
     */
    public final String prefix;

    /**
     * 目标库是否为 MySQL，决定事务与聚合语句的方言。
     */
    public final boolean mysql;

    /**
     * 小时表上的九个累计列，顺序与 {@link #values(JSONObject)} 返回数组的下标一一对应。
     */
    private static final String[] METRICS = {"calls", "actual_calls", "estimated_calls", "unknown_calls", "pending_calls", "input_tokens", "output_tokens", "estimated_input", "estimated_output"};

    /**
     * 创建账本。
     *
     * @param connections 连接来源。
     * @param prefix      表名前缀，取值受 {@link UsageSchema#prefix(String)} 约束。
     * @param mysql       目标库是否为 MySQL。
     * @throws IllegalArgumentException 前缀非法。
     */
    public UsageLedger(Connections connections, String prefix, boolean mysql) {
        this.connections = connections;
        this.prefix = UsageSchema.prefix(prefix);
        this.mysql = mysql;
    }

    /**
     * 由服务的存储配置构造 AIGC 账本。
     *
     * <p>MySQL 目标借用存储自带的连接池，SQLite 目标直接按配置里的文件路径打开。表名前缀固定为
     * {@code aigc_ai_usage_} ，因此 AIGC 与 Harness 的计量表即使同库也不会互相干扰。</p>
     *
     * @param storage 服务使用的存储。
     * @return 对应的账本实例。
     */
    public static UsageLedger storage(Storage storage) {
        boolean mysql = storage.getType() == StorageType.MySQL;
        return new UsageLedger(mysql ? () -> ((MySQLStorage) storage).borrowConnection()
                : () -> DriverManager.getConnection("jdbc:sqlite:" + storage.getConfig().getString("file")), "aigc_ai_usage_", mysql);
    }

    /**
     * 在一个事务里执行工作单元。
     *
     * <p>按方言开启事务：MySQL 关闭自动提交，SQLite 设置 busy_timeout、FULL 同步并立即开启写事务。
     * 工作单元成功则提交，失败先回滚再把原异常抛出——回滚本身失败时附加为 suppressed 而不覆盖原异常。</p>
     *
     * @param work 工作单元。
     * @param <T>  返回值类型。
     * @return 工作单元的返回值。
     * @throws IllegalStateException 连接或事务操作失败，底层存储不可用。
     */
    public <T> T transaction(Work<T> work) {
        try (Connection c = connections.open()) {
            if (mysql) {
                c.setAutoCommit(false);
            } else {
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA busy_timeout=10000");
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute("BEGIN IMMEDIATE");
                }
            }
            try {
                T value = work.run(c);
                if (mysql) {
                    c.commit();
                } else {
                    try (Statement s = c.createStatement()) {
                        s.execute("COMMIT");
                    }
                }
                return value;
            } catch (Exception e) {
                try {
                    if (mysql) {
                        c.rollback();
                    } else {
                        try (Statement s = c.createStatement()) {
                            s.execute("ROLLBACK");
                        }
                    }
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("AI_USAGE_STORAGE_UNAVAILABLE", e);
        }
    }

    /**
     * 初始化计量表结构。
     *
     * <p>只允许服务启动时调用，读者（Console）永远不建表。MySQL 先取名为前缀的 {@code GET_LOCK}
     * 命名锁，保证多实例同时启动时只有一个在建表；建表语句改写成 {@code IF NOT EXISTS} ，索引报
     * “已存在”（1061）视为成功。首次初始化会写入 store 单行标记，最后在 finally 中释放命名锁。</p>
     *
     * <p>store 标记必须在释放锁之前提交，否则另一个初始化者会看不到标记而重复初始化。</p>
     *
     * @throws IllegalStateException 建表失败或存储不可用。
     */
    public void initialize() {
        transaction(c -> {
            if (mysql) {
                JSONObject locked = one(c, "SELECT GET_LOCK(CONCAT(DATABASE(),?),10) AS acquired", ":" + prefix);
                if (locked == null || locked.optInt("acquired") != 1) {
                    throw new SQLException("USAGE_BOOTSTRAP_BUSY");
                }
            }
            try {
                for (String sql : UsageSchema.statements(prefix, mysql)) {
                    if (sql.startsWith("CREATE TABLE")) {
                        sql = sql.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
                    }
                    try (Statement s = c.createStatement()) {
                        s.execute(sql);
                    } catch (SQLException e) {
                        if (!(sql.startsWith("CREATE INDEX") && (e.getErrorCode() == 1061 || e.getMessage().contains("already exists")))) {
                            throw e;
                        }
                    }
                }
                JSONObject existing = one(c, "SELECT * FROM " + prefix + "store WHERE singleton=1");
                if (existing == null) {
                    long now = System.currentTimeMillis();
                    update(c, "INSERT INTO " + prefix + "store VALUES (1,?,1,?,?)", UUID.randomUUID().toString(), now, now);
                }
                metadata(c);
                // 先把 store 标记提交出去，再让其他初始化者去抢命名锁。
                if (mysql) {
                    c.commit();
                }
                return null;
            } finally {
                if (mysql) {
                    one(c, "SELECT RELEASE_LOCK(CONCAT(DATABASE(),?)) AS released", ":" + prefix);
                }
            }
        });
    }

    /**
     * 读取并校验库身份元数据。
     *
     * @param c 已开启事务的连接。
     * @return store 单行记录，含 {@code store_id} 、{@code collected_at} 、{@code updated_at} 等字段。
     * @throws SQLException 记录缺失或结构版本不是 1 。
     */
    public JSONObject metadata(Connection c) throws SQLException {
        JSONObject row = one(c, "SELECT * FROM " + prefix + "store WHERE singleton=1");
        if (row == null || row.getInt("schema_version") != 1) {
            throw new SQLException("AI_USAGE_SCHEMA_UNSUPPORTED");
        }
        return row;
    }

    /**
     * 取得 store 单行的写锁并校验结构版本。
     *
     * <p>MySQL 上等价于对该行加 {@code FOR UPDATE} ，从而把跨进程的计量写入串行化。</p>
     *
     * @param c 已开启事务的连接。
     * @throws SQLException 记录缺失或结构版本不是 1 。
     */
    private void lock(Connection c) throws SQLException {
        JSONObject m = one(c, "SELECT * FROM " + prefix + "store WHERE singleton=1" + (mysql ? " FOR UPDATE" : ""));
        if (m == null || m.getInt("schema_version") != 1) {
            throw new SQLException("AI_USAGE_NOT_INITIALIZED");
        }
    }

    /**
     * 登记一次开始的模型调用。
     *
     * <p>委托身份表示由发起方记账，本端直接跳过。</p>
     *
     * @param identity 结算身份。
     * @param id       meter ID。
     * @param model    模型名。
     * @param unit     执行单元 ID。
     * @param now      派发开始时间，UTC epoch 毫秒。
     */
    public void begin(UsageContext identity, String id, String model, String unit, long now) {
        if (!identity.delegated) {
            transaction(c -> {
                begin(c, identity, id, model, unit, now);
                return null;
            });
        }
    }

    /**
     * 在调用方的事务里登记一次开始的模型调用。
     *
     * <p>幂等：meter 已存在时校验归属字段是否一致，一致直接返回，不一致报 {@code USAGE_ID_CONFLICT} ，
     * 避免同一个 meter ID 被挪到别的域或 Contact 名下。这是 Harness 派发事务的一部分。</p>
     *
     * @param c        已开启事务的连接。
     * @param identity 结算身份。
     * @param id       meter ID。
     * @param model    模型名。
     * @param unit     执行单元 ID。
     * @param now      派发开始时间，UTC epoch 毫秒。
     * @throws SQLException 记录冲突或库操作失败。
     */
    public void begin(Connection c, UsageContext identity, String id, String model, String unit, long now) throws SQLException {
        if (identity.delegated) {
            return;
        }
        lock(c);
        JSONObject old = one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", valid(id, 128));
        if (old != null) {
            if (!old.getString("domain").equals(identity.domain) || old.getLong("contact_id") != identity.contactId
                    || !old.getString("model").equals(model) || !old.getString("run_id").equals(identity.runId)
                    || !old.getString("module").equals(identity.module) || !old.getString("unit_id").equals(unit)
                    || !old.getString("request_id").equals(identity.requestId)) {
                throw new SQLException("USAGE_ID_CONFLICT");
            }
            return;
        }
        update(c, "INSERT INTO " + prefix + "call (meter_id,domain,contact_id,module,model,unit_id,run_id,request_id,started_at,status,quality,revision) VALUES (?,?,?,?,?,?,?,?,?,'running','pending',0)",
                id, identity.domain, identity.contactId, valid(identity.module, 64), valid(model, 128), valid(unit, 128), valid(identity.runId, 128), valid(identity.requestId, 128), now);
        JSONObject row = one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", id);
        aggregate(c, row, null);
        touch(c);
    }

    /**
     * 构造一条终态证据，并做提交前校验。
     *
     * <p>质量只能是 {@code actual} 、{@code estimated} 、{@code unknown} ；输入与输出必须同时有值或
     * 同时缺失；非 {@code unknown} 质量必须给出 Token 数。缺失值保留 NULL，绝不补零。</p>
     *
     * @param id       meter ID。
     * @param revision 修订号，必须为正数。
     * @param status   执行状态。
     * @param quality  质量标签。
     * @param input    输入 Token，缺失传 {@code null} 。
     * @param output   输出 Token，缺失传 {@code null} 。
     * @param finished 结束时间，UTC epoch 毫秒。
     * @return 可直接交给 {@link #settle(JSONObject)} 的证据对象。
     * @throws IllegalArgumentException 质量、Token 组合或字段长度不合法。
     */
    public static JSONObject evidence(String id, long revision, String status, String quality, Long input, Long output, long finished) {
        if (!("actual".equals(quality) || "estimated".equals(quality) || "unknown".equals(quality))) {
            throw new IllegalArgumentException("INVALID_USAGE_QUALITY");
        }
        if ((input == null) != (output == null) || (input != null && (input < 0 || output < 0))) {
            throw new IllegalArgumentException("INVALID_USAGE_TOKENS");
        }
        if (!"unknown".equals(quality) && input == null) {
            throw new IllegalArgumentException("MISSING_USAGE_TOKENS");
        }
        return new JSONObject().put("meterId", id).put("revision", revision).put("status", valid(status, 32)).put("quality", quality)
                .put("input", input == null ? JSONObject.NULL : input).put("output", output == null ? JSONObject.NULL : output).put("finished", finished);
    }

    /**
     * 在一个新事务里提交终态证据。
     *
     * @param evidence 终态证据。
     * @throws IllegalStateException 存储不可用，或证据冲突。
     */
    public void settle(JSONObject evidence) {
        transaction(c -> {
            settle(c, evidence);
            return null;
        });
    }

    /**
     * 在调用方的事务里提交终态证据。
     *
     * <p>幂等与冲突判定：同一 meter 与 revision 的摘要一致则直接返回，不一致报
     * {@code USAGE_EVIDENCE_CONFLICT} ；revision 不大于库里现值、或已确认的实际用量被降级为非实际时忽略，
     * 避免迟到或重放的数据覆盖更强的证据。否则写入 revision 审计、更新 call ，并按新旧差值调整小时聚合。</p>
     *
     * @param c 已开启事务的连接。
     * @param e 终态证据，回放日志同样走这条路径，故按与在线证据相同的强度校验。
     * @throws SQLException 证据非法、冲突或记录不存在。
     */
    public void settle(Connection c, JSONObject e) throws SQLException {
        lock(c);
        // 回放日志里的证据也按在线证据的强度校验，不能因为是本地文件就放宽。
        evidence(e.getString("meterId"), e.getLong("revision"), e.getString("status"), e.getString("quality"),
                e.isNull("input") ? null : e.getLong("input"), e.isNull("output") ? null : e.getLong("output"), e.getLong("finished"));
        String id = e.getString("meterId"), hash = evidenceHash(e);
        long revision = e.getLong("revision");
        if (revision <= 0) {
            throw new SQLException("INVALID_USAGE_REVISION");
        }
        JSONObject known = one(c, "SELECT evidence_hash FROM " + prefix + "revision WHERE meter_id=? AND revision=?", id, revision);
        if (known != null) {
            if (!known.getString("evidence_hash").equals(hash)) {
                throw new SQLException("USAGE_EVIDENCE_CONFLICT");
            }
            return;
        }
        JSONObject old = one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", id);
        if (old == null) {
            throw new SQLException("USAGE_CALL_NOT_FOUND");
        }
        if (revision <= old.getLong("revision") || ("actual".equals(old.getString("quality")) && !"actual".equals(e.getString("quality")))) {
            return;
        }
        update(c, "INSERT INTO " + prefix + "revision VALUES (?,?,?,?,?)", id, revision, hash, e.toString(), System.currentTimeMillis());
        update(c, "UPDATE " + prefix + "call SET status=?,quality=?,input_tokens=?,output_tokens=?,finished_at=?,revision=? WHERE meter_id=?",
                e.getString("status"), e.getString("quality"), e.isNull("input") ? null : e.getLong("input"), e.isNull("output") ? null : e.getLong("output"), e.getLong("finished"), revision, id);
        aggregate(c, one(c, "SELECT * FROM " + prefix + "call WHERE meter_id=?", id), old);
        touch(c);
    }

    /**
     * 刷新 store 的最后更新时间。
     *
     * @param c 已开启事务的连接。
     * @throws SQLException 库操作失败。
     */
    private void touch(Connection c) throws SQLException {
        update(c, "UPDATE " + prefix + "store SET updated_at=? WHERE singleton=1", System.currentTimeMillis());
    }

    /**
     * 把一条 call 记录折算成九个累计口径的计数向量。
     *
     * <p>下标顺序同 {@link #METRICS} 。未派发的记录整列为 0；质量决定计入哪一类（实际、估算、未知、
     * 待核对）；只有实际与估算才带 Token，且分别落在实际列与估算列上。</p>
     *
     * @param r call 记录，允许为 {@code null} 。
     * @return 长度为 9 的计数向量。
     */
    private static long[] values(JSONObject r) {
        long[] v = new long[9];
        if (r == null || "not_dispatched".equals(r.optString("status"))) {
            return v;
        }
        v[0] = 1;
        String q = r.getString("quality");
        int i = "actual".equals(q) ? 1 : "estimated".equals(q) ? 2 : "unknown".equals(q) ? 3 : 4;
        v[i] = 1;
        if (i == 1 && "unknown".equals(r.getString("status"))) {
            v[4] = 1;
        }
        if (i == 1 || i == 2) {
            v[i == 1 ? 5 : 7] = r.getLong("input_tokens");
            v[i == 1 ? 6 : 8] = r.getLong("output_tokens");
        }
        return v;
    }

    /**
     * 按新旧记录之差调整小时聚合。
     *
     * <p>桶键由「整小时 + 域 + Contact + 模块 + 模型 + 单元」的摘要确定，桶不存在时先插入零值行，
     * 随后把九列加上 {@code 新值 - 旧值} 。因此同一 meter 的多次修订只会产生增量，不会重复计数。</p>
     *
     * @param c   已开启事务的连接。
     * @param r   变更后的 call 记录。
     * @param old 变更前的 call 记录，新登记时传 {@code null} 。
     * @throws SQLException 库操作失败。
     */
    private void aggregate(Connection c, JSONObject r, JSONObject old) throws SQLException {
        long hour = r.getLong("started_at") / 3600000L * 3600000L;
        String key = hash(new JSONArray().put(hour).put(r.getString("domain")).put(r.getLong("contact_id")).put(r.getString("module")).put(r.getString("model")).put(r.getString("unit_id")).toString());
        if (one(c, "SELECT bucket_id FROM " + prefix + "hourly WHERE bucket_id=?", key) == null) {
            update(c, "INSERT INTO " + prefix + "hourly VALUES (?,?,?,?,?,?,?,0,0,0,0,0,0,0,0,0)", key, hour, r.getString("domain"), r.getLong("contact_id"), r.getString("module"), r.getString("model"), r.getString("unit_id"));
        }
        long[] before = values(old), after = values(r);
        StringJoiner set = new StringJoiner(",");
        Object[] args = new Object[10];
        for (int i = 0; i < 9; i++) {
            set.add(METRICS[i] + "=" + METRICS[i] + "+?");
            args[i] = Math.subtractExact(after[i], before[i]);
        }
        args[9] = key;
        update(c, "UPDATE " + prefix + "hourly SET " + set + " WHERE bucket_id=?", args);
    }

    /**
     * 校验字段非空且不超长。
     *
     * @param s   字段值。
     * @param max 允许的最大长度。
     * @return 原样返回的字段值。
     * @throws IllegalArgumentException 值为 {@code null} 或超出长度上限。
     */
    public static String valid(String s, int max) {
        if (s == null || s.length() > max) {
            throw new IllegalArgumentException("INVALID_USAGE_FIELD");
        }
        return s;
    }

    /**
     * 计算证据的稳定摘要。
     *
     * <p>只取影响计费与状态的字段，因此同一证据无论重复提交多少次，摘要都相同。</p>
     *
     * @param e 终态证据。
     * @return SHA-256 十六进制摘要。
     */
    public static String evidenceHash(JSONObject e) {
        return hash(new JSONArray().put(e.getString("meterId")).put(e.getLong("revision"))
                .put(e.getString("quality")).put(e.opt("input")).put(e.opt("output")).toString());
    }

    /**
     * 计算字符串的 SHA-256 摘要。
     *
     * @param value 待摘要的字符串，按 UTF-8 编码。
     * @return 小写十六进制摘要。
     */
    public static String hash(String value) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) {
                s.append(String.format("%02x", b & 255));
            }
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 执行一条更新语句。
     *
     * @param c    数据库连接。
     * @param sql  带占位符的 SQL。
     * @param args 依次绑定的参数。
     * @return 受影响的行数。
     * @throws SQLException 库操作失败。
     */
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            return s.executeUpdate();
        }
    }

    /**
     * 查询单行。
     *
     * @param c    数据库连接。
     * @param sql  带占位符的 SQL。
     * @param args 依次绑定的参数。
     * @return 第一行记录；无结果返回 {@code null} 。
     * @throws SQLException 库操作失败。
     */
    public static JSONObject one(Connection c, String sql, Object... args) throws SQLException {
        JSONArray rows = rows(c, sql, args);
        return rows.length() == 0 ? null : rows.getJSONObject(0);
    }

    /**
     * 查询多行。
     *
     * <p>列名统一转成小写作为 JSON 键，SQL NULL 映射为 {@link JSONObject#NULL} ，不做零值替换。</p>
     *
     * @param c    数据库连接。
     * @param sql  带占位符的 SQL。
     * @param args 依次绑定的参数。
     * @return 结果行数组，无结果时为空数组。
     * @throws SQLException 库操作失败。
     */
    public static JSONArray rows(Connection c, String sql, Object... args) throws SQLException {
        JSONArray a = new JSONArray();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet r = s.executeQuery()) {
                ResultSetMetaData m = r.getMetaData();
                while (r.next()) {
                    JSONObject o = new JSONObject();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        Object v = r.getObject(i);
                        o.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT), v == null ? JSONObject.NULL : v);
                    }
                    a.put(o);
                }
            }
        }
        return a;
    }

    /**
     * 按顺序绑定语句参数。
     *
     * @param s    预编译语句。
     * @param args 参数数组，{@code null} 元素按 SQL NULL 绑定。
     * @throws SQLException 绑定失败。
     */
    private static void bind(PreparedStatement s, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            s.setObject(i + 1, args[i]);
        }
    }
}
