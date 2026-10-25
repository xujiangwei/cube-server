package cube.service.harness.skill;

import cube.service.harness.execution.ProviderInvocation;
import cube.service.harness.store.StoreException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import org.json.JSONObject;

public class TimeProvider extends AbstractProvider {
    private final ZoneId defaultZone;
    private final Clock clock;
    public TimeProvider(Properties p) { this(p, Clock.systemUTC()); }
    TimeProvider(Properties p, Clock clock) {
        super("time", "common.time.query", "time.read", p);
        defaultZone = ZoneId.of(p.getProperty("time.default.zone", "Asia/Shanghai"));
        this.clock = clock;
    }
    public void validate(JSONObject args) {
        super.validate(args);
        if (args.getString("action").equals("convert") != args.has("instant"))
            throw new StoreException("TIME_ARGUMENTS_INVALID");
        try {
            ZoneId.of(args.optString("zone", defaultZone.getId()));
            if (args.has("instant")) OffsetDateTime.parse(args.getString("instant"));
        } catch (DateTimeException e) { throw new StoreException("TIME_ARGUMENTS_INVALID"); }
    }
    protected JSONObject execute(ProviderInvocation c, JSONObject args) {
        return value(args);
    }
    JSONObject value(JSONObject args) {
        validate(args);
        ZoneId zone = ZoneId.of(args.optString("zone", defaultZone.getId()));
        Instant instant = args.getString("action").equals("now") ? clock.instant()
                : OffsetDateTime.parse(args.getString("instant")).toInstant();
        try {
            ZonedDateTime date = instant.atZone(zone);
            long millis = instant.toEpochMilli();
            if (millis < -9007199254740991L || millis > 9007199254740991L)
                throw new StoreException("TIME_RANGE_INVALID");
            return new JSONObject().put("instant", instant.toString()).put("zone", zone.getId())
                    .put("localTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(date))
                    .put("epochMillis", millis).put("offset", date.getOffset().getId());
        } catch (DateTimeException | ArithmeticException e) { throw new StoreException("TIME_RANGE_INVALID"); }
    }
}
