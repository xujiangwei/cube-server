/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.test;

import cell.core.talk.LiteralBase;
import cube.core.ColumnIndex;
import cube.core.Conditional;
import cube.core.RowMapper;
import cube.core.RowVisitor;
import cube.core.StorageException;
import cube.core.StorageField;
import cube.storage.SQLiteStorage;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式读取 API（{@link cube.core.Storage#queryList} / {@link cube.core.Storage#forEachRow}）自检。
 *
 * <p>用 SQLite 内存库把"老路径"（{@code executeQuery} + {@code StorageField}）与
 * "新路径"（{@code queryList} + 值数组）跑同一份数据，逐行比对输出，
 * 覆盖四件事：结果一致、NULL 保留、可提前终止、数组复用契约。</p>
 */
public final class RowApiTest {

    private static int failed = 0;

    private static final StorageField[] FIELDS = new StorageField[] {
            new StorageField("id", LiteralBase.LONG),
            new StorageField("contact_id", LiteralBase.LONG),
            new StorageField("point", LiteralBase.INT),
            new StorageField("timestamp", LiteralBase.LONG),
            new StorageField("source", LiteralBase.STRING),
            new StorageField("comment", LiteralBase.STRING)
    };

    private RowApiTest() {
    }

    public static void main(String[] args) {
        SQLiteStorage storage = open();
        try {
            prepare(storage);

            compareWithLegacyQuery(storage);
            verifyNullPreserved(storage);
            verifyEarlyStop(storage);
            verifyArrayReuse(storage);
            verifyColumnIndex(storage);
            verifyClosedStorage();
        } finally {
            storage.close();
        }

        System.out.println(0 == failed ? "RowApiTest PASS" : "RowApiTest FAIL - " + failed);
        if (0 != failed) {
            System.exit(1);
        }
    }

    private static SQLiteStorage open() {
        JSONObject config = new JSONObject();
        config.put("file", ":memory:");

        SQLiteStorage storage = new SQLiteStorage("row-api-test");
        storage.configure(config);
        storage.open();
        check("storage:open", storage.isOpen());
        return storage;
    }

    private static void prepare(SQLiteStorage storage) {
        storage.execute("CREATE TABLE point (id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id INTEGER, "
                + "point INTEGER, timestamp INTEGER, source TEXT, comment TEXT)");
        storage.execute("INSERT INTO point (contact_id, point, timestamp, source, comment) "
                + "VALUES (100, 5, 1000, 'alpha', 'first')");
        storage.execute("INSERT INTO point (contact_id, point, timestamp, source, comment) "
                + "VALUES (100, 7, 2000, 'beta', NULL)");
        storage.execute("INSERT INTO point (contact_id, point, timestamp, source, comment) "
                + "VALUES (200, 9, 3000, 'gamma', 'third')");
    }

    /**
     * 老路径与新路径的逐行对照。
     */
    private static void compareWithLegacyQuery(SQLiteStorage storage) {
        Conditional[] conditionals = new Conditional[] {
                Conditional.createEqualTo("contact_id", 100L)
        };

        List<String> legacy = new ArrayList<>();
        List<StorageField[]> rows = storage.executeQuery("point", FIELDS, conditionals);
        for (StorageField[] row : rows) {
            legacy.add(formatLegacy(row));
        }

        List<String> streamed = storage.queryList("point", FIELDS, conditionals,
                new RowMapper<String>() {
                    @Override
                    public String map(Object[] values, ColumnIndex columns) {
                        return formatValues(values);
                    }
                });

        System.out.println("  legacy   = " + legacy);
        System.out.println("  streamed = " + streamed);

        check("compare:size", legacy.size() == streamed.size());
        check("compare:content", legacy.equals(streamed));
        check("compare:rows", 2 == streamed.size());
    }

    private static void verifyNullPreserved(SQLiteStorage storage) {
        final String[] nullComment = new String[1];
        storage.forEachRow("point", FIELDS,
                new Conditional[] {Conditional.createEqualTo("contact_id", 100L)},
                new RowVisitor() {
                    private int index = 0;

                    @Override
                    public boolean visit(Object[] values, ColumnIndex columns) {
                        if (1 == this.index) {
                            nullComment[0] = (null == values[5]) ? "NULL" : String.valueOf(values[5]);
                        }
                        ++this.index;
                        return true;
                    }
                });

        check("null:preserved", "NULL".equals(nullComment[0]));
    }

    private static void verifyEarlyStop(SQLiteStorage storage) {
        final int[] visited = new int[1];
        storage.forEachRow("point", FIELDS, null, new RowVisitor() {
            @Override
            public boolean visit(Object[] values, ColumnIndex columns) {
                ++visited[0];
                return visited[0] < 2;
            }
        });

        check("earlyStop:count", 2 == visited[0]);
    }

    private static void verifyArrayReuse(SQLiteStorage storage) {
        final Object[][] seen = new Object[2][];
        final int[] index = new int[1];

        storage.forEachRow("point", FIELDS,
                new Conditional[] {Conditional.createEqualTo("contact_id", 100L)},
                new RowVisitor() {
                    @Override
                    public boolean visit(Object[] values, ColumnIndex columns) {
                        if (index[0] < 2) {
                            seen[index[0]] = values;
                        }
                        ++index[0];
                        return index[0] < 2;
                    }
                });

        check("reuse:sameArray", null != seen[0] && seen[0] == seen[1]);
    }

    private static void verifyColumnIndex(SQLiteStorage storage) {
        ColumnIndex columns = ColumnIndex.of(FIELDS);
        check("index:size", 6 == columns.size());
        check("index:id", 0 == columns.indexOf("id"));
        check("index:comment", 5 == columns.indexOf("comment"));
        check("index:missing", -1 == columns.indexOf("not_exist"));
        check("index:nameOf", "point".equals(columns.nameOf(2)));
    }

    private static void verifyClosedStorage() {
        JSONObject config = new JSONObject();
        config.put("file", ":memory:");

        SQLiteStorage storage = new SQLiteStorage("row-api-closed");
        storage.configure(config);
        // 不 open，直接流式查询：契约要求抛 StorageException，而不是 NPE
        try {
            storage.forEachRow("point", FIELDS, null, new RowVisitor() {
                @Override
                public boolean visit(Object[] values, ColumnIndex columns) {
                    return true;
                }
            });
            check("closed:throws", false);
        } catch (StorageException e) {
            check("closed:throws", true);
        } catch (Throwable t) {
            System.out.println("  unexpected: " + t);
            check("closed:throws", false);
        }
    }

    private static String formatLegacy(StorageField[] row) {
        StringBuilder buf = new StringBuilder();
        buf.append(row[0].getLong()).append('|');
        buf.append(row[1].getLong()).append('|');
        buf.append(row[2].getInt()).append('|');
        buf.append(row[3].getLong()).append('|');
        buf.append(row[4].getString()).append('|');
        buf.append(row[5].isNullValue() ? "null" : row[5].getString());
        return buf.toString();
    }

    private static String formatValues(Object[] values) {
        StringBuilder buf = new StringBuilder();
        buf.append(values[0]).append('|');
        buf.append(values[1]).append('|');
        buf.append(values[2]).append('|');
        buf.append(values[3]).append('|');
        buf.append(values[4]).append('|');
        buf.append(null == values[5] ? "null" : values[5]);
        return buf.toString();
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            ++failed;
            System.out.println("  [FAIL] " + name);
        }
        else {
            System.out.println("  [ OK ] " + name);
        }
    }
}
