/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.mgmt;

import cell.util.log.Logger;
import cube.console.storage.DataStorage;
import cube.console.tool.DeployTool;
import cube.util.ConfigUtils;
import cube.util.UnitDayRecord;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * 统计数据管理器。
 */
public class StatisticDataManager {

    // Total number of users
    public final String ITEM_TNU = "TNU";

    // Daily Active User
    public final String ITEM_DAU = "DAU";

    // Average online time
    public final String ITEM_AOT = "AOT";

    // Time Distribution
    public final String ITEM_TD = "TD";

    /**
     * 工作单元活跃回溯窗口：用于计算最近活动时间与近期活跃天数。
     */
    private static final long UNIT_TRAIL_WINDOW = 30L * 24 * 60 * 60 * 1000L;

    /**
     * 事件明细的保留天数，必须与 service 侧统计模块的保留期一致。
     * <p>
     * 区间超过该天数时，明细已被清理，无法再做区间去重活跃统计。
     */
    private static final long DETAIL_WINDOW_DAYS = 90L;

    /**
     * 工作状态：在线（有效的节点上报中仍有连接）。
     */
    public static final String UNIT_STATE_ONLINE = "online";

    /**
     * 工作状态：离线（回溯窗口内有活动，但当前不在线）。
     */
    public static final String UNIT_STATE_OFFLINE = "offline";

    /**
     * 工作状态：未激活（回溯窗口内没有任何活动）。
     */
    public static final String UNIT_STATE_INACTIVE = "inactive";

    // 联系人活动事件名，与 service 侧 ContactHook 的取值保持一致
    private static final String EVENT_SIGN_IN = "SignIn";
    private static final String EVENT_SIGN_OUT = "SignOut";
    private static final String EVENT_DEVICE_TIMEOUT = "DeviceTimeout";
    private static final String EVENT_COMEBACK = "Comeback";

    private DataStorage storage;
    private AIUsageManager aiUsage = new AIUsageManager(new JSONArray());

    /**
     * 单元台账缓存：只缓存数据库派生部分。
     */
    private UnitOverviewCache unitCache;

    /**
     * 单元概览接口的访问守卫。
     */
    private UnitAccessGuard unitGuard;

    /**
     * 单域可承载的单元数上限；不大于 0 表示不限。
     */
    private int maxUnitContacts;

    /**
     * 单元日汇总的命中次数。
     */
    private final AtomicLong summaryHits = new AtomicLong();

    /**
     * 单元日汇总的回落次数。
     */
    private final AtomicLong summaryFallbacks = new AtomicLong();

    /**
     * 因「该日尚未采集」而回落的次数：属于正常状态。
     */
    private final AtomicLong summaryFallbackNotCollected = new AtomicLong();

    /**
     * 因「汇总有问题」而回落的次数：需要人工介入。
     */
    private final AtomicLong summaryFallbackAbnormal = new AtomicLong();

    public JSONObject queryAIUsage(String operation, Map<String, String> params, User user) {
        AIUsageManager.Filter filter = new AIUsageManager.Filter(params, user);
        JSONObject data = aiUsage.query(operation, filter);
        if (storage != null) {
            JSONArray ranks = data.getJSONArray("breakdown");
            if ("contact".equals(filter.group)) for (int i = 0; i < ranks.length(); i++) {
                JSONObject row = ranks.getJSONObject(i);
                row.put("contact_name", storage.queryContactName(row.getString("domain"), row.getLong("contact_id")));
            }
            if (filter.contact != null && !filter.domain.isEmpty())
                data.put("contactName", storage.queryContactName(filter.domain, filter.contact));
        }
        return data;
    }

    public StatisticDataManager() {
        // 未装配配置前保持宽松：不做缓存、不要求鉴权，行为与引入缓存前一致
        this.unitCache = new UnitOverviewCache(0L, 0L, 32, 50000, 4, 300, 10000L);
        this.unitGuard = new UnitAccessGuard(false, 60, 60, 600, 600);
        this.maxUnitContacts = 0;
    }

    public void start() {
        String filepath = null;
        for (String path : DeployTool.CONSOLE_PROP_FILES) {
            File file = new File(path);
            if (file.exists()) {
                filepath = path;
                break;
            }
        }

        try {
            Properties properties = ConfigUtils.readProperties(filepath);
            this.aiUsage = new AIUsageManager(properties);
            this.storage = new DataStorage(properties);
            this.storage.open();
            this.configureUnitAccess(properties);
        } catch (IOException e) {
            Logger.w(this.getClass(), "#start", e);
        }
    }

    /**
     * 按配置装配单元概览的缓存与访问守卫。
     *
     * @param properties 控制台配置。
     */
    private void configureUnitAccess(Properties properties) {
        this.unitCache = new UnitOverviewCache(
                longValue(properties, "statistic.units.cache.ttl", 120000L),
                longValue(properties, "statistic.units.cache.stale", 600000L),
                intValue(properties, "statistic.units.cache.max.entries", 32),
                intValue(properties, "statistic.units.cache.max.units", 50000),
                intValue(properties, "statistic.units.concurrent.permits", 4),
                intValue(properties, "statistic.units.rate.global.per.minute", 300),
                longValue(properties, "statistic.units.cache.merge.timeout", 10000L));

        this.unitGuard = new UnitAccessGuard(
                booleanValue(properties, "statistic.units.require.auth", true),
                intValue(properties, "statistic.units.rate.user.per.minute", 12),
                intValue(properties, "statistic.units.rate.user.burst", 12),
                intValue(properties, "statistic.units.rate.ip.per.minute", 60),
                intValue(properties, "statistic.units.rate.ip.burst", 60));

        this.maxUnitContacts = intValue(properties, "statistic.units.max.contacts", 50000);
    }

    /**
     * 读取整型配置项。
     *
     * @param properties 配置。
     * @param key        键名。
     * @param fallback   缺省值。
     * @return 配置值。
     */
    private static int intValue(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (null == value || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            Logger.w(StatisticDataManager.class, "#intValue - Invalid value of " + key + ": " + value);
            return fallback;
        }
    }

    /**
     * 读取长整型配置项。
     *
     * @param properties 配置。
     * @param key        键名。
     * @param fallback   缺省值。
     * @return 配置值。
     */
    private static long longValue(Properties properties, String key, long fallback) {
        String value = properties.getProperty(key);
        if (null == value || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            Logger.w(StatisticDataManager.class, "#longValue - Invalid value of " + key + ": " + value);
            return fallback;
        }
    }

    /**
     * 读取布尔配置项。
     *
     * @param properties 配置。
     * @param key        键名。
     * @param fallback   缺省值。
     * @return 配置值。
     */
    private static boolean booleanValue(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (null == value || value.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * 取得单元概览接口的访问守卫。
     *
     * @return 访问守卫。
     */
    public UnitAccessGuard getUnitGuard() {
        return this.unitGuard;
    }

    /**
     * 取得单元台账缓存。
     *
     * @return 台账缓存。
     */
    public UnitOverviewCache getUnitCache() {
        return this.unitCache;
    }

    public void stop() {
        if (null != this.storage) {
            this.storage.close();
            this.storage = null;
        }
    }

    public JSONObject queryStatisticData(String domain, Calendar calendar) {
        JSONObject data = this.storage.queryContactStatistics(domain, calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DATE));
        return data;
    }

    public JSONObject queryStatisticData(String domain, int year, int month, int date) {
        JSONObject data = this.storage.queryContactStatistics(domain, year, month, date);
        return data;
    }

    /**
     * 查询指定域在日期区间内的逐日统计与区间汇总。
     *
     * <p>周/月/年不单独物化：日统计表每域每年不足两千行，按区间取逐日数据后即时聚合，
     * 成本远低于维护多套汇总表，也不会出现「日表与月表对不上」的双写问题。</p>
     *
     * <p>聚合口径有三个容易做错的地方，这里显式区分：</p>
     * <ul>
     *     <li><b>DAU</b>：逐日去重，跨日相加会重复计数，因此只给出 {@code dauSum}（日活之和）、
     *     {@code dauMax}、{@code dauAvg}，不冒充周活/月活；</li>
     *     <li><b>UV</b>：区间去重活跃人数来自事件明细，受明细保留期限制，取不到时返回 -1 并标注
     *     {@code uvScoped=unavailable}，不回退成 dauSum；</li>
     *     <li><b>AOT</b>：按「区间总时长 ÷ 区间总人数」加权，而不是对日均值取平均；
     *     早期日期没有分子分母，用 {@code AOT × DAU} 还原并计入 {@code estimatedDays}。</li>
     * </ul>
     *
     * @param domain 指定域。
     * @param from   起始日期（含）。
     * @param to     结束日期（含）。
     * @return 返回区间数据；存储器未就绪时返回 {@code null} 。
     */
    public JSONObject queryStatisticRange(String domain, Calendar from, Calendar to) {
        if (null == this.storage) {
            return null;
        }

        // 统一到日边界：调用方传进来的可能是「当前时刻」，直接参与去重统计会多算一天
        Calendar fromDay = startOfDay(from);
        Calendar toDay = startOfDay(to);

        List<JSONObject> days = this.storage.queryContactStatisticsRange(domain,
                toYmd(fromDay), toYmd(toDay));

        long aotTotal = 0L;
        long aotUsers = 0L;
        int dauSum = 0;
        int dauMax = 0;
        int numDays = 0;
        int estimatedDays = 0;
        int tnu = 0;

        for (JSONObject day : days) {
            JSONObject statistic = day.getJSONObject("statistic");
            if (!statistic.has("TNU")) {
                // 该日没有统计结果（服务停机或尚未采集），不参与汇总
                continue;
            }

            ++numDays;

            int dau = statistic.optInt("DAU", 0);
            dauSum += dau;
            if (dau > dauMax) {
                dauMax = dau;
            }

            tnu = statistic.optInt("TNU", tnu);

            long dayTotal = statistic.optLong("AOTT", -1L);
            int dayUsers = statistic.optInt("AOTU", -1);

            if (dayTotal < 0L || dayUsers < 0) {
                // 旧口径数据只有日均值，用 AOT × DAU 还原分子分母
                dayUsers = dau;
                dayTotal = Math.round(statistic.optDouble("AOT", 0.0d) * 3600000.0d) * (long) dayUsers;
                ++estimatedDays;
            }

            aotTotal += dayTotal;
            aotUsers += dayUsers;
        }

        JSONObject summary = new JSONObject();
        summary.put("days", numDays);
        summary.put("tnu", tnu);
        summary.put("dauSum", dauSum);
        summary.put("dauMax", dauMax);
        summary.put("dauAvg", (numDays > 0) ? round2((double) dauSum / (double) numDays) : 0.0d);
        summary.put("aotTotalMillis", aotTotal);
        summary.put("aotUsers", aotUsers);
        summary.put("aot", (aotUsers > 0)
                ? round2((double) aotTotal / 3600000.0d / (double) aotUsers) : 0.0d);
        summary.put("estimatedDays", estimatedDays);

        // 区间去重活跃人数：只有明细仍在保留期内才能精确计算
        int uv = -1;
        if (this.withinDetailWindow(fromDay)) {
            uv = this.storage.countDistinctActiveUsers(domain, fromDay.getTimeInMillis(),
                    toDay.getTimeInMillis() + 24L * 60L * 60L * 1000L);
        }
        summary.put("uv", uv);
        summary.put("uvScoped", (uv >= 0) ? "detail" : "unavailable");

        JSONObject data = new JSONObject();
        data.put("domain", domain);
        data.put("from", toYmdJson(fromDay));
        data.put("to", toYmdJson(toDay));
        data.put("summary", summary);
        data.put("days", days);

        return data;
    }

    /**
     * 把日历归一到当日零点。
     *
     * @param calendar 指定日历。
     * @return 返回新的日历实例，入参为 {@code null} 时返回当日零点。
     */
    private static Calendar startOfDay(Calendar calendar) {
        Calendar result = (null == calendar) ? Calendar.getInstance() : (Calendar) calendar.clone();
        result.set(Calendar.HOUR_OF_DAY, 0);
        result.set(Calendar.MINUTE, 0);
        result.set(Calendar.SECOND, 0);
        result.set(Calendar.MILLISECOND, 0);
        return result;
    }

    /**
     * 判断区间起点是否仍在事件明细的保留期内。
     *
     * <p>该值与 service 侧事件明细的保留天数保持一致：超出后明细已被清理，
     * 无法再做区间去重统计。</p>
     *
     * @param from 区间起点。
     * @return 在保留期内返回 {@code true} 。
     */
    private boolean withinDetailWindow(Calendar from) {
        long span = System.currentTimeMillis() - from.getTimeInMillis();
        return span <= DETAIL_WINDOW_DAYS * 24L * 60L * 60L * 1000L;
    }

    private static int toYmd(Calendar cal) {
        return cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DATE);
    }

    private static JSONObject toYmdJson(Calendar cal) {
        JSONObject json = new JSONObject();
        json.put("year", cal.get(Calendar.YEAR));
        json.put("month", cal.get(Calendar.MONTH) + 1);
        json.put("date", cal.get(Calendar.DATE));
        return json;
    }

    public List<String> getDomains() {
        return this.storage.queryAllDomains();
    }

    /**
     * 查询单元概览，并返回缓存命中情况。
     * <p>
     * 结果由两部分拼成：台账统计（来自统计库，可缓存）与实时状态（来自节点上报，不缓存）。
     * 只有前者进入缓存，因此缓存命中不会让刚上报的实体停留在离线态。
     *
     * @param domain 指定域。
     * @param calendar 统计日期。
     * @param capabilityMap 按物理实体归并的能力。
     * @param onlineUnitIds 有效上报中仍有连接的物理实体 ID。
     * @return 查询结果；数据不可用时 {@link UnitOverviewOutcome#data} 为 {@code null} 。
     */
    public UnitOverviewOutcome queryUnitOverviewOutcome(String domain, Calendar calendar,
                                                         Map<Long, List<JSONObject>> capabilityMap,
                                                         Set<Long> onlineUnitIds) {
        if (null == this.storage) {
            return UnitOverviewOutcome.notReady();
        }

        String key = this.unitCacheKey(domain, calendar);
        final boolean[] overflow = new boolean[1];
        UnitOverviewCache.Result result = this.unitCache.get(key, new UnitOverviewCache.Loader() {
            @Override
            public UnitLedger load() {
                return StatisticDataManager.this.queryUnitLedger(domain, calendar, overflow);
            }
        });

        // 单元数超限优先判定：即便还有旧快照可用，也必须让调用方知道当前域已超出承载上限
        if (overflow[0]) {
            return UnitOverviewOutcome.tooLarge();
        }

        if (null == result.ledger) {
            return result.failed ? UnitOverviewOutcome.failed() : UnitOverviewOutcome.rejected();
        }

        JSONObject data = this.composeUnitOverview(domain, result.ledger, capabilityMap, onlineUnitIds);
        return UnitOverviewOutcome.ready(data, result.stale, result.merged);
    }

    /**
     * 构造单元概览的缓存键：域与统计日期。
     *
     * @param domain   指定域。
     * @param calendar 统计日期。
     * @return 缓存键。
     */
    public String unitCacheKey(String domain, Calendar calendar) {
        Calendar day = (Calendar) calendar.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return domain + "|" + toYmd(day);
    }

    /**
     * 查询单元概览：实时在线由有效连接上报决定，联系人事件只用于历史活动统计。
     *
     * @param domain 指定域。
     * @param calendar 统计日期。
     * @param capabilityMap 按物理实体归并的能力，允许保留已断开连接的能力。
     * @param onlineUnitIds 同一批有效上报中仍有连接的物理实体 ID；无上报时为空。
     * @return 返回单元概览数据。
     */
    public JSONObject queryUnitOverview(String domain, Calendar calendar,
                                        Map<Long, List<JSONObject>> capabilityMap, Set<Long> onlineUnitIds) {
        UnitOverviewOutcome outcome = this.queryUnitOverviewOutcome(domain, calendar, capabilityMap, onlineUnitIds);
        return outcome.data;
    }

    /**
     * 查询单元在回溯窗口内的最近活动时间与活跃天数。
     *
     * <p>正常路径把聚合下推到数据库：窗口内的事件条数远大于单元条数，聚合后回源的数据量降一个数量级。
     * 天分桶使用 JVM 本地时区的偏移，而不是数据库服务器时区 —— 两者未必一致，用服务器时区分日
     * 会与日历口径产生偏差。</p>
     *
     * <p>当本地时区在窗口内发生过切换（夏令时或行政改期），固定偏移的天分桶不再等价于本地日历，
     * 此时退回按事件逐条归集，保证口径不变。</p>
     *
     * @param domain    指定域。
     * @param beginning 起始时间戳（含）。
     * @param ending    结束时间戳（不含）。
     * @return 单元 ID 到活跃情况的映射。
     */
    private Map<Long, DataStorage.UnitTrail> queryUnitTrail(String domain, long beginning, long ending) {
        Map<Long, DataStorage.UnitTrail> result = new HashMap<>();

        TimeZone zone = TimeZone.getDefault();
        long offset = zone.getOffset(beginning);

        if (hasZoneShift(zone, beginning, ending)) {
            // 时区在窗口内切换：逐条归集，保持与本地日历一致
            List<DataStorage.ContactEvent> events = this.storage.queryContactEvents(domain, beginning, ending, true);
            if (null == events) {
                // 查询失败：与「窗口内没有活动」不同，不能当成空结果
                return null;
            }

            Map<Long, Long> lastActiveMap = new HashMap<>();
            Map<Long, Set<Integer>> activeDayMap = new HashMap<>();
            for (DataStorage.ContactEvent event : events) {
                Long last = lastActiveMap.get(event.contactId);
                if (null == last || event.time > last) {
                    lastActiveMap.put(event.contactId, event.time);
                }

                Set<Integer> days = activeDayMap.get(event.contactId);
                if (null == days) {
                    days = new HashSet<Integer>();
                    activeDayMap.put(event.contactId, days);
                }
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(event.time);
                days.add(cal.get(Calendar.DAY_OF_YEAR));
            }

            for (Map.Entry<Long, Long> entry : lastActiveMap.entrySet()) {
                Set<Integer> days = activeDayMap.get(entry.getKey());
                result.put(entry.getKey(), new DataStorage.UnitTrail(entry.getKey(), entry.getValue(),
                        (null == days) ? 0 : days.size()));
            }
            return result;
        }

        List<DataStorage.UnitTrail> trails = this.storage.queryUnitTrail(domain, beginning, ending, offset);
        if (null == trails) {
            // 查询失败：与「窗口内没有活动」不同，不能当成空结果
            return null;
        }

        for (DataStorage.UnitTrail trail : trails) {
            result.put(trail.contactId, trail);
        }
        return result;
    }

    /**
     * 判断时区在给定窗口内是否发生过偏移切换。
     *
     * @param zone      时区。
     * @param beginning 起始时间戳（含）。
     * @param ending    结束时间戳（不含）。
     * @return 发生过切换返回 {@code true} 。
     */
    private static boolean hasZoneShift(TimeZone zone, long beginning, long ending) {
        long offset = zone.getOffset(beginning);
        // 每 6 小时取样：真实时区的偏移变更都会持续数月，按天取样已足够，
        // 这里加密取样是为了覆盖「变更幅度小于 1 天」的罕见时区
        for (long time = beginning + 21600000L; time < ending; time += 21600000L) {
            if (zone.getOffset(time) != offset) {
                return true;
            }
        }
        return zone.getOffset(ending - 1) != offset;
    }

    /**
     * 读取统计日内各单元的活动统计。
     *
     * <p>优先读 service 侧采集的日汇总表：汇总可用时每次只回传「单元数」量级的数据，
     * 不再全量扫描事件明细。汇总不可用时回落到明细路径，两条路径口径一致，
     * 结果相同，差别只在数据来源与代价。</p>
     *
     * <p>汇总的可用性由 service 侧写入的标记行决定：该日已完整采集、汇总口径版本匹配、
     * 日界口径一致，三者缺一即回落。日界口径比对的是采集时记录的日起始时间戳，
     * 两端时区不同时会直接回落，不会静默给出按不同日界算出的结果。</p>
     *
     * @param domain    指定域。
     * @param day       统计日的日历。
     * @param beginning 统计日起始时间戳（含）。
     * @param ending    统计日结束时间戳（不含）。
     * @return 返回单元 ID 到当日活动统计的映射。
     */
    private Map<Long, DataStorage.UnitDayStat> queryUnitDayStats(String domain, Calendar day,
                                                                 long beginning, long ending) {
        int year = day.get(Calendar.YEAR);
        int month = day.get(Calendar.MONTH) + 1;
        int date = day.get(Calendar.DATE);

        DataStorage.UnitDailySummary summary =
                this.storage.queryUnitDailySummary(domain, year, month, date, beginning);
        if (summary.available) {
            this.summaryHits.incrementAndGet();
            HashMap<Long, DataStorage.UnitDayStat> map = new HashMap<>();
            for (UnitDayRecord record : summary.records) {
                map.put(record.contactId, new DataStorage.UnitDayStat(record.eventCount,
                        record.durationMillis, record.firstActive, record.hourMask));
            }
            return map;
        }

        // 汇总不可用：记录原因并回落到明细路径，按事件流现算。
        // 回落原因必须可见：否则线上无法判断汇总路径是否真的在生效。
        this.recordSummaryFallback(summary.reason, domain, year, month, date);

        // 汇总不可用：回落到明细路径，按事件流现算
        List<DataStorage.ContactEvent> dayEvents =
                this.storage.queryContactEvents(domain, beginning, ending, true);
        if (null == dayEvents) {
            // 明细查询失败：不能当成「当天没有活动」，否则整份台账会失真
            return null;
        }

        HashMap<Long, List<DataStorage.ContactEvent>> dayEventMap = new HashMap<>();
        for (DataStorage.ContactEvent event : dayEvents) {
            List<DataStorage.ContactEvent> list = dayEventMap.get(event.contactId);
            if (null == list) {
                list = new ArrayList<DataStorage.ContactEvent>();
                dayEventMap.put(event.contactId, list);
            }
            list.add(event);
        }

        HashMap<Long, DataStorage.UnitDayStat> map = new HashMap<>();
        for (Map.Entry<Long, List<DataStorage.ContactEvent>> entry : dayEventMap.entrySet()) {
            List<DataStorage.ContactEvent> events = entry.getValue();
            map.put(entry.getKey(), new DataStorage.UnitDayStat(events.size(),
                    calcOnlineDuration(events, ending), events.get(0).time, hourMaskOf(events, beginning)));
        }
        return map;
    }

    /**
     * 记录一次汇总回落。
     *
     * <p>不同原因的性质不同：「尚未采集」是正常状态（例如凌晨时段），
     * 「版本不符」「日界不符」「数据不完整」则说明采集侧有问题，需要人工介入，
     * 因此后者用 WARN 级别记录，前者用 DEBUG。</p>
     *
     * @param reason 回落原因。
     * @param domain 域。
     * @param year   统计日的年。
     * @param month  统计日的月。
     * @param date   统计日的日。
     */
    private void recordSummaryFallback(String reason, String domain, int year, int month, int date) {
        this.summaryFallbacks.incrementAndGet();

        if (DataStorage.UnitDailySummary.REASON_NOT_COLLECTED.equals(reason)
                || DataStorage.UnitDailySummary.REASON_NO_DATA.equals(reason)) {
            this.summaryFallbackNotCollected.incrementAndGet();
            Logger.d(this.getClass(), "#queryUnitDayStats - Unit daily summary not collected yet for domain '"
                    + domain + "' on " + year + "-" + month + "-" + date + ", falling back to detail path");
            return;
        }

        this.summaryFallbackAbnormal.incrementAndGet();
        Logger.w(this.getClass(), "#queryUnitDayStats - Unit daily summary unusable (" + reason
                + ") for domain '" + domain + "' on " + year + "-" + month + "-" + date
                + ", falling back to detail path");
    }

    /**
     * 采集单元日汇总路径的指标。
     *
     * @return 指标快照。
     */
    public JSONObject getUnitSummaryMetrics() {
        JSONObject json = new JSONObject();
        json.put("hits", this.summaryHits.get());
        json.put("fallbacks", this.summaryFallbacks.get());
        json.put("fallbackNotCollected", this.summaryFallbackNotCollected.get());
        json.put("fallbackAbnormal", this.summaryFallbackAbnormal.get());
        long total = this.summaryHits.get() + this.summaryFallbacks.get();
        json.put("hitRate", (total > 0L) ? ((double) this.summaryHits.get() / (double) total) : 0.0d);
        return json;
    }

    /**
     * 计算一批事件的活跃小时掩码。
     *
     * @param events    事件列表。
     * @param beginning 统计日起始时间戳。
     * @return 返回小时掩码：第 n 位为 1 表示该单元在第 n 小时有事件。
     */
    private static long hourMaskOf(List<DataStorage.ContactEvent> events, long beginning) {
        long mask = 0L;
        for (DataStorage.ContactEvent event : events) {
            int slice = (int) ((event.time - beginning) / (60L * 60 * 1000L));
            if (slice < 0) {
                slice = 0;
            } else if (slice > 23) {
                slice = 23;
            }
            mask |= (1L << slice);
        }
        return mask;
    }

    /**
     * 读取单元台账（数据库派生部分）。
     * <p>
     * 本方法只碰统计库，产出与节点上报无关的台账快照：实体基础信息、日内活动统计、
     * 回溯窗口内的最近活动时间与活跃天数、逐小时活跃切片。快照可被缓存。
     *
     * @param domain 指定域。
     * @param calendar 统计日期。
     * @return 台账快照；统计库不可用时返回 {@code null} 。
     */
    public UnitLedger queryUnitLedger(String domain, Calendar calendar) {
        return this.queryUnitLedger(domain, calendar, new boolean[1]);
    }

    /**
     * 读取单元台账（数据库派生部分）。
     *
     * @param domain    指定域。
     * @param calendar  统计日期。
     * @param overflow  长度为 1 的标记数组：单元数超限时置为 {@code true} 。
     * @return 台账快照；统计库不可用时返回 {@code null} 。
     */
    public UnitLedger queryUnitLedger(String domain, Calendar calendar, boolean[] overflow) {
        if (null == this.storage) {
            return null;
        }

        Calendar day = (Calendar) calendar.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);

        long beginning = day.getTimeInMillis();
        long ending = beginning + 24L * 60 * 60 * 1000L;
        long now = System.currentTimeMillis();

        // 台账中的实体
        List<JSONObject> contacts = this.storage.queryUnitContacts(domain, this.maxUnitContacts);
        if (null == contacts) {
            // 统计库不可用：返回 null 让缓存走降级路径，而不是把空台账当成真实结果
            return null;
        }

        if (this.maxUnitContacts > 0 && contacts.size() > this.maxUnitContacts) {
            // 单元数超限：宁可拒绝也不能静默截断，否则台账会少单元而无人察觉
            if (null != overflow) {
                overflow[0] = true;
            }
            return null;
        }
        // 统计日的活动统计：优先读 service 采集的日汇总，缺汇总时回落到明细扫描
        Map<Long, DataStorage.UnitDayStat> dayStatMap =
                this.queryUnitDayStats(domain, day, beginning, ending);
        if (null == dayStatMap) {
            // 活动统计不可用：台账不完整，不能对外提供
            return null;
        }
        // 回溯窗口内的最近活动时间与活跃天数（已下推到数据库聚合）
        Map<Long, DataStorage.UnitTrail> trailMap =
                this.queryUnitTrail(domain, now - UNIT_TRAIL_WINDOW, now + 1000L);
        if (null == trailMap) {
            // 回溯窗口不可用：台账不完整，不能对外提供
            return null;
        }

        // 统计日各小时活跃单元数：由每个单元的活跃小时掩码汇总而来
        int[] timeline = new int[24];
        int peakSlice = -1;
        int peakUnits = 0;
        for (DataStorage.UnitDayStat stat : dayStatMap.values()) {
            long mask = stat.hourMask;
            for (int slice = 0; slice < 24; ++slice) {
                if (((mask >> slice) & 1L) != 0L) {
                    ++timeline[slice];
                }
            }
        }
        for (int i = 0; i < 24; ++i) {
            if (timeline[i] > 0 && timeline[i] > peakUnits) {
                peakUnits = timeline[i];
                peakSlice = i;
            }
        }

        // 逐单元汇总
        List<UnitLedger.Unit> units = new ArrayList<UnitLedger.Unit>();
        int activeCount = 0;
        long totalDuration = 0L;
        for (JSONObject contact : contacts) {
            long id = contact.getLong("id");

            DataStorage.UnitDayStat stat = dayStatMap.get(id);
            int numEvents = (null == stat) ? 0 : stat.eventCount;
            long durationMillis = (null == stat) ? 0L : stat.durationMillis;
            if (numEvents > 0) {
                ++activeCount;
                totalDuration += durationMillis;
            }

            DataStorage.UnitTrail trail = trailMap.get(id);
            long lastActiveTime = (null == trail) ? 0L : trail.lastActiveTime;
            int activeDays = (null == trail) ? 0 : trail.activeDays;

            units.add(new UnitLedger.Unit(id, contact.getString("name"), contact.getString("device"),
                    contact.getString("platform"), numEvents, durationMillis, lastActiveTime,
                    (null == stat) ? 0L : stat.firstActive, activeDays));
        }

        units.sort(UnitOverviewCache.LEDGER_ORDER);

        return new UnitLedger(Collections.unmodifiableList(units), contacts.size(), activeCount,
                totalDuration, timeline, peakSlice, beginning, ending);
    }

    /**
     * 把台账快照与实时状态拼成响应数据。
     * <p>
     * 在线状态与承载能力来自节点上报，每次请求都实时叠加：在线实体排在最前，
     * 其余按「最近活动时间倒序、ID 升序」排列，与台账快照的顺序一致。
     *
     * @param domain 指定域。
     * @param ledger 台账快照。
     * @param capabilityMap 按物理实体归并的能力。
     * @param onlineUnitIds 有效上报中仍有连接的物理实体 ID。
     * @return 响应数据。
     */
    public JSONObject composeUnitOverview(String domain, UnitLedger ledger,
                                          Map<Long, List<JSONObject>> capabilityMap, Set<Long> onlineUnitIds) {
        List<JSONObject> online = new ArrayList<JSONObject>();
        List<JSONObject> offline = new ArrayList<JSONObject>();
        List<JSONObject> inactive = new ArrayList<JSONObject>();

        for (UnitLedger.Unit unit : ledger.units) {
            JSONObject row = new JSONObject();
            row.put("id", unit.id);
            row.put("name", unit.name);
            row.put("device", unit.device);
            row.put("platform", unit.platform);
            row.put("activeCount", unit.eventCount);
            row.put("duration", round2(unit.durationMillis / (60.0 * 60.0 * 1000.0)));
            row.put("lastActiveTime", unit.lastActiveTime);
            row.put("firstActiveTime", unit.firstActiveTime);
            row.put("activeDays", unit.activeDays);

            // 该物理实体当前承载的能力（一个实体可能有多个单元），节点未上报时为空数组
            List<JSONObject> capabilities = capabilityMap.get(unit.id);
            JSONArray capabilityArray = new JSONArray();
            if (null != capabilities) {
                for (JSONObject capability : capabilities) {
                    capabilityArray.put(capability);
                }
            }
            row.put("capabilities", capabilityArray);

            if (onlineUnitIds.contains(unit.id)) {
                row.put("state", UNIT_STATE_ONLINE);
                online.add(row);
            } else if (unit.lastActiveTime > 0) {
                row.put("state", UNIT_STATE_OFFLINE);
                offline.add(row);
            } else {
                row.put("state", UNIT_STATE_INACTIVE);
                inactive.add(row);
            }
        }

        // 在线优先，其余沿用台账快照的顺序（最近活动时间倒序、ID 升序）
        JSONArray sortedUnits = new JSONArray();
        for (JSONObject unit : online) {
            sortedUnits.put(unit);
        }
        for (JSONObject unit : offline) {
            sortedUnits.put(unit);
        }
        for (JSONObject unit : inactive) {
            sortedUnits.put(unit);
        }

        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(ledger.beginning);

        JSONArray timeline = new JSONArray();
        for (int i = 0; i < 24; ++i) {
            timeline.put(this.unitTimeSlice(i, ledger.beginning, ledger.timelineUnits[i]));
        }

        JSONObject peak = (ledger.peakSlice < 0) ? null
                : this.unitTimeSlice(ledger.peakSlice, ledger.beginning, ledger.timelineUnits[ledger.peakSlice]);

        JSONObject data = new JSONObject();
        data.put("domain", domain);
        data.put("year", day.get(Calendar.YEAR));
        data.put("month", day.get(Calendar.MONTH) + 1);
        data.put("date", day.get(Calendar.DATE));
        data.put("beginning", ledger.beginning);
        data.put("ending", ledger.ending);
        data.put("total", ledger.total);
        data.put("activeCount", ledger.activeCount);
        data.put("onlineCount", online.size());
        data.put("offlineCount", offline.size());
        data.put("inactiveCount", inactive.size());
        data.put("totalDuration", round2(ledger.totalDurationMillis / (60.0 * 60.0 * 1000.0)));
        data.put("avgDuration", (ledger.activeCount > 0)
                ? round2(ledger.totalDurationMillis / (60.0 * 60.0 * 1000.0) / ledger.activeCount) : 0.0);
        data.put("peak", (null == peak) ? JSONObject.NULL : peak);
        data.put("timeline", timeline);
        data.put("units", sortedUnits);
        return data;
    }

    /**
     * 构造逐小时活跃切片。
     *
     * @param slice     小时序号。
     * @param beginning 统计日起始时间戳。
     * @param numUnits  该小时的活跃单元数。
     * @return 切片数据。
     */
    private JSONObject unitTimeSlice(int slice, long beginning, int numUnits) {
        JSONObject json = new JSONObject();
        json.put("slice", slice);
        json.put("beginning", beginning + slice * 60L * 60 * 1000L);
        json.put("ending", beginning + (slice + 1) * 60L * 60 * 1000L);
        json.put("numUnits", numUnits);
        return json;
    }

    /**
     * 计算单个单元在给定区间内的在线时长。
     * <p>
     * 口径与 service 侧 {@code StatisticsSystem#calcAOT} 一致：SignIn / Comeback 视为会话开始，
     * SignOut 结束会话，DeviceTimeout 结束会话并扣除 15 秒心跳余量；区间结束时仍未结束的会话
     * 按到区间结束计算。
     *
     * <p>末尾未闭合的会话必须<b>累加</b>而不是只在「完全没有闭合会话」时补：当日若存在
     * 「先签出、再签入且未签出」，旧写法会把末段整段丢弃。</p>
     *
     * @param events 按时间升序排列的事件列表。
     * @param ending 区间结束时间戳。
     * @return 返回在线时长，单位：毫秒。
     */
    private static long calcOnlineDuration(List<DataStorage.ContactEvent> events, long ending) {
        long startTime = 0;
        long duration = 0;

        for (DataStorage.ContactEvent event : events) {
            String name = event.event;
            long time = event.time;

            if (EVENT_SIGN_IN.equals(name) || EVENT_COMEBACK.equals(name)) {
                if (startTime == 0) {
                    startTime = time;
                }
                continue;
            }

            if (EVENT_DEVICE_TIMEOUT.equals(name)) {
                if (startTime > 0) {
                    duration += Math.max(0L, (time - startTime) - 15000L);
                }
                startTime = 0;
            } else if (EVENT_SIGN_OUT.equals(name)) {
                if (startTime > 0) {
                    duration += Math.max(0L, time - startTime);
                }
                startTime = 0;
            }
        }

        if (startTime != 0) {
            duration += Math.max(0L, ending - startTime);
        }

        return (duration < 0L) ? 0L : duration;
    }

    /**
     * 保留两位小数。
     */
    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
