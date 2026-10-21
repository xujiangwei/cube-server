/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Properties;

/**
 * 专用 Harness 存储库的连接配置与准入校验。
 *
 * <p>只接受 dedicated Harness 数据库：库名必须为 {@code harness_} 前缀，MySQL
 * 还要求专用运行账户；不存在旧 Storage/config 回退。dialect 仅支持
 * {@code sqlite} 与 {@code mysql}；SQLite 限定绝对根目录下的固定文件名，
 * MySQL 由 host/port/database 拼装 JDBC URL，不接受任意 JDBC 选项。
 */
public final class StoreConfig {
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
     * 拼装好的 JDBC URL，方言相关，构造时定稿，运行期不可变。
     */
    private final String url;

    /**
     * JDBC 用户名；SQLite 下为空串，MySQL 下必须为 harness_ 前缀专用账户。
     */
    private final String user;

    /**
     * JDBC 密码；SQLite 下为空串，MySQL 下不允许为空。
     */
    private final String password;

    /**
     * 从私有属性构造存储配置并完成全部准入校验。
     *
     * <p>任一条款不满足即拒绝，绝不降级或猜测默认库。
     *
     * @param p 配置属性，读取 store.dialect、store.database、store.identity 及方言专属键
     * @throws StoreException STORE_TARGET_REFUSED（库名/身份格式非法、SQLite 根目录或
     *                        数据文件不合规、MySQL host/port 非法）、STORE_ACCOUNT_REFUSED
     *                        （MySQL 账户非 harness_ 前缀或密码为空）、STORE_DIALECT_REFUSED
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
            user = password = "";
        } else if (dialect.equals("mysql")) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String host = p.getProperty("store.mysql.host", "");
            int port = Integer.parseInt(p.getProperty("store.mysql.port", "3306"));
            if (!host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535) {
                throw new StoreException("STORE_TARGET_REFUSED");
            }
            user = p.getProperty("store.mysql.user", "");
            password = p.getProperty("store.mysql.password", "");
            if (!user.matches("harness_[a-z0-9_]{1,48}") || password.isEmpty()) {
                throw new StoreException("STORE_ACCOUNT_REFUSED");
            }
            // 不接受任意 JDBC URL/选项、多语句、本地文件或旧凭据。
            boolean localDevelopment = "true".equals(p.getProperty("store.mysql.loopbackDevelopment"))
                    && (host.equals("127.0.0.1") || host.equals("localhost"));
            url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                    + "&connectTimeout=5000&socketTimeout=10000&allowMultiQueries=false"
                    + "&allowLoadLocalInfile=false&allowUrlInLocalInfile=false&sslMode="
                    + (localDevelopment ? "DISABLED&allowPublicKeyRetrieval=true" : "VERIFY_IDENTITY");
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
     * <p>MySQL：设置 READ COMMITTED，核对当前库即为目标专用库，并逐条解析
     * SHOW GRANTS，拒绝全局/跨库/角色授权与 WITH GRANT OPTION，只保留
     * 本库 DML 级授权。SQLite：开启 busy_timeout、外键与 synchronous=FULL。
     *
     * @return 已完成校验的连接；调用方负责关闭
     * @throws SQLException   连接失败或校验查询执行失败
     * @throws StoreException STORE_TARGET_REFUSED（当前库不是目标专用库）、
     *                        STORE_ACCOUNT_REFUSED（授权过宽或不存在本库授权）
     */
    public Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            if (mysql()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT DATABASE()")) {
                    if (!r.next() || !database.equals(r.getString(1))) {
                        throw new StoreException("STORE_TARGET_REFUSED");
                    }
                }
                // 拒绝全局、跨库与角色授权，包括继承而来的宽泛账户。
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW GRANTS")) {
                    boolean own = false;
                    while (r.next()) {
                        String grant = r.getString(1).replace("\\_", "_");
                        if (grant.startsWith("GRANT USAGE ON *.* TO ")) {
                            continue;
                        }
                        if (!grant.startsWith("GRANT ") || !grant.contains(" ON `" + database + "`.* TO ")
                                || grant.contains("WITH GRANT OPTION")) {
                            throw new StoreException("STORE_ACCOUNT_REFUSED");
                        }
                        own = true;
                    }
                    if (!own) {
                        throw new StoreException("STORE_ACCOUNT_REFUSED");
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
            c.close();
            throw e;
        }
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
