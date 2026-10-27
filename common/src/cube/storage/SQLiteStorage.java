/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.storage;

import cell.core.talk.LiteralBase;
import cell.util.log.Logger;
import cube.core.*;
import cube.util.SQLUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 SQLite 的存储器。
 *
 * <p><b>契约约定</b>：{@link SQLUtils#spellInsert(String, StorageField[])} 与
 * {@link SQLUtils#spellUpdate(String, StorageField[], Conditional[])} 在
 * <b>不存在有效赋值字段</b>时会返回 {@code null}（而不是拼出一条非法 SQL）。
 * 因此本类的写入方法必须对该返回值判空，并按原有的「记录日志 + 返回 false」语义处理。</p>
 *
 * <p>另外，{@code open()} 失败时 {@code connection} 会保持为 {@code null}，
 * 所有执行方法都必须据此显式失败，而不是抛出 {@link NullPointerException}。</p>
 */
public class SQLiteStorage extends AbstractStorage {

    /** 配置项：数据库文件路径。 */
    public final static String CONFIG_FILE = "file";

    private volatile Connection connection = null;

    /**
     * 访问串行化锁。
     *
     * <p>原实现直接以连接对象作为监视器：一旦 {@link #open()} 重新赋值连接，锁对象就换了，
     * 两个线程可以同时进入临界区。锁必须是独立且不变的。</p>
     */
    private final Object lock = new Object();

    public SQLiteStorage(String name) {
        super(name, StorageType.SQLite);
    }

    /**
     * 打开存储器：建立单连接并完成初始化。
     *
     * <p>重复调用幂等。配置文件缺 {@code file} 项或驱动加载失败时不建立连接，
     * 后续操作统一按“连接未就绪”失败，不抛空指针。</p>
     */
    @Override
    public void open() {
        synchronized (this.lock) {
            if (null != this.connection) {
                return;
            }

            JSONObject config = this.getConfig();
            String file = null;
            try {
                file = config.getString(CONFIG_FILE);
            } catch (JSONException e) {
                Logger.e(this.getClass(), "Open SQLite Storage", e);
                return;
            }

            try {
                this.connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                this.applyPragmas();
                Logger.i(this.getClass(), "SQLite storage is ready: " + file);
            } catch (SQLException e) {
                Logger.e(this.getClass(), "Open SQLite Storage", e);
            }
        }
    }

    /**
     * 应用连接级 PRAGMA。
     *
     * <p>WAL 让读写不互相阻塞；{@code busy_timeout} 把"数据库被占用"从立即失败改为等待重试，
     * 这是单连接串行访问下最容易被忽略的一处稳定性配置。</p>
     */
    private void applyPragmas() {
        Statement statement = null;
        try {
            statement = this.connection.createStatement();
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            Logger.w(this.getClass(), "#applyPragmas - Failed to apply PRAGMA", e);
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    Logger.d(this.getClass(), "#applyPragmas - Failed to close statement: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 关闭存储器：关闭底层连接。
     *
     * <p>允许重复调用；关闭后所有读写操作都会失败。</p>
     */
    @Override
    public void close() {
        synchronized (this.lock) {
            if (null == this.connection) {
                return;
            }

            try {
                this.connection.close();
            } catch (SQLException e) {
                Logger.e(this.getClass(), "Close SQLite Storage", e);
            }

            this.connection = null;
        }
    }

    /**
     * 判断存储器是否已打开。
     *
     * @return 连接已建立返回 {@code true} 。
     */
    @Override
    public boolean isOpen() {
        return null != this.connection;
    }

    /**
     * 应用配置。
     *
     * <p>仅记录配置对象，连接在 {@link #open()} 时才真正建立，因此可在打开前后重复调用。</p>
     *
     * @param config 配置对象。
     */
    @Override
    public void configure(JSONObject config) {
        super.configure(config);
    }

    /**
     * 判断底层连接是否就绪。
     *
     * @return 连接可用返回 {@code true} 。
     */
    private boolean isReady() {
        if (null == this.connection) {
            Logger.e(this.getClass(), "#isReady - The SQLite connection is not ready");
            return false;
        }

        return true;
    }

    /**
     * 判断数据表是否存在。
     *
     * <p>通过 {@code SELECT * FROM <table> LIMIT 1} 探测，语句可执行即视为表存在。</p>
     *
     * @param table 表名。
     * @return 表存在返回 {@code true} 。
     */
    @Override
    public boolean exist(String table) {
        if (!this.isReady()) {
            return false;
        }

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                statement.setQueryTimeout(10);
                statement.executeQuery("SELECT * FROM " + SQLUtils.correctTableName(table) + " LIMIT 1");
            } catch (SQLException e) {
                return false;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#exist - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }

            return true;
        }
    }

    /**
     * 创建数据表。
     *
     * <p>字段按 SQLite 方言修正后拼装 {@code CREATE TABLE} 。连接未就绪或字段为空时返回 {@code false} 。</p>
     *
     * @param table  表名。
     * @param fields 字段定义。
     * @return 建表成功返回 {@code true} 。
     */
    @Override
    public boolean executeCreate(String table, StorageField[] fields) {
        if (!this.isReady()) {
            return false;
        }

        if (null == fields || 0 == fields.length) {
            Logger.e(this.getClass(), "#executeCreate - Empty fields, table: " + table);
            return false;
        }

        // 按 SQLite 方言修正字段。StorageField 不可变，这里构造修正后的副本，
        // 不改写调用方持有的对象（同一份字段数组还会被 executeQuery 使用）。
        StorageField[] fixedFields = new StorageField[fields.length];
        for (int i = 0; i < fields.length; ++i) {
            fixedFields[i] = this.fixBigintAndAutoIncrement(fields[i]);
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellCreateTable(table, fixedFields);

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                Logger.e(this.getClass(), "SQL: " + sql, e);
                return false;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeCreate - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }

        return true;
    }

    /**
     * 按 SQLite 方言修正字段：{@code BIGINT} 改为 {@code INTEGER} ，
     * {@code AUTO_INCREMENT} 改为 {@code AUTOINCREMENT} 。
     *
     * <p>入参不会被修改。无需修正时原样返回入参，否则返回修正后的新实例。</p>
     *
     * @param field 待修正的字段。
     * @return 返回可直接用于拼装 SQL 的字段。
     */
    private StorageField fixBigintAndAutoIncrement(StorageField field) {
        LiteralBase literal = field.getLiteralBase();
        if (literal == LiteralBase.LONG) {
            // SQLite 使用 INTEGER 存储 8 字节整数
            literal = LiteralBase.INT;
        }

        // getConstraints() 返回内部数组的副本，可直接就地修正
        Constraint[] constraints = field.getConstraints();
        boolean changed = (literal != field.getLiteralBase());
        if (null != constraints) {
            for (int i = 0; i < constraints.length; ++i) {
                if (constraints[i] == Constraint.AUTO_INCREMENT) {
                    constraints[i] = Constraint.AUTOINCREMENT;
                    changed = true;
                }
            }
        }

        if (!changed) {
            return field;
        }

        return new StorageField(field.getTableName(), field.getName(), literal, field.getValue(), constraints);
    }

    /**
     * 插入单条记录。
     *
     * @param table  表名。
     * @param fields 待插入的字段与值。
     * @return 插入成功返回 {@code true} 。
     */
    @Override
    public boolean executeInsert(String table, StorageField[] fields) {
        if (!this.isReady()) {
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellInsert(table, fields);
        if (null == sql) {
            // 所有字段值均为空，不存在合法的 INSERT 语句
            return false;
        }

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                Logger.e(this.getClass(), "SQL: " + sql, e);
                return false;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeInsert - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }
        return true;
    }

    /**
     * 批量插入记录。
     *
     * <p>在同一个连接上逐条执行，单条失败不中断后续，只要有一条失败即返回 {@code false} 。</p>
     *
     * @param table      表名。
     * @param fieldsList 每条记录的字段与值。
     * @return 全部成功返回 {@code true} 。
     */
    @Override
    public boolean executeInsert(String table, List<StorageField[]> fieldsList) {
        if (!this.isReady()) {
            return false;
        }

        boolean success = true;

        synchronized (this.lock) {
            for (StorageField[] fields : fieldsList) {
                // 拼写 SQL 语句
                String sql = SQLUtils.spellInsert(table, fields);
                if (null == sql) {
                    // 所有字段值均为空，跳过该条记录
                    success = false;
                    continue;
                }

                Statement statement = null;
                try {
                    statement = this.connection.createStatement();
                    statement.executeUpdate(sql);
                } catch (SQLException e) {
                    Logger.e(this.getClass(), "SQL: " + sql, e);
                    success = false;
                    continue;
                } finally {
                    if (null != statement) {
                        try {
                            statement.close();
                        } catch (SQLException e) {
                            // 释放失败不影响主流程，仅记录
                            Logger.d(SQLiteStorage.class, "#executeInsert - Failed to close 'statement': " + e.getMessage());
                        }
                    }
                }
            }
        }

        return success;
    }

    /**
     * 按条件更新记录。
     *
     * @param table        表名。
     * @param fields       待更新的字段与值。
     * @param conditionals 更新条件。
     * @return 至少更新一行返回 {@code true} 。
     */
    @Override
    public boolean executeUpdate(String table, StorageField[] fields, Conditional[] conditionals) {
        if (!this.isReady()) {
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellUpdate(table, fields, conditionals);
        if (null == sql) {
            // 没有有效的赋值字段
            return false;
        }

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                Logger.e(this.getClass(), "SQL: " + sql, e);
                return false;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeUpdate - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }

        return true;
    }

    /**
     * 按条件删除记录。
     *
     * @param table        表名。
     * @param conditionals 删除条件。
     * @return 语句执行成功返回 {@code true} 。
     */
    @Override
    public boolean executeDelete(String table, Conditional[] conditionals) {
        if (!this.isReady()) {
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellDelete(table, conditionals);

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                Logger.e(this.getClass(), "SQL: " + sql, e);
                return false;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeDelete - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }
        return true;
    }

    /**
     * 查询全表数据。
     *
     * @param table  表名。
     * @param fields 需要读取的字段；为 {@code null} 时读取全部列。
     * @return 结果行集合，查询失败返回 {@code null} 。
     */
    @Override
    public List<StorageField[]> executeQuery(String table, StorageField[] fields) {
        return this.executeQuery(table, fields, null);
    }

    /**
     * 按条件查询数据。
     *
     * <p>SQLite 使用单连接，整个查询在 {@code lock} 上串行化，避免并发读改写冲突。
     * 查询失败返回 {@code null} ，由调用方判空。</p>
     *
     * @param table        表名。
     * @param fields       需要读取的字段；为 {@code null} 时读取全部列。
     * @param conditionals 查询条件，允许为 {@code null} 。
     * @return 结果行集合，查询失败返回 {@code null} 。
     */
    @Override
    public List<StorageField[]> executeQuery(String table, StorageField[] fields, Conditional[] conditionals) {
        ArrayList<StorageField[]> result = new ArrayList<>();

        if (!this.isReady()) {
            return result;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellSelect(table, fields, conditionals);

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery(sql);

                while (rs.next()) {
                    if (null == fields) {
                        StorageField[] row = StorageFields.scanResultSet(rs);
                        result.add(row);
                    } else {
                        StorageField[] row = new StorageField[fields.length];

                        for (int i = 0; i < fields.length; ++i) {
                            StorageField sf = fields[i];
                            LiteralBase literal = sf.getLiteralBase();
                            if (literal == LiteralBase.STRING) {
                                String value = rs.getString(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            } else if (literal == LiteralBase.LONG) {
                                long value = rs.getLong(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            } else if (literal == LiteralBase.INT) {
                                int value = rs.getInt(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            } else if (literal == LiteralBase.BOOL) {
                                boolean value = rs.getBoolean(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            }
                        }

                        result.add(row);
                    }
                }
            } catch (SQLException e) {
                Logger.d(this.getClass(), e.getMessage());
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeQuery - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }

        return result;
    }

    /**
     * 跨表查询数据。
     *
     * @param tables       参与查询的表名。
     * @param fields       需要读取的字段。
     * @param conditionals 查询条件，允许为 {@code null} 。
     * @return 结果行集合，查询失败时为空集合。
     */
    @Override
    public List<StorageField[]> executeQuery(String[] tables, StorageField[] fields, Conditional[] conditionals) {
        ArrayList<StorageField[]> result = new ArrayList<>();

        if (!this.isReady()) {
            return result;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellSelect(tables, fields, conditionals);

        Statement statement = null;

        synchronized (this.lock) {
            try {
                statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery(sql);
                while (rs.next()) {
                    if (null == fields) {
                        StorageField[] row = StorageFields.scanResultSet(rs);
                        result.add(row);
                    } else {
                        StorageField[] row = new StorageField[fields.length];

                        for (int i = 0; i < fields.length; ++i) {
                            StorageField sf = fields[i];
                            LiteralBase literal = sf.getLiteralBase();

                            if (literal == LiteralBase.STRING) {
                                String value = rs.getString(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            } else if (literal == LiteralBase.LONG) {
                                long value = rs.getLong(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            } else if (literal == LiteralBase.INT) {
                                int value = rs.getInt(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            } else if (literal == LiteralBase.BOOL) {
                                boolean value = rs.getBoolean(sf.getName());
                                row[i] = new StorageField(sf.getName(), sf.getLiteralBase(), value);
                            }
                        }

                        result.add(row);
                    }
                }
            } catch (SQLException e) {
                Logger.d(this.getClass(), e.getMessage());
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeQuery - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }

        return result;
    }

    /**
     * 执行原生查询语句。
     *
     * @param sql 完整查询语句。
     * @return 结果行集合，查询失败返回 {@code null} 。
     */
    @Override
    public List<StorageField[]> executeQuery(String sql) {
        synchronized (this.lock) {
            ArrayList<StorageField[]> result = new ArrayList<>();

            if (!this.isReady()) {
                return result;
            }

            Statement statement = null;

            try {
                statement = this.connection.createStatement();
                ResultSet rs = statement.executeQuery(sql);
                while (rs.next()) {
                    StorageField[] row = StorageFields.scanResultSet(rs);
                    result.add(row);
                }
            } catch (SQLException e) {
                Logger.d(this.getClass(), e.getMessage());
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#executeQuery - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }

            return result;
        }
    }

    /**
     * 流式遍历查询结果。
     *
     * <p><b>注意</b>：SQLite 走单连接 + 全局锁，遍历期间会一直持锁，
     * 因此只适合小结果集或离线任务；跨表大批量扫描应走 MySQL 路径。
     * 回调里不要查询或关闭同一个存储；同线程的锁可重入，不能用它保护回调内的嵌套访问。</p>
     * <p>
     * {@inheritDoc}
     */
    @Override
    public void forEachRow(String table, StorageField[] fields, Conditional[] conditionals,
                           RowVisitor visitor) {
        if (null == visitor) {
            throw new IllegalArgumentException("RowVisitor is required, table: " + table);
        }

        fields = StorageRows.copyFields(fields);
        String sql = SQLUtils.spellSelect(table, fields, conditionals);

        Statement statement = null;
        ResultSet resultSet = null;
        synchronized (this.lock) {
            // 与 open/close 共用锁，避免就绪检查和使用连接之间被关闭。
            Connection current = this.connection;
            if (null == current) {
                throw new StorageException("Streaming query failed, storage is not open: " + table);
            }
            try {
                statement = current.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY);
                resultSet = statement.executeQuery(sql);

                ColumnIndex columns = (null == fields)
                        ? ColumnIndex.of(resultSet.getMetaData())
                        : ColumnIndex.of(fields);

                Object[] values = new Object[columns.size()];
                while (resultSet.next()) {
                    for (int i = 0; i < values.length; ++i) {
                        values[i] = (null == fields)
                                ? resultSet.getObject(i + 1)
                                : StorageRows.read(resultSet, i + 1, fields[i].getLiteralBase());
                    }

                    if (!visitor.visit(values, columns)) {
                        break;
                    }
                }
            } catch (SQLException e) {
                Logger.w(this.getClass(), "#forEachRow - Query failed, table: " + table, e);
                throw new StorageException("Streaming query failed: " + table, e);
            } finally {
                StorageRows.closeQuietly(resultSet);
                StorageRows.closeQuietly(statement);
            }
        }
    }

    /**
     * 执行原生 SQL 语句。
     *
     * @param sql 完整 SQL 语句。
     * @return 执行成功返回 {@code true} 。
     */
    @Override
    public boolean execute(String sql) {
        synchronized (this.lock) {
            if (!this.isReady()) {
                return false;
            }

            Statement statement = null;

            try {
                statement = this.connection.createStatement();
                return statement.execute(sql);
            } catch (SQLException e) {
                Logger.w(this.getClass(), "#execute - SQL: " + sql, e);
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(SQLiteStorage.class, "#execute - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }

            return false;
        }
    }
}
