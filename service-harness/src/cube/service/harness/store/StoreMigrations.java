/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

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
 * Explicit, additive migration only; runtime startup never initializes a database.
 */
public final class StoreMigrations {
    public static final int VERSION = 7;
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

    private static byte[] source(StoreConfig config, int version) throws Exception {
        try (InputStream in =
                     StoreMigrations.class.getResourceAsStream(
                             "/store/migrations/" + config.dialect + "/00" + version + ".sql")) {
            if (in == null) throw new StoreException("STORE_MIGRATION_MISSING");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    public static String digest(StoreConfig config) throws Exception {
        return digest(config, VERSION);
    }

    private static String digest(StoreConfig config, int version) throws Exception {
        StringBuilder out = new StringBuilder();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (int i = 1; i <= version; i++) hash.update(source(config, i));
        for (byte b : hash.digest()) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    static Set<String> tables(Connection c, StoreConfig config) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet r =
                     s.executeQuery(
                             config.mysql()
                                     ? "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                                     : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) names.add(r.getString(1));
        }
        return names;
    }

    public static void verify(Connection c, StoreConfig config) throws Exception {
        verify(c, config, VERSION);
    }

    private static void verify(Connection c, StoreConfig config, int version) throws Exception {
        Set<String> expected = new HashSet<>(TABLES);
        if (version >= 2)
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
        if (version >= 3) expected.addAll(Arrays.asList("harness_model_backend", "harness_model_job"));
        if (version >= 4)
            expected.addAll(Arrays.asList("harness_inference", "harness_inference_event"));
        if (version >= 5)
            expected.addAll(
                    Arrays.asList(
                            "harness_runner_identity",
                            "harness_tool_job",
                            "harness_tool_revocation",
                            "harness_broker_exchange"));
        if (version >= 6)
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
        if (version >= 7) expected.add("harness_feedback");
        if (!tables(c, config).equals(expected)) throw new StoreException("STORE_SCHEMA_MISMATCH");
        try (Statement s = c.createStatement();
             ResultSet r =
                     s.executeQuery(
                             "SELECT store_id, schema_version, migration_hash FROM harness_store_identity WHERE singleton=1")) {
            if (!r.next()
                    || !config.identity.equals(r.getString(1))
                    || r.getInt(2) != version
                    || !digest(config, version).equals(r.getString(3)))
                throw new StoreException("STORE_IDENTITY_MISMATCH");
        }
    }

    public static void migrate(StoreConfig config, boolean initializeEmpty) throws Exception {
        try (Connection c = config.connect()) {
            if (config.mysql()) {
                try (PreparedStatement s = c.prepareStatement("SELECT GET_LOCK(?,10)")) {
                    s.setString(1, "harness:migrate:" + config.database);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || r.getInt(1) != 1) throw new StoreException("STORE_MIGRATION_BUSY");
                    }
                }
            } else StoreConfig.begin(c, false);
            try {
                Set<String> existing = tables(c, config);
                if (!existing.isEmpty()) {
                    int old;
                    if (!existing.contains("harness_store_identity"))
                        throw new StoreException("STORE_SCHEMA_MISMATCH");
                    try (Statement s = c.createStatement();
                         ResultSet r =
                                 s.executeQuery(
                                         "SELECT schema_version FROM harness_store_identity WHERE singleton=1")) {
                        if (!r.next()) throw new StoreException("STORE_IDENTITY_MISMATCH");
                        old = r.getInt(1);
                    }
                    if (old < 1 || old > VERSION) throw new StoreException("STORE_IDENTITY_MISMATCH");
                    verify(c, config, old);
                    for (int version = old + 1; version <= VERSION; version++) {
                        execute(c, config, version);
                        try (PreparedStatement s =
                                     c.prepareStatement(
                                             "UPDATE harness_store_identity SET schema_version=?,migration_hash=? WHERE singleton=1 AND schema_version=?")) {
                            s.setInt(1, version);
                            s.setString(2, digest(config, version));
                            s.setInt(3, version - 1);
                            if (s.executeUpdate() != 1) throw new StoreException("STORE_MIGRATION_BUSY");
                        }
                    }
                    verify(c, config);
                } else {
                    if (!initializeEmpty) throw new StoreException("STORE_INITIALIZE_REQUIRED");
                    for (int version = 1; version <= VERSION; version++) execute(c, config, version);
                    try (PreparedStatement s =
                                 c.prepareStatement("INSERT INTO harness_store_identity VALUES (1,?,?,?)")) {
                        s.setString(1, config.identity);
                        s.setInt(2, VERSION);
                        s.setString(3, digest(config));
                        s.executeUpdate();
                    }
                    verify(c, config);
                }
                if (!config.mysql()) StoreConfig.end(c, false, true);
            } catch (Exception e) {
                if (!config.mysql()) StoreConfig.end(c, false, false);
                // MySQL DDL may have committed. A partial schema fails closed; no destructive retry/down.
                throw e;
            } finally {
                if (config.mysql())
                    try (Statement s = c.createStatement()) {
                        s.execute("SELECT RELEASE_LOCK('harness:migrate:" + config.database + "')");
                    }
            }
        }
    }

    private static void execute(Connection c, StoreConfig config, int version) throws Exception {
        for (String part : new String(source(config, version), StandardCharsets.UTF_8).split(";"))
            if (!part.trim().isEmpty())
                try (Statement s = c.createStatement()) {
                    s.execute(part.trim());
                }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2
                || !(args[0].equals("verify")
                || args[0].equals("initialize-empty")
                || args[0].equals("upgrade")))
            throw new IllegalArgumentException(
                    "StoreMigrations verify|initialize-empty|upgrade <private.properties>");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[1]))) {
            p.load(in);
        }
        StoreConfig config = new StoreConfig(p);
        if (args[0].equals("initialize-empty")) migrate(config, true);
        else if (args[0].equals("upgrade")) migrate(config, false);
        else
            try (Connection c = config.connect()) {
                verify(c, config);
            }
        System.out.println(
                "PASS: Harness store " + args[0] + " " + config.dialect + " schema=" + VERSION);
    }
}
