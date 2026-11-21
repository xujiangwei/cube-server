/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 当前存储定义的元数据校验与缺失对象检测。
 */
final class StoreSchema {
    /**
     * CREATE TABLE 语句解析正则。
     */
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (harness_[a-z_]+) \\((.*)\\).*", Pattern.DOTALL);

    /**
     * CREATE INDEX 语句解析正则。
     */
    private static final Pattern INDEX = Pattern.compile("CREATE INDEX (harness_[a-z_]+) ON (harness_[a-z_]+)\\s*\\((.*)\\)", Pattern.DOTALL);

    /**
     * 表名到表定义的映射。
     */
    final Map<String, Table> tables = new LinkedHashMap<>();

    /**
     * 单列定义：名称、类型、默认值与原始定义。
     */
    static final class Column {
        /**
         * 列名、类型、默认值与原始定义。
         */
        final String name;
        final String type;
        final String defaultValue;
        final String definition;

        /**
         * 是否非空。
         */
        final boolean notNull;

        /**
         * 从 DDL 片段解析列定义。
         *
         * @param definition 列定义文本。
         */
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

    /**
     * 单表定义：列、主键、唯一约束、外键与索引。
     */
    static final class Table {
        /**
         * 列名到列定义的映射，保持声明顺序。
         */
        final Map<String, Column> columns = new LinkedHashMap<>();

        /**
         * 主键列表达式。
         */
        String primary = "";

        /**
         * 唯一约束列集合与外键表达式集合。
         */
        final Set<String> unique = new HashSet<>();
        final Set<String> foreign = new HashSet<>();

        /**
         * 索引名到列表达式的映射。
         */
        final Map<String, String> indexes = new LinkedHashMap<>();

        /**
         * 解析并加入一个列定义。
         *
         * @param definition 列定义文本。
         */
        void column(String definition) {
            Column column = new Column(definition);
            columns.put(column.name, column);
            if (definition.contains("PRIMARY KEY")) {
                primary = column.name;
            }
            if (definition.contains("UNIQUE")) {
                unique.add(column.name);
            }
        }
    }

    /**
     * 从 DDL 语句解析并加入表或索引定义。
     *
     * @param sql DDL 语句。
     */
    void add(String sql) {
        Matcher m = TABLE.matcher(sql);
        if (m.matches()) {
            Table table = new Table();
            tables.put(m.group(1), table);
            for (String part : split(m.group(2))) {
                if (part.startsWith("PRIMARY KEY")) {
                    table.primary = inside(part);
                } else if (part.startsWith("UNIQUE")) {
                    table.unique.add(inside(part));
                } else if (part.startsWith("FOREIGN KEY")) {
                    Matcher fk = Pattern.compile("FOREIGN KEY\\s*\\((.*?)\\) REFERENCES ([a-z_]+)\\s*\\((.*?)\\)").matcher(part);
                    if (!fk.matches()) {
                        throw new StoreException("STORE_SCHEMA_UNSUPPORTED");
                    }
                    table.foreign.add(compact(fk.group(1)) + "->" + fk.group(2) + ":" + compact(fk.group(3)));
                } else {
                    table.column(part);
                }
            }
        } else if ((m = INDEX.matcher(sql)).matches()) {
            tables.get(m.group(2)).indexes.put(m.group(1), compact(m.group(3)));
        } else {
            throw new StoreException("STORE_SCHEMA_UNSUPPORTED");
        }
    }

    /**
     * 校验现有对象与期望定义一致；partial=true 只允许缺失的追加对象。
     *
     * @param c       数据库连接。
     * @param config  存储配置。
     * @param partial 是否允许缺失的追加对象。
     * @throws SQLException 校验查询失败。
     */
    void verify(Connection c, StoreConfig config, boolean partial) throws SQLException {
        Set<String> existing = StoreBootstrap.tables(c, config);
        if (!tables.keySet().containsAll(existing) || (!partial && !existing.equals(tables.keySet()))) {
            mismatch("tables");
        }
        for (String name : existing) {
            Table expected = tables.get(name);
            Map<String, String[]> actual = columns(c, config, name);
            if (!expected.columns.keySet().containsAll(actual.keySet())
                    || (!partial && !actual.keySet().equals(expected.columns.keySet()))) {
                mismatch(name + ".columns");
            }
            // 按位置写入要求声明顺序；只有可空或有默认值的列可被修复。
            List<String> order = new ArrayList<>();
            boolean missing = false;
            for (Column column : expected.columns.values()) {
                if (actual.containsKey(column.name)) {
                    if (missing && !config.mysql()) {
                        mismatch(name + ".column_order");
                    }
                    order.add(column.name);
                } else {
                    missing = true;
                    if (column.notNull && column.defaultValue == null) {
                        mismatch(name + "." + column.name);
                    }
                }
            }
            if (!order.equals(new ArrayList<>(actual.keySet()))) {
                mismatch(name + ".column_order");
            }
            for (Map.Entry<String, String[]> entry : actual.entrySet()) {
                Column column = expected.columns.get(entry.getKey());
                String[] a = entry.getValue();
                boolean required = column.notNull || (config.mysql() && Arrays.asList(expected.primary.split(",")).contains(column.name));
                if (!column.type.equals(type(a[0])) || required != Boolean.parseBoolean(a[1])
                        || !Objects.equals(column.defaultValue, defaultValue(a[2]))) {
                    mismatch(name + "." + column.name);
                }
            }
            DatabaseMetaData md = c.getMetaData();
            SortedMap<Integer, String> pk = new TreeMap<>();
            try (ResultSet r = md.getPrimaryKeys(c.getCatalog(), null, name)) {
                while (r.next()) {
                    pk.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME"));
                }
            }
            if (!expected.primary.equals(join(pk))) {
                mismatch(name + ".primary");
            }
            Map<String, SortedMap<Integer, String>> indexes = new HashMap<>();
            Set<String> uniqueNames = new HashSet<>();
            Set<String> actualUnique = new HashSet<>();
            try (ResultSet r = md.getIndexInfo(c.getCatalog(), null, name, false, false)) {
                while (r.next()) {
                    String index = r.getString("INDEX_NAME");
                    String column = r.getString("COLUMN_NAME");
                    if (index == null || column == null) {
                        continue;
                    }
                    indexes.computeIfAbsent(index, k -> new TreeMap<>()).put(r.getInt("ORDINAL_POSITION"), column);
                    if (!r.getBoolean("NON_UNIQUE")) {
                        uniqueNames.add(index);
                    }
                    String direction = r.getString("ASC_OR_DESC");
                    if (expected.indexes.containsKey(index) && "D".equals(direction)) {
                        mismatch(name + "." + index);
                    }
                }
            }
            for (String index : uniqueNames) {
                String value = join(indexes.get(index));
                if (!value.equals(expected.primary)) {
                    actualUnique.add(value);
                }
            }
            if (!actualUnique.equals(expected.unique)) {
                mismatch(name + ".unique");
            }
            for (Map.Entry<String, String> index : expected.indexes.entrySet()) {
                SortedMap<Integer, String> found = indexes.get(index.getKey());
                if (found == null && partial) {
                    continue;
                }
                if (found == null || !join(found).equals(index.getValue()) || uniqueNames.contains(index.getKey())) {
                    mismatch(name + "." + index.getKey());
                }
            }
            if (!foreign(c, config, name).equals(expected.foreign)) {
                mismatch(name + ".foreign");
            }
        }
    }

    /**
     * 安全修复缺失列，同时保持按位置写入所需的列顺序。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @return 待执行的 ALTER TABLE 语句列表。
     * @throws SQLException 元数据查询失败。
     */
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

    /**
     * 判断表或索引是否已经存在。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @param sql    待判断的 DDL 语句。
     * @return 已存在返回 true。
     * @throws SQLException 元数据查询失败。
     */
    boolean applied(Connection c, StoreConfig config, String sql) throws SQLException {
        Matcher m = TABLE.matcher(sql);
        if (m.matches()) {
            return StoreBootstrap.tables(c, config).contains(m.group(1));
        }
        if ((m = INDEX.matcher(sql)).matches()) {
            try (ResultSet r = c.getMetaData().getIndexInfo(c.getCatalog(), null, m.group(2), false, false)) {
                while (r.next()) {
                    if (m.group(1).equals(r.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
            return false;
        }
        throw new StoreException("STORE_SCHEMA_UNSUPPORTED");
    }

    /**
     * 通过元数据读取表的列信息。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @param table  表名。
     * @return 列名到 [类型, 是否非空, 默认值] 的映射。
     * @throws SQLException 查询失败。
     */
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

    /**
     * 通过元数据读取表的外键定义。
     *
     * @param c      数据库连接。
     * @param config 存储配置。
     * @param table  表名。
     * @return 外键表达式集合。
     * @throws SQLException 查询失败。
     */
    private static Set<String> foreign(Connection c, StoreConfig config, String table) throws SQLException {
        Map<String, SortedMap<Integer, String>> from = new HashMap<>();
        Map<String, SortedMap<Integer, String>> to = new HashMap<>();
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
                    if (!restrict(r.getInt("DELETE_RULE")) || !restrict(r.getInt("UPDATE_RULE"))) {
                        mismatch(table + ".foreign.action");
                    }
                } else if (!Arrays.asList("NO ACTION", "RESTRICT").contains(r.getString("on_delete"))
                        || !Arrays.asList("NO ACTION", "RESTRICT").contains(r.getString("on_update"))) {
                    mismatch(table + ".foreign.action");
                }
            }
        }
        Set<String> out = new HashSet<>();
        for (String key : from.keySet()) {
            out.add(join(from.get(key)) + "->" + targets.get(key) + ":" + join(to.get(key)));
        }
        return out;
    }

    /**
     * 判断外键动作是否为限制型。
     *
     * @param rule JDBC 外键动作码。
     * @return NO ACTION 或 RESTRICT 返回 true。
     */
    private static boolean restrict(int rule) {
        return rule == DatabaseMetaData.importedKeyNoAction || rule == DatabaseMetaData.importedKeyRestrict;
    }

    /**
     * 把有序列映射拼接为逗号分隔字符串。
     *
     * @param values 有序列映射。
     * @return 拼接结果。
     */
    private static String join(SortedMap<Integer, String> values) {
        return String.join(",", values.values());
    }

    /**
     * 去掉字符串中的全部空白。
     *
     * @param value 原字符串。
     * @return 去空白后的字符串。
     */
    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }

    /**
     * 取括号内内容并去空白。
     *
     * @param value 含括号的字符串。
     * @return 括号内的紧凑内容。
     */
    private static String inside(String value) {
        return compact(value.substring(value.indexOf('(') + 1, value.lastIndexOf(')')));
    }

    /**
     * 规范化 SQL 类型文本以便比较。
     *
     * @param value 原类型文本。
     * @return 规范化类型。
     */
    private static String type(String value) {
        return value.toLowerCase(Locale.ROOT).replace("integer", "int").replaceAll("(?<=int)\\(\\d+\\)", "").replaceAll("\\s+", "");
    }

    /**
     * 规范化默认值文本。
     *
     * @param value 原默认值文本。
     * @return 规范化默认值，NULL 返回 null。
     */
    private static String defaultValue(String value) {
        if (value == null || value.equalsIgnoreCase("NULL")) {
            return null;
        }
        return value.replaceAll("^[(']+|[)']+$", "");
    }

    /**
     * 按顶层逗号切分列定义文本。
     *
     * @param value 原文本。
     * @return 切分后的片段列表。
     */
    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(value.substring(start).trim());
        return out;
    }

    /**
     * 抛出 schema 不匹配错误。
     *
     * @param object 不匹配的对象标识。
     */
    private static void mismatch(String object) {
        throw new StoreException("STORE_SCHEMA_MISMATCH", new IllegalStateException(object));
    }
}
