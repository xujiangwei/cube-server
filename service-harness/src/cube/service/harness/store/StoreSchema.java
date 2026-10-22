/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadata checks and missing-object detection for the current store definition. */
final class StoreSchema {
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (harness_[a-z_]+) \\((.*)\\).*", Pattern.DOTALL);
    private static final Pattern INDEX = Pattern.compile("CREATE INDEX (harness_[a-z_]+) ON (harness_[a-z_]+)\\s*\\((.*)\\)", Pattern.DOTALL);
    final Map<String, Table> tables = new LinkedHashMap<>();

    static final class Column {
        final String name, type, defaultValue, definition;
        final boolean notNull;
        Column(String definition) {
            this.definition = definition;
            String[] parts = definition.trim().split("\\s+", 3);
            name = parts[0];
            type = type(parts[1]);
            String tail = parts.length == 3 ? parts[2] : "";
            notNull = tail.contains("NOT NULL");
            int at = tail.indexOf("DEFAULT ");
            defaultValue = at < 0 ? null : defaultValue(tail.substring(at + 8).trim());
        }
    }

    static final class Table {
        final Map<String, Column> columns = new LinkedHashMap<>();
        String primary = "";
        final Set<String> unique = new HashSet<>(), foreign = new HashSet<>();
        final Map<String, String> indexes = new LinkedHashMap<>();
        void column(String definition) {
            Column column = new Column(definition);
            columns.put(column.name, column);
            if (definition.contains("PRIMARY KEY")) primary = column.name;
            if (definition.contains("UNIQUE")) unique.add(column.name);
        }
    }

    void add(String sql) {
        Matcher m = TABLE.matcher(sql);
        if (m.matches()) {
            Table table = new Table();
            tables.put(m.group(1), table);
            for (String part : split(m.group(2))) {
                if (part.startsWith("PRIMARY KEY")) table.primary = inside(part);
                else if (part.startsWith("UNIQUE")) table.unique.add(inside(part));
                else if (part.startsWith("FOREIGN KEY")) {
                    Matcher fk = Pattern.compile("FOREIGN KEY\\s*\\((.*?)\\) REFERENCES ([a-z_]+)\\s*\\((.*?)\\)").matcher(part);
                    if (!fk.matches()) throw new StoreException("STORE_SCHEMA_UNSUPPORTED");
                    table.foreign.add(compact(fk.group(1)) + "->" + fk.group(2) + ":" + compact(fk.group(3)));
                } else table.column(part);
            }
        } else if ((m = INDEX.matcher(sql)).matches()) {
            tables.get(m.group(2)).indexes.put(m.group(1), compact(m.group(3)));
        } else throw new StoreException("STORE_SCHEMA_UNSUPPORTED");
    }

    /** Existing objects must match; partial=true allows only absent additive objects. */
    void verify(Connection c, StoreConfig config, boolean partial) throws SQLException {
        Set<String> existing = StoreBootstrap.tables(c, config);
        if (!tables.keySet().containsAll(existing) || (!partial && !existing.equals(tables.keySet()))) mismatch("tables");
        for (String name : existing) {
            Table expected = tables.get(name);
            Map<String, String[]> actual = columns(c, config, name);
            if (!expected.columns.keySet().containsAll(actual.keySet())
                    || (!partial && !actual.keySet().equals(expected.columns.keySet()))) mismatch(name + ".columns");
            // Positional writes require the declared order. Only nullable/defaulted columns can be repaired.
            List<String> order = new ArrayList<>();
            boolean missing = false;
            for (Column column : expected.columns.values()) {
                if (actual.containsKey(column.name)) {
                    if (missing && !config.mysql()) mismatch(name + ".column_order");
                    order.add(column.name);
                } else {
                    missing = true;
                    if (column.notNull && column.defaultValue == null) mismatch(name + "." + column.name);
                }
            }
            if (!order.equals(new ArrayList<>(actual.keySet()))) mismatch(name + ".column_order");
            for (Map.Entry<String, String[]> entry : actual.entrySet()) {
                Column column = expected.columns.get(entry.getKey());
                String[] a = entry.getValue();
                boolean required = column.notNull || (config.mysql() && Arrays.asList(expected.primary.split(",")).contains(column.name));
                if (!column.type.equals(type(a[0])) || required != Boolean.parseBoolean(a[1])
                        || !Objects.equals(column.defaultValue, defaultValue(a[2]))) mismatch(name + "." + column.name);
            }
            DatabaseMetaData md = c.getMetaData();
            SortedMap<Integer, String> pk = new TreeMap<>();
            try (ResultSet r = md.getPrimaryKeys(c.getCatalog(), null, name)) {
                while (r.next()) pk.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
            if (!expected.primary.equals(join(pk))) mismatch(name + ".primary");
            Map<String, SortedMap<Integer, String>> indexes = new HashMap<>();
            Set<String> uniqueNames = new HashSet<>(), actualUnique = new HashSet<>();
            try (ResultSet r = md.getIndexInfo(c.getCatalog(), null, name, false, false)) {
                while (r.next()) {
                    String index = r.getString("INDEX_NAME"), column = r.getString("COLUMN_NAME");
                    if (index == null || column == null) continue;
                    indexes.computeIfAbsent(index, k -> new TreeMap<>()).put(r.getInt("ORDINAL_POSITION"), column);
                    if (!r.getBoolean("NON_UNIQUE")) uniqueNames.add(index);
                    String direction = r.getString("ASC_OR_DESC");
                    if (expected.indexes.containsKey(index) && "D".equals(direction)) mismatch(name + "." + index);
                }
            }
            for (String index : uniqueNames) {
                String value = join(indexes.get(index));
                if (!value.equals(expected.primary)) actualUnique.add(value);
            }
            if (!actualUnique.equals(expected.unique)) mismatch(name + ".unique");
            for (Map.Entry<String, String> index : expected.indexes.entrySet()) {
                SortedMap<Integer, String> found = indexes.get(index.getKey());
                if (found == null && partial) continue;
                if (found == null || !join(found).equals(index.getValue()) || uniqueNames.contains(index.getKey())) mismatch(name + "." + index.getKey());
            }
            if (!foreign(c, config, name).equals(expected.foreign)) mismatch(name + ".foreign");
        }
    }

    /** Repair safe missing columns while retaining the order required by positional writes. */
    List<String> missingColumns(Connection c, StoreConfig config) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String name : StoreBootstrap.tables(c, config)) {
            Map<String, String[]> actual = columns(c, config, name);
            String previous = null;
            for (Column column : tables.get(name).columns.values()) {
                if (!actual.containsKey(column.name)) {
                    out.add("ALTER TABLE " + name + " ADD COLUMN " + column.definition
                            + (config.mysql() ? previous == null ? " FIRST" : " AFTER " + previous : ""));
                }
                previous = column.name;
            }
        }
        return out;
    }

    /** Return whether a table or index already exists. */
    boolean applied(Connection c, StoreConfig config, String sql) throws SQLException {
        Matcher m = TABLE.matcher(sql);
        if (m.matches()) return StoreBootstrap.tables(c, config).contains(m.group(1));
        if ((m = INDEX.matcher(sql)).matches()) {
            try (ResultSet r = c.getMetaData().getIndexInfo(c.getCatalog(), null, m.group(2), false, false)) {
                while (r.next()) if (m.group(1).equals(r.getString("INDEX_NAME"))) return true;
            }
            return false;
        }
        throw new StoreException("STORE_SCHEMA_UNSUPPORTED");
    }

    private static Map<String, String[]> columns(Connection c, StoreConfig config, String table) throws SQLException {
        Map<String, String[]> out = new LinkedHashMap<>();
        String query = config.mysql()
                ? "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "' ORDER BY ORDINAL_POSITION"
                : "PRAGMA table_info(" + table + ")";
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            while (r.next()) {
                String name = r.getString(config.mysql() ? "COLUMN_NAME" : "name");
                out.put(name, new String[]{r.getString(config.mysql() ? "COLUMN_TYPE" : "type"),
                        String.valueOf(config.mysql() ? "NO".equals(r.getString("IS_NULLABLE")) : r.getInt("notnull") != 0),
                        r.getString(config.mysql() ? "COLUMN_DEFAULT" : "dflt_value")});
            }
        }
        return out;
    }

    private static Set<String> foreign(Connection c, StoreConfig config, String table) throws SQLException {
        Map<String, SortedMap<Integer, String>> from = new HashMap<>(), to = new HashMap<>();
        Map<String, String> targets = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = config.mysql()
                ? c.getMetaData().getImportedKeys(c.getCatalog(), null, table)
                : s.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (r.next()) {
                String key = r.getString(config.mysql() ? "FK_NAME" : "id");
                int position = r.getInt(config.mysql() ? "KEY_SEQ" : "seq");
                targets.put(key, r.getString(config.mysql() ? "PKTABLE_NAME" : "table"));
                from.computeIfAbsent(key, k -> new TreeMap<>()).put(position, r.getString(config.mysql() ? "FKCOLUMN_NAME" : "from"));
                to.computeIfAbsent(key, k -> new TreeMap<>()).put(position, r.getString(config.mysql() ? "PKCOLUMN_NAME" : "to"));
                if (config.mysql()) {
                    if (!restrict(r.getInt("DELETE_RULE")) || !restrict(r.getInt("UPDATE_RULE"))) mismatch(table + ".foreign.action");
                } else if (!Arrays.asList("NO ACTION", "RESTRICT").contains(r.getString("on_delete"))
                        || !Arrays.asList("NO ACTION", "RESTRICT").contains(r.getString("on_update"))) mismatch(table + ".foreign.action");
            }
        }
        Set<String> out = new HashSet<>();
        for (String key : from.keySet()) out.add(join(from.get(key)) + "->" + targets.get(key) + ":" + join(to.get(key)));
        return out;
    }

    private static boolean restrict(int rule) {
        return rule == DatabaseMetaData.importedKeyNoAction || rule == DatabaseMetaData.importedKeyRestrict;
    }
    private static String join(SortedMap<Integer, String> values) { return String.join(",", values.values()); }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
    private static String inside(String value) { return compact(value.substring(value.indexOf('(') + 1, value.lastIndexOf(')'))); }
    private static String type(String value) {
        return value.toLowerCase(Locale.ROOT).replace("integer", "int").replaceAll("(?<=int)\\(\\d+\\)", "").replaceAll("\\s+", "");
    }
    private static String defaultValue(String value) {
        if (value == null || value.equalsIgnoreCase("NULL")) return null;
        return value.replaceAll("^[(']+|[)']+$", "");
    }
    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) { out.add(value.substring(start, i).trim()); start = i + 1; }
        }
        out.add(value.substring(start).trim());
        return out;
    }
    private static void mismatch(String object) { throw new StoreException("STORE_SCHEMA_MISMATCH", new IllegalStateException(object)); }
}
