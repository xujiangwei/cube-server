/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.storage;

import cube.common.entity.Contact;
import cube.core.Storage;
import cube.core.StorageField;
import cube.storage.StorageFactory;
import cube.storage.StorageType;
import cube.util.SQLUtils;
import cube.util.UnitDayRecord;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 联系人数据存储器。
 */
public class DataStorage {

    // Total number of users
    public final String ITEM_TNU = "TNU";

    // Daily Active User
    public final String ITEM_DAU = "DAU";

    // Average online time
    public final String ITEM_AOT = "AOT";

    // 当日活跃用户在线总时长（毫秒）
    public final String ITEM_AOT_TOTAL = "AOTT";

    // 当日参与 AOT 计算的活跃用户数
    public final String ITEM_AOT_USERS = "AOTU";

    // Time Distribution
    public final String ITEM_TD = "TD";

    /**
     * 历史数据兼容阈值。
     * <p>
     * 统计口径 1 的 AOT 以毫秒入库，口径 2 起改为小时。日均在线时长不可能超过该阈值，
     * 因此把大于该阈值的值按毫秒换算为小时。
     */
    private static final double LEGACY_AOT_MILLIS_THRESHOLD = 1000.0;

    private final String contactStatisticsTablePrefix = "contact_statistics_";

    /**
     * 工作单元日汇总表前缀，与 service 侧采集写入的表同名。
     */
    private final String contactUnitDailyTablePrefix = "contact_unit_daily_";

    private final String contactEventTablePrefix = "contact_event_log_";

    private final String contactTablePrefix = "contact_";

    private Storage authStorage;

    private Storage statisticStorage;

    public DataStorage(Properties properties) {
        JSONObject config = new JSONObject();
        config.put(StorageFactory.MYSQL_HOST, properties.getProperty("auth.mysql.host"));
        config.put(StorageFactory.MYSQL_PORT, properties.getProperty("auth.mysql.port"));
        config.put(StorageFactory.MYSQL_SCHEMA, properties.getProperty("auth.mysql.schema"));
        config.put(StorageFactory.MYSQL_USER, properties.getProperty("auth.mysql.user"));
        config.put(StorageFactory.MYSQL_PASSWORD, properties.getProperty("auth.mysql.password"));
        this.authStorage = StorageFactory.getInstance().createStorage(StorageType.MySQL, "authData", config);

        config = new JSONObject();
        config.put(StorageFactory.MYSQL_HOST, properties.getProperty("statistic.mysql.host"));
        config.put(StorageFactory.MYSQL_PORT, properties.getProperty("statistic.mysql.port"));
        config.put(StorageFactory.MYSQL_SCHEMA, properties.getProperty("statistic.mysql.schema"));
        config.put(StorageFactory.MYSQL_USER, properties.getProperty("statistic.mysql.user"));
        config.put(StorageFactory.MYSQL_PASSWORD, properties.getProperty("statistic.mysql.password"));
        this.statisticStorage = StorageFactory.getInstance().createStorage(StorageType.MySQL, "StatisticData", config);
    }

    public void open() {
        this.authStorage.open();
        this.statisticStorage.open();
    }

    public void close() {
        this.authStorage.close();
        this.statisticStorage.close();
    }

    public String queryContactName(String domain, long contactId) {
        try {
            String table = SQLUtils.correctTableName(this.contactTablePrefix + domain);
            List<StorageField[]> rows = this.authStorage.executeQuery("SELECT `name` FROM " + table + " WHERE `id`=" + contactId);
            return rows == null || rows.isEmpty() || rows.get(0)[0].isNullValue() ? "" : rows.get(0)[0].getString();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    /**
     * 读取全部域。
     *
     * @return 返回域列表；查询失败时返回 {@code null} 。
     */
    public List<String> queryAllDomains() {
        ArrayList<String> list = new ArrayList<>();

        List<StorageField[]> result = this.authStorage.executeQuery("SELECT DISTINCT `domain` FROM `auth_domain`");
        if (null == result) {
            // 查询失败：与「没有域」是不同的结果，调用方据此判断数据是否可信
            return null;
        }

        for (StorageField[] row : result) {
            list.add(row[0].getString());
        }

        return list;
    }

    public JSONObject queryContactStatistics(String domain, int year, int month, int date) {
        String table = this.contactStatisticsTablePrefix + domain;
        table = SQLUtils.correctTableName(table);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `item`,`data` FROM ").append(table);
        sql.append(" WHERE `year`=").append(year);
        sql.append(" AND `month`=").append(month);
        sql.append(" AND `date`=").append(date);

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
        if (null == result || result.isEmpty()) {
            return null;
        }

        JSONObject json = new JSONObject();

        for (StorageField[] row : result) {
            parseItem(json, row[0].getString(), row[1].getString());
        }

        return json;
    }

    /**
     * 查询指定域在日期区间内的逐日统计。
     *
     * @param domain  指定域。
     * @param fromYmd 起始日期，格式为 <code>yyyyMMdd</code> 的整数。
     * @param toYmd   结束日期，格式为 <code>yyyyMMdd</code> 的整数。
     * @return 返回按日期升序的列表，每项包含 <code>year</code>、<code>month</code>、<code>date</code>、
     * <code>statistic</code>；无数据时返回空列表。
     */
    public List<JSONObject> queryContactStatisticsRange(String domain, int fromYmd, int toYmd) {
        ArrayList<JSONObject> list = new ArrayList<>();

        String table = this.contactStatisticsTablePrefix + domain;
        table = SQLUtils.correctTableName(table);

        // 统计表每域每年不足两千行，用日期表达式过滤即可，不值得为区间查询再建联合索引
        StringBuilder sql = new StringBuilder("SELECT `year`,`month`,`date`,`item`,`data` FROM ");
        sql.append(table);
        sql.append(" WHERE (`year`*10000+`month`*100+`date`)>=").append(fromYmd);
        sql.append(" AND (`year`*10000+`month`*100+`date`)<=").append(toYmd);
        sql.append(" ORDER BY `year`,`month`,`date`");

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
        if (null == result || result.isEmpty()) {
            return list;
        }

        JSONObject current = null;
        int currentYear = -1;
        int currentMonth = -1;
        int currentDate = -1;

        for (StorageField[] row : result) {
            int year = row[0].getInt();
            int month = row[1].getInt();
            int date = row[2].getInt();

            if (null == current || year != currentYear || month != currentMonth || date != currentDate) {
                current = new JSONObject();
                current.put("year", year);
                current.put("month", month);
                current.put("date", date);
                current.put("statistic", new JSONObject());
                list.add(current);

                currentYear = year;
                currentMonth = month;
                currentDate = date;
            }

            parseItem(current.getJSONObject("statistic"), row[3].getString(), row[4].getString());
        }

        return list;
    }

    /**
     * 统计区间内去重后的活跃用户数。
     *
     * <p>周/月活跃人数不能由日活跃数相加得到（同一用户会被重复计数），必须回到事件明细去重。
     * 明细只保留有限天数，超出后返回 -1 表示不可用。</p>
     *
     * @param domain    指定域。
     * @param beginning 起始时间戳（含）。
     * @param ending    结束时间戳（不含）。
     * @return 返回去重活跃用户数；明细不可用时返回 -1 。
     */
    public int countDistinctActiveUsers(String domain, long beginning, long ending) {
        String table = SQLUtils.correctTableName(this.contactEventTablePrefix + domain);

        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT `contact_id`) FROM ");
        sql.append(table);
        sql.append(" WHERE `event`='SignIn'");
        sql.append(" AND `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        sql.append(" AND `contact_id`>=").append(Contact.MIN_USER_CONTACT_ID);

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
        if (null == result || result.isEmpty()) {
            return -1;
        }

        return result.get(0)[0].isNullValue() ? 0 : result.get(0)[0].getInt();
    }

    /**
     * 解析一条统计指标到目标结构。
     *
     * @param json 目标结构。
     * @param item 指标名。
     * @param data 指标值。
     */
    private void parseItem(JSONObject json, String item, String data) {
        if (item.equals(ITEM_TNU) || item.equals(ITEM_DAU) || item.equals(ITEM_AOT_USERS)) {
            json.put(item, Integer.parseInt(data));
        } else if (item.equals(ITEM_AOT)) {
            // 单位：小时
            double hours = Double.parseDouble(data);
            if (hours > LEGACY_AOT_MILLIS_THRESHOLD) {
                // 兼容旧口径（毫秒）数据
                hours = hours / (60.0 * 60.0 * 1000.0);
            }
            json.put(item, Math.round(hours * 100) / 100.0);
        } else if (item.equals(ITEM_AOT_TOTAL)) {
            json.put(item, Long.parseLong(data));
        } else if (item.equals(ITEM_TD)) {
            json.put(item, new JSONArray(data));
        }
    }

    /**
     * 查询指定域在工作单元维度上的日汇总。
     *
     * <p>数据由 service 侧的日统计采集写入：每行是一个工作单元在一个统计日的活动汇总，
     * 另有一行标记（{@code contact_id} 为 0）表示该日已完整采集。</p>
     *
     * <p>读到的汇总只在「该日已完整采集」「汇总口径版本匹配」「日界口径一致」三者
     * 同时成立时才可用：任一条件不满足都返回 {@code null}，由调用方回落到明细路径。
     * 日界口径用采集时记录的日起始时间戳比对，避免两端时区不同导致静默算错。</p>
     *
     * @param domain    指定域。
     * @param year      统计日的年。
     * @param month     统计日的月。
     * @param date      统计日的日。
     * @param dayBegin  统计日起始时间戳，用于校验日界口径。
     * @return 返回该日的单元汇总；不可用时返回 {@code null} 。
     */
    public UnitDailySummary queryUnitDailySummary(String domain, int year, int month, int date,
                                                    long dayBegin) {
        String table = SQLUtils.correctTableName(this.contactUnitDailyTablePrefix + domain);

        StringBuilder sql = new StringBuilder(
                "SELECT `contact_id`,`event_count`,`duration`,`first_active`,`hour_mask`,");
        sql.append("`day_begin`,`version`,`unit_count` FROM ");
        sql.append(table);
        sql.append(" WHERE `year`=").append(year);
        sql.append(" AND `month`=").append(month);
        sql.append(" AND `date`=").append(date);

        List<StorageField[]> rows = this.statisticStorage.executeQuery(sql.toString());
        if (null == rows) {
            // 查询失败：与「该日没有汇总数据」是不同的情况，必须单独计数
            return UnitDailySummary.unavailable(UnitDailySummary.REASON_QUERY_FAILED);
        }

        if (rows.isEmpty()) {
            return UnitDailySummary.unavailable(UnitDailySummary.REASON_NO_DATA);
        }

        boolean complete = false;
        long markerDayBegin = 0L;
        int markerVersion = 0;
        int markerUnitCount = 0;
        ArrayList<UnitDayRecord> list = new ArrayList<>();

        for (StorageField[] row : rows) {
            long contactId = row[0].getLong();
            long rowDayBegin = row[5].getLong();
            int rowVersion = row[6].getInt();

            if (0L == contactId) {
                // 标记行：该日已完整采集
                complete = true;
                markerDayBegin = rowDayBegin;
                markerVersion = rowVersion;
                markerUnitCount = (row.length > 7) ? row[7].getInt() : 0;
                continue;
            }

            list.add(new UnitDayRecord(contactId, row[1].getInt(), row[2].getLong(),
                    row[3].getLong(), row[4].getLong()));
        }

        if (!complete) {
            // 该日尚未完整采集：回落到明细路径
            return UnitDailySummary.unavailable(UnitDailySummary.REASON_NOT_COLLECTED);
        }

        if (markerVersion != UnitDayRecord.VERSION) {
            // 汇总口径与服务端不一致：旧口径的汇总不能当作新口径的结果
            return UnitDailySummary.unavailable(UnitDailySummary.REASON_VERSION_MISMATCH);
        }

        if (markerDayBegin != dayBegin) {
            // 日界口径不一致（例如两端时区不同）：回落到明细路径，避免静默算错
            return UnitDailySummary.unavailable(UnitDailySummary.REASON_DAY_BOUNDARY_MISMATCH);
        }

        if (markerUnitCount != list.size()) {
            // 行数与标记不符：说明数据行不完整（多节点交错采集时可能出现），
            // 宁可回落到明细路径，也不能用残缺的汇总当完整结果
            return UnitDailySummary.unavailable(UnitDailySummary.REASON_INCOMPLETE);
        }

        return UnitDailySummary.available(list);
    }

    /**
     * 单元日汇总的查询结果。
     *
     * <p>把「汇总不可用」的原因也带出来：调用方据此打日志并计数，
     * 否则回落是静默的，线上无法判断汇总路径是否真的在生效。</p>
     */
    public static class UnitDailySummary {

        /**
         * 不可用原因：该日还没有任何汇总数据。
         */
        public static final String REASON_NO_DATA = "noData";

        /**
         * 不可用原因：查询汇总表失败（数据库不可用）。
         * <p>
         * 与 {@link #REASON_NO_DATA} 分开：查询失败是故障，需要人工介入；
         * 没有数据是正常状态（例如该日尚未采集）。
         */
        public static final String REASON_QUERY_FAILED = "queryFailed";

        /**
         * 不可用原因：该日尚未采集完成（缺少标记行）。
         */
        public static final String REASON_NOT_COLLECTED = "notCollected";

        /**
         * 不可用原因：汇总口径版本与当前不一致。
         */
        public static final String REASON_VERSION_MISMATCH = "versionMismatch";

        /**
         * 不可用原因：日界口径与当前不一致。
         */
        public static final String REASON_DAY_BOUNDARY_MISMATCH = "dayBoundaryMismatch";

        /**
         * 不可用原因：数据行数与标记不符，数据不完整。
         */
        public static final String REASON_INCOMPLETE = "incomplete";

        /**
         * 汇总是否可用。
         */
        public final boolean available;

        /**
         * 不可用原因；可用时为空串。
         */
        public final String reason;

        /**
         * 汇总行；不可用时为空列表。
         */
        public final List<UnitDayRecord> records;

        private UnitDailySummary(boolean available, String reason, List<UnitDayRecord> records) {
            this.available = available;
            this.reason = reason;
            this.records = records;
        }

        /**
         * 构造可用结果。
         *
         * @param records 汇总行。
         * @return 返回结果。
         */
        public static UnitDailySummary available(List<UnitDayRecord> records) {
            return new UnitDailySummary(true, "", records);
        }

        /**
         * 构造不可用结果。
         *
         * @param reason 不可用原因。
         * @return 返回结果。
         */
        public static UnitDailySummary unavailable(String reason) {
            return new UnitDailySummary(false, reason, new ArrayList<UnitDayRecord>());
        }
    }

    /**
     * 查询指定域里的 AIGC 工作单元联系人。
     * <p>
     * 工作单元即 ID 十进制位数大于等于 6 且小于 8 位的联系人（例如 <code>Unit-531001</code>），
     * 位数小于 6 位的联系人不是统计对象。
     *
     * @param domain      指定域。
     * @param maxContacts 最多返回的单元数；不大于 0 表示不限。超限时多取一行，由调用方判定并拒绝。
     * @return 返回单元列表，每项包含 <code>id</code>、<code>name</code>、<code>device</code>、<code>platform</code>；
     * 查询失败时返回 {@code null} 。
     */
    public List<JSONObject> queryUnitContacts(String domain, int maxContacts) {
        ArrayList<JSONObject> list = new ArrayList<>();

        String table = SQLUtils.correctTableName(this.contactTablePrefix + domain);

        StringBuilder sql = new StringBuilder(
                "SELECT `id`,`name`,`recent_device_name`,`recent_device_platform` FROM ");
        sql.append(table);
        sql.append(" WHERE `id`>=").append(Contact.MIN_UNIT_CONTACT_ID);
        sql.append(" AND `id`<").append(Contact.MIN_USER_CONTACT_ID);
        // 按主键升序取，可走主键索引的范围扫描，无需额外排序
        sql.append(" ORDER BY `id`");
        if (maxContacts > 0) {
            // 多取一行用于判断是否超限：超限时由调用方拒绝，而不是静默截断
            sql.append(" LIMIT ").append(maxContacts + 1);
        }

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
        if (null == result) {
            // 查询失败：不能当成「该域没有单元」，否则空台账会被缓存下来持续对外提供
            return null;
        }

        for (StorageField[] row : result) {
            JSONObject json = new JSONObject();
            json.put("id", row[0].getLong());
            json.put("name", row[1].isNullValue() ? "" : row[1].getString());
            json.put("device", (row.length > 2 && !row[2].isNullValue()) ? row[2].getString() : "");
            json.put("platform", (row.length > 3 && !row[3].isNullValue()) ? row[3].getString() : "");
            list.add(json);
        }

        return list;
    }

    /**
     * 查询指定域里联系人的活动事件。
     *
     * @param domain    指定域。
     * @param beginning 起始时间戳（含）。
     * @param ending    结束时间戳（不含）。
     * @param unitOnly  为 <code>true</code> 时只查询 AIGC 工作单元的事件，否则只查询用户的事件。
     * @return 返回按时间升序排列的事件列表；查询失败时返回 {@code null} 。
     */
    public List<ContactEvent> queryContactEvents(String domain, long beginning, long ending, boolean unitOnly) {
        ArrayList<ContactEvent> list = new ArrayList<>();

        String table = SQLUtils.correctTableName(this.contactEventTablePrefix + domain);

        // 用索引提示固定走 idx_contact_time：InnoDB 的二级索引隐含主键列，该索引的
        // 物理顺序是 (contact_id, time, sn)，正好覆盖下面的排序要求，无需额外排序；
        // 不指定时优化器会偏向按 time 过滤的索引，反而多出一次 filesort。
        //
        // 用优化器提示而不是 FORCE INDEX：提示在索引缺失时被忽略并只记告警，查询照常
        // 成功；FORCE INDEX 指向不存在的索引会直接报 1176 让整条查询失败。控制台读的是
        // 统计库里的事件表，而索引由服务端创建，两端不保证是同一份表 —— 不能让索引的
        // 存在性成为查询的硬前提。
        StringBuilder sql = new StringBuilder("SELECT `contact_id`,`event`,`time` FROM ");
        sql.append(table);
        sql.append(" t /*+ INDEX(t idx_contact_time) */");
        sql.append(" WHERE `contact_id`>=");
        if (unitOnly) {
            sql.append(Contact.MIN_UNIT_CONTACT_ID);
            sql.append(" AND `contact_id`<").append(Contact.MIN_USER_CONTACT_ID);
        } else {
            sql.append(Contact.MIN_USER_CONTACT_ID);
        }
        sql.append(" AND `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        // 按 (contact_id, time, sn) 排序：末位的 sn 是事件表的自增主键，天然单调，
        // 同一毫秒内的多个事件顺序由此确定，不再依赖执行计划。service 侧采集汇总时
        // 用完全相同的排序，两条路径的会话配对结果因此完全一致。
        sql.append(" ORDER BY `contact_id`,`time`,`sn`");

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
        if (null == result) {
            // 查询失败：与「当天没有事件」是不同的结果，不能混为一谈
            return null;
        }

        for (StorageField[] row : result) {
            list.add(new ContactEvent(row[0].getLong(), row[1].getString(), row[2].getLong()));
        }

        return list;
    }

    /**
     * 查询指定域里工作单元在回溯窗口内的活跃情况。
     * <p>
     * 把「最近活动时间」与「活跃天数」在数据库里聚合完毕，不再把窗口内的全部事件拉回应用层再归集：
     * 事件行数远大于单元行数，下推后回源的数据量降一个数量级。
     * </p>
     * <p>
     * 天分桶使用调用方给定的时区偏移，而不是数据库自身的时区 —— 数据库服务器时区与
     * 控制台进程时区未必一致，用服务器时区分日会与日历口径产生偏差。
     * </p>
     *
     * @param domain           指定域。
     * @param beginning        起始时间戳（含）。
     * @param ending           结束时间戳（不含）。
     * @param zoneOffsetMillis 时区偏移（毫秒），本地时区为 UTC+8 时取 8 小时。
     * @return 每个有活动的单元一行；无数据时返回空列表，查询失败时返回 {@code null} 。
     */
    public List<UnitTrail> queryUnitTrail(String domain, long beginning, long ending, long zoneOffsetMillis) {
        ArrayList<UnitTrail> list = new ArrayList<>();

        String table = SQLUtils.correctTableName(this.contactEventTablePrefix + domain);
        String sql = buildUnitTrailSql(table, beginning, ending, zoneOffsetMillis);

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql);
        if (null == result) {
            // 查询失败：与「窗口内没有活动」是不同的结果，不能混为一谈
            return null;
        }

        for (StorageField[] row : result) {
            list.add(new UnitTrail(row[0].getLong(), row[1].getLong(), row[2].getInt()));
        }

        return list;
    }

    /**
     * 构造回溯窗口的聚合查询语句。
     * <p>
     * 单独暴露是为了让验证用例能对真实数据库执行同一条语句，比对下推结果与日历口径是否一致。
     * </p>
     *
     * @param table            事件表名，已由 {@link SQLUtils#correctTableName(String)} 收敛。
     * @param beginning        起始时间戳（含）。
     * @param ending           结束时间戳（不含）。
     * @param zoneOffsetMillis 时区偏移（毫秒）。
     * @return 聚合查询语句。
     */
    public static String buildUnitTrailSql(String table, long beginning, long ending, long zoneOffsetMillis) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT `contact_id`,MAX(`time`) AS `last_active_time`,");
        // 天序号：把时间戳平移到本地时区后再整除一天，与本地日历的日期一一对应。
        // 用 DIV 整数除法而非 FLOOR(x/y)：后者在 MySQL 小数除法下会四舍五入，
        // 真实小数部分极接近 1 时会进位到下一个整数，把事件分到错误的天。
        sql.append("COUNT(DISTINCT (`time`+").append(zoneOffsetMillis).append(") DIV 86400000) AS `active_days`");
        sql.append(" FROM ").append(table);
        sql.append(" WHERE `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        sql.append(" AND `contact_id`>=").append(Contact.MIN_UNIT_CONTACT_ID);
        sql.append(" AND `contact_id`<").append(Contact.MIN_USER_CONTACT_ID);
        sql.append(" GROUP BY `contact_id`");
        return sql.toString();
    }

    /**
     * 单元在回溯窗口内的活跃情况。
     */
    public static class UnitTrail {

        /**
         * 单元 ID。
         */
        public final long contactId;

        /**
         * 最近活动时间。
         */
        public final long lastActiveTime;

        /**
         * 活跃天数。
         */
        public final int activeDays;

        public UnitTrail(long contactId, long lastActiveTime, int activeDays) {
            this.contactId = contactId;
            this.lastActiveTime = lastActiveTime;
            this.activeDays = activeDays;
        }
    }

    /**
     * 单元在统计日内的活动统计。
     *
     * <p>汇总表与明细路径共用的结果形态：两条路径产出的字段含义完全一致，
     * 因此上层无需关心数据来自哪一条路径。</p>
     */
    public static class UnitDayStat {

        /**
         * 统计日内的活动事件数。
         */
        public final int eventCount;

        /**
         * 统计日内的在线时长，单位：毫秒。
         */
        public final long durationMillis;

        /**
         * 统计日内的首次活动时间。
         */
        public final long firstActive;

        /**
         * 统计日内的活跃小时掩码：第 n 位为 1 表示该单元在第 n 小时有事件。
         */
        public final long hourMask;

        public UnitDayStat(int eventCount, long durationMillis, long firstActive, long hourMask) {
            this.eventCount = eventCount;
            this.durationMillis = durationMillis;
            this.firstActive = firstActive;
            this.hourMask = hourMask;
        }
    }

    /**
     * 联系人活动事件。
     */
    public static class ContactEvent {

        public final long contactId;

        public final String event;

        public final long time;

        ContactEvent(long contactId, String event, long time) {
            this.contactId = contactId;
            this.event = event;
            this.time = time;
        }
    }
}
