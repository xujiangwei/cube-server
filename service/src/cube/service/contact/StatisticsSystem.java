/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.contact;

import cell.core.talk.LiteralBase;
import cell.util.Utils;
import cell.util.log.Logger;
import cube.common.entity.Contact;
import cube.common.entity.Device;
import cube.common.entity.TimeSlice;
import cube.core.Conditional;
import cube.core.Constraint;
import cube.core.Storage;
import cube.core.StorageField;
import cube.plugin.HookResult;
import cube.plugin.Plugin;
import cube.plugin.PluginContext;
import cube.plugin.PluginSystem;
import cube.storage.StorageFactory;
import cube.storage.StorageType;
import cube.util.SQLUtils;
import cube.util.UnitDayAggregator;
import cube.util.UnitDayRecord;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统计系统。
 */
public final class StatisticsSystem {

    // Total number of users
    public final String ITEM_TNU = "TNU";

    // Daily Active User
    public final String ITEM_DAU = "DAU";

    // Average online time
    public final String ITEM_AOT = "AOT";

    // 当日活跃用户的在线总时长（毫秒），与 AOT_USERS 一起构成 AOT 的分子分母
    public final String ITEM_AOT_TOTAL = "AOTT";

    // 当日参与 AOT 计算的活跃用户数
    public final String ITEM_AOT_USERS = "AOTU";

    // Time Distribution
    public final String ITEM_TD = "TD";

    // Statistics version：统计口径版本
    public final String ITEM_SV = "SV";

    /**
     * 当前统计口径版本。
     * <p>
     * 口径发生变化（例如调整用户判定规则、修改时长单位）时必须 <b>递增</b> 该值：
     * 每日统计会比对已入库的 {@link #ITEM_SV} 值，不一致时删除该日旧口径数据并重新统计，
     * 否则历史日期会一直保留旧口径的结果。
     *
     * <p>版本 3：日统计改为一次扫描聚合，并修正「已闭合会话后仍有未闭合会话时末段时长被丢弃」的缺陷。</p>
     *
     * <p>版本 4：日统计新增 {@link #ITEM_AOT_TOTAL} 与 {@link #ITEM_AOT_USERS}，使周/月/年
     * 的平均在线时长可以按「总时长 ÷ 总人数」加权，而不是对日均值做简单平均。</p>
     */
    public static final int STATISTICS_VERSION = 4;

    /**
     * 事件明细的保留天数。
     *
     * <p>明细是重算历史统计的唯一数据源，但它是高频写入且没有天然上限的表；保留过久会
     * 让存储和日统计扫描成本无限增长。90 天足以覆盖「当月 + 上一季度」的回溯需求，
     * 更早的周期趋势由日统计表承担。</p>
     */
    private static final int EVENT_RETENTION_DAYS = 90;

    /**
     * 清理事件明细时单条 DELETE 的行数上限，避免长事务长时间持锁。
     */
    private static final int EVENT_CLEANUP_BATCH = 5000;

    /**
     * 单次清理最多执行的批次数，防止首次上线时一次性删除过多历史行。
     */
    private static final int EVENT_CLEANUP_MAX_BATCH = 40;

    /**
     * 事件表索引：按时间范围读取当日事件，contact_id 作为索引列支撑去重计数。
     *
     * <p>不把 {@code event} 放进索引：该列建表类型是 TEXT，MySQL 不允许对 TEXT 列直接建索引
     * （必须指定前缀长度），而 SQLite 又不支持前缀长度语法。当日事件量在千级，
     * 回表过滤事件名的代价可以忽略。</p>
     */
    private static final String EVENT_INDEX_TIME_CONTACT = "idx_time_contact";

    /**
     * 事件表索引：支撑按联系人 + 时间范围读取单人事件序列。
     */
    private static final String EVENT_INDEX_CONTACT_TIME = "idx_contact_time";

    /**
     * 统计表索引：支撑按年月日读取当日指标。
     */
    private static final String STATISTICS_INDEX_PERIOD = "idx_period";

    /**
     * 工作单元日汇总表索引：按统计日读取汇总行。
     */
    private static final String UNIT_DAILY_INDEX_PERIOD = "idx_unit_daily_period";

    /**
     * 单元日汇总表必须存在的列。
     *
     * <p>建表语句不会给已存在的表补列，这些列一旦缺失就会让写入整批失败，
     * 因此启动时要逐一检查并补齐。</p>
     */
    private static final String[] UNIT_DAILY_REQUIRED_COLUMNS = new String[]{"unit_count"};

    /**
     * 单元日汇总表唯一键：一条 (年, 月, 日, 单元) 只允许一行。
     *
     * <p>多节点同时执行采集时，各节点的「先删后插」会交错，产生重复行；
     * 唯一键配合覆盖写可以保证最终只有一份数据，且结果与节点数无关。</p>
     */
    private static final String UNIT_DAILY_UNIQUE_KEY = "uk_unit_daily";

    /**
     * 统计表唯一键：一条 (年, 月, 日, 指标) 只允许一行。
     *
     * <p>多节点同时执行日统计时，各节点的「先删后插」会交错，产生重复行；
     * 唯一键配合覆盖写可以保证最终只有一份数据，且结果与节点数无关。</p>
     */
    private static final String STATISTICS_UNIQUE_KEY = "uk_period_item";

    private final String contactEventTablePrefix = "contact_event_log_";

    private final String contactStatisticsTablePrefix = "contact_statistics_";

    /**
     * 联系人表名前缀，与 {@code ContactStorage} 保持一致。
     * <p>
     * 统计存储器与联系人存储器使用同一份配置，因此可以直接查询联系人表。
     */
    private final String contactTablePrefix = "contact_";

    /**
     * 事件表。
     */
    private final StorageField[] eventTableFields = new StorageField[]{
            new StorageField("sn", LiteralBase.LONG, new Constraint[]{
                    Constraint.PRIMARY_KEY, Constraint.AUTOINCREMENT
            }),
            // 事件名
            new StorageField("event", LiteralBase.STRING, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 联系人 ID
            new StorageField("contact_id", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 事件时间戳
            new StorageField("time", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 事件数据
            new StorageField("event_data", LiteralBase.STRING, new Constraint[]{
                    Constraint.DEFAULT_NULL
            })
    };

    /**
     * 工作单元日汇总表前缀。
     *
     * <p>与事件明细表、统计表同构：每域一张表。表内每行是一个工作单元在一个统计日的
     * 活动汇总，供 console 直接读取，避免它反复全量扫描事件明细。</p>
     */
    private final String contactUnitDailyTablePrefix = "contact_unit_daily_";

    /**
     * 工作单元日汇总表的版本标记行。
     *
     * <p>该行不是数据，而是「本表某一日已完整采集」的标记：写在所有数据行之后，
     * 供 console 判断该日汇总是否可用。{@code contact_id} 取 0 表示标记行，
     * 与真实单元 ID（大于等于 {@link Contact#MIN_UNIT_CONTACT_ID}）不冲突。</p>
     */
    public static final long UNIT_DAILY_MARKER_ID = 0L;

    /**
     * 工作单元日汇总表。
     */
    private final StorageField[] unitDailyTableFields = new StorageField[]{
            new StorageField("sn", LiteralBase.LONG, new Constraint[]{
                    Constraint.PRIMARY_KEY, Constraint.AUTOINCREMENT
            }),
            // 统计日的年、月、日
            new StorageField("year", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            new StorageField("month", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            new StorageField("date", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 单元 ID；标记行为 0
            new StorageField("contact_id", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 当日事件总数
            new StorageField("event_count", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 当日在线时长，单位：毫秒
            new StorageField("duration", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 当日首次活动时间
            new StorageField("first_active", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 当日活跃小时掩码：第 n 位为 1 表示该单元在第 n 小时有事件
            new StorageField("hour_mask", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 该统计日的起始时间戳，用于校验日界口径是否一致
            new StorageField("day_begin", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 该日的数据行数：只写在标记行上，供读取侧校验数据是否完整
            new StorageField("unit_count", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 汇总口径版本
            new StorageField("version", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            })
    };

    /**
     * 统计表。
     */
    private final StorageField[] statisticsTableFields = new StorageField[]{
            new StorageField("sn", LiteralBase.LONG, new Constraint[]{
                    Constraint.PRIMARY_KEY, Constraint.AUTOINCREMENT
            }),
            // 统计项目
            new StorageField("item", LiteralBase.STRING, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 数据
            new StorageField("data", LiteralBase.STRING, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 统计时间记录：年
            new StorageField("year", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 统计时间记录：月
            new StorageField("month", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 统计时间记录：日
            new StorageField("date", LiteralBase.INT, new Constraint[]{
                    Constraint.NOT_NULL
            }),
            // 统计时间记录：时
            new StorageField("hour", LiteralBase.INT, new Constraint[]{
                    Constraint.DEFAULT_0
            }),
            // 入库时间
            new StorageField("timestamp", LiteralBase.LONG, new Constraint[]{
                    Constraint.NOT_NULL
            })
    };

    private ExecutorService executor;

    private Storage storage;

    private Timer timer;

    private Map<String, String> eventTableNameMap;

    private Map<String, String> statisticsTableNameMap;

    /**
     * 工作单元日汇总表名映射。
     */
    private Map<String, String> unitDailyTableNameMap;

    private List<String> domainNameList;

    /**
     * 采集任务重入保护。
     *
     * <p>启动线程与每日定时任务、以及多节点部署下同一进程内的多次触发都可能重叠，
     * 同一进程内必须串行，否则会重复扫描明细并产生重复写入。</p>
     */
    private final AtomicBoolean collecting = new AtomicBoolean(false);

    public StatisticsSystem(ExecutorService executor) {
        this.executor = executor;
        this.eventTableNameMap = new HashMap<>();
        this.statisticsTableNameMap = new HashMap<>();
        this.unitDailyTableNameMap = new HashMap<>();
    }

    public void start(StorageType type, JSONObject storageConfig, List<String> domainList) {
        this.domainNameList = domainList;

        // 存储器
        this.storage = StorageFactory.getInstance().createStorage(type, "Statistics", storageConfig);
        this.storage.open();

        PluginSystem pluginSystem = ContactManager.getInstance().getPluginSystem();
        pluginSystem.register(ContactHook.SignIn, new SignInPlugin());
        pluginSystem.register(ContactHook.SignOut, new SignOutPlugin());
        pluginSystem.register(ContactHook.DeviceTimeout, new DeviceTimeoutPlugin());
        pluginSystem.register(ContactHook.Comeback, new ComebackPlugin());

        this.execSelfChecking(domainList);

        // 每天凌晨 2 点执行，随机时长 5 分钟
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 2);
        cal.set(Calendar.MINUTE, Utils.randomInt(0, 4));
        cal.set(Calendar.SECOND, Utils.randomInt(0, 59));
        Date date = cal.getTime();
        if (date.before(new Date())) {
            // 执行时间已过，调整至一天后
            cal.add(Calendar.DAY_OF_MONTH, 1);
            date = cal.getTime();
        }

        this.timer = new Timer();
        this.timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    collect();
                } catch (Throwable e) {
                    // scheduleAtFixedRate 的任务一旦抛出未捕获异常，Timer 线程会终止、后续不再执行，
                    // 这里必须兜住，否则一条坏数据就会让统计永久停摆
                    Logger.e(this.getClass(), "#collect - Collect task failed", e);
                }
            }
        }, date, 24L * 60 * 60 * 1000);

        // 启动时尝试统计数据
        (new Thread() {
            @Override
            public void run() {
                // 先补齐索引：日统计的查询依赖它们，缺索引时任务会随明细增长迅速退化
                ensureIndexes();
                collect();
            }
        }).start();
    }

    public void stop() {
        if (null != this.storage) {
            this.storage.close();
            this.storage = null;
        }

        if (null != this.timer) {
            this.timer.cancel();
            this.timer = null;
        }
    }

    private void execSelfChecking(List<String> domainNameList) {
        for (String domain : domainNameList) {
            String table = this.contactEventTablePrefix + domain;
            table = SQLUtils.correctTableName(table);
            this.eventTableNameMap.put(domain, table);

            if (!this.storage.exist(table)) {
                if (this.storage.executeCreate(table, this.eventTableFields)) {
                    Logger.i(this.getClass(), "Created table '" + table + "' successfully");
                }
            }
        }

        for (String domain : domainNameList) {
            String table = this.contactStatisticsTablePrefix + domain;

            table = SQLUtils.correctTableName(table);
            this.statisticsTableNameMap.put(domain, table);

            if (!this.storage.exist(table)) {
                if (this.storage.executeCreate(table, this.statisticsTableFields)) {
                    Logger.i(this.getClass(), "Created table '" + table + "' successfully");
                }
            }
        }

        for (String domain : domainNameList) {
            String table = this.contactUnitDailyTablePrefix + domain;

            table = SQLUtils.correctTableName(table);
            this.unitDailyTableNameMap.put(domain, table);

            if (!this.storage.exist(table)) {
                if (this.storage.executeCreate(table, this.unitDailyTableFields)) {
                    Logger.i(this.getClass(), "Created table '" + table + "' successfully");
                }
            } else {
                // 表已存在：建表语句是 CREATE TABLE IF NOT EXISTS，不会校验也不会补齐
                // 已有表的列。缺列时写入会整批失败，必须显式补上。
                this.ensureUnitDailyColumns(table);
            }
        }
    }

    /**
     * 为单元日汇总表补齐缺失的列。
     *
     * <p>建表语句是 {@code CREATE TABLE IF NOT EXISTS}，表已存在时既不校验结构也不补列，
     * 而写入语句带列名 —— 缺列会让整批 INSERT 失败（{@code Unknown column}）。
     * 老库上若已建过这张表而没有后续新增的列，就会一直写入失败。</p>
     *
     * <p>补列只针对 {@code NOT NULL} 的整数列，给出 {@code DEFAULT 0} 才能在存量行上成功：
     * 没有默认值的 NOT NULL 列在加到已有数据的表上会失败。</p>
     *
     * @param table 单元日汇总表名。
     */
    private void ensureUnitDailyColumns(String table) {
        try {
            for (String column : UNIT_DAILY_REQUIRED_COLUMNS) {
                if (this.columnExists(table, column)) {
                    continue;
                }

                String sql = "ALTER TABLE `" + table + "` ADD COLUMN `" + column
                        + "` INTEGER NOT NULL DEFAULT 0";
                this.storage.execute(sql);

                if (this.columnExists(table, column)) {
                    Logger.i(this.getClass(), "Added missing column '" + column + "' to '" + table + "'");
                } else {
                    Logger.w(this.getClass(), "Failed to add missing column '" + column + "' to '"
                            + table + "'");
                }
            }
        } catch (Throwable e) {
            // 补列失败不阻断启动：采集会写入失败并跳过标记行，控制台回落到明细路径
            Logger.w(this.getClass(), "#ensureUnitDailyColumns - " + table, e);
        }
    }

    /**
     * 判断表中是否存在指定列。
     *
     * @param table  表名。
     * @param column 列名。
     * @return 存在返回 {@code true} 。
     */
    private boolean columnExists(String table, String column) {
        String sql;

        if (StorageType.SQLite == this.storage.getType()) {
            sql = "SELECT COUNT(*) FROM `pragma_table_info('" + table + "')` WHERE `name`='" + column + "'";
        } else {
            sql = "SELECT COUNT(*) FROM `information_schema`.`columns`"
                    + " WHERE `table_schema`=DATABASE()"
                    + " AND `table_name`='" + table + "'"
                    + " AND `column_name`='" + column + "'";
        }

        List<StorageField[]> result = this.storage.executeQuery(sql);
        if (null == result || result.isEmpty()) {
            return false;
        }

        return !result.get(0)[0].isNullValue() && result.get(0)[0].getLong() > 0L;
    }

    /**
     * 为事件表与统计表补齐索引。
     *
     * <p>建表语句只声明字段约束，不会创建任何二级索引；缺少索引时，日统计里的
     * 「按事件类型 + 时间范围」和「按联系人 + 时间范围」查询都会退化为全表扫描，
     * 随着明细增长，统计任务的耗时会快速超过一天。索引创建是幂等的，已存在则跳过。</p>
     */
    private void ensureIndexes() {
        for (String domain : this.domainNameList) {
            String eventTable = this.eventTableNameMap.get(domain);
            if (null != eventTable) {
                // 按时间范围定位当日事件，同时覆盖清理时的时间条件；左前缀 time 即可满足清理
                this.ensureIndex(eventTable, EVENT_INDEX_TIME_CONTACT, "`time`,`contact_id`");
                // 覆盖 AOT 的单日事件序列读取：按联系人分组、组内按时间排序
                this.ensureIndex(eventTable, EVENT_INDEX_CONTACT_TIME, "`contact_id`,`time`");
            }

            String statisticTable = this.statisticsTableNameMap.get(domain);
            if (null != statisticTable) {
                this.ensureIndex(statisticTable, STATISTICS_INDEX_PERIOD, "`year`,`month`,`date`");
                this.ensureStatisticsUniqueKey(statisticTable);
            }

            String unitDailyTable = this.unitDailyTableNameMap.get(domain);
            if (null != unitDailyTable) {
                // console 按统计日读取汇总表，走该索引的前缀即可
                this.ensureIndex(unitDailyTable, UNIT_DAILY_INDEX_PERIOD, "`year`,`month`,`date`");
                this.ensureUnitDailyUniqueKey(unitDailyTable);
            }
        }
    }

    /**
     * 创建单个索引。
     *
     * <p>{@code execute} 对 DDL 恒返回 {@code false}（不产生结果集），因此执行后必须复查
     * 索引是否存在来判断成败，不能依赖返回值。</p>
     *
     * @param table     表名，已由 {@link SQLUtils#correctTableName(String)} 收敛。
     * @param indexName 索引名。
     * @param columns   索引列，含反引号的列名列表。
     */
    private void ensureIndex(String table, String indexName, String columns) {
        try {
            if (this.indexExists(table, indexName)) {
                return;
            }

            long begin = System.currentTimeMillis();
            this.storage.execute("CREATE INDEX `" + indexName + "` ON `" + table + "` (" + columns + ")");
            long cost = System.currentTimeMillis() - begin;

            if (this.indexExists(table, indexName)) {
                Logger.i(this.getClass(), "Created index '" + indexName + "' on '" + table
                        + "' in " + cost + " ms");
            } else {
                Logger.w(this.getClass(), "Failed to create index '" + indexName + "' on '" + table + "'");
            }
        } catch (Throwable e) {
            // 建索引失败不阻断启动，统计仍可运行，只是随明细增长会变慢
            Logger.w(this.getClass(), "#ensureIndex - " + table + "." + indexName, e);
        }
    }

    /**
     * 判断索引是否已存在。
     *
     * @param table     表名。
     * @param indexName 索引名。
     * @return 存在返回 {@code true} 。
     */
    private boolean indexExists(String table, String indexName) {
        String sql;

        if (StorageType.SQLite == this.storage.getType()) {
            sql = "SELECT COUNT(*) FROM `sqlite_master` WHERE `type`='index' AND `name`='"
                    + indexName + "'";
        } else {
            sql = "SELECT COUNT(*) FROM `information_schema`.`statistics`"
                    + " WHERE `table_schema`=DATABASE()"
                    + " AND `table_name`='" + table + "'"
                    + " AND `index_name`='" + indexName + "'";
        }

        List<StorageField[]> result = this.storage.executeQuery(sql);
        if (null == result || result.isEmpty()) {
            return false;
        }

        return !result.get(0)[0].isNullValue() && result.get(0)[0].getLong() > 0L;
    }

    /**
     * 为统计表创建 (年, 月, 日, 指标) 唯一键。
     *
     * <p>多节点部署时每个节点都会在本地定时器里执行日统计，各节点的「先删后插」交错会留下
     * 重复行。唯一键配合 {@link #writeItem} 的覆盖写，使同一 (日, 指标) 无论被写多少次、
     * 被几个节点写，都只保留一行。历史库可能已有重复行，建键前先去重。</p>
     *
     * @param table 统计表名。
     */
    private void ensureStatisticsUniqueKey(String table) {
        try {
            if (this.indexExists(table, STATISTICS_UNIQUE_KEY)) {
                return;
            }

            long before = this.countRows(table);
            this.dedupeStatistics(table);
            long removed = before - this.countRows(table);
            if (removed > 0L) {
                Logger.i(this.getClass(), "Removed " + removed + " duplicate statistics rows from '"
                        + table + "'");
            }

            // item 列建表类型是 TEXT：MySQL 的唯一键必须给前缀长度，SQLite 不支持前缀语法
            String sql;
            if (StorageType.SQLite == this.storage.getType()) {
                sql = "CREATE UNIQUE INDEX `" + STATISTICS_UNIQUE_KEY + "` ON `" + table
                        + "` (`year`,`month`,`date`,`item`)";
            } else {
                sql = "ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + STATISTICS_UNIQUE_KEY
                        + "` (`year`,`month`,`date`,`item`(16))";
            }

            this.storage.execute(sql);

            if (this.indexExists(table, STATISTICS_UNIQUE_KEY)) {
                Logger.i(this.getClass(), "Created unique key '" + STATISTICS_UNIQUE_KEY
                        + "' on '" + table + "'");
            } else {
                // 历史数据里存在 item 前缀相同的行时会失败，此时退化为「可能重复」但不影响读取
                Logger.w(this.getClass(), "Failed to create unique key '" + STATISTICS_UNIQUE_KEY
                        + "' on '" + table + "'");
            }
        } catch (Throwable e) {
            Logger.w(this.getClass(), "#ensureStatisticsUniqueKey - " + table, e);
        }
    }

    /**
     * 为单元日汇总表创建 (年, 月, 日, 单元) 唯一键。
     *
     * <p>多节点同时执行采集时，各节点的「先删后插」会交错，产生重复行；
     * 唯一键配合覆盖写可以保证最终只有一份数据，且结果与节点数无关。
     * 历史库可能已有重复行，建键前先去重。</p>
     *
     * @param table 单元日汇总表名。
     */
    private void ensureUnitDailyUniqueKey(String table) {
        try {
            if (this.indexExists(table, UNIT_DAILY_UNIQUE_KEY)) {
                return;
            }

            long before = this.countRows(table);
            this.dedupeUnitDaily(table);
            long removed = before - this.countRows(table);
            if (removed > 0L) {
                Logger.i(this.getClass(), "Removed " + removed + " duplicate unit daily rows from '"
                        + table + "'");
            }

            // 所有列都是整数类型，MySQL 的唯一键不需要前缀长度
            String sql;
            if (StorageType.SQLite == this.storage.getType()) {
                sql = "CREATE UNIQUE INDEX `" + UNIT_DAILY_UNIQUE_KEY + "` ON `" + table
                        + "` (`year`,`month`,`date`,`contact_id`)";
            } else {
                sql = "ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + UNIT_DAILY_UNIQUE_KEY
                        + "` (`year`,`month`,`date`,`contact_id`)";
            }

            this.storage.execute(sql);

            if (this.indexExists(table, UNIT_DAILY_UNIQUE_KEY)) {
                Logger.i(this.getClass(), "Created unique key '" + UNIT_DAILY_UNIQUE_KEY + "' on '"
                        + table + "'");
            } else {
                // 历史数据里存在重复行时会失败，此时退化为「可能重复」但不影响读取
                Logger.w(this.getClass(), "Failed to create unique key '" + UNIT_DAILY_UNIQUE_KEY
                        + "' on '" + table + "'");
            }
        } catch (Throwable e) {
            Logger.w(this.getClass(), "#ensureUnitDailyUniqueKey - " + table, e);
        }
    }

    /**
     * 清理单元日汇总表的重复行，同一 (年, 月, 日, 单元) 只保留 sn 最小的一条。
     *
     * @param table 单元日汇总表名。
     */
    private void dedupeUnitDaily(String table) {
        String sql;

        if (StorageType.SQLite == this.storage.getType()) {
            sql = "DELETE FROM " + table + " WHERE `sn` NOT IN (SELECT MIN(`sn`) FROM " + table
                    + " GROUP BY `year`,`month`,`date`,`contact_id`)";
        } else {
            sql = "DELETE t1 FROM " + table + " t1 INNER JOIN " + table + " t2"
                    + " ON t1.`year`=t2.`year` AND t1.`month`=t2.`month`"
                    + " AND t1.`date`=t2.`date` AND t1.`contact_id`=t2.`contact_id`"
                    + " AND t1.`sn`>t2.`sn`";
        }

        this.storage.execute(sql);
    }

    /**
     * 清理统计表的重复行，同一 (年, 月, 日, 指标) 只保留 sn 最小的一条。
     *
     * @param table 统计表名。
     */
    private void dedupeStatistics(String table) {
        String sql;

        if (StorageType.SQLite == this.storage.getType()) {
            sql = "DELETE FROM " + table + " WHERE `sn` NOT IN (SELECT MIN(`sn`) FROM " + table
                    + " GROUP BY `year`,`month`,`date`,`item`)";
        } else {
            sql = "DELETE t1 FROM " + table + " t1 INNER JOIN " + table + " t2"
                    + " ON t1.`year`=t2.`year` AND t1.`month`=t2.`month`"
                    + " AND t1.`date`=t2.`date` AND t1.`item`=t2.`item`"
                    + " AND t1.`sn`>t2.`sn`";
        }

        this.storage.execute(sql);
    }

    /**
     * 统计表的行数。
     *
     * @param table 表名。
     * @return 返回行数，查询失败时返回 0 。
     */
    private long countRows(String table) {
        List<StorageField[]> result = this.storage.executeQuery("SELECT COUNT(*) FROM " + table);

        if (null == result || result.isEmpty() || result.get(0)[0].isNullValue()) {
            return 0L;
        }

        return result.get(0)[0].getLong();
    }

    /**
     * 清理超出保留期的单元日汇总。
     *
     * <p>汇总每行代表一个单元一天，行数随单元数与天数线性增长，不清理会无限膨胀。
     * 保留期与事件明细一致：明细是重算汇总的唯一数据源，明细已清理的日期，
     * 汇总行同样没有保留价值。</p>
     */
    private void cleanupUnitDaily() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        today.add(Calendar.DAY_OF_YEAR, -EVENT_RETENTION_DAYS);

        int cutOffYear = today.get(Calendar.YEAR);
        int cutOffMonth = today.get(Calendar.MONTH) + 1;
        int cutOffDate = today.get(Calendar.DATE);

        for (String domain : this.domainNameList) {
            String table = this.unitDailyTableNameMap.get(domain);
            if (null == table) {
                continue;
            }

            String sql = "DELETE FROM " + table
                    + " WHERE (`year`*10000+`month`*100+`date`)<" + (cutOffYear * 10000 + cutOffMonth * 100 + cutOffDate);
            if (!this.storage.execute(sql)) {
                Logger.w(this.getClass(), "#cleanupUnitDaily - Failed to clean unit daily summary of domain '"
                        + domain + "'");
            }
        }
    }

    /**
     * 清理超出保留期的事件明细。
     *
     * <p>明细是唯一能重算历史的原始数据，但不能无限保留：按批删除，避免一次大事务长时间持锁；
     * 单轮删除量有上限，首次上线时剩余的历史行由后续几天的任务逐步清完。</p>
     */
    private void cleanupEvents() {
        long cutOff = System.currentTimeMillis() - EVENT_RETENTION_DAYS * 24L * 60L * 60L * 1000L;

        for (String domain : this.domainNameList) {
            String table = this.eventTableNameMap.get(domain);
            if (null == table) {
                continue;
            }

            long last = Long.MAX_VALUE;
            long initial = 0L;
            long remaining = 0L;

            for (int i = 0; i < EVENT_CLEANUP_MAX_BATCH; ++i) {
                remaining = this.countEventsBefore(table, cutOff);
                if (remaining <= 0L) {
                    break;
                }

                if (0 == i) {
                    initial = remaining;
                }

                if (remaining >= last) {
                    // 上一批没有删掉任何数据，继续循环只会空转
                    Logger.w(this.getClass(), "Cleanup of '" + table + "' made no progress, expired: "
                            + remaining);
                    break;
                }
                last = remaining;

                // SQLite 默认编译选项不支持 DELETE ... LIMIT，改用主键子查询限定批次
                String delete;
                if (StorageType.SQLite == this.storage.getType()) {
                    delete = "DELETE FROM " + table + " WHERE `sn` IN (SELECT `sn` FROM "
                            + table + " WHERE `time`<" + cutOff
                            + " LIMIT " + EVENT_CLEANUP_BATCH + ")";
                } else {
                    delete = "DELETE FROM " + table + " WHERE `time`<" + cutOff
                            + " LIMIT " + EVENT_CLEANUP_BATCH;
                }

                this.storage.execute(delete);
            }

            if (initial > 0L) {
                Logger.i(this.getClass(), "Cleanup events of domain '" + domain + "': expired "
                        + initial + ", remaining " + Math.max(0L, this.countEventsBefore(table, cutOff))
                        + " (retention " + EVENT_RETENTION_DAYS + " days)");
            }
        }
    }

    /**
     * 统计早于指定时间的事件行数。
     *
     * @param table 表名。
     * @param time  时间上界（不含）。
     * @return 返回行数，查询失败时返回 0 。
     */
    private long countEventsBefore(String table, long time) {
        List<StorageField[]> result = this.storage.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE `time`<" + time);

        if (null == result || result.isEmpty() || result.get(0)[0].isNullValue()) {
            return 0L;
        }

        return result.get(0)[0].getLong();
    }

    /**
     * 每日统计的最少检查天数（含昨日）。
     *
     * <p>统计口径升级后需要回补最近的日期，否则「昨日与前一日的差值」类指标（例如 DNU）
     * 会在口径切换当天出现跳变。已按当前口径统计过的日期会被直接跳过，因此多检查几天无额外开销。</p>
     */
    private static final int COLLECT_DAYS = 3;

    private void collect() {
        if (!this.collecting.compareAndSet(false, true)) {
            Logger.w(this.getClass(), "#collect - Previous collect is still running, skip this round");
            return;
        }

        try {
            Calendar today = Calendar.getInstance();
            today.set(Calendar.HOUR_OF_DAY, 0);
            today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0);
            today.set(Calendar.MILLISECOND, 0);

            int days = this.resolveBackfillDays(today);

            for (int i = 1; i <= days; ++i) {
                Calendar day = (Calendar) today.clone();
                day.add(Calendar.DAY_OF_MONTH, -i);
                this.collectDay(day);
            }

            // 明细只在回溯期内保留，超出保留期的数据由日统计表承接
            this.cleanupEvents();
            // 单元日汇总与明细同保留期：它只服务「回溯窗口内的统计日」查询，
            // 超出保留期后明细已不可重算，保留汇总行也没有意义
            this.cleanupUnitDaily();
        } finally {
            this.collecting.set(false);
        }
    }

    /**
     * 计算本次需要回溯的天数。
     *
     * <p>固定只补 3 天时，服务停机超过 3 天就会在统计表里留下永久空洞。这里从昨日往前找
     * 第一个「所有域都已按当前口径统计完成」的日期，从它往前再多检查 {@link #COLLECT_DAYS}
     * 天用于口径切换；正常运行时第一天即命中，开销与原来一致。</p>
     *
     * <p>回溯上限取明细保留期：更早的日期已经没有原始事件，重算也只会得到空结果。</p>
     *
     * @param today 今日零点。
     * @return 返回需要回溯的天数，取值范围 {@code [COLLECT_DAYS, EVENT_RETENTION_DAYS]} 。
     */
    private int resolveBackfillDays(Calendar today) {
        int max = EVENT_RETENTION_DAYS;

        for (int i = 1; i <= max; ++i) {
            Calendar day = (Calendar) today.clone();
            day.add(Calendar.DAY_OF_MONTH, -i);

            if (this.isCollected(day)) {
                return Math.min(max, i + COLLECT_DAYS - 1);
            }
        }

        Logger.w(this.getClass(), "#resolveBackfillDays - No completed statistics found in "
                + max + " days, fall back to full window");

        return max;
    }

    /**
     * 判断所有域在指定日期是否都已按当前口径统计完成。
     *
     * @param day 指定日期。
     * @return 全部完成返回 {@code true} 。
     */
    private boolean isCollected(Calendar day) {
        for (String domain : this.domainNameList) {
            if (STATISTICS_VERSION != this.readStatisticsVersion(domain, day)) {
                return false;
            }
        }

        return true;
    }

    private void collectDay(Calendar cal) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int date = cal.get(Calendar.DATE);

        long beginning = cal.getTimeInMillis();
        long ending = beginning + (24L * 60 * 60 * 1000L);

        for (String domain : this.domainNameList) {
            try {
                this.collectDomainDay(domain, year, month, date, beginning, ending);
            } catch (Throwable e) {
                // 单个域失败不能影响其他域；下一天的任务会按 SV 版本重新补齐该日
                Logger.e(this.getClass(), "#collectDay - Failed to collect domain '" + domain
                        + "' for " + year + "-" + month + "-" + date, e);
            }

            try {
                // 单元维度独立于用户维度：用户维度已采集完成不代表单元维度也已采集
                this.collectUnitDaily(domain, year, month, date, beginning, ending);
            } catch (Throwable e) {
                Logger.e(this.getClass(), "#collectDay - Failed to collect unit daily summary of domain '"
                        + domain + "' for " + year + "-" + month + "-" + date, e);
            }
        }
    }

    private void collectDomainDay(String domain, int year, int month, int date,
                                  long beginning, long ending) {
        String statisticTable = this.statisticsTableNameMap.get(domain);

        int version = this.readStatisticsVersion(domain, year, month, date);

        if (version < 0) {
            // 查询失败，本轮跳过该域，避免用空结果误判为「未统计」后重复写入
            Logger.w(this.getClass(), "#collectDomainDay - Failed to read existing statistics of domain '"
                    + domain + "'");
            return;
        }

        if (STATISTICS_VERSION == version) {
            // 已按当前统计口径统计过，不重复统计
            return;
        }

        // 必须在 DELETE 之前读出旧 TNU：用户总数无法回溯，重算时要原样写回
        String existingTnu = (version > 0)
                ? this.readItem(domain, ITEM_TNU, year, month, date) : null;

        // 日用户总数：仅统计 ID 位数大于等于 8 位的用户，位数小于 8 位的是 AIGC 工作单元。
        // TNU 是「当日快照」，无法从事件明细回溯：重算历史日期时必须保留原值，否则会把
        // 停机期间新增的用户算进过去某一天。
        //
        // 先算 TNU 再删旧数据：查询失败时直接放弃本轮，旧数据保持原样；
        // 顺序颠倒会让「查询失败」变成「旧数据已删且当日未标记完成」，数据就此丢失。
        int total = this.resolveTotalUsers(domain, existingTnu);
        if (total < 0) {
            // 查询失败，本轮跳过该域：不写任何指标，也不写版本标记，
            // 下一天的任务会按 SV 版本重新补齐该日
            Logger.w(this.getClass(), "#collectDomainDay - Failed to count users of domain '"
                    + domain + "'");
            return;
        }

        if (version > 0) {
            // 统计口径已升级，清除当日旧口径数据后重新统计
            this.storage.execute("DELETE FROM " + statisticTable
                    + " WHERE `year`=" + year + " AND `month`=" + month + " AND `date`=" + date);
            Logger.i(this.getClass(), "Re-collect statistics of domain '" + domain
                    + "' for " + year + "-" + month + "-" + date
                    + " (version " + STATISTICS_VERSION + ")");
        }

        this.writeItem(statisticTable, ITEM_TNU, String.valueOf(total), year, month, date);

        // 计算 DAU
        int dau = this.calcDAU(domain, beginning, ending);
        this.writeItem(statisticTable, ITEM_DAU, String.valueOf(dau), year, month, date);

        // 在线时长：同时保存均值与分子分母，供周/月/年按「总时长 ÷ 总人数」加权
        AotResult aot = this.calcAOT(domain, beginning, ending);
        this.writeItem(statisticTable, ITEM_AOT, String.valueOf(aot.hours()), year, month, date);
        this.writeItem(statisticTable, ITEM_AOT_TOTAL, String.valueOf(aot.totalMillis), year, month, date);
        this.writeItem(statisticTable, ITEM_AOT_USERS, String.valueOf(aot.users), year, month, date);

        // 按照时间段进行统计
        List<TimeSlice> timeSlices = this.calcTimeDistribution(domain, beginning, ending);
        JSONArray tdArray = new JSONArray();
        for (TimeSlice timeSlice : timeSlices) {
            tdArray.put(timeSlice.toCompactJSON());
        }
        this.writeItem(statisticTable, ITEM_TD, tdArray.toString(), year, month, date);

        // 记录本日数据使用的统计口径版本（最后写入，作为「本日已完成」的提交标记）
        this.writeItem(statisticTable, ITEM_SV, String.valueOf(STATISTICS_VERSION), year, month, date);
    }

    /**
     * 读取指定域某日某个指标的原始值。
     *
     * @param domain 指定域。
     * @param item   指标名。
     * @param year   年。
     * @param month  月。
     * @param date   日。
     * @return 返回指标值；无记录或查询失败返回 {@code null} 。
     */
    private String readItem(String domain, String item, int year, int month, int date) {
        String table = this.statisticsTableNameMap.get(domain);
        if (null == table) {
            return null;
        }

        List<StorageField[]> rows = this.storage.executeQuery(table, new StorageField[]{
                new StorageField("data", LiteralBase.STRING)
        }, new Conditional[]{
                Conditional.createEqualTo("year", LiteralBase.INT, year),
                Conditional.createAnd(),
                Conditional.createEqualTo("month", LiteralBase.INT, month),
                Conditional.createAnd(),
                Conditional.createEqualTo("date", LiteralBase.INT, date),
                Conditional.createAnd(),
                Conditional.createEqualTo("item", LiteralBase.STRING, item)
        });

        if (null == rows || rows.isEmpty() || rows.get(0)[0].isNullValue()) {
            return null;
        }

        return rows.get(0)[0].getString();
    }


    /**
     * 写入一条统计指标。
     *
     * <p>多节点并发统计时，各节点的「先删后插」会交错并产生重复行，因此统计表上有
     * {@link #STATISTICS_UNIQUE_KEY} 唯一键，这里用覆盖写保证同一 (日, 指标) 只有一行。</p>
     *
     * @param table 统计表名。
     * @param item  指标名。
     * @param data  指标值。
     * @param year  年。
     * @param month 月。
     * @param date  日。
     */
    private void writeItem(String table, String item, String data, int year, int month, int date) {
        long timestamp = System.currentTimeMillis();

        StringBuilder sql = new StringBuilder("INSERT ");
        if (StorageType.SQLite == this.storage.getType()) {
            sql.append("OR REPLACE INTO ");
        } else {
            sql.append("INTO ");
        }

        sql.append(table);
        sql.append(" (`item`,`data`,`year`,`month`,`date`,`timestamp`) VALUES ('");
        sql.append(SQLUtils.correctString(item)).append("','");
        sql.append(SQLUtils.correctString(data)).append("',");
        sql.append(year).append(",").append(month).append(",").append(date).append(",");
        sql.append(timestamp).append(")");

        if (StorageType.SQLite != this.storage.getType()) {
            sql.append(" ON DUPLICATE KEY UPDATE `data`=VALUES(`data`),`timestamp`=VALUES(`timestamp`)");
        }

        this.storage.execute(sql.toString());
    }


    /**
     * 计算某日的用户总数。
     *
     * <p>用户总数是采集当时的状态，事件明细里没有「当时一共多少人」的信息，因此重算历史日期
     * 必须沿用已入库的值；只有首次采集的日期（无旧记录）才读取当前总数。</p>
     *
     * @param domain      指定域。
     * @param existingTnu 该日已入库的用户总数，{@code null} 表示无记录。
     * @return 返回用户总数；查询失败时返回 -1 。
     */
    private int resolveTotalUsers(String domain, String existingTnu) {
        if (null != existingTnu) {
            try {
                return Integer.parseInt(existingTnu.trim());
            } catch (NumberFormatException e) {
                Logger.w(this.getClass(), "#resolveTotalUsers - Invalid TNU '" + existingTnu
                        + "' of domain '" + domain + "'");
            }
        }

        return this.countUserContacts(domain);
    }


    /**
     * 读取指定域某日已入库的统计口径版本。
     *
     * @param domain 指定域。
     * @param year 年。
     * @param month 月。
     * @param date 日。
     * @return 返回版本号；无记录返回 0 ；查询失败返回 -1 。
     */
    /**
     * 读取指定域某日已入库的统计口径版本。
     *
     * @param domain 指定域。
     * @param day    指定日期。
     * @return 返回版本号；无记录返回 0 ；查询失败返回 -1 。
     */
    private int readStatisticsVersion(String domain, Calendar day) {
        return this.readStatisticsVersion(domain, day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DATE));
    }

    private int readStatisticsVersion(String domain, int year, int month, int date) {
        String table = this.statisticsTableNameMap.get(domain);
        if (null == table) {
            return 0;
        }

        List<StorageField[]> rows = this.storage.executeQuery(table, new StorageField[]{
                new StorageField("data", LiteralBase.STRING)
        }, new Conditional[]{
                Conditional.createEqualTo("year", LiteralBase.INT, year),
                Conditional.createAnd(),
                Conditional.createEqualTo("month", LiteralBase.INT, month),
                Conditional.createAnd(),
                Conditional.createEqualTo("date", LiteralBase.INT, date),
                Conditional.createAnd(),
                Conditional.createEqualTo("item", LiteralBase.STRING, ITEM_SV)
        });

        if (null == rows) {
            return -1;
        }

        if (rows.isEmpty()) {
            return 0;
        }

        return toStatisticsVersion(rows.get(0)[0].getString());
    }


    /**
     * 采集指定域在工作单元维度上的日汇总。
     *
     * <p>与用户维度的日统计在同一轮任务里完成，但两者独立：用户维度统计的是「联系人」，
     * 这里统计的是「工作单元」（ID 位数大于等于 6 位且小于 8 位），两者口径互不覆盖。</p>
     *
     * <p>采集结果写入 {@code contact_unit_daily_<domain>}，每行是一个单元一天。
     * 最后写入一行标记行（{@code contact_id} 为 0）表示「本表该日已完整采集」，
     * console 据此判断能否直接读汇总而不用回落到明细扫描。标记行写在数据行之后，
     * 因此标记存在即代表数据完整。</p>
     *
     * <p>重算时会先删除该日的旧行与旧标记：口径升级后旧数据必须整体替换，
     * 否则会与新口径混在一张表里。</p>
     *
     * @param domain    指定域。
     * @param year      统计日的年。
     * @param month     统计日的月。
     * @param date      统计日的日。
     * @param beginning 统计日起始时间戳（含）。
     * @param ending    统计日结束时间戳（不含）。
     */
    private void collectUnitDaily(String domain, int year, int month, int date,
                                  long beginning, long ending) {
        String table = this.unitDailyTableNameMap.get(domain);
        String eventTable = this.eventTableNameMap.get(domain);
        if (null == table || null == eventTable) {
            return;
        }

        // 该日已按当前口径采集过就不重复计算：日统计任务每天回补多天，
        // 无条件重算会让同一份数据一天被算多次，白白扫事件明细。
        if (this.isUnitDailyCollected(table, year, month, date, beginning)) {
            return;
        }

        // 先清掉该日的旧数据与旧标记：口径变化时旧行必须整体替换
        if (!this.storage.executeUpdate("DELETE FROM " + table + " WHERE `year`=" + year
                + " AND `month`=" + month + " AND `date`=" + date)) {
            Logger.w(this.getClass(), "#collectUnitDaily - Failed to clear unit daily summary of domain '"
                    + domain + "' for " + year + "-" + month + "-" + date);
            return;
        }

        // 只取工作单元区间的事件：按联系人 ID 升序、时间升序，便于按单元归集。
        //
        // 这里用原生 SQL 而不是字段级查询，原因有两个：
        //
        // 一是要用索引提示固定走 idx_contact_time：InnoDB 的二级索引隐含主键列，
        // 该索引的物理顺序是 (contact_id, time, sn)，正好覆盖排序要求，无需额外排序；
        // 不指定时优化器偏向按 time 过滤的索引，反而多出一次 filesort。
        // 用优化器提示而非 FORCE INDEX：提示在索引缺失时被忽略并只记告警，查询照常成功；
        // FORCE INDEX 指向不存在的索引会直接报 1176。老库的事件表可能没有这个索引，
        // 不能让它成为查询的硬前提。
        // 提示必须出现在 SELECT 之后，条件列表机制承载不了它。
        //
        // 二是原生 SQL 查询失败时返回的是空列表而不是 null，与「当天没有事件」
        // 无法区分。解决办法是加哨兵行：查询末尾 UNION 一行常量，
        // 成功时必然能读到它，失败时读不到。
        StringBuilder sql = new StringBuilder("SELECT ");
        if (StorageType.SQLite != this.storage.getType()) {
            // 用优化器提示而不是 FORCE INDEX：提示在索引缺失时被忽略并只记告警，
            // 查询照常成功；FORCE INDEX 指向不存在的索引会直接报 1176 让整条查询失败。
            // 老库的事件表可能没有这个索引，不能让它成为查询的硬前提。
            sql.append("/*+ INDEX(t idx_contact_time) */ ");
        }
        sql.append("`contact_id`,`event`,`time`,`sn` FROM ").append(eventTable).append(" t");
        sql.append(" WHERE `contact_id`>=").append(Contact.MIN_UNIT_CONTACT_ID);
        sql.append(" AND `contact_id`<").append(Contact.MIN_USER_CONTACT_ID);
        sql.append(" AND `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        // 哨兵行：contact_id 取 -1，与真实单元 ID 区间不重叠
        sql.append(" UNION ALL SELECT -1,'',").append(beginning).append(",0");
        // 必须对整个 UNION 的最终结果排序：派生表内的 ORDER BY 可能被 MySQL 忽略，
        // 同一单元会被拆成多个片段，生成重复汇总行并丢失会话时长。
        // sn 确定同一毫秒内的事件顺序，哨兵行随后按 contact_id 排除。
        sql.append(" ORDER BY `contact_id`,`time`,`sn`");

        List<StorageField[]> result = this.storage.executeQuery(sql.toString());

        // 取出数据行，同时确认哨兵行在不在。
        // 哨兵行的位置不作依赖：UNION ALL 不保证顺序，按 contact_id 识别更稳妥。
        List<StorageField[]> rows = new ArrayList<>();
        boolean hasSentinel = false;
        if (null != result) {
            for (StorageField[] row : result) {
                if (-1L == row[0].getLong()) {
                    hasSentinel = true;
                    continue;
                }
                rows.add(row);
            }
        }

        if (!hasSentinel) {
            // 查询失败：不能写标记行，console 会因缺少标记而回落到明细路径
            Logger.w(this.getClass(), "#collectUnitDaily - Failed to query events of domain '" + domain
                    + "' for " + year + "-" + month + "-" + date);
            return;
        }

        List<StorageField[]> records = new ArrayList<>();
        UnitDayAggregator aggregator = new UnitDayAggregator(beginning, ending);
        long currentId = -1L;
        int numUnits = 0;

        for (StorageField[] row : rows) {
            long contactId = row[0].getLong();

            if (contactId != currentId) {
                if (currentId >= 0L) {
                    records.add(this.buildUnitDailyRecord(year, month, date, currentId,
                            aggregator, beginning));
                    ++numUnits;
                }

                currentId = contactId;
                aggregator.reset();
            }

            aggregator.add(row[1].getString(), row[2].getLong());
        }

        if (currentId >= 0L) {
            records.add(this.buildUnitDailyRecord(year, month, date, currentId, aggregator, beginning));
            ++numUnits;
        }

        // 数据行先写，标记行最后写：标记行存在即代表该日数据完整。
        //
        // 标记行只在「确实聚合到了单元数据」时写：当天真的没有任何单元事件时
        // 同样不写标记 —— 那种情况下 console 会回落到明细路径，结果一致（都是空），
        // 但多一条标记行会让 console 把「没采到」误当成「采到且为空」，
        // 掩盖采集侧的失败。
        for (StorageField[] record : records) {
            if (!this.writeUnitDaily(table, record)) {
                Logger.w(this.getClass(), "#collectUnitDaily - Failed to write unit daily summary of domain '"
                        + domain + "' for " + year + "-" + month + "-" + date);
                return;
            }
        }

        if (numUnits <= 0) {
            Logger.i(this.getClass(), "No unit activity in domain '" + domain + "' for "
                    + year + "-" + month + "-" + date);
            return;
        }

        // 标记行单独写入：与数据行分开，失败时不会留下「有标记但数据不全」的中间态。
        // 标记行携带数据行数，读取侧可据此校验完整性 —— 多节点交错采集时，
        // 别的节点的「先删后插」可能清掉部分数据，此时读取侧会发现行数对不上而回落。
        if (!this.writeUnitDaily(table,
                this.buildUnitDailyMarker(year, month, date, beginning, numUnits))) {
            Logger.w(this.getClass(), "#collectUnitDaily - Failed to write unit daily marker of domain '"
                    + domain + "' for " + year + "-" + month + "-" + date);
            return;
        }

        Logger.i(this.getClass(), "Collected unit daily summary of domain '" + domain + "' for "
                + year + "-" + month + "-" + date + " (" + numUnits + " units)");
    }

    /**
     * 覆盖写入单元日汇总或完成标记，允许多个节点同时补采同一天。
     *
     * <p>只更新本次提供的字段，保留 MySQL 的自增主键。写入失败必须返回 false，
     * 不能使用 {@code execute} 的结果集标志，否则成功写入也会被误判为失败。</p>
     */
    private boolean writeUnitDaily(String table, StorageField[] fields) {
        String insert = SQLUtils.spellInsert(table, fields);
        if (null == insert) {
            return false;
        }
        if (StorageType.SQLite == this.storage.getType()) {
            return this.storage.executeUpdate("INSERT OR REPLACE" + insert.substring("INSERT".length()));
        }

        StringBuilder sql = new StringBuilder(insert).append(" ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (StorageField field : fields) {
            if (null == field || null == field.getValue()) {
                continue;
            }
            if (!first) {
                sql.append(',');
            }
            sql.append('`').append(field.getName()).append("`=VALUES(`")
                    .append(field.getName()).append("`)");
            first = false;
        }
        return this.storage.executeUpdate(sql.toString());
    }

    /**
     * 判断某域某日的单元日汇总是否已按当前口径采集完成。
     *
     * <p>以标记行为准：标记行存在、版本与当前一致、日界一致，才算采集完成。
     * 三者任一不满足都视为需要重算。</p>
     *
     * @param table     单元日汇总表名。
     * @param year      统计日的年。
     * @param month     统计日的月。
     * @param date      统计日的日。
     * @param beginning 统计日起始时间戳。
     * @return 已采集完成返回 {@code true} 。
     */
    private boolean isUnitDailyCollected(String table, int year, int month, int date, long beginning) {
        // 字段级查询：失败返回 null，与「没有标记行」区分开
        List<StorageField[]> rows = this.storage.executeQuery(table, new StorageField[]{
                new StorageField("day_begin", LiteralBase.LONG),
                new StorageField("version", LiteralBase.INT)
        }, new Conditional[]{
                Conditional.createEqualTo("year", year),
                Conditional.createAnd(),
                Conditional.createEqualTo("month", month),
                Conditional.createAnd(),
                Conditional.createEqualTo("date", date),
                Conditional.createAnd(),
                Conditional.createEqualTo("contact_id", UNIT_DAILY_MARKER_ID)
        });

        if (null == rows || rows.isEmpty()) {
            return false;
        }

        StorageField[] row = rows.get(0);
        return row[0].getLong() == beginning && row[1].getInt() == UnitDayRecord.VERSION;
    }

    /**
     * 把聚合器结算结果转成一行汇总记录。
     *
     * @param year       统计日的年。
     * @param month      统计日的月。
     * @param date       统计日的日。
     * @param contactId  单元 ID 。
     * @param aggregator 已喂入该单元当日全部事件的聚合器。
     * @param beginning  统计日起始时间戳。
     * @return 返回汇总记录。
     */
    private StorageField[] buildUnitDailyRecord(int year, int month, int date, long contactId,
                                                UnitDayAggregator aggregator, long beginning) {
        long duration = aggregator.settle();

        return new StorageField[]{
                new StorageField("year", LiteralBase.INT, year),
                new StorageField("month", LiteralBase.INT, month),
                new StorageField("date", LiteralBase.INT, date),
                new StorageField("contact_id", LiteralBase.LONG, contactId),
                new StorageField("event_count", LiteralBase.INT, aggregator.getEventCount()),
                new StorageField("duration", LiteralBase.LONG, duration),
                new StorageField("first_active", LiteralBase.LONG, aggregator.getFirstActive()),
                new StorageField("hour_mask", LiteralBase.LONG, aggregator.getHourMask()),
                new StorageField("day_begin", LiteralBase.LONG, beginning),
                new StorageField("version", LiteralBase.INT, UnitDayRecord.VERSION),
                // 数据行上的该列无意义，恒为 0；只有标记行记录真实行数
                new StorageField("unit_count", LiteralBase.INT, 0)
        };
    }

    /**
     * 构造「该日汇总已完整采集」的标记行。
     *
     * @param year      统计日的年。
     * @param month     统计日的月。
     * @param date      统计日的日。
     * @param beginning 统计日起始时间戳。
     * @return 返回标记记录。
     */
    private StorageField[] buildUnitDailyMarker(int year, int month, int date, long beginning,
                                                int unitCount) {
        return new StorageField[]{
                new StorageField("year", LiteralBase.INT, year),
                new StorageField("month", LiteralBase.INT, month),
                new StorageField("date", LiteralBase.INT, date),
                new StorageField("contact_id", LiteralBase.LONG, UNIT_DAILY_MARKER_ID),
                new StorageField("event_count", LiteralBase.INT, 0),
                new StorageField("duration", LiteralBase.LONG, 0L),
                new StorageField("first_active", LiteralBase.LONG, 0L),
                new StorageField("hour_mask", LiteralBase.LONG, 0L),
                new StorageField("day_begin", LiteralBase.LONG, beginning),
                new StorageField("version", LiteralBase.INT, UnitDayRecord.VERSION),
                // 标记行携带该日的数据行数：读取侧据此判断数据是否完整
                new StorageField("unit_count", LiteralBase.INT, unitCount)
        };
    }

    /**
     * 统计指定域里的用户总数。
     * <p>
     * 仅统计 ID 十进制位数大于等于 8 位的联系人，位数小于 8 位的是 AIGC 工作单元节点。
     *
     * @param domain 指定域。
     * @return 返回用户总数；查询失败时返回 -1 。
     */
    private int countUserContacts(String domain) {
        String table = SQLUtils.correctTableName(this.contactTablePrefix + domain);

        StringBuilder sql = new StringBuilder("SELECT COUNT(`id`) FROM ");
        sql.append(table);
        sql.append(" WHERE `id`>=").append(Contact.MIN_USER_CONTACT_ID);

        List<StorageField[]> result = this.storage.executeQuery(sql.toString());
        if (null == result || result.isEmpty()) {
            // 查询失败与「确实没有用户」都返回 -1：调用方据此放弃本轮统计，
            // 不用空结果把当日统计标记成已完成
            return -1;
        }

        return result.get(0)[0].isNullValue() ? 0 : result.get(0)[0].getInt();
    }

    /**
     * 解析统计口径版本字符串。
     *
     * @param value 已入库的版本字符串。
     * @return 返回版本号，无法解析时返回 0 。
     */
    private static int toStatisticsVersion(String value) {
        if (null == value) {
            return 0;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int calcDAU(String domain, long beginning, long ending) {
        String eventTable = this.eventTableNameMap.get(domain);

        // 条件顺序与 idx_event_time 的列顺序保持一致，命中覆盖索引后无需回表
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT `contact_id`) FROM ");
        sql.append(eventTable);
        sql.append(" WHERE `event`='SignIn'");
        sql.append(" AND `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        // 仅统计用户，排除 AIGC 工作单元
        sql.append(" AND `contact_id`>=").append(Contact.MIN_USER_CONTACT_ID);

        List<StorageField[]> result = this.storage.executeQuery(sql.toString());
        if (null == result || result.isEmpty()) {
            return 0;
        }

        return result.get(0)[0].isNullValue() ? 0 : result.get(0)[0].getInt();
    }

    /**
     * 计算指定时间区间内的用户在线时长。
     *
     * <p>一次查询取出当日全部用户事件后在内存里按联系人分组，不再对每个用户单独查询：
     * 逐用户查询是「用户数 × 全表扫描」，明细增长后会直接把日统计拖到不可用。</p>
     *
     * @param domain    指定域。
     * @param beginning 起始时间戳。
     * @param ending    结束时间戳。
     * @return 返回总时长与活跃人数，调用方按需要换算均值。
     */
    private AotResult calcAOT(String domain, long beginning, long ending) {
        String eventTable = this.eventTableNameMap.get(domain);

        StringBuilder sql = new StringBuilder("SELECT `contact_id`,`event`,`time` FROM ");
        sql.append(eventTable);
        sql.append(" WHERE `contact_id`>=").append(Contact.MIN_USER_CONTACT_ID);
        sql.append(" AND `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        sql.append(" ORDER BY `contact_id`,`time`");

        List<StorageField[]> rows = this.storage.executeQuery(sql.toString());
        if (null == rows || rows.isEmpty()) {
            return new AotResult(0L, 0);
        }

        long total = 0L;
        int numContacts = 0;

        long currentId = -1L;
        long sessionStart = 0L;
        long duration = 0L;
        boolean hasSignIn = false;

        for (StorageField[] row : rows) {
            long contactId = row[0].getLong();

            if (contactId != currentId) {
                if (currentId >= 0 && hasSignIn) {
                    total += closeSession(duration, sessionStart, ending);
                    ++numContacts;
                }

                currentId = contactId;
                sessionStart = 0L;
                duration = 0L;
                hasSignIn = false;
            }

            String name = row[1].getString();
            long time = row[2].getLong();

            if (ContactHook.SignIn.equals(name) || ContactHook.Comeback.equals(name)) {
                if (sessionStart == 0L) {
                    sessionStart = time;
                }
                hasSignIn = true;
            } else if (ContactHook.DeviceTimeout.equals(name)) {
                if (sessionStart > 0L) {
                    // 超时事件比最后一次心跳晚到，扣掉一个心跳周期
                    duration += Math.max(0L, (time - sessionStart) - 15000L);
                }
                sessionStart = 0L;
            } else if (ContactHook.SignOut.equals(name)) {
                if (sessionStart > 0L) {
                    duration += Math.max(0L, time - sessionStart);
                }
                sessionStart = 0L;
            }
        }

        if (currentId >= 0 && hasSignIn) {
            total += closeSession(duration, sessionStart, ending);
            ++numContacts;
        }

        if (numContacts == 0) {
            return new AotResult(0L, 0);
        }

        return new AotResult(total, numContacts);
    }

    /**
     * 单日在线时长的聚合结果。
     *
     * <p>保存分子（总时长）与分母（活跃人数）而不只是均值，是因为周/月/年的平均在线时长
     * 必须按「区间总时长 ÷ 区间总人数」加权计算；对日均值取平均会把大月和小月等权对待。</p>
     */
    private static final class AotResult {

        /**
         * 当日活跃用户的总在线时长，单位：毫秒。
         */
        final long totalMillis;

        /**
         * 当日参与统计的活跃用户数。
         */
        final int users;

        AotResult(long totalMillis, int users) {
            this.totalMillis = (totalMillis < 0L) ? 0L : totalMillis;
            this.users = (users < 0) ? 0 : users;
        }

        /**
         * 当日人均在线时长。
         *
         * @return 返回小时数，保留两位小数。
         */
        double hours() {
            if (this.users <= 0) {
                return 0.0;
            }

            double value = (double) this.totalMillis / (double) this.users;
            double hours = value / (60.0 * 60.0 * 1000.0);
            return Math.round(hours * 100) / 100.0;
        }
    }

    /**
     * 结算单个联系人的当日在线时长。
     *
     * <p>末尾仍未闭合的会话按「到当日结束」计入。旧实现只在「完全没有闭合会话」时才补这一段，
     * 当日存在「先签出、再签入且未签出」时末段被整段丢弃，AOT 因此长期偏低。</p>
     *
     * @param duration     已累计的时长。
     * @param sessionStart 未闭合会话的开始时间，0 表示没有未闭合会话。
     * @param ending       统计区间结束时间。
     * @return 返回当日总时长，单位：毫秒。
     */
    private static long closeSession(long duration, long sessionStart, long ending) {
        long result = duration;

        if (sessionStart > 0L) {
            result += Math.max(0L, ending - sessionStart);
        }

        return (result < 0L) ? 0L : result;
    }

    /**
     * 计算当日各小时的签入分布。
     *
     * <p>一次查询取出当日所有签入事件后按小时分桶，替换原先「24 次按小时全表扫描」的实现。</p>
     *
     * @param domain    指定域。
     * @param beginning 起始时间戳。
     * @param ending    结束时间戳。
     * @return 返回有时间片段的列表，按小时升序。
     */
    private List<TimeSlice> calcTimeDistribution(String domain, long beginning, long ending) {
        ArrayList<TimeSlice> list = new ArrayList<>();

        String eventTable = this.eventTableNameMap.get(domain);

        StringBuilder sql = new StringBuilder("SELECT `time`,`event_data` FROM ");
        sql.append(eventTable);
        sql.append(" WHERE `event`='SignIn'");
        sql.append(" AND `time`>=").append(beginning);
        sql.append(" AND `time`<").append(ending);
        // 仅统计用户，排除 AIGC 工作单元
        sql.append(" AND `contact_id`>=").append(Contact.MIN_USER_CONTACT_ID);
        sql.append(" ORDER BY `time`");

        List<StorageField[]> rows = this.storage.executeQuery(sql.toString());
        if (null == rows || rows.isEmpty()) {
            return list;
        }

        final long sliceLength = 60L * 60L * 1000L;

        TimeSlice[] slices = new TimeSlice[24];
        int[] counts = new int[24];

        for (StorageField[] row : rows) {
            long time = row[0].getLong();
            int index = (int) ((time - beginning) / sliceLength);
            if (index < 0 || index >= slices.length) {
                continue;
            }

            if (row.length < 2 || row[1].isNullValue()) {
                continue;
            }

            try {
                JSONObject json = new JSONObject(row[1].getString());

                // 读取联系人信息
                JSONObject contactJson = json.getJSONObject("contact");
                contactJson.remove("context");      // 删除 context 数据

                // 二次校验：跳过 AIGC 工作单元
                if (!Contact.isUserContactId(contactJson.optLong("id", 0L))) {
                    continue;
                }

                Contact contact = new Contact(contactJson);

                // 读取设备信息
                Device device = new Device(json.getJSONObject("device"));

                if (null == slices[index]) {
                    slices[index] = new TimeSlice(index,
                            beginning + index * sliceLength,
                            beginning + (index + 1) * sliceLength);
                }

                slices[index].addContact(contact, device);
                ++counts[index];
            } catch (Exception e) {
                // 单条脏数据不能中断整日统计，否则一次异常会让该日数据永远缺失
                Logger.w(this.getClass(), "#calcTimeDistribution - Ignore malformed event data of domain '"
                        + domain + "'", e);
            }
        }

        for (int i = 0; i < slices.length; ++i) {
            if (null != slices[i] && counts[i] > 0) {
                list.add(slices[i]);
            }
        }

        return list;
    }

    protected class SignInPlugin implements Plugin {

        protected SignInPlugin() {
        }

        @Override
        public void setup() {
        }

        @Override
        public void teardown() {
        }

        @Override
        public HookResult launch(PluginContext context) {
            final ContactPluginContext ctx = (ContactPluginContext) context;

            final Contact contact = ctx.getContact();
            final long time = System.currentTimeMillis();

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    String domain = contact.getDomain().getName();
                    String table = eventTableNameMap.get(domain);

                    storage.executeInsert(table, new StorageField[]{
                            new StorageField("event", LiteralBase.STRING, ctx.getKey()),
                            new StorageField("contact_id", LiteralBase.LONG, contact.getId()),
                            new StorageField("time", LiteralBase.LONG, time),
                            new StorageField("event_data", LiteralBase.STRING, ctx.toCompactJSON()),
                    });
                }
            });

            return null;
        }
    }

    protected class SignOutPlugin implements Plugin {

        protected SignOutPlugin() {
        }

        @Override
        public void setup() {
        }

        @Override
        public void teardown() {
        }

        @Override
        public HookResult launch(PluginContext context) {
            final ContactPluginContext ctx = (ContactPluginContext) context;

            final Contact contact = ctx.getContact();
            final long time = System.currentTimeMillis();

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    String domain = contact.getDomain().getName();
                    String table = eventTableNameMap.get(domain);

                    storage.executeInsert(table, new StorageField[]{
                            new StorageField("event", LiteralBase.STRING, ctx.getKey()),
                            new StorageField("contact_id", LiteralBase.LONG, contact.getId()),
                            new StorageField("time", LiteralBase.LONG, time),
                            new StorageField("event_data", LiteralBase.STRING, ctx.toCompactJSON()),
                    });
                }
            });

            return null;
        }
    }

    protected class DeviceTimeoutPlugin implements Plugin {

        protected DeviceTimeoutPlugin() {
        }

        @Override
        public void setup() {
        }

        @Override
        public void teardown() {
        }

        @Override
        public HookResult launch(PluginContext context) {
            final ContactPluginContext ctx = (ContactPluginContext) context;

            final Contact contact = ctx.getContact();
            final long time = System.currentTimeMillis();

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    String domain = contact.getDomain().getName();
                    String table = eventTableNameMap.get(domain);

                    storage.executeInsert(table, new StorageField[]{
                            new StorageField("event", LiteralBase.STRING, ctx.getKey()),
                            new StorageField("contact_id", LiteralBase.LONG, contact.getId()),
                            new StorageField("time", LiteralBase.LONG, time),
                            new StorageField("event_data", LiteralBase.STRING, ctx.toCompactJSON()),
                    });
                }
            });

            return null;
        }
    }

    protected class ComebackPlugin implements Plugin {

        protected ComebackPlugin() {
        }

        @Override
        public void setup() {
        }

        @Override
        public void teardown() {
        }

        @Override
        public HookResult launch(PluginContext context) {
            final ContactPluginContext ctx = (ContactPluginContext) context;

            final Contact contact = ctx.getContact();
            final long time = System.currentTimeMillis();

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    String domain = contact.getDomain().getName();
                    String table = eventTableNameMap.get(domain);

                    storage.executeInsert(table, new StorageField[]{
                            new StorageField("event", LiteralBase.STRING, ctx.getKey()),
                            new StorageField("contact_id", LiteralBase.LONG, contact.getId()),
                            new StorageField("time", LiteralBase.LONG, time),
                            new StorageField("event_data", LiteralBase.STRING, ctx.toCompactJSON()),
                    });
                }
            });

            return null;
        }
    }
}
