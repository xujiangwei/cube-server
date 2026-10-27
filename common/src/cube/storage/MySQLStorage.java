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
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MySQL 存储器。
 *
 * <p><b>连接获取约定</b>：{@link ConnectionPool#get()} 在池已满且等待超时后会返回
 * {@code null}。因此每一次 {@code this.pool.get()} 之后都必须判空，
 * 否则会在数据库压力过大时抛出 {@link NullPointerException}。</p>
 *
 * <p><b>连接归还约定</b>：凡是从池中取出的连接，都必须通过 try/finally 确保归还，
 * 否则连接会永久泄漏、池内计数只增不减，最终整个存储层不可用。</p>
 */
public class MySQLStorage extends AbstractStorage {

    /** 配置项：数据库主机地址。 */
    public final static String CONFIG_HOST = "host";
    /** 配置项：数据库端口，默认 3306 。 */
    public final static String CONFIG_PORT = "port";
    /** 配置项：库名，默认 {@code cube} 。 */
    public final static String CONFIG_SCHEMA = "schema";
    /** 配置项：登录用户名。 */
    public final static String CONFIG_USER = "user";
    /** 配置项：登录密码。 */
    public final static String CONFIG_PASSWORD = "password";

    /**
     * 配置项：事务隔离级别。
     *
     * <p>取值遵循 JDBC 常量约定，仅对显式借用连接的事务生效，会话内状态通过 JDBC 修改。</p>
     */
    public final static String CONFIG_TRANSACTION_ISOLATION = "transactionIsolation";

    /**
     * 配置项：是否自动提交，默认 {@code true} 。
     *
     * <p>{@link #borrowConnection()} 归还连接时会按该配置恢复会话状态。</p>
     */
    public final static String CONFIG_AUTO_COMMIT = "autoCommit";

    private volatile ConnectionPool pool;

    /**
     * 空闲连接回收任务。
     *
     * <p>原实现让 {@code ConnectionPool} 继承 {@code TimerTask} 却从未调度，回收逻辑是死代码。
     * 这里改为显式的单线程调度器，随 {@link #open()} 启动、{@link #close()} 取消。</p>
     */
    private volatile ScheduledExecutorService reaper;

    /**
     * 查询失败计数，按表维度累计。
     *
     * <p>失败本身不抛出异常（见 {@link #executeQuery(String, StorageField[], Conditional[])} 的契约），
     * 因此必须可观测：这个计数器由上报模块读取，让"连接池打满"在控制台可见。</p>
     */
    private final ConcurrentHashMap<String, AtomicLong> queryFailures = new ConcurrentHashMap<>();

    /**
     * 是否已校验过服务端 {@code sql_mode}。
     *
     * <p>只校验一次：{@link SQLUtils} 的字符串转义依赖反斜杠转义（MySQL 默认），
     * 若服务端开了 {@code NO_BACKSLASH_ESCAPES}，转义语义会变。这里只告警，不改变任何执行结果。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean sqlModeChecked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 连接池空闲连接回收间隔（秒）。
     */
    private final static long REAPER_INTERVAL_SECONDS = 60;

    /**
     * 构造 MySQL 存储器。
     *
     * <p>构造过程只保存名称与类型，不建立任何网络连接；连接池在 {@link #open()} 中创建。</p>
     *
     * @param name 存储器名称，用于日志与配置段定位。
     */
    public MySQLStorage(String name) {
        super(name, StorageType.MySQL);
    }

    /**
     * 打开存储器：加载 JDBC 驱动并建立连接池。
     *
     * <p>重复调用是幂等的，池已存在时直接返回。驱动缺失时不创建连接池，只记录错误日志，
     * 后续操作按“池未就绪”降级处理，避免把故障推迟到首次执行 SQL 才暴露。</p>
     */
    @Override
    public synchronized void open() {
        if (null != this.pool) {
            return;
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            // 驱动缺失时不再创建连接池，避免把故障推迟到第一次执行 SQL 才暴露
            Logger.e(this.getClass(), "#open - MySQL JDBC driver not found", e);
            return;
        }

        this.pool = new ConnectionPool(64, this.config);

        // 启动空闲连接回收。守护线程，避免阻塞进程退出。
        this.reaper = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "MySQLStorage-Reaper");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.reaper.scheduleWithFixedDelay(this.pool, REAPER_INTERVAL_SECONDS, REAPER_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        Logger.i(this.getClass(), "#open - Storage is ready: " + describeTarget());
    }

    /**
     * 关闭存储器：停止空闲连接回收任务并关闭连接池。
     *
     * <p>允许重复调用，未打开时直接返回。关闭后所有读写操作都会失败。</p>
     */
    @Override
    public synchronized void close() {
        if (null != this.reaper) {
            this.reaper.shutdownNow();
            this.reaper = null;
        }

        if (null == this.pool) {
            return;
        }

        this.pool.close();
        this.pool = null;
    }

    /**
     * 判断存储器是否已打开。
     *
     * @return 连接池已建立时返回 {@code true} 。
     */
    @Override
    public boolean isOpen() {
        return null != this.pool;
    }

    /**
     * 查询失败次数（指定表）。
     *
     * @param table 表名。
     * @return 累计失败次数。
     */
    public long getQueryFailureCount(String table) {
        AtomicLong counter = this.queryFailures.get(keyOf(table));
        return (null == counter) ? 0L : counter.get();
    }

    /**
     * 查询失败次数（全部表）。
     *
     * @return 累计失败次数。
     */
    public long getQueryFailureTotal() {
        long total = 0L;
        for (AtomicLong counter : this.queryFailures.values()) {
            total += counter.get();
        }
        return total;
    }

    private static String keyOf(String table) {
        return (null == table) ? "unknown" : table;
    }

    private void increaseQueryFailure(String table) {
        String key = keyOf(table);
        AtomicLong counter = this.queryFailures.get(key);
        if (null == counter) {
            AtomicLong created = new AtomicLong(0L);
            AtomicLong existing = this.queryFailures.putIfAbsent(key, created);
            counter = (null == existing) ? created : existing;
        }
        counter.incrementAndGet();
    }

    /**
     * 记录一次查询失败。
     *
     * <p>日志里保留表名、条件数量、耗时与池状态；完整 SQL 只在 DEBUG 级输出——
     * 值已内联进 SQL，WARN 级打印整条语句会把业务数据写进日志。</p>
     *
     * @param table        表名。
     * @param conditionals 条件列表。
     * @param sql          完整 SQL。
     * @param beginTime    开始时间戳。
     * @param cause        异常，允许为 {@code null} 。
     */
    private void logQueryFailure(String table, Conditional[] conditionals, String sql, long beginTime,
                                 Throwable cause) {
        this.increaseQueryFailure(table);

        int conditionCount = (null == conditionals) ? 0 : conditionals.length;
        long cost = System.currentTimeMillis() - beginTime;
        ConnectionPool current = this.pool;
        String poolState = (null == current) ? "closed" : (current.count.get() + "/" + current.maxConn);

        String message = "#executeQuery - Failed, table: " + table
                + ", conditions: " + conditionCount
                + ", cost: " + cost + "ms"
                + ", pool: " + poolState;

        if (null == cause) {
            Logger.w(this.getClass(), message);
        } else {
            Logger.w(this.getClass(), message, cause);
        }

        if (Logger.isDebugLevel()) {
            Logger.d(this.getClass(), "#executeQuery - SQL: " + sql);
        }
    }

    /**
     * 一次性校验服务端 {@code sql_mode}。
     *
     * <p>{@code SQLUtils} 通过"先转义反斜杠、再转义单引号"构造字面量，这依赖 MySQL 默认的
     * 反斜杠转义。若服务端启用了 {@code NO_BACKSLASH_ESCAPES}，同样的转义会产生不同语义。
     * 这里只在首次查询时检查一次并输出 WARN；校验本身失败也不影响后续查询。</p>
     *
     * @param connection 当前借出的连接。
     */
    private void checkSqlMode(Connection connection) {
        if (!this.sqlModeChecked.compareAndSet(false, true)) {
            return;
        }

        Statement statement = null;
        try {
            statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT @@SESSION.sql_mode");
            if (rs.next()) {
                String mode = rs.getString(1);
                if (null != mode && mode.toUpperCase().contains("NO_BACKSLASH_ESCAPES")) {
                    Logger.w(this.getClass(), "#checkSqlMode - NO_BACKSLASH_ESCAPES is enabled, "
                            + "the escaping in SQLUtils assumes backslash escapes, sql_mode: " + mode);
                }
            }
        } catch (Exception e) {
            Logger.d(this.getClass(), "#checkSqlMode - Skipped: " + e.getMessage());
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    Logger.d(MySQLStorage.class, "#checkSqlMode - Failed to close 'statement': " + e.getMessage());
                }
            }
        }
    }

    private String describeTarget() {
        if (null == this.config) {
            return "unknown";
        }

        return this.config.optString(CONFIG_HOST, "127.0.0.1")
                + ":" + this.config.optInt(CONFIG_PORT, 3306)
                + "/" + this.config.optString(CONFIG_SCHEMA, "cube");
    }

    /**
     * 借用池内连接，供需要显式 JDBC 事务的调用方使用。
     *
     * <p>返回的逻辑连接必须关闭（建议 try-with-resources）：close 会回滚未结束的事务、
     * 恢复连接状态并归还池，不关闭可复用的物理连接。池不可用或获取超时时抛出 SQLException。
     * 已关闭的逻辑连接不能继续使用；清理失败的物理连接直接丢弃。
     */
    public Connection borrowConnection() throws SQLException {
        final ConnectionPool current = this.pool;
        if (current == null) {
            throw new SQLException("MySQL connection pool is not open", "08003");
        }
        final Connection connection = current.get();
        if (connection == null) {
            throw new SQLException("MySQL connection pool could not provide a connection", "08001");
        }
        try {
            final boolean readOnly = connection.isReadOnly();
            final int isolation = connection.getTransactionIsolation();
            final String catalog = connection.getCatalog();
            final boolean autoCommit = this.config.optBoolean(CONFIG_AUTO_COMMIT, true);
            return (Connection) Proxy.newProxyInstance(MySQLStorage.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, new java.lang.reflect.InvocationHandler() {
                        private boolean closed;

                        @Override
                        public synchronized Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                                throws Throwable {
                            String name = method.getName();
                            if (method.getDeclaringClass() == Object.class) {
                                if (name.equals("equals")) return proxy == args[0];
                                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                                return "Pooled MySQL connection";
                            }
                            if (name.equals("close")) {
                                if (!closed) {
                                    closed = true;
                                    boolean reusable = false;
                                    try {
                                        if (!connection.isClosed()) {
                                            if (!connection.getAutoCommit()) {
                                                connection.rollback();
                                            }
                                            if (connection.isReadOnly() != readOnly) {
                                                connection.setReadOnly(readOnly);
                                            }
                                            if (connection.getTransactionIsolation() != isolation) {
                                                connection.setTransactionIsolation(isolation);
                                            }
                                            if (!Objects.equals(connection.getCatalog(), catalog)) {
                                                connection.setCatalog(catalog);
                                            }
                                            if (connection.getAutoCommit() != autoCommit) {
                                                connection.setAutoCommit(autoCommit);
                                            }
                                            connection.clearWarnings();
                                            reusable = true;
                                        }
                                    } finally {
                                        if (reusable) {
                                            current.returnConn(connection);
                                        } else {
                                            current.discard(connection);
                                        }
                                    }
                                }
                                return null;
                            }
                            if (name.equals("isClosed")) {
                                return closed || connection.isClosed();
                            }
                            if (closed) {
                                throw new SQLException("Pooled connection is closed", "08003");
                            }
                            if (name.equals("unwrap")) {
                                if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                                throw new SQLException("Physical pooled connection cannot be unwrapped");
                            }
                            if (name.equals("isWrapperFor")) {
                                return ((Class<?>) args[0]).isInstance(proxy);
                            }
                            try {
                                return method.invoke(connection, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                    });
        } catch (SQLException | RuntimeException e) {
            current.discard(connection);
            throw e;
        }
    }

    /**
     * 判断数据表是否存在。
     *
     * <p>通过 {@code SELECT * FROM <table> LIMIT 1} 探测，语句可执行即视为表存在。
     * 池未就绪、取不到连接或执行失败都返回 {@code false} ，不抛异常。</p>
     *
     * @param table 表名，拼装前会经 {@code SQLUtils.correctTableName} 校正。
     * @return 表存在返回 {@code true} 。
     */
    @Override
    public boolean exist(String table) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#exist - The connection pool is not ready");
            return false;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#exist - Failed to obtain connection");
            return false;
        }

        Statement statement = null;
        try {
            statement = connection.createStatement();
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
                    Logger.d(MySQLStorage.class, "#exist - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }
        return true;
    }

    /**
     * 在指定连接上执行自定义操作。
     *
     * <p>无论 {@code handler} 是否抛出异常，连接都会被归还到池中。</p>
     *
     * @param handler 由调用方实现的操作。
     */
    public void execute(ConnectionHandler handler) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#execute - The connection pool is not ready");
            return;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#execute - Failed to obtain connection");
            return;
        }

        try {
            handler.handle(connection);
        } finally {
            // 必须保证归还，否则 handler 抛异常时连接会永久泄漏
            this.pool.returnConn(connection);
        }
    }

    /**
     * 创建数据表。
     *
     * <p>字段先按 MySQL 方言修正（见 {@link #fixAutoIncrement} ），再拼装 {@code CREATE TABLE} 语句。
     * 修正作用于副本，调用方持有的字段数组不会被改写。字段为空或 SQL 执行失败返回 {@code false} 。</p>
     *
     * @param table  表名。
     * @param fields 字段定义。
     * @return 建表成功返回 {@code true} 。
     */
    @Override
    public boolean executeCreate(String table, StorageField[] fields) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeCreate - The connection pool is not ready");
            return false;
        }

        if (null == fields || 0 == fields.length) {
            Logger.e(this.getClass(), "#executeCreate - Empty fields, table: " + table);
            return false;
        }

        // 按 MySQL 方言修正字段。StorageField 不可变，这里构造修正后的副本，
        // 不改写调用方持有的对象（同一份字段数组还会被 executeQuery 使用）。
        StorageField[] fixedFields = new StorageField[fields.length];
        for (int i = 0; i < fields.length; ++i) {
            fixedFields[i] = fixAutoIncrement(fields[i]);
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeCreate - Failed to obtain connection");
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellCreateTable(table, fixedFields);
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            Logger.e(this.getClass(), "#executeCreate - SQL: " + sql, e);
            return false;
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeCreate - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }

        return true;
    }

    /**
     * 按 MySQL 方言修正字段：{@code AUTOINCREMENT} 改为 {@code AUTO_INCREMENT} 。
     *
     * <p>入参不会被修改。无需修正时原样返回入参，否则返回修正后的新实例。</p>
     *
     * @param field 待修正的字段。
     * @return 返回可直接用于拼装 SQL 的字段。
     */
    private StorageField fixAutoIncrement(StorageField field) {
        // getConstraints() 返回内部数组的副本，可直接就地修正
        Constraint[] constraints = field.getConstraints();
        if (null == constraints) {
            return field;
        }

        boolean changed = false;
        for (int i = 0; i < constraints.length; ++i) {
            if (constraints[i] == Constraint.AUTOINCREMENT) {
                constraints[i] = Constraint.AUTO_INCREMENT;
                changed = true;
            }
        }

        if (!changed) {
            return field;
        }

        return new StorageField(field.getTableName(), field.getName(), field.getLiteralBase(),
                field.getValue(), constraints);
    }

    /**
     * 插入单条记录。
     *
     * <p>所有字段值均为空时无法拼出合法语句，直接返回 {@code false} 。</p>
     *
     * @param table  表名。
     * @param fields 待插入的字段与值。
     * @return 插入成功返回 {@code true} 。
     */
    @Override
    public boolean executeInsert(String table, StorageField[] fields) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeInsert - The connection pool is not ready");
            return false;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeInsert - Failed to obtain connection");
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellInsert(table, fields);
        if (null == sql) {
            // 所有字段值均为空，不存在合法的 INSERT 语句
            this.pool.returnConn(connection);
            return false;
        }

        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            Logger.e(this.getClass(), "#executeInsert - SQL: " + sql, e);
            return false;
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeInsert - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }
        return true;
    }

    /**
     * 批量插入记录。
     *
     * <p>逐条执行、单条失败不中断后续，只要有一条失败即返回 {@code false} 。
     * 整批共用一个连接，结束后统一归还。</p>
     *
     * @param table      表名。
     * @param fieldsList 每条记录的字段与值。
     * @return 全部成功返回 {@code true} 。
     */
    @Override
    public boolean executeInsert(String table, List<StorageField[]> fieldsList) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeInsert - The connection pool is not ready");
            return false;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeInsert - Failed to obtain connection");
            return false;
        }

        boolean success = true;
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
                statement = connection.createStatement();
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                Logger.e(this.getClass(), "#executeInsert - SQL: " + sql, e);
                success = false;
                continue;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(MySQLStorage.class, "#executeInsert - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }

        this.pool.returnConn(connection);

        return success;
    }

    /**
     * 按条件更新记录。
     *
     * <p>没有有效赋值字段时无法拼出语句，直接返回 {@code false} ；
     * 语句执行成功但影响行数为 0 时同样返回 {@code false} 。</p>
     *
     * @param table        表名。
     * @param fields       待更新的字段与值。
     * @param conditionals 更新条件。
     * @return 至少更新一行返回 {@code true} 。
     */
    @Override
    public boolean executeUpdate(String table, StorageField[] fields, Conditional[] conditionals) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeUpdate - The connection pool is not ready");
            return false;
        }

        boolean updated = false;

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeUpdate - Failed to obtain connection");
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellUpdate(table, fields, conditionals);
        if (null == sql) {
            // 没有有效的赋值字段
            this.pool.returnConn(connection);
            return false;
        }

        Statement statement = null;
        try {
            statement = connection.createStatement();
            int row = statement.executeUpdate(sql);
            if (row > 0) {
                updated = true;
            }
        } catch (SQLException e) {
            Logger.e(this.getClass(), "#executeUpdate - SQL: " + sql, e);
            return false;
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeUpdate - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }

        return updated;
    }

    /**
     * 按条件删除记录。
     *
     * <p>语句执行成功即返回 {@code true} ，不区分实际删除行数；条件为空数组时删除全表。</p>
     *
     * @param table        表名。
     * @param conditionals 删除条件。
     * @return 语句执行成功返回 {@code true} 。
     */
    @Override
    public boolean executeDelete(String table, Conditional[] conditionals) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeDelete - The connection pool is not ready");
            return false;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeDelete - Failed to obtain connection");
            return false;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellDelete(table, conditionals);

        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            Logger.e(this.getClass(), "#executeDelete - SQL: " + sql, e);
            return false;
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeDelete - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }
        return true;
    }

    /**
     * 查询全表数据。
     *
     * @param table  表名。
     * @param fields 需要读取的字段，为 {@code null} 时读取全部列。
     * @return 结果行集合，查询失败返回 {@code null} 。
     */
    @Override
    public List<StorageField[]> executeQuery(String table, StorageField[] fields) {
        return this.executeQuery(table, fields, null);
    }

    /**
     * 按条件查询数据。
     *
     * <p>按 {@code fields} 声明的字面量类型逐列读取。池未就绪、取连接失败或 SQL 异常时，
     * 累计失败计数并记录日志，然后返回 {@code null} ，由调用方判空。</p>
     *
     * @param table        表名。
     * @param fields       需要读取的字段，为 {@code null} 时读取全部列。
     * @param conditionals 查询条件，允许为 {@code null} 。
     * @return 结果行集合，查询失败返回 {@code null} 。
     */
    @Override
    public List<StorageField[]> executeQuery(String table, StorageField[] fields, Conditional[] conditionals) {
        long beginTime = System.currentTimeMillis();

        if (null == this.pool) {
            this.logQueryFailure(table, conditionals, null, beginTime, null);
            return null;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            this.logQueryFailure(table, conditionals, null, beginTime, null);
            return null;
        }

        ArrayList<StorageField[]> result = new ArrayList<>();

        // 一次性校验 sql_mode（只告警，不影响查询）
        this.checkSqlMode(connection);

        // 拼写 SQL 语句
        String sql = SQLUtils.spellSelect(table, fields, conditionals);

        Statement statement = null;
        try {
            statement = connection.createStatement();
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
            this.logQueryFailure(table, conditionals, sql, beginTime, e);
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeQuery - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }
        return result;
    }

    /**
     * 跨表查询数据。
     *
     * <p>与单表重载的差异在于失败语义：本重载返回空集合而非 {@code null} ，
     * 因为现有调用点尚未逐点判空，契约统一后再切换。</p>
     *
     * @param tables       参与查询的表名。
     * @param fields       需要读取的字段。
     * @param conditionals 查询条件，允许为 {@code null} 。
     * @return 结果行集合，查询失败时为空集合。
     */
    @Override
    public List<StorageField[]> executeQuery(String[] tables, StorageField[] fields, Conditional[] conditionals) {
        ArrayList<StorageField[]> result = new ArrayList<>();
        long beginTime = System.currentTimeMillis();

        // TODO 契约统一（见 EVALUATION-2026-10-25 R1）：本重载与 String sql 重载在失败时仍返回空集合，
        // 因为调用点（ContactStorage/AIGCStorage 等）尚未判空。切换为 null 前必须逐点补齐判空。
        if (null == this.pool) {
            this.logQueryFailure(Arrays.toString(tables), conditionals, null, beginTime, null);
            return result;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            this.logQueryFailure(Arrays.toString(tables), conditionals, null, beginTime, null);
            return result;
        }

        // 拼写 SQL 语句
        String sql = SQLUtils.spellSelect(tables, fields, conditionals);

        Statement statement = null;
        try {
            statement = connection.createStatement();
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
            this.logQueryFailure(Arrays.toString(tables), conditionals, sql, beginTime, e);
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeQuery - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }

        return result;
    }

    /**
     * 执行原生查询语句。
     *
     * <p>绕过字段描述直接执行 SQL ，返回的每行由 {@code StorageFields.scanResultSet} 扫描得到。
     * 查询失败会累计失败计数并返回 {@code null} 。</p>
     *
     * @param sql 完整查询语句。
     * @return 结果行集合，查询失败返回 {@code null} 。
     */
    @Override
    public List<StorageField[]> executeQuery(String sql) {
        ArrayList<StorageField[]> result = new ArrayList<>();
        long beginTime = System.currentTimeMillis();

        if (null == this.pool) {
            this.logQueryFailure(null, null, sql, beginTime, null);
            return result;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            this.logQueryFailure(null, null, sql, beginTime, null);
            return result;
        }

        // 一次性校验 sql_mode（只告警，不影响查询）
        this.checkSqlMode(connection);

        Statement statement = null;

        try {
            statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(sql);
            while (rs.next()) {
                StorageField[] row = StorageFields.scanResultSet(rs);
                result.add(row);
            }
        } catch (SQLException e) {
            this.logQueryFailure(null, null, sql, beginTime, e);
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#executeQuery - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }

        return result;
    }

    /**
     * 流式遍历查询结果。
     *
     * <p>第 2 个参数 {@code Integer.MIN_VALUE} 是关键：Connector/J 默认会把整个结果集
     * 先读进客户端内存，不设它的话"流式"只是换了个遍历写法，堆压力一点没降。
     * 流式结果集在读完之前独占本连接，因此必须走 {@code finally} 释放。</p>
     * <p>
     * {@inheritDoc}
     */
    @Override
    public void forEachRow(String table, StorageField[] fields, Conditional[] conditionals,
                           RowVisitor visitor) {
        if (null == visitor) {
            throw new IllegalArgumentException("RowVisitor is required, table: " + table);
        }

        long beginTime = System.currentTimeMillis();

        // 在借用前完成参数校验和 SQL 构建，避免参数异常泄漏连接。
        fields = StorageRows.copyFields(fields);
        String sql = SQLUtils.spellSelect(table, fields, conditionals);
        ConnectionPool current = this.pool;
        if (null == current) {
            this.logQueryFailure(table, conditionals, null, beginTime, null);
            throw new StorageException("Streaming query failed, storage is not open: " + table);
        }
        Connection connection = current.get();
        if (null == connection) {
            this.logQueryFailure(table, conditionals, null, beginTime, null);
            throw new StorageException("Streaming query failed, no connection: " + table);
        }

        Statement statement = null;
        ResultSet resultSet = null;
        boolean reusable = true;
        try {
            statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(Integer.MIN_VALUE);
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
            reusable = null == e.getSQLState() || !e.getSQLState().startsWith("08");
            this.logQueryFailure(table, conditionals, sql, beginTime, e);
            throw new StorageException("Streaming query failed: " + table, e);
        } finally {
            reusable = StorageRows.closeQuietly(resultSet) && reusable;
            reusable = StorageRows.closeQuietly(statement) && reusable;
            // close/open 可能更换池；连接必须归还到借用时的池。
            if (reusable) {
                current.returnConn(connection);
            } else {
                current.discard(connection);
            }
        }
    }

    /**
     * 执行原生 SQL 语句。
     *
     * <p>用于无法用字段描述表达的语句，例如 DDL 或带子查询的更新。</p>
     *
     * @param sql 完整 SQL 语句。
     * @return 执行成功返回 {@code true} 。
     */
    @Override
    public boolean execute(String sql) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#execute - The connection pool is not ready");
            return false;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#execute - Failed to obtain connection");
            return false;
        }

        Statement statement = null;

        try {
            statement = connection.createStatement();
            return statement.execute(sql);
        } catch (SQLException e) {
            Logger.w(this.getClass(), "#execute - SQL: " + sql, e);
        } finally {
            if (null != statement) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#execute - Failed to close 'statement': " + e.getMessage());
                }
            }

            this.pool.returnConn(connection);
        }

        return false;
    }


    /**
     * 连接池。
     * 连接池会对长时间未使用的连接做有效性探测，探测失败则丢弃并重建。
     *
     * <p>回收任务由 {@link MySQLStorage#open()} 调度的单线程调度器驱动，
     * 每 {@link MySQLStorage#REAPER_INTERVAL_SECONDS} 秒执行一次 {@link #run()}。</p>
     */
    protected class ConnectionPool implements Runnable {

        private final int timeout = 28000;

        private final int maxConn;

        private JSONObject config;

        private ConcurrentLinkedQueue<Connection> connections;

        private ConcurrentHashMap<Connection, Long> timestamps;

        private AtomicInteger count;

        private volatile boolean closed;

        protected ConnectionPool(int maxConn, JSONObject config) {
            this.maxConn = maxConn;
            this.config = config;
            this.connections = new ConcurrentLinkedQueue<>();
            this.timestamps = new ConcurrentHashMap<>();
            this.count = new AtomicInteger(0);
            Logger.i(this.getClass(), "ConnectionPool - max connections: " + maxConn);
        }

        protected Connection get() {
            synchronized (this) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (!this.closed && this.count.get() >= this.maxConn) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        Logger.w(this.getClass(), "#get - The connection pool is full, max connections: " + this.maxConn);
                        return null;
                    }
                    try {
                        this.wait(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (this.closed) return null;
                this.count.incrementAndGet();
            }

            try {
                Connection conn = null;
                synchronized (this) {
                    conn = this.connections.poll();
                }
                while (null != conn) {
                    try {
                        if (conn.isClosed()) {
                            // 已关闭的连接直接丢弃，同时清掉时间戳，避免映射泄漏
                            this.timestamps.remove(conn);
                            synchronized (this) {
                                conn = this.connections.poll();
                            }
                        } else {
                            Long timestamp = this.timestamps.get(conn);
                            if (null != timestamp) {
                                if (System.currentTimeMillis() - timestamp > this.timeout) {
                                    if (!this.testConnection(conn)) {
                                        this.timestamps.remove(conn);
                                        Logger.d(this.getClass(), "#get - The connection timeout, create new connection");
                                        try {
                                            conn.close();
                                        } catch (Exception e) {
                                            Logger.d(this.getClass(), "#get - Failed to close the expired connection: "
                                                    + e.getMessage());
                                        } finally {
                                            conn = null;
                                        }

                                        // 获取下一个
                                        synchronized (this) {
                                            conn = this.connections.poll();
                                        }
                                    } else {
                                        break;
                                    }
                                } else {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    } catch (SQLException e) {
                        Logger.w(this.getClass(), "#get", e);
                    }
                }

                if (null == conn) {
                    StringBuilder url = new StringBuilder();
                    url.append("jdbc:mysql://");
                    url.append(this.config.has(CONFIG_HOST) ? this.config.getString(CONFIG_HOST) : "127.0.0.1");
                    url.append(":");
                    url.append(this.config.has(CONFIG_PORT) ? this.config.getInt(CONFIG_PORT) : 3306);
                    url.append("/");
                    url.append(this.config.has(CONFIG_SCHEMA) ? this.config.getString(CONFIG_SCHEMA) : "cube");
                    url.append("?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8");
                    url.append("&connectTimeout=10000");
                    url.append("&socketTimeout=60000");
                    url.append("&useInformationSchema=true");
                    url.append("&nullCatalogMeansCurrent=true");
                    // Dedicated transaction pools retain their JDBC defaults between borrows.
                    // Local state avoids metadata queries and redundant SET/rollback round trips.
                    // A broken transactional session must fail rather than silently reconnect.
                    if (this.config.has(CONFIG_TRANSACTION_ISOLATION)) {
                        url.append("&useLocalSessionState=true&useLocalTransactionState=true");
                        url.append("&alwaysSendSetIsolation=false&autoReconnect=false");
                    } else {
                        url.append("&autoReconnect=true");
                    }
                    url.append("&failOverReadOnly=false");

                    try {
                        conn = DriverManager.getConnection(url.toString(),
                                this.config.getString(CONFIG_USER), this.config.getString(CONFIG_PASSWORD));
                        if (this.config.has(CONFIG_TRANSACTION_ISOLATION)) {
                            conn.setTransactionIsolation(this.config.getInt(CONFIG_TRANSACTION_ISOLATION));
                        }
                        if (this.config.has(CONFIG_AUTO_COMMIT)) {
                            conn.setAutoCommit(this.config.getBoolean(CONFIG_AUTO_COMMIT));
                        }
                    } catch (SQLException e) {
                        if (conn != null) {
                            try {
                                conn.close();
                            } catch (SQLException closeFailure) {
                                e.addSuppressed(closeFailure);
                            }
                            conn = null;
                        }
                        Logger.e(this.getClass(), "#get - " + url.toString(), e);
                    }
                }

                if (null == conn) {
                    this.count.decrementAndGet();
                    synchronized (this) {
                        this.notifyAll();
                    }
                    return null;
                }

                if (this.closed) {
                    this.discard(conn);
                    return null;
                }
                return conn;
            } catch (Exception e) {
                Logger.e(this.getClass(), "#get - Error", e);
                this.count.decrementAndGet();
                return null;
            }
        }

        protected synchronized void returnConn(Connection connection) {
            if (null == connection) {
                synchronized (this) {
                    this.notifyAll();
                }
                return;
            }

            if (this.closed) {
                this.discard(connection);
                return;
            }

            try {
                if (!connection.isClosed()) {
                    this.timestamps.put(connection, System.currentTimeMillis());
                    synchronized (this) {
                        this.connections.offer(connection);
                    }
                } else {
                    this.timestamps.remove(connection);
                }
            } catch (SQLException e) {
                Logger.e(this.getClass(), "#returnConn", e);
            }

            this.count.decrementAndGet();

            synchronized (this) {
                this.notifyAll();
            }
        }

        protected void discard(Connection connection) {
            this.timestamps.remove(connection);
            try {
                connection.close();
            } catch (SQLException e) {
                Logger.d(MySQLStorage.class, "#discard - Failed to close connection");
            } finally {
                this.count.decrementAndGet();
                synchronized (this) {
                    this.notifyAll();
                }
            }
        }

        protected synchronized void close() {
            this.closed = true;
            ArrayList<Connection> connList = new ArrayList<>(this.connections);
            this.connections.clear();
            this.timestamps.clear();

            this.notifyAll();

            for (Connection conn : connList) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    Logger.e(this.getClass(), "#close - Failed to close connection", e);
                }
            }
        }

        protected boolean testConnection(Connection conn) {
            try {
                // JDBC4 的有效性探测：由驱动发轻量 ping，避免每次借用都跑一条 SQL
                return conn.isValid(2);
            } catch (SQLException e) {
                Logger.d(this.getClass(), "#testConnection - " + e.getMessage());
                return false;
            } catch (RuntimeException e) {
                Logger.d(this.getClass(), "#testConnection - " + e.getMessage());
                return false;
            }
        }

        @Override
        public void run() {
            // 回收任务绝不能让异常逃逸：一旦抛出，调度器会取消后续所有执行
            try {
                Iterator<Connection> iter = this.connections.iterator();
                while (iter.hasNext()) {
                    Connection conn = iter.next();
                    boolean expired = false;
                    // 与 get() 的 poll 使用同一把锁，并确认取得空闲连接所有权。
                    synchronized (this) {
                        Long timestamp = this.timestamps.get(conn);
                        if (null != timestamp && System.currentTimeMillis() - timestamp > 60 * 1000
                                && this.connections.remove(conn)) {
                            this.timestamps.remove(conn);
                            expired = true;
                        }
                    }
                    if (expired) {
                        try {
                            conn.close();
                        } catch (Exception e) {
                            Logger.d(MySQLStorage.class, "#run - Failed to close 'conn': " + e.getMessage());
                        }
                    }
                }

                synchronized (this) {
                    this.notifyAll();
                }
            } catch (Throwable t) {
                Logger.w(this.getClass(), "#run - Recycle task failed", t);
            }
        }
    }
}
