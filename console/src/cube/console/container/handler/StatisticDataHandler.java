/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.container.handler;

import cube.console.Console;
import cube.console.Utils;
import cube.console.container.Handlers;
import cube.util.CrossDomainHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 统计数据相关接口。
 *
 * <ul>
 *   <li><code>GET /statistic/recent</code> —— 昨日用户统计（仅统计 ID 位数大于等于 8 位的联系人）</li>
 *   <li><code>GET /statistic/daily</code> —— 指定日期的用户统计</li>
 *   <li><code>GET /statistic/range</code> —— 日期区间的逐日统计与区间汇总（供周/月/年视图使用）</li>
 *   <li><code>GET /statistic/units</code> —— 昨日 AI 单元概览（ID 位数大于等于 6 且小于 8 位的联系人），
 *       每行是一个 Contact 物理实体，<code>capabilities</code> 为该实体承载的能力（来自节点上报）</li>
 *   <li><code>GET /statistic/unit-deployments</code> —— 按单元名称组织当前部署快照</li>
 * </ul>
 */
public class StatisticDataHandler extends ContextHandler {

    /**
     * 区间查询允许的最大天数：一年 + 一天，避免一次拉取过长时间跨度。
     */
    private static final int MAX_RANGE_DAYS = 366;

    private Console console;

    public StatisticDataHandler(Console console) {
        super("/statistic");
        this.setHandler(new Handler());
        this.console = console;
    }

    protected class Handler extends CrossDomainHandler {

        public Handler() {
        }

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (target.startsWith("/ai-usage/")) {
                if (!Handlers.checkCookie(request, console)) { respond(response, HttpStatus.UNAUTHORIZED_401); return; }
                try {
                    cube.console.mgmt.UserToken session = console.getUserManager().getToken(Handlers.getToken(request));
                    if (session == null || session.user == null) { respond(response, HttpStatus.UNAUTHORIZED_401); return; }
                    Map<String, String> params = new HashMap<>();
                    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                        if (entry.getValue().length != 1) throw new IllegalArgumentException("Duplicate query parameter");
                        params.put(entry.getKey(), entry.getValue()[0]);
                    }
                    String operation = target.substring("/ai-usage/".length());
                    JSONObject result = console.getStatisticDataManager().queryAIUsage(operation, params, session.user);
                    if ("export".equals(operation)) {
                        response.setStatus(HttpStatus.OK_200); response.setCharacterEncoding("UTF-8");
                        response.setContentType("text/csv; charset=UTF-8");
                        response.setHeader("Content-Disposition", "attachment; filename=ai-usage.csv");
                        response.setHeader("Cache-Control", "no-store");
                        response.getWriter().write(cube.console.mgmt.AIUsageManager.csv(result));
                        this.complete();
                    } else { response.setHeader("Cache-Control", "no-store"); respondOk(response, result); }
                } catch (SecurityException denied) { respond(response, HttpStatus.FORBIDDEN_403); }
                  catch (IllegalArgumentException invalid) { respond(response, HttpStatus.BAD_REQUEST_400); }
                return;
            }
            if (target.equals("/unit-deployments")) {
                if (!Handlers.checkCookie(request, console)) {
                    respond(response, HttpStatus.UNAUTHORIZED_401);
                    return;
                }
                String domain = request.getParameter("domain");
                if (domain == null || domain.trim().isEmpty()) {
                    respond(response, HttpStatus.BAD_REQUEST_400);
                    return;
                }
                respondOk(response, console.queryUnitDeployments(domain.trim()));
            } else if (target.equals("/units")) {
                // AI 单元概览（ID 十进制位数大于等于 6 且小于 8 位的联系人）
                String query = request.getQueryString();
                Map<String, String> params = (null == query) ? new HashMap<String, String>()
                        : Utils.parseQueryStringParams(URLDecoder.decode(query, "UTF-8"));

                String domain = params.get("domain");
                if (null == domain || domain.isEmpty()) {
                    respond(response, HttpStatus.BAD_REQUEST_400);
                    return;
                }

                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.DAY_OF_YEAR, -1);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);

                // 缺省统计昨日，也支持按指定日期查询（与 /statistic/daily 对齐）
                if (params.containsKey("year") && params.containsKey("month")
                        && params.containsKey("date")) {
                    calendar.set(Calendar.YEAR, Integer.parseInt(params.get("year")));
                    calendar.set(Calendar.MONTH, Integer.parseInt(params.get("month")) - 1);
                    calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(params.get("date")));
                }

                JSONObject data = console.getStatisticDataManager().queryUnitOverview(domain, calendar,
                        console.queryUnitCapabilities(domain));
                if (null == data) {
                    respond(response, HttpStatus.NOT_FOUND_404);
                    return;
                }

                data.put("tag", console.getTag());
                // 能力来自节点上报，给出最近一次收到上报的时间，便于前端说明数据新鲜度
                data.put("unitReportTime", console.getUnitReportTimestamp());

                respondOk(response, data);
            } else if (target.equals("/recent")) {
                String query = URLDecoder.decode(request.getQueryString(), "UTF-8");
                Map<String, String> params = Utils.parseQueryStringParams(query);

                String domain = params.get("domain");

                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.DAY_OF_YEAR, -1);

                JSONObject statistic = console.getStatisticDataManager().queryStatisticData(domain, calendar);
                if (null == statistic) {
                    respond(response, HttpStatus.NOT_FOUND_404);
                    return;
                }

                // 查找前一天数据，计算新增用户数
                JSONObject prevStatistic = console.getStatisticDataManager().queryStatisticData(domain,
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH) + 1,
                        calendar.get(Calendar.DATE) - 1);
                // 计算 DNU
                if (null != prevStatistic) {
                    statistic.put("DNU", statistic.getInt("TNU") - prevStatistic.getInt("TNU"));
                }

                JSONObject data = new JSONObject();
                data.put("tag", console.getTag());
                data.put("statistic", statistic);
                data.put("year", calendar.get(Calendar.YEAR));
                data.put("month", calendar.get(Calendar.MONTH) + 1);
                data.put("date", calendar.get(Calendar.DATE));

                respondOk(response, data);
            } else if (target.equals("/range")) {
                String query = request.getQueryString();
                Map<String, String> params = (null == query) ? new HashMap<String, String>()
                        : Utils.parseQueryStringParams(URLDecoder.decode(query, "UTF-8"));

                String domain = params.get("domain");
                if (null == domain || domain.isEmpty()) {
                    respond(response, HttpStatus.BAD_REQUEST_400);
                    return;
                }

                Calendar to = Calendar.getInstance();
                Calendar from = Calendar.getInstance();

                try {
                    if (params.containsKey("days")) {
                        int days = Integer.parseInt(params.get("days"));
                        if (days < 1) {
                            days = 1;
                        }
                        if (days > MAX_RANGE_DAYS) {
                            days = MAX_RANGE_DAYS;
                        }
                        from.add(Calendar.DAY_OF_MONTH, -(days - 1));
                    } else if (params.containsKey("from") && params.containsKey("to")) {
                        if (!setDate(from, params.get("from")) || !setDate(to, params.get("to"))) {
                            respond(response, HttpStatus.BAD_REQUEST_400);
                            return;
                        }
                    } else {
                        // 缺省最近 30 天（含今日）
                        from.add(Calendar.DAY_OF_MONTH, -29);
                    }
                } catch (NumberFormatException e) {
                    respond(response, HttpStatus.BAD_REQUEST_400);
                    return;
                }

                // 日期部分比较，忽略时分秒
                if (toYmd(from) > toYmd(to)) {
                    respond(response, HttpStatus.BAD_REQUEST_400);
                    return;
                }

                JSONObject data = console.getStatisticDataManager().queryStatisticRange(domain, from, to);
                if (null == data) {
                    respond(response, HttpStatus.NOT_FOUND_404);
                    return;
                }

                data.put("tag", console.getTag());
                respondOk(response, data);
            } else if (target.equals("/daily")) {
                String query = URLDecoder.decode(request.getQueryString(), "UTF-8");
                Map<String, String> params = Utils.parseQueryStringParams(query);

                String domain = params.get("domain");

                Calendar calendar = Calendar.getInstance();

                int year = calendar.get(Calendar.YEAR);
                int month = calendar.get(Calendar.MONTH) + 1;
                int date = calendar.get(Calendar.DAY_OF_MONTH);

                if (params.containsKey("year")) {
                    year = Integer.parseInt(params.get("year"));
                }
                if (params.containsKey("month")) {
                    month = Integer.parseInt(params.get("month"));
                }
                if (params.containsKey("date")) {
                    date = Integer.parseInt(params.get("date"));
                }

                calendar.set(Calendar.YEAR, year);
                calendar.set(Calendar.MONTH, month - 1);
                calendar.set(Calendar.DAY_OF_MONTH, date);

                JSONObject statistic = console.getStatisticDataManager().queryStatisticData(domain, calendar);
                if (null == statistic) {
                    respond(response, HttpStatus.NOT_FOUND_404);
                    return;
                }

                JSONObject data = new JSONObject();
                data.put("tag", console.getTag());
                data.put("statistic", statistic);
                data.put("year", calendar.get(Calendar.YEAR));
                data.put("month", calendar.get(Calendar.MONTH) + 1);
                data.put("date", calendar.get(Calendar.DATE));

                respondOk(response, data);
            }
        }

        /**
         * 解析 <code>yyyy-MM-dd</code> 格式的日期到日历对象，时分秒清零。
         *
         * @param calendar 目标日历。
         * @param value    日期字符串。
         * @return 解析成功返回 {@code true} 。
         */
        private boolean setDate(Calendar calendar, String value) {
            if (null == value) {
                return false;
            }

            String[] parts = value.split("-");
            if (parts.length != 3) {
                return false;
            }

            int year;
            int month;
            int date;

            try {
                year = Integer.parseInt(parts[0]);
                month = Integer.parseInt(parts[1]);
                date = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return false;
            }

            if (month < 1 || month > 12 || date < 1 || date > 31) {
                return false;
            }

            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, date);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            return true;
        }

        /**
         * 把日历转换为 <code>yyyyMMdd</code> 形式的整数，便于比较大小。
         *
         * @param calendar 指定日历。
         * @return 返回日期整数。
         */
        private int toYmd(Calendar calendar) {
            return calendar.get(Calendar.YEAR) * 10000
                    + (calendar.get(Calendar.MONTH) + 1) * 100
                    + calendar.get(Calendar.DATE);
        }
    }
}
