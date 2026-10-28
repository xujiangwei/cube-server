package cube.dispatcher.monitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded aggregates only: request bodies, credentials and concrete resource IDs are never retained. */
public final class RestMetrics {
    public static final long BUCKET_MS = 10_000;
    private final LongSupplier nanos;
    private final long originNanos;
    private final long startedAt;
    private final Group all = new Group(181);
    private final Map<String, Group> modules = new LinkedHashMap<>();
    private final Map<String, Group> routes = new LinkedHashMap<>();
    private final Map<String, Group> downstream = new LinkedHashMap<>();

    public RestMetrics() { this(System::nanoTime, System::currentTimeMillis); }

    public RestMetrics(LongSupplier nanos, LongSupplier wallClock) {
        this.nanos = nanos;
        this.originNanos = nanos.getAsLong();
        this.startedAt = wallClock.getAsLong();
    }

    private long elapsed() { return Math.max(0, (nanos.getAsLong() - originNanos) / 1_000_000); }

    public static final class Sample {
        private final long start;
        private final Group module;
        private final Group route;
        private long firstByte = -1;
        private boolean failed;
        private boolean complete;
        private Sample(long start, Group module, Group route) {
            this.start = start; this.module = module; this.route = route;
        }
    }

    public synchronized Sample begin(String module, String route, String method) {
        // Catalog-derived keys are finite. This extra bound also protects custom extension registrations.
        String key = module + "\n" + method + "\n" + route;
        if (!routes.containsKey(key) && routes.size() >= 1024) {
            module = "Other"; method = "OTHER"; route = "未匹配路由";
            key = module + "\n" + method + "\n" + route;
        }
        Group m = modules.computeIfAbsent(module, ignored -> new Group(7));
        Group r = routes.computeIfAbsent(key, ignored -> new Group(7));
        long epoch = elapsed() / BUCKET_MS;
        for (Group g : new Group[] {all, m, r}) {
            ++g.inFlight;
            ++g.total.requests;
            ++g.bucket(epoch).requests;
        }
        return new Sample(nanos.getAsLong(), m, r);
    }

    public synchronized void firstByte(Sample sample) {
        if (sample != null && !sample.complete && sample.firstByte < 0) sample.firstByte = nanos.getAsLong();
    }

    public synchronized void failure(Sample sample) {
        if (sample != null && !sample.complete) sample.failed = true;
    }

    public synchronized void complete(Sample sample, int status, boolean streaming) {
        if (sample == null || sample.complete) return;
        sample.complete = true;
        double duration = Math.max(0, nanos.getAsLong() - sample.start) / 1_000_000.0;
        double first = sample.firstByte < 0 ? -1 : Math.max(0, sample.firstByte - sample.start) / 1_000_000.0;
        long epoch = elapsed() / BUCKET_MS;
        for (Group g : new Group[] {all, sample.module, sample.route}) {
            --g.inFlight;
            g.total.finish(status, sample.failed, streaming, duration, first);
            g.bucket(epoch).finish(status, sample.failed, streaming, duration, first);
        }
    }

    public synchronized void sent(String endpoint, boolean accepted) {
        Group group = downstream.computeIfAbsent(endpoint, ignored -> new Group(7));
        for (Stats stats : new Stats[] {group.total, group.bucket(elapsed() / BUCKET_MS)}) {
            ++stats.requests;
            if (!accepted) ++stats.failures;
        }
    }

    public synchronized JSONObject downstream(String endpoint) {
        Group group = downstream.get(endpoint);
        Stats recent = group == null ? new Stats() : group.sum(elapsed() / BUCKET_MS - 6, elapsed() / BUCKET_MS);
        return new JSONObject().put("calls", recent.requests).put("sendFailures", recent.failures)
                .put("totalCalls", group == null ? 0 : group.total.requests)
                .put("totalSendFailures", group == null ? 0 : group.total.failures);
    }

    public synchronized JSONObject snapshot() {
        long elapsed = elapsed();
        long end = elapsed / BUCKET_MS;
        long start = Math.max(0, end - 6);
        double seconds = (end - start) * BUCKET_MS / 1000.0;
        JSONArray moduleRows = new JSONArray();
        for (Map.Entry<String, Group> entry : modules.entrySet()) {
            moduleRows.put(row(entry.getValue(), start, end, seconds).put("module", entry.getKey()));
        }
        JSONArray routeRows = new JSONArray();
        for (Map.Entry<String, Group> entry : routes.entrySet()) {
            String[] key = entry.getKey().split("\n", 3);
            routeRows.put(row(entry.getValue(), start, end, seconds)
                    .put("module", key[0]).put("method", key[1]).put("route", key[2]));
        }
        JSONArray series = new JSONArray();
        for (long epoch = Math.max(0, end - 180); epoch < end; ++epoch) {
            series.put(all.sum(epoch, epoch + 1).json(10).put("timestamp", startedAt + epoch * BUCKET_MS));
        }
        return new JSONObject().put("startedAt", startedAt).put("windowSeconds", seconds)
                .put("windowStartedAt", startedAt + start * BUCKET_MS).put("windowEndedAt", startedAt + end * BUCKET_MS)
                .put("window", all.sum(start, end).json(seconds)).put("inFlight", all.inFlight)
                .put("total", all.total.json(elapsed / 1000.0)).put("series", series)
                .put("modules", moduleRows).put("routes", routeRows);
    }

    private static JSONObject row(Group group, long start, long end, double seconds) {
        return new JSONObject().put("window", group.sum(start, end).json(seconds))
                .put("totalRequests", group.total.requests).put("inFlight", group.inFlight);
    }

    private static final class Group {
        final Stats total = new Stats();
        final Stats[] buckets;
        int inFlight;
        Group(int size) { buckets = new Stats[size]; }
        Stats bucket(long epoch) {
            int slot = (int) (epoch % buckets.length);
            if (buckets[slot] == null || buckets[slot].epoch != epoch) {
                buckets[slot] = new Stats();
                buckets[slot].epoch = epoch;
            }
            return buckets[slot];
        }
        Stats sum(long start, long end) {
            Stats result = new Stats();
            for (Stats bucket : buckets) {
                if (bucket != null && bucket.epoch >= start && bucket.epoch < end) result.add(bucket);
            }
            return result;
        }
    }

    private static final class Stats {
        // Quarter-octave upper bounds in milliseconds (about 19% relative resolution).
        final long[] histogram = new long[128];
        final long[] statuses = new long[6];
        long epoch, requests, completed, failures, transportFailures, normalCount, streamCount, firstByteCount;
        double durationMs, maxMs, streamDurationMs, firstByteMs;

        void finish(int status, boolean transport, boolean stream, double duration, double first) {
            ++completed;
            if (status >= 100 && status < 600) ++statuses[status / 100];
            if (transport) ++transportFailures;
            if ((status >= 400 && status < 600) || transport) ++failures;
            if (stream) {
                ++streamCount;
                streamDurationMs += duration;
                if (first >= 0) { ++firstByteCount; firstByteMs += first; }
            } else {
                ++normalCount;
                durationMs += duration;
                maxMs = Math.max(maxMs, duration);
                int index = duration <= 1 ? 0 : Math.min(127, (int) Math.ceil(Math.log(duration) / Math.log(2) * 4));
                ++histogram[index];
            }
        }
        void add(Stats other) {
            requests += other.requests; completed += other.completed; failures += other.failures;
            transportFailures += other.transportFailures; normalCount += other.normalCount; streamCount += other.streamCount;
            firstByteCount += other.firstByteCount; durationMs += other.durationMs; maxMs = Math.max(maxMs, other.maxMs);
            streamDurationMs += other.streamDurationMs; firstByteMs += other.firstByteMs;
            for (int i = 0; i < histogram.length; ++i) histogram[i] += other.histogram[i];
            for (int i = 0; i < statuses.length; ++i) statuses[i] += other.statuses[i];
        }
        Object p95() {
            if (normalCount == 0) return JSONObject.NULL;
            long rank = (long) Math.ceil(normalCount * .95), count = 0;
            for (int i = 0; i < histogram.length; ++i) {
                count += histogram[i];
                if (count >= rank) return rounded(Math.min(maxMs, Math.pow(2, i / 4.0)));
            }
            return rounded(maxMs);
        }
        JSONObject json(double seconds) {
            JSONObject codes = new JSONObject();
            for (int i = 1; i < statuses.length; ++i) codes.put(i + "xx", statuses[i]);
            return new JSONObject().put("requests", requests).put("completed", completed)
                    .put("failures", failures).put("transportFailures", transportFailures).put("statusCodes", codes)
                    .put("rps", seconds <= 0 ? 0 : rounded(requests / seconds))
                    .put("failureRate", completed == 0 ? JSONObject.NULL : rounded(100.0 * failures / completed))
                    .put("normalCount", normalCount).put("avgMs", mean(durationMs, normalCount)).put("p95Ms", p95())
                    .put("streamCount", streamCount).put("streamAvgMs", mean(streamDurationMs, streamCount))
                    .put("firstByteAvgMs", mean(firstByteMs, firstByteCount));
        }
        static Object mean(double sum, long count) { return count == 0 ? JSONObject.NULL : rounded(sum / count); }
        static double rounded(double number) { return Math.round(number * 1000) / 1000.0; }
    }
}
