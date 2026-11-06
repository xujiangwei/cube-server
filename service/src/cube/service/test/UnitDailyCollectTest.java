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
import cell.core.talk.LiteralBase;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * <p>用例覆盖三种情形：查询失败不写标记行、当天无事件不写标记行、
 * 正常采集写标记行且携带数据行数。</p>
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
