/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.test;

import cube.common.entity.Point;
import cube.core.Conditional;
import cube.core.StorageField;
import cube.service.contact.ContactStorage;
import cube.storage.SQLiteStorage;
import cube.storage.StorageFields;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code ContactStorage#readPoints} 改造试点：老路径与新路径的逐行对照。
 *
 * <p>用 SQLite 内存库跑真实数据：同一张 {@code point_xxx} 表，
 * 一边走 {@code executeQuery + StorageFields.get}（改造前的写法），
 * 一边走 {@code queryList + 列下标}（改造后的实现），比较两者输出是否逐字段一致。</p>
 */
public final class ReadPointsPilotTest {

    private static final String DOMAIN = "test.local";

    private static final String TABLE = "point_test_local";

    private static int failed = 0;

    private ReadPointsPilotTest() {
    }

    public static void main(String[] args) {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        JSONObject config = new JSONObject();
        config.put("file", ":memory:");

        SQLiteStorage storage = new SQLiteStorage("read-points-pilot");
        storage.configure(config);

        // 用注入构造函数：ContactStorage(ExecutorService, Storage)
        ContactStorage contactStorage = new ContactStorage(executor, storage);

        try {
            contactStorage.open();
            contactStorage.execSelfChecking(Arrays.asList(DOMAIN));

            prepare(storage);

            List<String> legacy = legacyRead(storage);
            List<String> pilot = format(contactStorage.readPoints(100L, DOMAIN));

            System.out.println("  legacy = " + legacy);
            System.out.println("  pilot  = " + pilot);

            check("pilot:size", 2 == pilot.size());
            check("pilot:content", legacy.equals(pilot));
            check("pilot:nullComment", 2 == pilot.size() && pilot.get(1).endsWith("|null"));
            check("pilot:otherContact", 0 == contactStorage.readPoints(999L, DOMAIN).size());
            check("pilot:unknownDomain", 0 == contactStorage.readPoints(100L, "unknown.local").size());
        } finally {
            contactStorage.close();
            executor.shutdown();
        }

        System.out.println(0 == failed ? "ReadPointsPilotTest PASS" : "ReadPointsPilotTest FAIL - " + failed);
        if (0 != failed) {
            System.exit(1);
        }
    }

    private static void prepare(SQLiteStorage storage) {
        storage.execute("INSERT INTO " + TABLE + " (contact_id, point, timestamp, source, comment) "
                + "VALUES (100, 5, 1000, 'alpha', 'first')");
        storage.execute("INSERT INTO " + TABLE + " (contact_id, point, timestamp, source, comment) "
                + "VALUES (100, 7, 2000, 'beta', NULL)");
        storage.execute("INSERT INTO " + TABLE + " (contact_id, point, timestamp, source, comment) "
                + "VALUES (200, 9, 3000, 'gamma', 'third')");
    }

    /**
     * 改造前的读取方式（保留作为对照组）：全量物化 + 每行一个 HashMap。
     */
    private static List<String> legacyRead(SQLiteStorage storage) {
        List<String> list = new ArrayList<>();
        List<StorageField[]> rows = storage.executeQuery(TABLE, null, new Conditional[] {
                Conditional.createEqualTo("contact_id", 100L)
        });

        for (StorageField[] row : rows) {
            Map<String, StorageField> data = StorageFields.get(row);
            StringBuilder buf = new StringBuilder();
            buf.append(data.get("id").getLong()).append('|');
            buf.append(data.get("timestamp").getLong()).append('|');
            buf.append(data.get("contact_id").getLong()).append('|');
            buf.append(data.get("point").getInt()).append('|');
            buf.append(data.get("source").getString()).append('|');
            buf.append(data.get("comment").isNullValue() ? "null" : data.get("comment").getString());
            list.add(buf.toString());
        }

        return list;
    }

    private static List<String> format(List<Point> points) {
        List<String> list = new ArrayList<>();
        for (Point point : points) {
            StringBuilder buf = new StringBuilder();
            buf.append(point.getId()).append('|');
            buf.append(point.getTimestamp()).append('|');
            buf.append(point.getContactId()).append('|');
            buf.append(point.getPoint()).append('|');
            buf.append(point.getSource()).append('|');
            buf.append(null == point.getComment() ? "null" : point.getComment());
            list.add(buf.toString());
        }

        return list;
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
