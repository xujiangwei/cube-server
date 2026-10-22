/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.storage;

import cell.core.talk.LiteralBase;
import cell.util.log.Logger;
import cube.core.AbstractStorage;
import cube.core.Conditional;
import cube.core.Constraint;
import cube.core.StorageField;
import cube.util.SQLUtils;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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

    public final static String CONFIG_HOST = "host";
    public final static String CONFIG_PORT = "port";
    public final static String CONFIG_SCHEMA = "schema";
    public final static String CONFIG_USER = "user";
    public final static String CONFIG_PASSWORD = "password";

    private volatile ConnectionPool pool;

    public MySQLStorage(String name) {
        super(name, StorageType.MySQL);
    }

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
    }

    @Override
    public synchronized void close() {
        if (null == this.pool) {
            return;
        }

        this.pool.close();
        this.pool = null;
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
                                            if (!connection.getAutoCommit()) {
                                                connection.setAutoCommit(true);
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

    @Override
    public List<StorageField[]> executeQuery(String table, StorageField[] fields) {
        return this.executeQuery(table, fields, null);
    }

    @Override
    public List<StorageField[]> executeQuery(String table, StorageField[] fields, Conditional[] conditionals) {
        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeQuery - The connection pool is not ready");
            return null;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeQuery - Failed to obtain connection");
            return null;
        }

        ArrayList<StorageField[]> result = new ArrayList<>();

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
            Logger.w(this.getClass(), "#executeQuery - SQL: " + sql, e);
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

    @Override
    public List<StorageField[]> executeQuery(String[] tables, StorageField[] fields, Conditional[] conditionals) {
        ArrayList<StorageField[]> result = new ArrayList<>();

        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeQuery - The connection pool is not ready");
            return result;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeQuery - Failed to obtain connection");
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
            Logger.w(this.getClass(), "#executeQuery - SQL: " + sql, e);
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

    @Override
    public List<StorageField[]> executeQuery(String sql) {
        ArrayList<StorageField[]> result = new ArrayList<>();

        if (null == this.pool) {
            Logger.e(this.getClass(), "#executeQuery - The connection pool is not ready");
            return result;
        }

        Connection connection = this.pool.get();
        if (null == connection) {
            Logger.e(this.getClass(), "#executeQuery - Failed to obtain connection");
            return result;
        }

        Statement statement = null;

        try {
            statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(sql);
            while (rs.next()) {
                StorageField[] row = StorageFields.scanResultSet(rs);
                result.add(row);
            }
        } catch (SQLException e) {
            Logger.w(this.getClass(), "#executeQuery - SQL: " + sql, e);
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
     */
    protected class ConnectionPool extends TimerTask {

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
                            // 获取下一个
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
                    url.append("&autoReconnect=true");
                    url.append("&failOverReadOnly=false");

                    try {
                        conn = DriverManager.getConnection(url.toString(),
                                this.config.getString(CONFIG_USER), this.config.getString(CONFIG_PASSWORD));
                    } catch (SQLException e) {
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

            synchronized (this) {
                this.notifyAll();
            }

            synchronized (this) {
                this.notifyAll();
            }

            for (Connection conn : connList) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    Logger.e(this.getClass(), "#close - Failed to close connection", e);
                }
            }
        }

        protected boolean testConnection(Connection conn) {
            Statement statement = null;
            try {
                statement = conn.createStatement();
                ResultSet rs = statement.executeQuery("select version()");
                if (rs.next()) {
                    return true;
                } else {
                    try {
                        statement.close();
                        statement = null;
                        conn.close();
                    } catch (SQLException ex) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(MySQLStorage.class, "#testConnection - Failed to close 'conn': " + ex.getMessage());
                    }
                    return false;
                }
            } catch (Exception e) {
                Logger.d(this.getClass(), e.getMessage());
                try {
                    statement.close();
                    statement = null;
                    conn.close();
                } catch (SQLException ex) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(MySQLStorage.class, "#testConnection - Failed to close 'conn': " + ex.getMessage());
                }
                return false;
            } finally {
                if (null != statement) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // 释放失败不影响主流程，仅记录
                        Logger.d(MySQLStorage.class, "#testConnection - Failed to close 'statement': " + e.getMessage());
                    }
                }
            }
        }

        @Override
        public void run() {
            Iterator<Connection> iter = this.connections.iterator();
            while (iter.hasNext()) {
                Connection conn = iter.next();
                Long timestamp = this.timestamps.get(conn);
                if (null != timestamp) {
                    if (System.currentTimeMillis() - timestamp > 60 * 1000) {
                        this.timestamps.remove(conn);
                        iter.remove();
                        try {
                            conn.close();
                        } catch (Exception e) {
                            // 释放失败不影响主流程，仅记录
                            Logger.d(MySQLStorage.class, "#run - Failed to close 'conn': " + e.getMessage());
                        }
                    }
                }
            }

            synchronized (this) {
                this.notifyAll();
            }
        }
    }
}
