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
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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

    /** 工作单元在线判定窗口：最近 24 小时内有活动即视为在线。 */
    private static final long UNIT_ONLINE_WINDOW = 24L * 60 * 60 * 1000L;

    /** 工作单元活跃回溯窗口：用于计算最近活动时间与近期活跃天数。 */
    private static final long UNIT_TRAIL_WINDOW = 30L * 24 * 60 * 60 * 1000L;

    /**
     * 事件明细的保留天数，必须与 service 侧统计模块的保留期一致。
     *
     * 区间超过该天数时，明细已被清理，无法再做区间去重活跃统计。
     */
    private static final long DETAIL_WINDOW_DAYS = 90L;

    /** 工作状态：在线。 */
    public static final String UNIT_STATE_ONLINE = "online";

    /** 工作状态：离线（回溯窗口内有活动，但当前不在线）。 */
    public static final String UNIT_STATE_OFFLINE = "offline";

    /** 工作状态：未激活（回溯窗口内没有任何活动）。 */
    public static final String UNIT_STATE_INACTIVE = "inactive";

    // 联系人活动事件名，与 service 侧 ContactHook 的取值保持一致
    private static final String EVENT_SIGN_IN = "SignIn";
    private static final String EVENT_SIGN_OUT = "SignOut";
    private static final String EVENT_DEVICE_TIMEOUT = "DeviceTimeout";
    private static final String EVENT_COMEBACK = "Comeback";

    private DataStorage storage;
    private AIUsageManager aiUsage = new AIUsageManager(new JSONArray());
    public JSONObject queryAIUsage(String operation, Map<String, String> params, User user) {
        AIUsageManager.Filter filter = new AIUsageManager.Filter(params, user);
        JSONObject data = aiUsage.query(operation, filter);
        if (storage != null) {
            JSONArray ranks = data.getJSONArray("breakdown");
            if ("contact".equals(filter.group)) for (int i = 0; i < ranks.length(); i++) {
                JSONObject row = ranks.getJSONObject(i);
                row.put("contact_name", storage.queryContactName(row.getString("domain"), row.getLong("contact_id")));
            }
            if (filter.contact != null && !filter.domain.isEmpty()) data.put("contactName", storage.queryContactName(filter.domain, filter.contact));
        }
        return data;
    }

    public StatisticDataManager() {
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
        } catch (IOException e) {
            Logger.w(this.getClass(), "#start", e);
        }
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
     * @param from 起始日期（含）。
     * @param to 结束日期（含）。
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

    public JSONObject queryUnitOverview(String domain, Calendar calendar) {
        return this.queryUnitOverview(domain, calendar, Collections.<Long, List<JSONObject>>emptyMap());
    }

    /**
     * 查询指定域的 AIGC 工作单元概览。
     *
     * 工作单元是 ID 十进制位数大于等于 6 且小于 8 位的联系人（例如 <code>Unit-531001</code>），
     * 位数小于 6 位的联系人不是统计对象；「联系人概览」只统计 ID 位数大于等于 8 位的联系人。
     *
     * <p>台账的一行是一个 Contact 物理实体：实体的基础信息与活动统计来自统计库，而实体承载的
     * 能力（{@code AICapability}）只存在于节点内存，由节点周期上报后经
     * {@code Console#queryUnitCapabilities} 归并传入——同一个物理实体上可以注册多个 AIGC 单元，
     * 因此这里挂到行上的是一个能力数组。</p>
     *
     * @param domain 指定域。
     * @param calendar 统计日期，按该日 00:00 ~ 24:00 统计日活动。
     * @param capabilityMap Key 为物理实体 ID 、Value 为该实体承载的能力列表，由节点上报汇总得到。
     * @return 返回单元概览数据。
     */
    public JSONObject queryUnitOverview(String domain, Calendar calendar,
                                        Map<Long, List<JSONObject>> capabilityMap) {
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

        // 单元基础信息（ID 位数大于等于 6 且小于 8 位的联系人）
        List<JSONObject> contacts = this.storage.queryUnitContacts(domain);

        // 统计日内的事件
        List<DataStorage.ContactEvent> dayEvents =
                this.storage.queryContactEvents(domain, beginning, ending, true);
        // 回溯窗口内的事件，用于计算最近活动时间与工作状态
        List<DataStorage.ContactEvent> trailEvents =
                this.storage.queryContactEvents(domain, now - UNIT_TRAIL_WINDOW, now + 1000L, true);

        // 按单元归集统计日事件
        Map<Long, List<DataStorage.ContactEvent>> dayEventMap = new HashMap<>();
        for (DataStorage.ContactEvent event : dayEvents) {
            List<DataStorage.ContactEvent> list = dayEventMap.get(event.contactId);
            if (null == list) {
                list = new ArrayList<>();
                dayEventMap.put(event.contactId, list);
            }
            list.add(event);
        }

        // 回溯窗口内的最近活动时间与活跃天数
        Map<Long, Long> lastActiveMap = new HashMap<>();
        Map<Long, Set<Integer>> activeDayMap = new HashMap<>();
        for (DataStorage.ContactEvent event : trailEvents) {
            Long last = lastActiveMap.get(event.contactId);
            if (null == last || event.time > last) {
                lastActiveMap.put(event.contactId, event.time);
            }

            Set<Integer> days = activeDayMap.get(event.contactId);
            if (null == days) {
                days = new HashSet<>();
                activeDayMap.put(event.contactId, days);
            }
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(event.time);
            days.add(cal.get(Calendar.DAY_OF_YEAR));
        }

        // 统计日各小时活跃单元数
        Map<Integer, Set<Long>> sliceMap = new HashMap<>();
        for (DataStorage.ContactEvent event : dayEvents) {
            int slice = (int) ((event.time - beginning) / (60L * 60 * 1000L));
            Set<Long> units = sliceMap.get(slice);
            if (null == units) {
                units = new HashSet<>();
                sliceMap.put(slice, units);
            }
            units.add(event.contactId);
        }

        JSONArray timeline = new JSONArray();
        JSONObject peak = null;
        for (int i = 0; i < 24; ++i) {
            Set<Long> units = sliceMap.get(i);
            int num = (null == units) ? 0 : units.size();

            JSONObject slice = new JSONObject();
            slice.put("slice", i);
            slice.put("beginning", beginning + i * 60L * 60 * 1000L);
            slice.put("ending", beginning + (i + 1) * 60L * 60 * 1000L);
            slice.put("numUnits", num);
            timeline.put(slice);

            if (num > 0 && (null == peak || num > peak.getInt("numUnits"))) {
                peak = slice;
            }
        }

        // 逐单元汇总
        JSONArray units = new JSONArray();
        int activeCount = 0;
        int onlineCount = 0;
        int offlineCount = 0;
        int inactiveCount = 0;
        double totalDuration = 0;
        int numActiveUnits = 0;

        for (JSONObject contact : contacts) {
            long id = contact.getLong("id");

            List<DataStorage.ContactEvent> events = dayEventMap.get(id);
            int numEvents = (null == events) ? 0 : events.size();
            long durationMillis = (null == events) ? 0 : calcOnlineDuration(events, ending);
            if (numEvents > 0) {
                ++numActiveUnits;
                totalDuration += durationMillis;
            }

            Long lastActive = lastActiveMap.get(id);
            long lastActiveTime = (null == lastActive) ? 0 : lastActive;

            String state;
            if (lastActiveTime > 0 && (now - lastActiveTime) <= UNIT_ONLINE_WINDOW) {
                state = UNIT_STATE_ONLINE;
                ++onlineCount;
            }
            else if (lastActiveTime > 0) {
                state = UNIT_STATE_OFFLINE;
                ++offlineCount;
            }
            else {
                state = UNIT_STATE_INACTIVE;
                ++inactiveCount;
            }

            if (numEvents > 0) {
                ++activeCount;
            }

            Set<Integer> days = activeDayMap.get(id);

            // 该物理实体当前承载的能力（一个实体可能有多个单元），节点未上报时为空数组
            List<JSONObject> capabilities = capabilityMap.get(id);
            JSONArray capabilityArray = new JSONArray();
            if (null != capabilities) {
                for (JSONObject capability : capabilities) {
                    capabilityArray.put(capability);
                }
            }

            JSONObject unit = new JSONObject();
            unit.put("id", id);
            unit.put("name", contact.getString("name"));
            unit.put("device", contact.getString("device"));
            unit.put("platform", contact.getString("platform"));
            unit.put("state", state);
            unit.put("activeCount", numEvents);
            unit.put("duration", round2(durationMillis / (60.0 * 60.0 * 1000.0)));
            unit.put("lastActiveTime", lastActiveTime);
            unit.put("firstActiveTime", (numEvents > 0) ? events.get(0).time : 0L);
            unit.put("activeDays", (null == days) ? 0 : days.size());
            unit.put("capabilities", capabilityArray);
            units.put(unit);
        }

        // 在线优先、最近活动时间降序、ID 升序
        List<JSONObject> sorted = new ArrayList<>(units.length());
        for (int i = 0; i < units.length(); ++i) {
            sorted.add(units.getJSONObject(i));
        }
        sorted.sort((a, b) -> {
            int sa = stateWeight(a.getString("state"));
            int sb = stateWeight(b.getString("state"));
            if (sa != sb) {
                return sa - sb;
            }
            long la = a.getLong("lastActiveTime");
            long lb = b.getLong("lastActiveTime");
            if (la != lb) {
                return (la > lb) ? -1 : 1;
            }
            long ia = a.getLong("id");
            long ib = b.getLong("id");
            return (ia < ib) ? -1 : ((ia == ib) ? 0 : 1);
        });

        JSONArray sortedUnits = new JSONArray();
        for (JSONObject unit : sorted) {
            sortedUnits.put(unit);
        }

        JSONObject data = new JSONObject();
        data.put("domain", domain);
        data.put("year", day.get(Calendar.YEAR));
        data.put("month", day.get(Calendar.MONTH) + 1);
        data.put("date", day.get(Calendar.DATE));
        data.put("beginning", beginning);
        data.put("ending", ending);
        data.put("total", contacts.size());
        data.put("activeCount", activeCount);
        data.put("onlineCount", onlineCount);
        data.put("offlineCount", offlineCount);
        data.put("inactiveCount", inactiveCount);
        data.put("totalDuration", round2(totalDuration / (60.0 * 60.0 * 1000.0)));
        data.put("avgDuration", (numActiveUnits > 0)
                ? round2(totalDuration / (60.0 * 60.0 * 1000.0) / numActiveUnits) : 0.0);
        data.put("peak", (null == peak) ? JSONObject.NULL : peak);
        data.put("timeline", timeline);
        data.put("units", sortedUnits);
        return data;
    }

    /**
     * 计算单个单元在给定区间内的在线时长。
     *
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
            }
            else if (EVENT_SIGN_OUT.equals(name)) {
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
     * 工作状态排序权重，在线优先。
     */
    private static int stateWeight(String state) {
        if (UNIT_STATE_ONLINE.equals(state)) {
            return 0;
        }
        if (UNIT_STATE_OFFLINE.equals(state)) {
            return 1;
        }
        return 2;
    }

    /**
     * 保留两位小数。
     */
    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
