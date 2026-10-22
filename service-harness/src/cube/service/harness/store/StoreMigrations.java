/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.ConfigPaths;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 任务账本结构迁移器：显式、只增不改（additive）的迁移执行与启动校验。
 *
 * <p>运行时启动绝不初始化或自动迁移数据库，只做校验：表集合与
 * harness_store_identity 行（store_id、schema_version、migration_hash）
 * 必须与代码期望逐字节一致。迁移序号仅用于结构兼容校验，每步对应类路径下
 * {@code /store/migrations/{dialect}/00N.sql} 脚本，digest 为 1..N 全部
 * 脚本串联后的 SHA-256。已有库只能通过 upgrade 显式前进；失败一律
 * fail closed，不做破坏性重试或降级（down）。
 */
public final class StoreMigrations {
    /**
     * 当前数据库迁移序号；verify 的期望表集合与 identity 行均按此序号计算。
     */
    public static final int VERSION = 7;

    /**
     * 任务执行基线表集合（12 张 harness_ 表），verify 时与实测表集合整体比对，缺一即不一致。
     */
    static final Set<String> TABLES =
            new HashSet<>(
                    Arrays.asList(
                            "harness_store_identity",
                            "harness_run",
                            "harness_lease",
                            "harness_request",
                            "harness_event",
                            "harness_outbox",
                            "harness_inbox",
                            "harness_round",
                            "harness_tool_call",
                            "harness_result",
                            "harness_usage",
                            "harness_usage_adjustment"));

    /**
     * 读取指定方言、指定版本的迁移脚本字节。
     *
     * @param config  存储配置，决定 dialect 与资源路径
     * @param version 迁移版本号（1..VERSION），映射到 00N.sql
     * @return 脚本原始字节（UTF-8 SQL 文本）
     * @throws StoreException STORE_MIGRATION_MISSING（类路径下对应脚本不存在）
     * @throws Exception      资源读取失败
     */
    private static byte[] source(StoreConfig config, int version) throws Exception {
        try (InputStream in =
                     StoreMigrations.class.getResourceAsStream(
                             "/store/migrations/" + config.dialect + "/00" + version + ".sql")) {
            if (in == null) {
                throw new StoreException("STORE_MIGRATION_MISSING");
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 计算当前 VERSION 的迁移摘要。
     *
     * @param config 存储配置
     * @return 1..VERSION 全部脚本串联后的 SHA-256 十六进制摘要
     * @throws Exception 脚本读取失败
     */
    public static String digest(StoreConfig config) throws Exception {
        return digest(config, VERSION);
    }

    /**
     * 计算到指定版本为止的迁移摘要。
     *
     * @param config  存储配置
     * @param version 目标版本号（1..VERSION）
     * @return 1..version 全部脚本串联后的 SHA-256 十六进制摘要，与 identity 行 migration_hash 比对
     * @throws Exception 脚本读取失败
     */
    private static String digest(StoreConfig config, int version) throws Exception {
        StringBuilder out = new StringBuilder();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (int i = 1; i <= version; i++) {
            hash.update(source(config, i));
        }
        for (byte b : hash.digest()) {
            out.append(String.format("%02x", b & 255));
        }
        return out.toString();
    }

    /**
     * 查询连接当前可见的全部表名。
     *
     * @param c      连接
     * @param config 存储配置，决定方言特定的系统目录查询
     * @return 表名集合；MySQL 取 information_schema.tables 当前库，SQLite 取 sqlite_master 且排除 sqlite_ 内部表
     * @throws SQLException 查询执行失败
     */
    static Set<String> tables(Connection c, StoreConfig config) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet r =
                     s.executeQuery(
                             config.mysql()
                                     ? "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                                     : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) {
                names.add(r.getString(1));
            }
        }
        return names;
    }

    /**
     * 启动校验入口：按当前 VERSION 校验库 schema 与身份。
     *
     * @param c      连接
     * @param config 存储配置
     * @throws StoreException STORE_SCHEMA_MISMATCH（表集合与期望不一致）、
     *                        STORE_IDENTITY_MISMATCH（identity 行的 store_id/schema_version/migration_hash 不匹配）
     * @throws Exception      查询或摘要计算失败
     */
    public static void verify(Connection c, StoreConfig config) throws Exception {
        verify(c, config, VERSION);
    }

    /**
     * 按目标版本校验库 schema 与身份。
     *
     * <p>期望表集合由任务执行基线，以及资源、模型作业、推理、工具、
     * 交互与文件、反馈等已执行迁移的增量表组成；随后校验 identity 行的 store_id、
     * schema_version 与 migration_hash。
     *
     * @param c       连接
     * @param config  存储配置
     * @param version 目标版本号；升级过程中用于逐版本验证，启动时为 VERSION
     * @throws StoreException STORE_SCHEMA_MISMATCH（表集合与期望不一致）、
     *                        STORE_IDENTITY_MISMATCH（identity 行缺失或字段不匹配）
     * @throws Exception      查询或摘要计算失败
     */
    private static void verify(Connection c, StoreConfig config, int version) throws Exception {
        Set<String> expected = new HashSet<>(TABLES);
        if (version >= 2) {
            expected.addAll(
                    Arrays.asList(
                            "harness_release",
                            "harness_binding",
                            "harness_profile",
                            "harness_run_config",
                            "harness_release_ref",
                            "harness_run_skill",
                            "harness_workspace",
                            "harness_context"));
        }
        if (version >= 3) {
            expected.addAll(Arrays.asList("harness_model_backend", "harness_model_job"));
        }
        if (version >= 4) {
            expected.addAll(Arrays.asList("harness_inference", "harness_inference_event"));
        }
        if (version >= 5) {
            expected.addAll(
                    Arrays.asList(
                            "harness_runner_identity",
                            "harness_tool_job",
                            "harness_tool_revocation",
                            "harness_broker_exchange"));
        }
        if (version >= 6) {
            expected.addAll(
                    Arrays.asList(
                            "harness_wait",
                            "harness_control_receipt",
                            "harness_file",
                            "harness_file_request",
                            "harness_file_pin",
                            "harness_file_binding",
                            "harness_file_policy",
                            "harness_file_root"));
        }
        if (version >= 7) {
            expected.add("harness_feedback");
        }
        if (!tables(c, config).equals(expected)) {
            throw new StoreException("STORE_SCHEMA_MISMATCH");
        }
        try (Statement s = c.createStatement();
             ResultSet r =
                     s.executeQuery(
                             "SELECT store_id, schema_version, migration_hash FROM harness_store_identity WHERE singleton=1")) {
            if (!r.next()
                    || !config.identity.equals(r.getString(1))
                    || r.getInt(2) != version
                    || !digest(config, version).equals(r.getString(3))) {
                throw new StoreException("STORE_IDENTITY_MISMATCH");
            }
        }
    }

    /**
     * 显式迁移入口：升级已有库，或在库为空时显式初始化。
     *
     * <p>MySQL 以 GET_LOCK 全局串行化迁移；已有库按 old→VERSION 逐版本执行
     * 脚本并以 CAS 方式推进 identity 行；空库仅在 initializeEmpty 为 true 时
     * 按完整迁移链连续建库，否则拒绝。MySQL DDL 可能已提交，部分失败一律
     * fail closed，不做破坏性重试或降级。
     *
     * @param config          存储配置
     * @param initializeEmpty 是否允许把空库初始化为当前 VERSION
     * @throws StoreException STORE_MIGRATION_BUSY（拿不到迁移锁或 identity 行
     *                        CAS 失败）、STORE_SCHEMA_MISMATCH（有表但缺 identity 行）、
     *                        STORE_IDENTITY_MISMATCH（identity 行版本越界）、
     *                        STORE_INITIALIZE_REQUIRED（空库且未显式允许初始化）
     * @throws Exception      连接、脚本执行或校验失败
     */
    public static void migrate(StoreConfig config, boolean initializeEmpty) throws Exception {
        try (Connection c = config.connect()) {
            if (config.mysql()) {
                try (PreparedStatement s = c.prepareStatement("SELECT GET_LOCK(?,10)")) {
                    s.setString(1, "harness:migrate:" + config.database);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || r.getInt(1) != 1) {
                            throw new StoreException("STORE_MIGRATION_BUSY");
                        }
                    }
                }
            } else {
                StoreConfig.begin(c, false);
            }
            try {
                Set<String> existing = tables(c, config);
                if (!existing.isEmpty()) {
                    int old;
                    if (!existing.contains("harness_store_identity")) {
                        throw new StoreException("STORE_SCHEMA_MISMATCH");
                    }
                    try (Statement s = c.createStatement();
                         ResultSet r =
                                 s.executeQuery(
                                         "SELECT schema_version FROM harness_store_identity WHERE singleton=1")) {
                        if (!r.next()) {
                            throw new StoreException("STORE_IDENTITY_MISMATCH");
                        }
                        old = r.getInt(1);
                    }
                    if (old < 1 || old > VERSION) {
                        throw new StoreException("STORE_IDENTITY_MISMATCH");
                    }
                    verify(c, config, old);
                    for (int version = old + 1; version <= VERSION; version++) {
                        execute(c, config, version);
                        try (PreparedStatement s =
                                     c.prepareStatement(
                                             "UPDATE harness_store_identity SET schema_version=?,migration_hash=? WHERE singleton=1 AND schema_version=?")) {
                            s.setInt(1, version);
                            s.setString(2, digest(config, version));
                            s.setInt(3, version - 1);
                            if (s.executeUpdate() != 1) {
                                throw new StoreException("STORE_MIGRATION_BUSY");
                            }
                        }
                    }
                    verify(c, config);
                } else {
                    if (!initializeEmpty) {
                        throw new StoreException("STORE_INITIALIZE_REQUIRED");
                    }
                    for (int version = 1; version <= VERSION; version++) {
                        execute(c, config, version);
                    }
                    try (PreparedStatement s =
                                 c.prepareStatement("INSERT INTO harness_store_identity VALUES (1,?,?,?)")) {
                        s.setString(1, config.identity);
                        s.setInt(2, VERSION);
                        s.setString(3, digest(config));
                        s.executeUpdate();
                    }
                    verify(c, config);
                }
                if (!config.mysql()) {
                    StoreConfig.end(c, false, true);
                }
            } catch (Exception e) {
                if (!config.mysql()) {
                    StoreConfig.end(c, false, false);
                }
                // MySQL DDL 可能已提交；部分 schema 一律 fail closed，不做破坏性重试或降级。
                throw e;
            } finally {
                if (config.mysql()) {
                    try (Statement s = c.createStatement()) {
                        s.execute("SELECT RELEASE_LOCK('harness:migrate:" + config.database + "')");
                    }
                }
            }
        }
    }

    /**
     * 执行单个版本的迁移脚本：按分号切分后逐条执行。
     *
     * <p>两种方言都不允许一次执行多语句，空片段（trim 后）跳过。
     *
     * @param c       连接
     * @param config  存储配置
     * @param version 要执行的迁移版本号
     * @throws Exception 脚本读取或语句执行失败
     */
    private static void execute(Connection c, StoreConfig config, int version) throws Exception {
        for (String part : new String(source(config, version), StandardCharsets.UTF_8).split(";")) {
            if (!part.trim().isEmpty()) {
                try (Statement s = c.createStatement()) {
                    s.execute(part.trim());
                }
            }
        }
    }

    /**
     * 命令行入口：verify | initialize-empty | upgrade。
     *
     * @param args 恰好两个参数：命令字与私有 properties 文件路径
     * @throws IllegalArgumentException 参数个数不为 2 或命令字不在
     *                                  verify/initialize-empty/upgrade 之内
     * @throws Exception                配置加载、连接、迁移或校验失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2
                || !(args[0].equals("verify")
                || args[0].equals("initialize-empty")
                || args[0].equals("upgrade"))) {
            throw new IllegalArgumentException(
                    "StoreMigrations verify|initialize-empty|upgrade <private.properties>");
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[1]))) {
            p.load(in);
        }
        ConfigPaths.normalize(p);
        StoreConfig config = new StoreConfig(p);
        if (args[0].equals("initialize-empty")) {
            migrate(config, true);
        } else if (args[0].equals("upgrade")) {
            migrate(config, false);
        } else {
            try (Connection c = config.connect()) {
                verify(c, config);
            }
        }
        System.out.println(
                "PASS: Harness store " + args[0] + " " + config.dialect + " schema=" + VERSION);
    }
}
