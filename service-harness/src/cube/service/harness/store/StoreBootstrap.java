/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.aigc.usage.UsageSchema;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前存储定义与仅限启动时的构造；读取方只做校验。
 */
public final class StoreBootstrap {
    // 精确的旧版 mysql.sql 摘要：仅表引擎/字符集/排序规则默认值有变化。
    private static final String MYSQL_SCHEMA_BEFORE_EXPLICIT_COLLATIONS =
            "e5693c212ea5adaf520264157f04485669498167c201827a616fa6e646d83317";

    /**
     * 工具类，禁止实例化。
     */
    private StoreBootstrap() {
    }

    /**
     * 读取并规范化当前 store 定义脚本。
     *
     * @param config 存储配置。
     * @return 规范化后的 SQL 脚本文本。
     * @throws Exception 脚本资源缺失或读取失败。
     */
    private static String source(StoreConfig config) throws Exception {
        try (InputStream in = StoreBootstrap.class.getResourceAsStream("/store/" + config.dialect + ".sql")) {
            if (in == null) {
                throw new StoreException("STORE_SCHEMA_MISSING");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n") + "\n"
                    + String.join(";\n", UsageSchema.statements("harness_ai_usage_", config.mysql())) + ";\n";
        }
    }

    /**
     * 当前定义的稳定摘要，与平台换行符无关。
     *
     * @param config 存储配置。
     * @return SHA-256 十六进制摘要。
     * @throws Exception 读取定义失败。
     */
    public static String digest(StoreConfig config) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        StringBuilder out = new StringBuilder();
        for (byte b : hash.digest(source(config).getBytes(StandardCharsets.UTF_8))) {
            out.append(String.format("%02x", b & 255));
        }
        return out.toString();
    }

    /**
     * 把脚本文本拆为非空 SQL 语句列表。
     *
     * @param config 存储配置。
     * @return SQL 语句列表。
     * @throws Exception 读取定义失败。
     */
    private static List<String> statements(StoreConfig config) throws Exception {
        List<String> out = new ArrayList<>();
        for (String sql : source(config).split(";")) {
            if (!sql.trim().isEmpty()) {
                out.add(sql.trim());
            }
        }
        return out;
    }

    /**
     * 按语句列表构建期望 schema。
     *
     * @param statements SQL 语句列表。
     * @return 期望 schema。
     */
    private static StoreSchema schema(List<String> statements) {
        StoreSchema schema = new StoreSchema();
        for (String sql : statements) {
            schema.add(sql);
        }
        return schema;
    }

    /**
     * 查询库中现有表名集合。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @return 表名集合。
     * @throws SQLException 查询失败。
     */
    static Set<String> tables(Connection c, StoreConfig config) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(config.mysql()
                ? "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) {
                names.add(r.getString(1));
            }
        }
        return names;
    }

    /**
     * 只读校验，供 doctor 与 JdbcRunStore 使用。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @throws Exception 校验失败。
     */
    public static void verify(Connection c, StoreConfig config) throws Exception {
        schema(statements(config)).verify(c, config, false);
        verifyIdentity(c, config);
    }

    /**
     * 严格校验库身份标记。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @throws Exception 校验失败。
     */
    private static void verifyIdentity(Connection c, StoreConfig config) throws Exception {
        verifyIdentity(c, config, false);
    }

    /**
     * 校验库身份标记，可选允许已知的旧版 MySQL 定义。
     *
     * @param c                        数据库连接。
     * @param config                   存储配置。
     * @param allowPreviousMysqlSchema 是否允许旧版 MySQL 定义。
     * @return 是否命中了旧版定义。
     * @throws Exception 校验失败。
     */
    private static boolean verifyIdentity(Connection c, StoreConfig config, boolean allowPreviousMysqlSchema) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT singleton,store_id,schema_hash FROM harness_store_identity")) {
            if (!r.next() || r.getInt(1) != 1 || !config.identity.equals(r.getString(2))) {
                throw new StoreException("STORE_IDENTITY_MISMATCH");
            }
            String hash = r.getString(3);
            boolean previous = allowPreviousMysqlSchema && (config.mysql()
                    ? "4756a216e50c7b534ae81d97a7f1706e14fc0da80b195224b51c2ad63041e6f7".equals(hash) || MYSQL_SCHEMA_BEFORE_EXPLICIT_COLLATIONS.equals(hash) || "dcda116b812ddb9ecc491e81e5614e72ce8b2e2ca134a39ef2ba3bf3424ce61b".equals(hash)
                    || "23708b9e87686f3c0cc9d42afbff6add4a0b508a7ac73aecfb343f201a49e3d8".equals(hash)
                    : "f85520430e33210547b3a4b99e2d73f2ee1730e9978a71c91e3fa98d65055c21".equals(hash) || "e722e01d82aeaff998fc45783308f8c2cb6ef923b48f04b32f402ce3cd9ee021".equals(hash)
                    || "5369b969ed2bd18fb85d223df7ef2aa29e0f8d1299648795efc875a828afb838".equals(hash));
            if ((!digest(config).equals(hash) && !previous) || r.next()) {
                throw new StoreException("STORE_IDENTITY_MISMATCH");
            }
            return previous;
        }
    }

    /**
     * 服务启动时在接受请求前构造并校验当前 schema。
     *
     * @param config 存储配置。
     * @throws Exception 构造或校验失败。
     */
    public static void selfCheck(StoreConfig config) throws Exception {
        List<String> statements = statements(config);
        StoreSchema expected = schema(statements);
        try (Connection c = config.connect()) {
            if (config.mysql()) {
                c.setAutoCommit(false);
                // 其它 JVM 构造 DDL 时完整严格校验可能超过十秒。
                try (PreparedStatement s = c.prepareStatement("SELECT GET_LOCK(?,60)")) {
                    // 库名最多 56 字符；MySQL 锁名允许 64。
                    s.setString(1, "harness:" + config.database);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || r.getInt(1) != 1) {
                            throw new StoreException("STORE_BOOTSTRAP_BUSY");
                        }
                    }
                }
            } else {
                StoreConfig.begin(c, false);
            }
            try {
                Set<String> existing = tables(c, config);
                expected.verify(c, config, true);
                boolean empty = existing.isEmpty();
                boolean markerOnly = existing.equals(Collections.singleton("harness_store_identity"));
                if (empty) {
                    execute(c, statements.get(0));
                } else if (!existing.contains("harness_store_identity")) {
                    throw new StoreException("STORE_SCHEMA_MISMATCH");
                }
                boolean hasIdentity;
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM harness_store_identity")) {
                    r.next();
                    hasIdentity = r.getInt(1) != 0;
                }
                if (!hasIdentity) {
                    // MySQL 可能在创建标记表后、写入身份前中断。
                    if (!empty && !markerOnly) {
                        throw new StoreException("STORE_IDENTITY_MISMATCH");
                    }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO harness_store_identity VALUES (1,?,?)")) {
                        s.setString(1, config.identity);
                        s.setString(2, digest(config));
                        s.executeUpdate();
                    }
                }
                boolean updateSchemaHash = verifyIdentity(c, config, true);
                for (String sql : expected.missingColumns(c, config)) {
                    execute(c, sql);
                }
                for (String sql : statements) {
                    if (!expected.applied(c, config, sql)) {
                        execute(c, sql);
                    }
                }
                expected.verify(c, config, false);
                if (updateSchemaHash) {
                    // 只有所有 DDL 与结构校验成功后才推进标记。
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE harness_store_identity SET schema_hash=? WHERE singleton=1 AND store_id=?")) {
                        s.setString(1, digest(config));
                        s.setString(2, config.identity);

                        if (s.executeUpdate() != 1) {
                            throw new StoreException("STORE_IDENTITY_MISMATCH");
                        }
                    }
                }
                verifyIdentity(c, config);
                if (config.mysql()) {
                    c.commit();
                } else {
                    StoreConfig.end(c, false, true);
                }
            } catch (Exception e) {
                if (config.mysql()) {
                    c.rollback();
                } else {
                    StoreConfig.end(c, false, false);
                }
                throw e;
            } finally {
                if (config.mysql()) {
                    try (PreparedStatement s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        s.setString(1, "harness:" + config.database);
                        s.execute();
                    }
                }
            }
        }
    }

    /**
     * 在连接上执行一条 DDL/DML 语句。
     *
     * @param c   数据库连接。
     * @param sql 待执行 SQL。
     * @throws SQLException 执行失败。
     */
    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
