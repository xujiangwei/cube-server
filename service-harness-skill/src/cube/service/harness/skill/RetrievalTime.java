/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.skill;

import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有界的中文/ISO 时间语法；相对日期使用原始消息所持久化的时钟。
 */
final class RetrievalTime {
    /**
     * 中文或阿拉伯数字。
     */
    private static final String NUMBER = "[0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3}";

    /**
     * 带偏移的 ISO 时间正则。
     */
    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:[Zz]|[+-]\\d{2}:\\d{2})");

    /**
     * 日期表达正则。
     */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日|今天|今日|昨天|昨日|前天|明天|明日|后天");

    /**
     * 时段表达正则。
     */
    private static final Pattern CLOCK = Pattern.compile("(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚间|昨晚|今晚)?\\s*(" + NUMBER + ")(?::([0-9]{2})(?::([0-9]{2}))?|[点时](?:(" + NUMBER + ")分?|(半|一刻|三刻))?)");

    /**
     * 相对时间范围正则。
     */
    private static final Pattern RECENT = Pattern.compile("(?:最近|过去)([0-9]{1,3}|[一二两三四五六七八九十]{1,3})(?:个)?(小时|分钟|天)");

    /**
     * 检索时区。
     */
    final ZoneId zone;

    /**
     * 起始日期与截止日期。
     */
    LocalDate day;
    LocalDate lastDay;

    /**
     * 起始时刻与截止时刻。
     */
    LocalTime start;
    LocalTime end;

    /**
     * 绝对起止时间。
     */
    Instant absoluteStart;
    Instant absoluteEnd;

    /**
     * 消息接收时刻锚点。
     */
    Instant dayAnchor;

    /**
     * 是否识别到时间表达。
     */
    boolean present;

    /**
     * 是否为整天区间。
     */
    boolean wholeDay;

    /**
     * 解析问题提示。
     */
    String issue = "";

    /**
     * 按时区标识构造解析器。
     *
     * @param zone 时区标识。
     */
    RetrievalTime(String zone) {
        this.zone = ZoneId.of(zone);
    }

    /**
     * 把指定区间用空格遮蔽。
     *
     * @param text  原文。
     * @param begin 起始位置。
     * @param end   结束位置。
     * @return 遮蔽后的文本。
     */
    private static String blank(String text, int begin, int end) {
        StringBuilder b = new StringBuilder(text);
        for (int i = begin; i < end; i++) {
            b.setCharAt(i, ' ');
        }
        return b.toString();
    }

    /**
     * 解析中文或阿拉伯数字。
     *
     * @param value 数字文本。
     * @return 数值。
     */
    private static int number(String value) {
        if (value.matches("[0-9]+")) {
            return Integer.parseInt(value);
        }
        if (value.equals("两")) {
            return 2;
        }
        String digits = "零一二三四五六七八九";
        value = value.replace('〇', '零').replace('两', '二');
        if (!value.matches("[零一二三四五六七八九]|[一二三四五六七八九]?十[一二三四五六七八九]?")) {
            throw new IllegalArgumentException("中文数字格式无效。");
        }
        int ten = value.indexOf('十');
        if (ten >= 0) {
            return (ten == 0 ? 1 : digits.indexOf(value.charAt(0))) * 10 + (ten == value.length() - 1 ? 0 : digits.indexOf(value.charAt(ten + 1)));
        }
        if (value.length() == 1 && digits.indexOf(value.charAt(0)) >= 0) {
            return digits.indexOf(value.charAt(0));
        }
        throw new IllegalArgumentException();
    }

    /**
     * 把日期表达解析为日期。
     *
     * @param value    日期文本。
     * @param received 消息接收时刻（毫秒）。
     * @return 日期。
     */
    private LocalDate date(String value, long received) {
        LocalDate today = Instant.ofEpochMilli(received).atZone(zone).toLocalDate();
        if (Arrays.asList("今天", "今日").contains(value)) {
            return today;
        }
        if (Arrays.asList("昨天", "昨日").contains(value)) {
            return today.minusDays(1);
        }
        if (value.equals("前天")) {
            return today.minusDays(2);
        }
        if (Arrays.asList("明天", "明日").contains(value)) {
            return today.plusDays(1);
        }
        if (value.equals("后天")) {
            return today.plusDays(2);
        }
        String[] parts = value.replace('年', '-').replace('月', '-').replace("日", "").split("-");
        return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /**
     * 把时段表达解析为时刻。
     *
     * @param m      时段正则匹配器。
     * @param period 时段词，可为 null。
     * @return 时刻。
     */
    private static LocalTime clock(Matcher m, String period) {
        int hour = number(m.group(2));
        int minute = m.group(3) != null ? Integer.parseInt(m.group(3)) : m.group(5) != null ? number(m.group(5)) : 0;
        if (m.group(6) != null) {
            minute = m.group(6).equals("半") ? 30 : m.group(6).equals("一刻") ? 15 : 45;
        }
        int second = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        if (period == null && m.group(3) == null && hour > 0 && hour < 12) {
            throw new IllegalArgumentException("请明确上午、下午，或使用24小时制的15:00写法。");
        }
        if (period != null) {
            if (hour > 12) {
                throw new IllegalArgumentException("时段与小时不一致，请使用明确的24小时制时间。");
            }
            if (hour == 0 && !period.equals("凌晨")) {
                throw new IllegalArgumentException("零点的日期和时段有歧义，请使用明确日期及24小时制时间。");
            }
            if (period.equals("中午") && !Arrays.asList(11, 12, 1, 2).contains(hour)) {
                throw new IllegalArgumentException("中午与小时不一致，请使用明确的24小时制时间。");
            }
            if (hour == 12 && !Arrays.asList("下午", "中午", "凌晨").contains(period)) {
                throw new IllegalArgumentException("12点的日期和时段有歧义，请使用明确日期及24小时制时间。");
            }
            if (Arrays.asList("下午", "晚上", "傍晚", "晚间", "昨晚", "今晚").contains(period)) {
                if (hour < 12) {
                    hour += 12;
                }
            } else if (period.equals("中午")) {
                if (hour < 11) {
                    hour += 12;
                }
            } else if (hour == 12) {
                hour = 0;
            }
        }
        return LocalTime.of(hour, minute, second);
    }

    /**
     * 解析时间表达并返回未消费的文本，供语义覆盖检查使用。
     *
     * @param source   原始文本。
     * @param received 消息接收时刻（毫秒）。
     * @return 剔除时间片段后的文本。
     */
    String apply(String source, long received) {
        String text = source;
        String masked = source;
        Matcher iso = ISO.matcher(text);
        List<String> isoValues = new ArrayList<>();
        while (iso.find()) {
            isoValues.add(iso.group());
            masked = blank(masked, iso.start(), iso.end());
        }
        if (!isoValues.isEmpty()) {
            present = true;
            wholeDay = false;
            issue = "";
            try {
                if (isoValues.size() != 2) {
                    throw new IllegalArgumentException("请给出两个带偏移的起止时间。");
                }
                absoluteStart = OffsetDateTime.parse(isoValues.get(0).toUpperCase(Locale.ROOT)).toInstant();
                absoluteEnd = OffsetDateTime.parse(isoValues.get(1).toUpperCase(Locale.ROOT)).toInstant();
                day = absoluteStart.atZone(zone).toLocalDate();
                lastDay = absoluteEnd.atZone(zone).toLocalDate();
                start = absoluteStart.atZone(zone).toLocalTime();
                end = absoluteEnd.atZone(zone).toLocalTime();
            } catch (Exception e) {
                issue = e.getMessage();
            }
            return masked;
        }
        Matcher recent = RECENT.matcher(text);
        if (recent.find()) {
            present = true;
            wholeDay = false;
            issue = "";
            try {
                long n = number(recent.group(1));
                if (n <= 0) {
                    throw new IllegalArgumentException("时间长度必须大于零。");
                }
                long seconds = Math.multiplyExact(n, recent.group(2).equals("天") ? 86400L : recent.group(2).equals("小时") ? 3600L : 60L);
                absoluteEnd = Instant.ofEpochMilli(received);
                absoluteStart = absoluteEnd.minusSeconds(seconds);
                day = absoluteStart.atZone(zone).toLocalDate();
                lastDay = absoluteEnd.atZone(zone).toLocalDate();
                start = absoluteStart.atZone(zone).toLocalTime();
                end = absoluteEnd.atZone(zone).toLocalTime();
                masked = blank(masked, recent.start(), recent.end());
                if (recent.find()) {
                    throw new IllegalArgumentException("存在多个相对时间范围，请明确一个范围。");
                }
            } catch (Exception e) {
                issue = e.getMessage();
            }
            return masked;
        }
        List<LocalDate> dates = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        Matcher datesMatcher = DATE.matcher(text);
        try {
            while (datesMatcher.find()) {
                dates.add(date(datesMatcher.group(), received));
                positions.add(datesMatcher.start());
                masked = blank(masked, datesMatcher.start(), datesMatcher.end());
            }
            Matcher clocks = CLOCK.matcher(masked);
            List<LocalTime> times = new ArrayList<>();
            List<LocalDate> clockDays = new ArrayList<>();
            String inheritedPeriod = null;
            while (clocks.find()) {
                String period = clocks.group(1);
                if (period != null) {
                    inheritedPeriod = period;
                }
                times.add(clock(clocks, period == null ? inheritedPeriod : period));
                LocalDate clockDay = day;
                for (int i = 0; i < positions.size(); i++) {
                    if (positions.get(i) < clocks.start()) {
                        clockDay = dates.get(i);
                    }
                }
                if (period != null && (period.equals("昨晚") || period.equals("今晚"))) {
                    clockDay = Instant.ofEpochMilli(received).atZone(zone).toLocalDate().minusDays(period.equals("昨晚") ? 1 : 0);
                }
                clockDays.add(clockDay);
                masked = blank(masked, clocks.start(), clocks.end());
            }
            if (!dates.isEmpty() || !times.isEmpty()) {
                present = true;
                issue = "";
                absoluteStart = null;
                absoluteEnd = null;
                if (dates.size() > 2) {
                    throw new IllegalArgumentException("存在多个日期范围，请明确一组起止时间。");
                }
                if (dates.size() == 2 && times.size() == 2 && !dates.get(0).equals(clockDays.get(0))) {
                    throw new IllegalArgumentException("请明确每个日期对应的起止时刻。");
                }
                if (!dates.isEmpty()) {
                    dayAnchor = Instant.ofEpochMilli(received);
                    day = dates.get(0);
                    lastDay = dates.size() > 1 ? dates.get(dates.size() - 1) : day;
                }
                if (!times.isEmpty()) {
                    wholeDay = false;
                    if (times.size() != 2) {
                        throw new IllegalArgumentException("请明确一个起止时段。");
                    }
                    start = times.get(0);
                    end = times.get(1);
                    if (clockDays.get(0) != null) {
                        day = clockDays.get(0);
                    }
                    if (clockDays.get(1) != null) {
                        lastDay = clockDays.get(1);
                    } else {
                        lastDay = day;
                    }
                } else if (dates.size() > 1) {
                    throw new IllegalArgumentException("跨日期范围请明确截止时刻，避免包含整天的歧义。");
                } else if (start == null || wholeDay) {
                    wholeDay = true;
                    start = LocalTime.MIDNIGHT;
                    end = LocalTime.MIDNIGHT;
                    lastDay = day.plusDays(1);
                }
            }
        } catch (Exception e) {
            present = true;
            issue = e.getMessage() == null ? "时间格式或日期无效，请明确起止时间。" : e.getMessage();
        }
        return masked;
    }

    /**
     * 把解析结果解析为带偏移的检索时间区间。
     *
     * @param config 档案时间配置。
     * @param now    当前时刻。
     * @return 时间区间 JSON；不可用返回 null。
     */
    JSONObject resolve(JSONObject config, Instant now) {
        if (!present) {
            if (config.getBoolean("required")) {
                issue = "请提供检索日期或时间范围。";
            }
            return null;
        }
        if (config.getString("mode").equals("none")) {
            issue = "该检索档案没有时间过滤能力，请选择支持时间筛选的资源或取消时间条件。";
            return null;
        }
        if (!issue.isEmpty()) {
            return null;
        }
        try {
            Instant from = absoluteStart;
            Instant to = absoluteEnd;
            if (from == null) {
                if (day == null || lastDay == null || start == null || end == null) {
                    throw new IllegalArgumentException("请补充明确日期，不能猜测检索日期。");
                }
                LocalDateTime a = LocalDateTime.of(day, start);
                LocalDateTime b = LocalDateTime.of(lastDay, end);
                List<ZoneOffset> ao = zone.getRules().getValidOffsets(a);
                List<ZoneOffset> bo = zone.getRules().getValidOffsets(b);
                if (ao.size() != 1 || bo.size() != 1) {
                    throw new IllegalArgumentException("时间处于夏令时缺口或重叠，请使用带偏移的ISO时间。");
                }
                from = a.toInstant(ao.get(0));
                to = b.toInstant(bo.get(0));
            }
            if (wholeDay && !config.getBoolean("allowFuture") && dayAnchor != null && day.equals(dayAnchor.atZone(zone).toLocalDate()) && to.isAfter(dayAnchor)) {
                to = dayAnchor;
            }
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException("结束时间必须晚于开始时间；跨午夜请明确两端日期。");
            }
            if (Duration.between(from, to).compareTo(Duration.ofHours(config.getInt("maxRangeHours"))) > 0) {
                throw new IllegalArgumentException("检索时间跨度超过该档案上限，请缩小范围。");
            }
            if (!config.getBoolean("allowFuture") && to.isAfter(now)) {
                throw new IllegalArgumentException("请求包含尚未发生的时间，请确认日期或选择已发生的范围。");
            }
            return new JSONObject()
                    .put("from", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(from.atZone(zone)))
                    .put("to", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(to.atZone(zone)))
                    .put("zone", zone.getId())
                    .put("mode", config.getString("mode"));
        } catch (Exception e) {
            issue = e.getMessage();
            return null;
        }
    }
}
