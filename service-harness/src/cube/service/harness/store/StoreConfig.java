/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.storage.MySQLStorage;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Properties;

/**
 * 专用 Harness 存储库的连接配置与准入校验。
 *
 * <p>只接受 dedicated Harness 数据库：库名必须为 {@code harness_} 前缀，
 * 不存在旧业务库配置回退。dialect 仅支持
 * {@code sqlite} 与 {@code mysql}；SQLite 限定绝对根目录下的固定文件名，
 * MySQL 复用 common 的 MySQLStorage 连接池与连接参数。
 */
public final class StoreConfig implements AutoCloseable {
    /**
     * 存储方言，取自 store.dialect，仅接受 "sqlite" 或 "mysql"。
     */
    public final String dialect;

    /**
     * 目标库名，必须匹配 harness_[a-z0-9_]{1,48}；SQLite 下同时决定数据文件名。
     */
    public final String database;

    /**
     * 库身份标识，必须匹配 [A-Za-z0-9_-]{16,128}，与 harness_store_identity 行绑定校验。
     */
    public final String identity;

    /**
     * SQLite JDBC URL；MySQL 使用连接池，此字段为 null。
     */
    private final String url;

    /**
     * common 的 MySQL 连接池存储器；SQLite 下为 null，由当前配置独立持有。
     */
    private final MySQLStorage mysqlStorage;

    /**
     * 从私有属性构造存储配置并完成全部准入校验。
     *
     * <p>任一条款不满足即拒绝，绝不降级或猜测默认库。
     *
     * @param p 配置属性，读取 store.dialect、store.database、store.identity 及方言专属键
     * @throws StoreException STORE_TARGET_REFUSED（库名/身份格式非法、SQLite 根目录或
     *                        数据文件不合规、MySQL host/port 非法）、STORE_ACCOUNT_REFUSED
     *                        （MySQL 用户名或密码为空）、STORE_DIALECT_REFUSED
     *                        （dialect 既非 sqlite 也非 mysql）
     * @throws Exception      SQLite JDBC 驱动类加载失败
     */
    public StoreConfig(Properties p) throws Exception {
        dialect = p.getProperty("store.dialect", "");
        database = p.getProperty("store.database", "");
        identity = p.getProperty("store.identity", "");
        if (!database.matches("harness_[a-z0-9_]{1,48}") || !identity.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new StoreException("STORE_TARGET_REFUSED");
        }
        if (dialect.equals("sqlite")) {
            Class.forName("org.sqlite.JDBC");
            Path root = Paths.get(p.getProperty("store.sqlite.root", ""));
            Path file = Paths.get(p.getProperty("store.sqlite.file", ""));
            if (!root.isAbsolute() || !file.isAbsolute() || !Files.isDirectory(root)) {
                throw new StoreException("STORE_TARGET_REFUSED");
            }
            root = root.toRealPath();
            Path parent = file.getParent().toRealPath();
            if (!parent.startsWith(root) || !file.getFileName().toString().equals(database + ".db")
                    || Files.isSymbolicLink(file)) {
                throw new StoreException("STORE_TARGET_REFUSED");
            }
            url = "jdbc:sqlite:" + parent.resolve(file.getFileName());
            mysqlStorage = null;
        } else if (dialect.equals("mysql")) {
            String host = p.getProperty("store.mysql.host", "");
            int port = Integer.parseInt(p.getProperty("store.mysql.port", "3306"));
            if (!host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535) {
                throw new StoreException("STORE_TARGET_REFUSED");
            }
            String user = p.getProperty("store.mysql.user", "");
            String password = p.getProperty("store.mysql.password", "");
            if (user.isEmpty() || password.isEmpty()) {
                throw new StoreException("STORE_ACCOUNT_REFUSED");
            }
            url = null;
            mysqlStorage = new MySQLStorage("HarnessStore");
            mysqlStorage.configure(new JSONObject()
                    .put(MySQLStorage.CONFIG_HOST, host)
                    .put(MySQLStorage.CONFIG_PORT, port)
                    .put(MySQLStorage.CONFIG_SCHEMA, database)
                    .put(MySQLStorage.CONFIG_USER, user)
                    .put(MySQLStorage.CONFIG_PASSWORD, password)
                    .put(MySQLStorage.CONFIG_TRANSACTION_ISOLATION, Connection.TRANSACTION_READ_COMMITTED)
                    .put(MySQLStorage.CONFIG_AUTO_COMMIT, false));
            mysqlStorage.open();
        } else {
            throw new StoreException("STORE_DIALECT_REFUSED");
        }
    }

    /**
     * 是否为 MySQL 方言。
     *
     * @return dialect 等于 "mysql" 时返回 true，否则为 SQLite
     */
    public boolean mysql() {
        return dialect.equals("mysql");
    }

    /**
     * 建立数据库连接并执行方言特定的安全校验。
     *
     * <p>MySQL：从 common 连接池借用连接、设置 READ COMMITTED，并核对当前库即为目标库。
     * 数据库操作权限由 MySQL 在执行 SQL 时校验。SQLite：开启 busy_timeout、外键与 synchronous=FULL。
     *
     * @return 已完成校验的连接；调用方负责关闭，MySQL 下关闭表示归还连接池
     * @throws SQLException   连接失败或校验查询执行失败
     * @throws StoreException STORE_TARGET_REFUSED（当前库不是目标专用库）
     */
    public Connection connect() throws SQLException {
        return connect(false);
    }

    /**
     * Borrow a manual-commit connection for a ledger transaction, retaining the pool defaults.
     */
    public Connection connectTransaction() throws SQLException {
        return connect(true);
    }

    private Connection connect(boolean transaction) throws SQLException {
        Connection c = mysql() ? mysqlStorage.borrowConnection() : DriverManager.getConnection(url);
        try {
            if (mysql()) {
                // Maintenance/bootstrap callers keep the existing autocommit contract.
                // Ledger transactions avoid switching it on and off for each operation.
                if (!transaction) c.setAutoCommit(true);
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT DATABASE()")) {
                    if (!r.next() || !database.equals(r.getString(1))) {
                        throw new StoreException("STORE_TARGET_REFUSED");
                    }
                }
            } else {
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA busy_timeout=5000");
                    s.execute("PRAGMA foreign_keys=ON");
                    s.execute("PRAGMA synchronous=FULL");
                }
            }
            return c;
        } catch (RuntimeException | SQLException e) {
            try {
                c.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * 关闭当前配置持有的 MySQL 连接池；SQLite 连接由各调用方独立关闭。
     */
    @Override
    public void close() {
        if (mysqlStorage != null) mysqlStorage.close();
    }

    /**
     * 开启一个存储事务。
     *
     * @param c     连接
     * @param mysql true 走 MySQL 的 setAutoCommit(false)，false 走 SQLite 的 BEGIN IMMEDIATE 写锁
     * @throws SQLException 语句执行失败
     */
    static void begin(Connection c, boolean mysql) throws SQLException {
        if (mysql) {
            c.setAutoCommit(false);
        } else {
            try (Statement s = c.createStatement()) {
                s.execute("BEGIN IMMEDIATE");
            }
        }
    }

    /**
     * 结束一个存储事务。
     *
     * @param c      连接
     * @param mysql  true 走 MySQL 的 commit/rollback，false 走 SQLite 的 COMMIT/ROLLBACK
     * @param commit true 提交，false 回滚
     * @throws SQLException 语句执行失败
     */
    static void end(Connection c, boolean mysql, boolean commit) throws SQLException {
        if (mysql) {
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
        } else {
            try (Statement s = c.createStatement()) {
                s.execute(commit ? "COMMIT" : "ROLLBACK");
            }
        }
    }
}
