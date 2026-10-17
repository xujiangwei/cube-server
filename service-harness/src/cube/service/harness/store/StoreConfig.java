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
 * Only dedicated Harness databases are accepted. No legacy Storage/config fallback.
 */
public final class StoreConfig {
    public final String dialect, database, identity;
    private final String url, user, password;

    public StoreConfig(Properties p) throws Exception {
        dialect = p.getProperty("store.dialect", "");
        database = p.getProperty("store.database", "");
        identity = p.getProperty("store.identity", "");
        if (!database.matches("harness_[a-z0-9_]{1,48}") || !identity.matches("[A-Za-z0-9_-]{16,128}"))
            throw new StoreException("STORE_TARGET_REFUSED");
        if (dialect.equals("sqlite")) {
            Class.forName("org.sqlite.JDBC");
            Path root = Paths.get(p.getProperty("store.sqlite.root", ""));
            Path file = Paths.get(p.getProperty("store.sqlite.file", ""));
            if (!root.isAbsolute() || !file.isAbsolute() || !Files.isDirectory(root))
                throw new StoreException("STORE_TARGET_REFUSED");
            root = root.toRealPath();
            Path parent = file.getParent().toRealPath();
            if (!parent.startsWith(root) || !file.getFileName().toString().equals(database + ".db")
                    || Files.isSymbolicLink(file)) throw new StoreException("STORE_TARGET_REFUSED");
            url = "jdbc:sqlite:" + parent.resolve(file.getFileName());
            user = password = "";
        } else if (dialect.equals("mysql")) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String host = p.getProperty("store.mysql.host", "");
            int port = Integer.parseInt(p.getProperty("store.mysql.port", "3306"));
            if (!host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535)
                throw new StoreException("STORE_TARGET_REFUSED");
            user = p.getProperty("store.mysql.user", "");
            password = p.getProperty("store.mysql.password", "");
            if (!user.matches("harness_[a-z0-9_]{1,48}") || password.isEmpty())
                throw new StoreException("STORE_ACCOUNT_REFUSED");
            // No arbitrary JDBC URL/options, multi-statements, local files or legacy credentials.
            boolean localDevelopment = "true".equals(p.getProperty("store.mysql.loopbackDevelopment"))
                    && (host.equals("127.0.0.1") || host.equals("localhost"));
            url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
                    + "&connectTimeout=5000&socketTimeout=10000&allowMultiQueries=false"
                    + "&allowLoadLocalInfile=false&allowUrlInLocalInfile=false&sslMode="
                    + (localDevelopment ? "DISABLED&allowPublicKeyRetrieval=true" : "VERIFY_IDENTITY");
        } else throw new StoreException("STORE_DIALECT_REFUSED");
    }

    public boolean mysql() {
        return dialect.equals("mysql");
    }

    public Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            if (mysql()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT DATABASE()")) {
                    if (!r.next() || !database.equals(r.getString(1))) throw new StoreException("STORE_TARGET_REFUSED");
                }
                // Reject global, other-database and role grants, including inherited broad accounts.
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW GRANTS")) {
                    boolean own = false;
                    while (r.next()) {
                        String grant = r.getString(1).replace("\\_", "_");
                        if (grant.startsWith("GRANT USAGE ON *.* TO ")) continue;
                        if (!grant.startsWith("GRANT ") || !grant.contains(" ON `" + database + "`.* TO ")
                                || grant.contains("WITH GRANT OPTION"))
                            throw new StoreException("STORE_ACCOUNT_REFUSED");
                        own = true;
                    }
                    if (!own) throw new StoreException("STORE_ACCOUNT_REFUSED");
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

    static void begin(Connection c, boolean mysql) throws SQLException {
        if (mysql) c.setAutoCommit(false);
        else try (Statement s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
        }
    }

    static void end(Connection c, boolean mysql, boolean commit) throws SQLException {
        if (mysql) {
            if (commit) c.commit();
            else c.rollback();
        } else try (Statement s = c.createStatement()) {
            s.execute(commit ? "COMMIT" : "ROLLBACK");
        }
    }
}
