/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.test;

import cube.common.entity.Contact;
import cube.core.Conditional;
import cube.core.Storage;
import cube.core.StorageField;
import cube.service.contact.StatisticsSystem;
import cube.storage.StorageFactory;
import cube.storage.StorageType;
import cube.util.UnitDayRecord;
import cell.core.talk.LiteralBase;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单元日汇总采集的失败语义验证。
 *
 * <p>核心要验的是「查询失败不能被当成当天没有事件」：原生 SQL 查询失败时返回的是
 * 空列表而不是 null，两者无法区分。曾经因此在事件表读取失败时写下标记行，
 * 让 console 误以为该日已完整采集，把「采集失败」变成了「当天全员无活动」的
 * 静默错值。</p>
 *
 * <p>覆盖查询/写入失败、空日、表结构升级、事件排序、多采集器交错覆盖写、
 * 失败重试，以及 MySQL/SQLite 原生写入的返回值语义。汇总表带生产唯一键。</p>
 */
public class UnitDailyCollectTest {

    private static int assertions = 0;

    /**
     * 一小时的毫秒数。
     */
    private static final long HOUR = 60L * 60 * 1000L;

    /**
     * 测试用域名。
     */
    private static final String DOMAIN = "collect_test_domain";

    public static void main(String[] args) throws Exception {
        queryFailureWritesNoMarker();
        noEventWritesNoMarker();
        normalCollectWritesMarkerWithUnitCount();
        createTableHasNoDuplicateColumn();
        legacyTableGetsMissingColumn();
        missingIndexDegradesGracefully();
        interleavedEventsStayInOneSummary();
        overlappingCollectorsOverwriteRows();
        failedWriteCanRetry();
        failedDeleteStopsCollection();
        sqliteWritesReportSuccessAndFailure();

        System.out.println("UnitDailyCollectTest: " + assertions + " assertions passed");
    }

    /**
     * 事件表查询失败时不得写下标记行。
     */
    private static void queryFailureWritesNoMarker() throws Exception {
        Harness harness = new Harness("collect_fail_domain", null);
        try {
            // 事件表故意不存在：字段级查询失败时返回 null
            harness.collect();

            check(0 == harness.countMarkerRows(), "query failure: no marker row must be written");
            check(0 == harness.countDataRows(), "query failure: no data row must be written");
        }
        finally {
            harness.cleanup();
        }
    }

    /**
     * 当天确实没有任何单元事件时不写标记行。
     *
     * <p>这种情况下写标记行会让 console 把「没采到」当成「采到且为空」，
     * 掩盖采集侧的问题；不写标记行时 console 回落到明细路径，结果同样正确。</p>
     */
    private static void noEventWritesNoMarker() throws Exception {
        Harness harness = new Harness("collect_empty_domain", "contact_event_log_collect_empty_domain");
        try {
            harness.createEventTable();

            harness.collect();

            check(0 == harness.countMarkerRows(), "no event: no marker row must be written");
            check(0 == harness.countDataRows(), "no event: no data row must be written");
        }
        finally {
            harness.cleanup();
        }
    }

    /**
     * 正常采集：写下标记行，且标记行携带真实的数据行数。
     */
    private static void normalCollectWritesMarkerWithUnitCount() throws Exception {
        Harness harness = new Harness("collect_ok_domain", "contact_event_log_collect_ok_domain");
        try {
            harness.createEventTable();
            harness.insertEvent(100001L, "SignIn", harness.beginning + HOUR);
            harness.insertEvent(100001L, "SignOut", harness.beginning + 3L * HOUR);
            harness.insertEvent(100002L, "SignIn", harness.beginning + 2L * HOUR);

            harness.collect();

            check(1 == harness.countMarkerRows(), "normal collect: exactly one marker row");
            check(2 == harness.countDataRows(), "normal collect: two unit rows");

            StorageField[] marker = harness.readMarker();
            check(null != marker, "normal collect: marker row must be readable");
            if (null != marker) {
                check(2 == marker[0].getInt(), "normal collect: marker carries the data row count");
            }
        }
        finally {
            harness.cleanup();
        }
    }

    /**
     * 生产建表语句不得包含重复列。
     *
     * <p>字段声明里若重复声明了同一列，{@code CREATE TABLE} 会直接失败，
     * 汇总表根本建不出来。</p>
     */
    private static void createTableHasNoDuplicateColumn() throws Exception {
        Harness harness = new Harness("create_probe_domain", null);
        try {
            // 夹具的建表走生产路径，这里确认它确实建出来了
            check(harness.tableExists(), "create table: production path must create the table");

            java.util.Set<String> names = new java.util.HashSet<>();
            int duplicated = 0;
            for (StorageField field : harness.productionFields()) {
                if (!names.add(field.getName())) {
                    ++duplicated;
                }
            }
            check(0 == duplicated, "create table: field declaration must not repeat a column");
        }
        finally {
            harness.cleanup();
        }
    }

    /**
     * 老表缺少新增列时，启动自检要把它补上。
     *
     * <p>建表语句是 {@code CREATE TABLE IF NOT EXISTS}，对已存在的表既不校验结构也不补列；
     * 缺列时写入会整批失败，必须显式补齐。</p>
     */
    private static void legacyTableGetsMissingColumn() throws Exception {
        Harness harness = new Harness("legacy_probe_domain", null);
        try {
            // 模拟老表：只有建表时的原始列，没有后来新增的 unit_count
            harness.storage.execute("DROP TABLE IF EXISTS `" + harness.summaryTable + "`");
            harness.storage.execute("CREATE TABLE `" + harness.summaryTable + "` ("
                    + "`sn` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "`year` int NOT NULL,`month` int NOT NULL,`date` int NOT NULL,"
                    + "`contact_id` bigint NOT NULL,`event_count` int NOT NULL,"
                    + "`duration` bigint NOT NULL,`first_active` bigint NOT NULL,"
                    + "`hour_mask` bigint NOT NULL,`day_begin` bigint NOT NULL,"
                    + "`version` int NOT NULL)");
            check(!harness.hasColumn("unit_count"), "legacy table: unit_count must be absent initially");

            // 走服务端的补列入口
            harness.ensureColumns();

            check(harness.hasColumn("unit_count"), "legacy table: missing column must be added");
        }
        finally {
            harness.cleanup();
        }
    }

    /**
     * 事件表缺少索引提示指定的索引时，查询要能降级而不是整条失败。
     *
     * <p>{@code FORCE INDEX} 指向不存在的索引会直接报错让查询失败；
     * 优化器提示则被忽略、查询照常成功。老库的事件表可能没有该索引。</p>
     */
    private static void missingIndexDegradesGracefully() throws Exception {
        Harness harness = new Harness("noindex_probe_domain", "contact_event_log_noindex_probe_domain");
        try {
            harness.createEventTable();
            harness.insertEvent(100001L, "SignIn", harness.beginning + HOUR);
            harness.insertEvent(100001L, "SignOut", harness.beginning + 3L * HOUR);

            // 删掉索引提示里指定的那个：模拟老库事件表没有该索引
            harness.storage.execute("ALTER TABLE `" + harness.eventTable
                    + "` DROP INDEX `idx_contact_time`");

            // 走生产的采集路径：缺索引时查询必须降级成功，而不是整条失败。
            // 若用 FORCE INDEX，缺索引会直接报 1176，采集拿不到任何事件，
            // 标记行不会写出，采集结果为空。
            harness.collect();

            check(1 == harness.countMarkerRows(),
                    "missing index: production collect path must still complete the day");
            // 两条事件属于同一个单元，汇总后只有一行数据
            check(1 == harness.countDataRows(),
                    "missing index: production collect path must still read the events");
        }
        finally {
            harness.cleanup();
        }
    }

    /** 交错落库的事件按单元聚合，同毫秒事件以 sn 决定顺序。 */
    private static void interleavedEventsStayInOneSummary() throws Exception {
        Harness h = new Harness("collect_order_domain", "contact_event_log_collect_order_domain");
        try {
            h.createEventTable();
            h.insertEvent(100002L, "SignIn", h.beginning + 2L * HOUR);
            h.insertEvent(100001L, "SignIn", h.beginning + HOUR);
            h.insertEvent(100002L, "SignOut", h.beginning + 4L * HOUR);
            h.insertEvent(100001L, "SignOut", h.beginning + 3L * HOUR);
            h.insertEvent(100001L, "SignIn", h.beginning + 3L * HOUR);
            h.insertEvent(100001L, "SignOut", h.beginning + 5L * HOUR);
            h.collect();
            check(2 == h.countDataRows(), "interleaved events: exactly two unit rows");
            check(2 == h.readMarker()[0].getInt(), "interleaved events: marker counts units");
            StorageField[] first = h.readUnit(100001L);
            check(4 == first[0].getInt(), "interleaved events: all four events retained");
            check(4L * HOUR == first[1].getLong(), "interleaved events: both sessions retained");
            StorageField[] second = h.readUnit(100002L);
            check(2 == second[0].getInt() && 2L * HOUR == second[1].getLong(),
                    "interleaved events: second unit totals");
        }
        finally { h.cleanup(); }
    }

    /** 第二个采集器在第一个查询完成后抢先写入，数据行和标记都必须覆盖成功。 */
    private static void overlappingCollectorsOverwriteRows() throws Exception {
        final Harness h = new Harness("collect_overlap_domain", "contact_event_log_collect_overlap_domain");
        try {
            h.createEventTable();
            h.insertEvent(100001L, "SignIn", h.beginning + HOUR);
            h.insertEvent(100001L, "SignOut", h.beginning + 3L * HOUR);
            final boolean[] injected = {false};
            h.useStorage((Storage) Proxy.newProxyInstance(Storage.class.getClassLoader(),
                    new Class<?>[] {Storage.class}, (object, method, args) -> {
                        Object result = method.invoke(h.storage, args);
                        if (!injected[0] && "executeQuery".equals(method.getName())
                                && args.length == 1 && ((String) args[0]).contains("UNION ALL")) {
                            injected[0] = true;
                            StatisticsSystem other = new StatisticsSystem(null);
                            for (String name : new String[] {"storage", "unitDailyTableNameMap", "eventTableNameMap"}) {
                                Field field = StatisticsSystem.class.getDeclaredField(name);
                                field.setAccessible(true);
                                field.set(other, "storage".equals(name) ? h.storage : field.get(h.system));
                            }
                            Method collect = StatisticsSystem.class.getDeclaredMethod("collectUnitDaily",
                                    String.class, int.class, int.class, int.class, long.class, long.class);
                            collect.setAccessible(true);
                            collect.invoke(other, DOMAIN, h.year, h.month, h.date, h.beginning, h.ending);
                            // 改成旧值，证明随后发生的是覆盖写而非忽略重复键。
                            check(h.storage.executeUpdate("UPDATE " + h.summaryTable
                                    + " SET `event_count`=99,`duration`=-1,`first_active`=0,`hour_mask`=0,"
                                    + "`day_begin`=0,`version`=0,`unit_count`=99"), "overlap: seed stale rows");
                        }
                        return result;
                    }));
            h.collect();
            check(injected[0], "overlap: second collector ran between query and write");
            check(1 == h.countDataRows() && 1 == h.countMarkerRows(), "overlap: exactly one data row and marker");
            StorageField[] row = h.readUnit(100001L);
            check(2 == row[0].getInt() && 2L * HOUR == row[1].getLong(), "overlap: replace stale totals");
            check(h.beginning + HOUR == row[2].getLong(), "overlap: replace first activity");
            check(((1L << 1) | (1L << 3)) == row[3].getLong(), "overlap: replace hour mask");
            check(h.beginning == row[4].getLong() && UnitDayRecord.VERSION == row[5].getInt(),
                    "overlap: replace day boundary and version");
            check(1 == h.readMarker()[0].getInt(), "overlap: replace marker count");
            check(h.storage.executeUpdate("UPDATE " + h.summaryTable
                    + " SET `duration`=`duration` WHERE `contact_id`=-999"), "mysql: zero rows is success");
            h.collect();
            check(1 == h.countDataRows() && 1 == h.countMarkerRows(), "overlap: repeat collection is idempotent");
        }
        finally { h.cleanup(); }
    }

    /** 真实 SQL 写入失败不得留下完成标记，后续重试和旧版本重算可完成。 */
    private static void failedWriteCanRetry() throws Exception {
        final Harness h = new Harness("collect_writefail_domain", "contact_event_log_collect_writefail_domain");
        try {
            h.createEventTable();
            h.insertEvent(100001L, "SignIn", h.beginning + HOUR);
            h.useStorage((Storage) Proxy.newProxyInstance(Storage.class.getClassLoader(),
                    new Class<?>[] {Storage.class}, (object, method, args) -> {
                        if ("executeUpdate".equals(method.getName()) && args.length == 1
                                && ((String) args[0]).startsWith("INSERT")) {
                            return h.storage.executeUpdate("INSERT INTO `missing_unit_daily_write_probe` VALUES (1)");
                        }
                        return method.invoke(h.storage, args);
                    }));
            h.collect();
            check(0 == h.countMarkerRows(), "failed write: no completion marker");
            h.useStorage(h.storage);
            h.collect();
            check(1 == h.countDataRows() && 1 == h.countMarkerRows(), "failed write: retry completes");
            check(h.storage.executeUpdate("UPDATE " + h.summaryTable + " SET `version`=0"), "old version: seed old day");
            h.collect();
            check(UnitDayRecord.VERSION == h.readUnit(100001L)[5].getInt(), "old version: completed day rebuilt");
        }
        finally { h.cleanup(); }
    }

    /** 删除失败时禁止继续写入，不把旧数据标记成采集成功。 */
    private static void failedDeleteStopsCollection() throws Exception {
        final Harness h = new Harness("collect_deletefail_domain", "contact_event_log_collect_deletefail_domain");
        try {
            h.createEventTable();
            h.insertEvent(100001L, "SignIn", h.beginning + HOUR);
            h.collect();
            h.storage.executeUpdate("UPDATE " + h.summaryTable + " SET `version`=0,`unit_count`=99");
            final int[] writes = {0};
            h.useStorage((Storage) Proxy.newProxyInstance(Storage.class.getClassLoader(),
                    new Class<?>[] {Storage.class}, (object, method, args) -> {
                        if ("executeUpdate".equals(method.getName()) && args.length == 1) {
                            String sql = (String) args[0];
                            if (sql.startsWith("DELETE")) return false;
                            if (sql.startsWith("INSERT")) ++writes[0];
                        }
                        return method.invoke(h.storage, args);
                    }));
            h.collect();
            check(0 == writes[0], "failed delete: no new writes");
            check(99 == h.readMarker()[0].getInt(), "failed delete: old marker left unchanged");
        }
        finally { h.cleanup(); }
    }

    /** SQLite 的覆盖写和写入状态与 MySQL 保持一致。 */
    private static void sqliteWritesReportSuccessAndFailure() throws Exception {
        JSONObject config = new JSONObject();
        config.put("file", ":memory:");
        Storage storage = StorageFactory.getInstance().createStorage(StorageType.SQLite, "UnitDailySQLiteTest", config);
        try {
            check(!storage.executeUpdate("DELETE FROM probe"), "sqlite: unopened storage fails");
            storage.open();
            check(storage.executeUpdate("CREATE TABLE probe (`contact_id` INTEGER PRIMARY KEY,`duration` INTEGER)"),
                    "sqlite: create probe");
            StatisticsSystem system = new StatisticsSystem(null);
            Field field = StatisticsSystem.class.getDeclaredField("storage");
            field.setAccessible(true);
            field.set(system, storage);
            Method write = StatisticsSystem.class.getDeclaredMethod("writeUnitDaily", String.class, StorageField[].class);
            write.setAccessible(true);
            for (long duration : new long[] {1L, 2L, 2L}) {
                check((Boolean) write.invoke(system, "probe", new StorageField[] {
                        new StorageField("contact_id", LiteralBase.LONG, 100001L),
                        new StorageField("duration", LiteralBase.LONG, duration)
                }), "sqlite: insert, replace and unchanged replace succeed");
            }
            List<StorageField[]> rows = storage.executeQuery("SELECT `duration` FROM probe");
            check(1 == rows.size() && 2L == rows.get(0)[0].getLong(), "sqlite: exactly one updated row");
            check(storage.executeUpdate("DELETE FROM probe WHERE `contact_id`=-1"), "sqlite: zero rows is success");
            check(!storage.executeUpdate("INSERT INTO missing_probe VALUES (1)"), "sqlite: invalid write fails");

            // 还要通过完整采集路径验证 UNION 最终排序在 SQLite 上可执行。
            Field fields = StatisticsSystem.class.getDeclaredField("unitDailyTableFields");
            fields.setAccessible(true);
            check(storage.executeCreate("summary", (StorageField[]) fields.get(system)), "sqlite: create summary");
            check(storage.executeUpdate("CREATE UNIQUE INDEX uk_summary ON summary (`year`,`month`,`date`,`contact_id`)"),
                    "sqlite: create unique key");
            check(storage.executeUpdate("CREATE TABLE events (`sn` INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "`contact_id` INTEGER,`event` TEXT,`time` INTEGER)"), "sqlite: create event table");
            Field unitMap = StatisticsSystem.class.getDeclaredField("unitDailyTableNameMap");
            unitMap.setAccessible(true);
            Map<String, String> summaryMap = new HashMap<>();
            summaryMap.put(DOMAIN, "summary");
            unitMap.set(system, summaryMap);
            Field eventMap = StatisticsSystem.class.getDeclaredField("eventTableNameMap");
            eventMap.setAccessible(true);
            Map<String, String> events = new HashMap<>();
            events.put(DOMAIN, "events");
            eventMap.set(system, events);
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            long begin = day.getTimeInMillis();
            check(storage.executeUpdate("INSERT INTO events (`contact_id`,`event`,`time`) VALUES "
                    + "(100002,'SignIn'," + (begin + HOUR) + "),"
                    + "(100001,'SignIn'," + (begin + HOUR) + "),"
                    + "(100002,'SignOut'," + (begin + 3L * HOUR) + "),"
                    + "(100001,'SignOut'," + (begin + 3L * HOUR) + ")"), "sqlite: insert interleaved events");
            Method collect = StatisticsSystem.class.getDeclaredMethod("collectUnitDaily",
                    String.class, int.class, int.class, int.class, long.class, long.class);
            collect.setAccessible(true);
            collect.invoke(system, DOMAIN, day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1,
                    day.get(Calendar.DATE), begin, begin + 24L * HOUR);
            rows = storage.executeQuery("SELECT `contact_id`,`event_count`,`duration`,`unit_count`"
                    + " FROM summary ORDER BY `contact_id`");
            check(3 == rows.size() && 2 == rows.get(0)[3].getInt(), "sqlite: two summaries and completion marker");
            check(2 == rows.get(1)[1].getInt() && 2L * HOUR == rows.get(1)[2].getLong()
                    && 2 == rows.get(2)[1].getInt() && 2L * HOUR == rows.get(2)[2].getLong(),
                    "sqlite: both units retain complete sessions");
        }
        finally { storage.close(); }
        check(!storage.executeUpdate("DELETE FROM probe"), "sqlite: closed storage fails");
    }

    /**
     * 采集夹具：真实存储 + 反射调用被测方法。
     */
    private static class Harness {

        /**
         * 统计系统实例。
         */
        final StatisticsSystem system;

        /**
         * 存储。
         */
        final Storage storage;

        /**
         * 汇总表名。
         */
        final String summaryTable;

        /**
         * 事件表名。
         */
        final String eventTable;

        /**
         * 统计日起始时间戳。
         */
        final long beginning;

        /**
         * 统计日结束时间戳。
         */
        final long ending;

        /**
         * 测试用域名。
         */
        final String domain;

        /**
         * 统计日的年。
         */
        final int year;

        /**
         * 统计日的月。
         */
        final int month;

        /**
         * 统计日的日。
         */
        final int date;

        /**
         * 构造夹具。
         *
         * @param domain    测试用域名。
         * @param eventName 事件表名；传 {@code null} 表示事件表不存在。
         * @throws Exception 构造失败时抛出。
         */
        Harness(String domain, String eventName) throws Exception {
            this.eventTable = eventName;
            this.domain = domain;

            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            this.beginning = day.getTimeInMillis();
            this.ending = this.beginning + 24L * HOUR;
            this.year = day.get(Calendar.YEAR);
            this.month = day.get(Calendar.MONTH) + 1;
            this.date = day.get(Calendar.DATE);

            this.summaryTable = "contact_unit_daily_" + this.domain;

            JSONObject config = new JSONObject();
            config.put(StorageFactory.MYSQL_HOST, System.getProperty("test.db.host", "127.0.0.1"));
            config.put(StorageFactory.MYSQL_PORT, Integer.parseInt(
                    System.getProperty("test.db.port", "3306")));
            config.put(StorageFactory.MYSQL_SCHEMA, System.getProperty("test.db.schema", "cube_dev"));
            config.put(StorageFactory.MYSQL_USER, System.getProperty("test.db.user", "root"));
            config.put(StorageFactory.MYSQL_PASSWORD, System.getProperty("test.db.password", ""));

            this.storage = StorageFactory.getInstance().createStorage(
                    StorageType.MySQL, "UnitDailyCollectTest", config);
            this.storage.open();

            this.system = new StatisticsSystem((java.util.concurrent.ExecutorService) null);
            Field storageField = StatisticsSystem.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            storageField.set(this.system, this.storage);

            Map<String, String> unitMap = new HashMap<>();
            unitMap.put(DOMAIN, this.summaryTable);
            Field unitField = StatisticsSystem.class.getDeclaredField("unitDailyTableNameMap");
            unitField.setAccessible(true);
            unitField.set(this.system, unitMap);

            Map<String, String> eventMap = new HashMap<>();
            eventMap.put(DOMAIN, this.eventTable);
            Field eventField = StatisticsSystem.class.getDeclaredField("eventTableNameMap");
            eventField.setAccessible(true);
            eventField.set(this.system, eventMap);

            this.createSummaryTable();
        }

        /**
         * 建立汇总表。
         *
         * <p>刻意走生产同一条建表路径（{@code executeCreate} + 服务端的字段声明），
         * 而不是手工拼 SQL：手工建表会绕开字段声明，重复列声明之类的缺陷就测不出来。</p>
         */
        void createSummaryTable() throws Exception {
            this.storage.execute("DROP TABLE IF EXISTS `" + this.summaryTable + "`");
            if (!this.storage.executeCreate(this.summaryTable, this.productionFields())) {
                throw new IllegalStateException("Failed to create summary table: " + this.summaryTable);
            }
            Method method = StatisticsSystem.class.getDeclaredMethod("ensureUnitDailyUniqueKey", String.class);
            method.setAccessible(true);
            method.invoke(this.system, this.summaryTable);
        }

        /** 注入故障或并发调度代理，断言仍使用真实存储。 */
        void useStorage(Storage storage) throws Exception {
            Field field = StatisticsSystem.class.getDeclaredField("storage");
            field.setAccessible(true);
            field.set(this.system, storage);
        }

        StorageField[] readUnit(long contactId) {
            List<StorageField[]> rows = this.storage.executeQuery("SELECT `event_count`,`duration`,"
                    + "`first_active`,`hour_mask`,`day_begin`,`version` FROM " + this.summaryTable
                    + " WHERE `contact_id`=" + contactId);
            check(null != rows && rows.size() == 1, "unit summary: exactly one row for " + contactId);
            return rows.get(0);
        }

        /**
         * 读取服务端声明的汇总表字段。
         *
         * @return 返回字段定义。
         * @throws Exception 反射读取失败时抛出。
         */
        private StorageField[] productionFields() throws Exception {
            Field field = StatisticsSystem.class.getDeclaredField("unitDailyTableFields");
            field.setAccessible(true);
            return (StorageField[]) field.get(this.system);
        }

        /**
         * 建立事件表。
         */
        void createEventTable() {
            this.storage.execute("DROP TABLE IF EXISTS `" + this.eventTable + "`");
            this.storage.execute("CREATE TABLE `" + this.eventTable + "` ("
                    + "`sn` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "`event` text NOT NULL,`contact_id` bigint NOT NULL,"
                    + "`time` bigint NOT NULL,`event_data` text,"
                    + "KEY `idx_contact_time` (`contact_id`,`time`))");
        }

        /**
         * 插入一条事件。
         *
         * @param contactId 联系人 ID。
         * @param event     事件名。
         * @param time      时间戳。
         */
        void insertEvent(long contactId, String event, long time) {
            this.storage.execute("INSERT INTO `" + this.eventTable
                    + "` (`event`,`contact_id`,`time`) VALUES ('" + event + "'," + contactId + "," + time + ")");
        }

        /**
         * 调用被测的采集方法。
         */
        void collect() throws Exception {
            Method method = StatisticsSystem.class.getDeclaredMethod("collectUnitDaily",
                    String.class, int.class, int.class, int.class, long.class, long.class);
            method.setAccessible(true);
            method.invoke(this.system, DOMAIN, this.year, this.month, this.date,
                    this.beginning, this.ending);
        }

        /**
         * 统计标记行数。
         *
         * @return 返回行数。
         */
        int countMarkerRows() {
            return this.countRows(0L, true);
        }

        /**
         * 统计数据行数。
         *
         * @return 返回行数。
         */
        int countDataRows() {
            return this.countRows(Contact.MIN_UNIT_CONTACT_ID, false);
        }

        /**
         * 按联系人 ID 统计行数。
         *
         * @param contactId 联系人 ID 。
         * @param exact     为 {@code true} 时精确匹配，否则匹配单元区间。
         * @return 返回行数。
         */
        private int countRows(long contactId, boolean exact) {
            Conditional[] conditionals;
            if (exact) {
                conditionals = new Conditional[] {
                        Conditional.createEqualTo("contact_id", contactId),
                        Conditional.createAnd(),
                        Conditional.createEqualTo("year", this.year),
                        Conditional.createAnd(),
                        Conditional.createEqualTo("month", this.month),
                        Conditional.createAnd(),
                        Conditional.createEqualTo("date", this.date)
                };
            }
            else {
                conditionals = new Conditional[] {
                        Conditional.createGreaterThanEqual(
                                new StorageField("contact_id", contactId)),
                        Conditional.createAnd(),
                        Conditional.createLessThan(
                                new StorageField("contact_id", Contact.MIN_USER_CONTACT_ID)),
                        Conditional.createAnd(),
                        Conditional.createEqualTo("year", this.year),
                        Conditional.createAnd(),
                        Conditional.createEqualTo("month", this.month),
                        Conditional.createAnd(),
                        Conditional.createEqualTo("date", this.date)
                };
            }

            List<StorageField[]> rows = this.storage.executeQuery(this.summaryTable,
                    new StorageField[] {new StorageField("contact_id", LiteralBase.LONG)},
                    conditionals);
            return (null == rows) ? -1 : rows.size();
        }

        /**
         * 读取标记行的数据行数列。
         *
         * @return 返回该列；读不到时返回 {@code null} 。
         */
        StorageField[] readMarker() {
            List<StorageField[]> rows = this.storage.executeQuery(this.summaryTable,
                    new StorageField[] {new StorageField("unit_count", LiteralBase.INT)},
                    new Conditional[] {
                            Conditional.createEqualTo("contact_id", 0L),
                            Conditional.createAnd(),
                            Conditional.createEqualTo("year", this.year),
                            Conditional.createAnd(),
                            Conditional.createEqualTo("month", this.month),
                            Conditional.createAnd(),
                            Conditional.createEqualTo("date", this.date)
                    });
            if (null == rows || rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        }

        /**
         * 汇总表是否存在。
         *
         * @return 存在返回 {@code true} 。
         */
        boolean tableExists() {
            return this.storage.exist(this.summaryTable);
        }

        /**
         * 汇总表是否含有指定列。
         *
         * @param column 列名。
         * @return 存在返回 {@code true} 。
         */
        boolean hasColumn(String column) {
            List<StorageField[]> rows = this.storage.executeQuery(
                    "SELECT COUNT(*) FROM `information_schema`.`columns`"
                    + " WHERE `table_schema`=DATABASE()"
                    + " AND `table_name`='" + this.summaryTable + "'"
                    + " AND `column_name`='" + column + "'");
            if (null == rows || rows.isEmpty()) {
                return false;
            }
            return !rows.get(0)[0].isNullValue() && rows.get(0)[0].getLong() > 0L;
        }

        /**
         * 调用服务端的补列入口。
         *
         * @throws Exception 反射调用失败时抛出。
         */
        void ensureColumns() throws Exception {
            Method method = StatisticsSystem.class.getDeclaredMethod(
                    "ensureUnitDailyColumns", String.class);
            method.setAccessible(true);
            method.invoke(this.system, this.summaryTable);
        }

        /**
         * 清理测试表。
         */
        void cleanup() {
            this.storage.execute("DROP TABLE IF EXISTS `" + this.summaryTable + "`");
            if (null != this.eventTable) {
                this.storage.execute("DROP TABLE IF EXISTS `" + this.eventTable + "`");
            }
            this.storage.close();
        }
    }

    /**
     * 断言。
     *
     * @param condition 条件。
     * @param message   失败信息。
     */
    private static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
