/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Current store definition and startup-only construction; readers only verify.
 */
public final class StoreBootstrap {
    // Exact previous mysql.sql digest: only table engine/charset/collation defaults changed.
    private static final String MYSQL_SCHEMA_BEFORE_EXPLICIT_COLLATIONS =
            "e5693c212ea5adaf520264157f04485669498167c201827a616fa6e646d83317";

    private StoreBootstrap() {
    }

    private static String source(StoreConfig config) throws Exception {
        try (InputStream in = StoreBootstrap.class.getResourceAsStream("/store/" + config.dialect + ".sql")) {
            if (in == null) throw new StoreException("STORE_SCHEMA_MISSING");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n") + "\n"
                    + String.join(";\n", cube.aigc.usage.UsageSchema.statements("harness_ai_usage_", config.mysql())) + ";\n";
        }
    }

    /**
     * Stable digest of the current definition, independent of platform line endings.
     */
    public static String digest(StoreConfig config) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        StringBuilder out = new StringBuilder();
        for (byte b : hash.digest(source(config).getBytes(StandardCharsets.UTF_8))) {
            out.append(String.format("%02x", b & 255));
        }
        return out.toString();
    }

    private static List<String> statements(StoreConfig config) throws Exception {
        List<String> out = new ArrayList<>();
        for (String sql : source(config).split(";")) if (!sql.trim().isEmpty()) out.add(sql.trim());
        return out;
    }

    private static StoreSchema schema(List<String> statements) {
        StoreSchema schema = new StoreSchema();
        for (String sql : statements) schema.add(sql);
        return schema;
    }

    static Set<String> tables(Connection c, StoreConfig config) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(config.mysql()
                ? "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) names.add(r.getString(1));
        }
        return names;
    }

    /**
     * Read-only validation, also used by doctor and JdbcRunStore.
     */
    public static void verify(Connection c, StoreConfig config) throws Exception {
        schema(statements(config)).verify(c, config, false);
        verifyIdentity(c, config);
    }

    private static void verifyIdentity(Connection c, StoreConfig config) throws Exception {
        verifyIdentity(c, config, false);
    }

    /**
     * Only startup may resume construction from the known previous MySQL definition.
     */
    private static boolean verifyIdentity(Connection c, StoreConfig config, boolean allowPreviousMysqlSchema) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT singleton,store_id,schema_hash FROM harness_store_identity")) {
            if (!r.next() || r.getInt(1) != 1 || !config.identity.equals(r.getString(2))) {
                throw new StoreException("STORE_IDENTITY_MISMATCH");
            }
            String hash = r.getString(3);
            boolean previous = allowPreviousMysqlSchema && (config.mysql()
                    ? MYSQL_SCHEMA_BEFORE_EXPLICIT_COLLATIONS.equals(hash) || "dcda116b812ddb9ecc491e81e5614e72ce8b2e2ca134a39ef2ba3bf3424ce61b".equals(hash)
                    : "e722e01d82aeaff998fc45783308f8c2cb6ef923b48f04b32f402ce3cd9ee021".equals(hash));
            if ((!digest(config).equals(hash) && !previous) || r.next()) {
                throw new StoreException("STORE_IDENTITY_MISMATCH");
            }
            return previous;
        }
    }

    /**
     * Service startup constructs and checks the current schema before accepting requests.
     */
    public static void selfCheck(StoreConfig config) throws Exception {
        List<String> statements = statements(config);
        StoreSchema expected = schema(statements);
        try (Connection c = config.connect()) {
            if (config.mysql()) {
                c.setAutoCommit(false);
                // Full strict verification can exceed ten seconds while another JVM constructs DDL.
                try (PreparedStatement s = c.prepareStatement("SELECT GET_LOCK(?,60)")) {
                    // The database name is at most 56 characters; MySQL lock names allow 64.
                    s.setString(1, "harness:" + config.database);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next() || r.getInt(1) != 1) throw new StoreException("STORE_BOOTSTRAP_BUSY");
                    }
                }
            } else StoreConfig.begin(c, false);
            try {
                Set<String> existing = tables(c, config);
                expected.verify(c, config, true);
                boolean empty = existing.isEmpty();
                boolean markerOnly = existing.equals(Collections.singleton("harness_store_identity"));
                if (empty) execute(c, statements.get(0));
                else if (!existing.contains("harness_store_identity"))
                    throw new StoreException("STORE_SCHEMA_MISMATCH");
                boolean hasIdentity;
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM harness_store_identity")) {
                    r.next();
                    hasIdentity = r.getInt(1) != 0;
                }
                if (!hasIdentity) {
                    // MySQL may have stopped between creating the marker table and writing its identity.
                    if (!empty && !markerOnly) throw new StoreException("STORE_IDENTITY_MISMATCH");
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO harness_store_identity VALUES (1,?,?)")) {
                        s.setString(1, config.identity);
                        s.setString(2, digest(config));
                        s.executeUpdate();
                    }
                }
                boolean updateSchemaHash = verifyIdentity(c, config, true);
                for (String sql : expected.missingColumns(c, config)) execute(c, sql);
                for (String sql : statements) if (!expected.applied(c, config, sql)) execute(c, sql);
                expected.verify(c, config, false);
                if (updateSchemaHash) {
                    // Advance the marker only after all DDL and structural checks succeed.
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE harness_store_identity SET schema_hash=? WHERE singleton=1 AND store_id=?")) {
                        s.setString(1, digest(config));
                        s.setString(2, config.identity);

                        if (s.executeUpdate() != 1) throw new StoreException("STORE_IDENTITY_MISMATCH");
                    }
                }
                verifyIdentity(c, config);
                if (config.mysql()) c.commit();
                else StoreConfig.end(c, false, true);
            } catch (Exception e) {
                if (config.mysql()) c.rollback();
                else StoreConfig.end(c, false, false);
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

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
