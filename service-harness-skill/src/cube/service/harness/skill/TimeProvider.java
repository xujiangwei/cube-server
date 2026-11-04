/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.store.StoreException;
import org.json.JSONObject;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * 时间查询技能：给出当前时刻，或把给定时刻换算到指定时区。
 */
public class TimeProvider extends AbstractProvider {
    /**
     * 毫秒时间戳的安全上限，对应 JavaScript 的 {@code Number.MAX_SAFE_INTEGER} 。
     */
    private static final long MAX_SAFE_MILLIS = 9007199254740991L;

    /**
     * 默认时区。
     */
    private final ZoneId defaultZone;

    /**
     * 时钟来源，便于测试注入固定时刻。
     */
    private final Clock clock;

    /**
     * 构造函数。
     *
     * @param p 技能私有配置。
     */
    public TimeProvider(Properties p) {
        this(p, Clock.systemUTC());
    }

    /**
     * 构造函数。
     *
     * @param p     技能私有配置。
     * @param clock 时钟来源。
     */
    TimeProvider(Properties p, Clock clock) {
        super("time", "common.time.query", "time.read", p);
        this.defaultZone = ZoneId.of(p.getProperty("time.default.zone", "Asia/Shanghai"));
        this.clock = clock;
    }

    /**
     * 校验调用参数：{@code convert} 必须带 {@code instant} ，{@code now} 必须不带。
     *
     * @param args 调用参数。
     */
    @Override
    public void validate(JSONObject args) {
        super.validate(args);
        // 两个动作对 instant 的要求恰好相反，用异或表达。
        if (args.getString("action").equals("convert") != args.has("instant")) {
            throw new StoreException("TIME_ARGUMENTS_INVALID");
        }
        try {
            ZoneId.of(args.optString("zone", this.defaultZone.getId()));
            if (args.has("instant")) {
                OffsetDateTime.parse(args.getString("instant"));
            }
        } catch (DateTimeException e) {
            throw new StoreException("TIME_ARGUMENTS_INVALID");
        }
    }

    /**
     * 执行时间查询。
     *
     * @param context Broker 提供的可信调用上下文。
     * @param args    调用参数。
     * @return 返回时间信息。
     */
    @Override
    protected JSONObject execute(ProviderInvocation context, JSONObject args) {
        return value(args);
    }

    /**
     * 计算目标时区下的时间表示。
     *
     * @param args 调用参数。
     * @return 返回时间信息，含本地时间与偏移。
     */
    JSONObject value(JSONObject args) {
        validate(args);
        ZoneId zone = ZoneId.of(args.optString("zone", this.defaultZone.getId()));
        Instant instant;
        if (args.getString("action").equals("now")) {
            instant = this.clock.instant();
        } else {
            instant = OffsetDateTime.parse(args.getString("instant")).toInstant();
        }
        try {
            ZonedDateTime date = instant.atZone(zone);
            long millis = instant.toEpochMilli();
            // 超出安全整数范围的毫秒值在下游会被静默截断，这里直接拒绝。
            if (millis < -MAX_SAFE_MILLIS || millis > MAX_SAFE_MILLIS) {
                throw new StoreException("TIME_RANGE_INVALID");
            }
            return new JSONObject()
                    .put("instant", instant.toString())
                    .put("zone", zone.getId())
                    .put("localTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(date))
                    .put("epochMillis", millis)
                    .put("offset", date.getOffset().getId());
        } catch (DateTimeException | ArithmeticException e) {
            throw new StoreException("TIME_RANGE_INVALID");
        }
    }
}
