/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.storage;

import cube.core.Storage;
import cube.core.StorageField;
import cube.common.entity.Contact;
import cube.storage.StorageFactory;
import cube.storage.StorageType;
import cube.util.SQLUtils;
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
     *
     * 统计口径 1 的 AOT 以毫秒入库，口径 2 起改为小时。日均在线时长不可能超过该阈值，
     * 因此把大于该阈值的值按毫秒换算为小时。
     */
    private static final double LEGACY_AOT_MILLIS_THRESHOLD = 1000.0;

    private final String contactStatisticsTablePrefix = "contact_statistics_";

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

    public List<String> queryAllDomains() {
        ArrayList<String> list = new ArrayList<>();

        List<StorageField[]> result = this.authStorage.executeQuery("SELECT DISTINCT `domain` FROM `auth_domain`");

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
     * @param domain 指定域。
     * @param fromYmd 起始日期，格式为 <code>yyyyMMdd</code> 的整数。
     * @param toYmd 结束日期，格式为 <code>yyyyMMdd</code> 的整数。
     * @return 返回按日期升序的列表，每项包含 <code>year</code>、<code>month</code>、<code>date</code>、
     *         <code>statistic</code>；无数据时返回空列表。
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
     * @param domain 指定域。
     * @param beginning 起始时间戳（含）。
     * @param ending 结束时间戳（不含）。
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
        }
        else if (item.equals(ITEM_AOT)) {
            // 单位：小时
            double hours = Double.parseDouble(data);
            if (hours > LEGACY_AOT_MILLIS_THRESHOLD) {
                // 兼容旧口径（毫秒）数据
                hours = hours / (60.0 * 60.0 * 1000.0);
            }
            json.put(item, Math.round(hours * 100) / 100.0);
        }
        else if (item.equals(ITEM_AOT_TOTAL)) {
            json.put(item, Long.parseLong(data));
        }
        else if (item.equals(ITEM_TD)) {
            json.put(item, new JSONArray(data));
        }
    }

    /**
     * 查询指定域里的 AIGC 工作单元联系人。
     *
     * 工作单元即 ID 十进制位数大于等于 6 且小于 8 位的联系人（例如 <code>Unit-531001</code>），
     * 位数小于 6 位的联系人不是统计对象。
     *
     * @param domain 指定域。
     * @return 返回单元列表，每项包含 <code>id</code>、<code>name</code>、<code>device</code>、<code>platform</code>。
     */
    public List<JSONObject> queryUnitContacts(String domain) {
        ArrayList<JSONObject> list = new ArrayList<>();

        String table = SQLUtils.correctTableName(this.contactTablePrefix + domain);

        StringBuilder sql = new StringBuilder(
                "SELECT `id`,`name`,`recent_device_name`,`recent_device_platform` FROM ");
        sql.append(table);
        sql.append(" WHERE `id`>=").append(Contact.MIN_UNIT_CONTACT_ID);
        sql.append(" AND `id`<").append(Contact.MIN_USER_CONTACT_ID);
        sql.append(" ORDER BY `id`");

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
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
     * @param domain 指定域。
     * @param beginning 起始时间戳（含）。
     * @param ending 结束时间戳（不含）。
     * @param unitOnly 为 <code>true</code> 时只查询 AIGC 工作单元的事件，否则只查询用户的事件。
     * @return 返回按时间升序排列的事件列表。
     */
    public List<ContactEvent> queryContactEvents(String domain, long beginning, long ending, boolean unitOnly) {
        ArrayList<ContactEvent> list = new ArrayList<>();

        String table = SQLUtils.correctTableName(this.contactEventTablePrefix + domain);

        StringBuilder sql = new StringBuilder("SELECT `contact_id`,`event`,`time` FROM ");
        sql.append(table);
        sql.append(" WHERE `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        if (unitOnly) {
            sql.append(" AND `contact_id`>=").append(Contact.MIN_UNIT_CONTACT_ID);
            sql.append(" AND `contact_id`<").append(Contact.MIN_USER_CONTACT_ID);
        }
        else {
            sql.append(" AND `contact_id`>=").append(Contact.MIN_USER_CONTACT_ID);
        }
        sql.append(" ORDER BY `time`");

        List<StorageField[]> result = this.statisticStorage.executeQuery(sql.toString());
        for (StorageField[] row : result) {
            list.add(new ContactEvent(row[0].getLong(), row[1].getString(), row[2].getLong()));
        }

        return list;
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
