package cube.monitor;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded, monotonic service operation metrics. No payloads, tokens or run IDs are retained. */
public final class ServiceMetrics {
    private final LongSupplier clock;
    private final long origin, startedAt;
    private final Group business = new Group(181), controls = new Group(7);
    private final Map<String, Group> modules = new LinkedHashMap<>(), actions = new LinkedHashMap<>();
    private long messages;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, JSONObject> workers = new LinkedHashMap<>();
    public ServiceMetrics() { this(System::nanoTime, System::currentTimeMillis); }
    public ServiceMetrics(LongSupplier clock, LongSupplier wall) { this.clock = clock; origin = clock.getAsLong(); startedAt = wall.getAsLong(); }
    private long now() { return Math.max(0, clock.getAsLong() - origin); }
    private long epoch() { return now() / 10_000_000_000L; }
    public synchronized void message() { ++messages; }
    public static boolean control(String action) {
        return Arrays.asList("latency", "getcapabilitysnapshot", "pluginstatus", "keepalive", "keepalivechannel",
                "heartbeat").contains(action.toLowerCase(Locale.ROOT));
    }
    private static String label(String value) {
        if (value == null || value.isEmpty()) return "Unknown";
        value = value.replaceAll("[\\p{Cntrl}]", "_");
        return value.substring(0, Math.min(128, value.length()));
    }
    public static final class Sample {
        final Group[] groups; final long submitted; long started = -1, first = -1; boolean done, streaming;
        Sample(long at, Group... groups) { submitted = at; this.groups = groups; }
    }
    public synchronized Sample submit(String module, String action, boolean control) {
        module = label(module); action = label(action);
        String key = module + "\n" + action + "\n" + control;
        if (!actions.containsKey(key) && actions.size() >= 1024) { module = "Other"; action = "未匹配动作"; key = module + "\n" + action + "\n" + control; }
        Group m = modules.computeIfAbsent(module + "\n" + control, ignored -> new Group(7));
        Group a = actions.computeIfAbsent(key, ignored -> new Group(7));
        Sample sample = new Sample(now(), control ? controls : business, m, a);
        for (Group g : sample.groups) { Stats bucket = g.touch(epoch()); ++g.queued; ++bucket.requests; ++g.total.requests; g.gauge(bucket); }
        return sample;
    }
    public synchronized void start(Sample sample) {
        if (sample == null || sample.done || sample.started >= 0) return;
        sample.started = now();
        for (Group g : sample.groups) { Stats b = g.touch(epoch()); --g.queued; ++g.active; g.gauge(b); }
    }
    public synchronized void first(Sample sample) { if (sample != null && !sample.done && sample.first < 0) sample.first = now(); }
    public synchronized void streaming(Sample sample) { if (sample != null) sample.streaming = true; }
    public synchronized void complete(Sample sample, String outcome) {
        if (sample == null || sample.done) return;
        sample.done = true;
        if (!Arrays.asList("succeeded", "failed", "timed_out", "cancelled", "rejected", "accepted", "unknown").contains(outcome)) outcome = "unknown";
        long at = now(); double duration = (at - sample.submitted) / 1e6;
        double queue = ((sample.started < 0 ? at : sample.started) - sample.submitted) / 1e6;
        for (Group g : sample.groups) {
            Stats b = g.touch(epoch());
            if (sample.started < 0) --g.queued; else --g.active;
            for (Stats s : new Stats[] {b, g.total}) s.finish(outcome, duration, queue, sample.streaming, sample.first < 0 ? -1 : (sample.first - sample.submitted) / 1e6);
            g.gauge(b);
        }
    }
    private static JSONObject row(Group g, long end, double seconds) {
        g.touch(end);
        return new JSONObject().put("window", g.sum(Math.max(0, end - 6), end).json(seconds))
                .put("total", g.total.json(0)).put("queued", g.queued).put("running", g.active);
    }
    public synchronized JSONObject snapshot() {
        long end = epoch(); double seconds = Math.min(6, end) * 10;
        business.touch(end); controls.touch(end);
        JSONArray series = new JSONArray(), moduleRows = new JSONArray(), actionRows = new JSONArray();
        for (long e = Math.max(0, end - 180); e < end; ++e) series.put(business.sum(e, e + 1).json(10).put("timestamp", startedAt + e * 10000));
        for (Map.Entry<String, Group> entry : modules.entrySet()) {
            String[] key = entry.getKey().split("\n", 2);
            if (key[1].equals("false")) moduleRows.put(row(entry.getValue(), end, seconds).put("module", key[0]));
        }
        for (Map.Entry<String, Group> entry : actions.entrySet()) {
            String[] key = entry.getKey().split("\n", 3);
            actionRows.put(row(entry.getValue(), end, seconds).put("module", key[0]).put("action", key[1]).put("control", Boolean.parseBoolean(key[2])));
        }
        return row(business, end, seconds).put("startedAt", startedAt).put("windowSeconds", seconds)
                .put("windowStartedAt", startedAt + Math.max(0, end - 6) * 10000).put("windowEndedAt", startedAt + end * 10000)
                .put("series", series).put("modules", moduleRows).put("actions", actionRows)
                .put("control", row(controls, end, seconds)).put("receivedMessages", messages);
    }
    public synchronized void observePeer(cell.core.talk.TalkContext context, cell.core.talk.dialect.ActionDialect dialect) {
        if (context == null || dialect == null || !dialect.containsParam("_performer")) return;
        JSONObject source = dialect.getParamAsJson("_performer");
        String address = context.getSessionHost() + ":" + context.getSessionPort();
        String name = source == null ? "" : source.optString("node", "");
        String id = name.isEmpty() ? address : name;
        if (!peers.containsKey(id) && peers.size() >= 256) peers.remove(peers.keySet().iterator().next());
        Peer peer = peers.computeIfAbsent(id, ignored -> new Peer(context, address));
        peer.context = context; peer.address = address; peer.name = name.isEmpty() ? "Dispatcher" : name; peer.seen = now(); ++peer.calls;
    }
    public synchronized JSONArray upstreams() {
        peers.values().removeIf(p -> now() - p.seen > 1_800_000_000_000L);
        JSONArray result = new JSONArray();
        for (Map.Entry<String, Peer> entry : peers.entrySet()) {
            Peer p = entry.getValue();
            result.put(new JSONObject().put("id", entry.getKey()).put("name", p.name).put("address", p.address)
                    .put("ready", p.context.isValid()).put("totalCalls", p.calls).put("lastSeenAgeMs", (now() - p.seen) / 1_000_000));
        }
        return result;
    }
    /** Retain recently disconnected workers, without retaining credentials or descriptors. */
    public synchronized JSONArray rememberWorkers(JSONArray current, Set<String> failedModules) {
        Set<String> seen = new HashSet<>(); long at = now() / 1_000_000;
        for (int i = 0; i < current.length(); ++i) {
            JSONObject worker = new JSONObject(current.getJSONObject(i).toString());
            String id = worker.getString("module") + ":" + worker.getString("workerId") + ":" + worker.optString("instanceId", "");
            worker.put("id", id).put("connected", true).put("lastSeen", at); seen.add(id);
            if (!workers.containsKey(id) && workers.size() >= 512) workers.remove(workers.keySet().iterator().next());
            workers.put(id, worker);
        }
        workers.values().removeIf(w -> at - w.getLong("lastSeen") > 1_800_000);
        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : workers.entrySet()) {
            JSONObject worker = new JSONObject(entry.getValue().toString());
            if (!seen.contains(entry.getKey())) worker.put("connected", failedModules.contains(worker.getString("module")) ? JSONObject.NULL : false)
                    .put("ready", JSONObject.NULL).put("healthy", JSONObject.NULL).put("available", JSONObject.NULL);
            worker.put("lastSeenAgeMs", at - worker.getLong("lastSeen")); worker.remove("lastSeen"); result.put(worker);
        }
        return result;
    }
    private static class Peer {
        cell.core.talk.TalkContext context; String address; String name; long seen, calls;
        Peer(cell.core.talk.TalkContext context, String address) { this.context = context; this.address = address; }
    }
    private static class Group {
        final Stats total = new Stats(); final Stats[] buckets; int queued, active; long last = -1;
        Group(int size) { buckets = new Stats[size]; }
        Stats touch(long epoch) {
            for (long e = Math.max(last + 1, epoch - buckets.length + 1); e <= epoch; ++e) {
                Stats b = new Stats(); b.epoch = e; gauge(b); buckets[(int) (e % buckets.length)] = b;
            }
            last = Math.max(last, epoch); return buckets[(int) (epoch % buckets.length)];
        }
        void gauge(Stats b) { b.queuedPeak = Math.max(b.queuedPeak, queued); b.runningPeak = Math.max(b.runningPeak, active); total.queuedPeak = Math.max(total.queuedPeak, queued); total.runningPeak = Math.max(total.runningPeak, active); }
        Stats sum(long start, long end) { Stats sum = new Stats(); for (Stats b : buckets) if (b != null && b.epoch >= start && b.epoch < end) sum.add(b); return sum; }
    }
    private static class Stats {
        long epoch, requests, completed, succeeded, failed, timedOut, cancelled, rejected, accepted, unknown, normal, streams, firstCount;
        int queuedPeak, runningPeak; double duration, queue, execution, max, streamDuration, first;
        final long[] histogram = new long[128];
        void finish(String outcome, double ms, double queued, boolean streaming, double firstMs) {
            if (outcome.equals("rejected")) { ++rejected; return; }
            ++completed;
            switch (outcome) { case "succeeded": ++succeeded; break; case "failed": ++failed; break; case "timed_out": ++timedOut; break; case "cancelled": ++cancelled; break; case "accepted": ++accepted; break; default: ++unknown; }
            queue += queued; execution += Math.max(0, ms - queued);
            if (streaming) { ++streams; streamDuration += ms; if (firstMs >= 0) { ++firstCount; first += firstMs; } }
            else { ++normal; duration += ms; max = Math.max(max, ms); ++histogram[ms <= 1 ? 0 : Math.min(127, (int) Math.ceil(Math.log(ms) / Math.log(2) * 4))]; }
        }
        void add(Stats b) {
            requests += b.requests; completed += b.completed; succeeded += b.succeeded; failed += b.failed; timedOut += b.timedOut; cancelled += b.cancelled; rejected += b.rejected; unknown += b.unknown; accepted += b.accepted;
            normal += b.normal; streams += b.streams; firstCount += b.firstCount; duration += b.duration; queue += b.queue; execution += b.execution;
            max = Math.max(max, b.max); streamDuration += b.streamDuration; first += b.first; queuedPeak = Math.max(queuedPeak, b.queuedPeak); runningPeak = Math.max(runningPeak, b.runningPeak);
            for (int i = 0; i < histogram.length; ++i) histogram[i] += b.histogram[i];
        }
        Object p95() { if (normal == 0) return JSONObject.NULL; long count = 0, rank = (long) Math.ceil(normal * .95); for (int i = 0; i < histogram.length; ++i) if ((count += histogram[i]) >= rank) return round(Math.min(max, Math.pow(2, i / 4.0))); return round(max); }
        static double round(double n) { return Math.round(n * 1000) / 1000.0; }
        static Object mean(double sum, long n) { return n == 0 ? JSONObject.NULL : round(sum / n); }
        JSONObject json(double seconds) {
            long known = completed - unknown - accepted;
            return new JSONObject().put("requests", requests).put("completed", completed).put("succeeded", succeeded).put("failed", failed)
                    .put("timedOut", timedOut).put("cancelled", cancelled).put("rejected", rejected).put("unknown", unknown).put("accepted", accepted).put("knownCompleted", known)
                    .put("rps", seconds > 0 ? round(requests / seconds) : 0).put("failureRate", known == 0 ? JSONObject.NULL : round((failed + timedOut) * 100.0 / known))
                    .put("avgMs", mean(duration, normal)).put("p95Ms", p95()).put("queueAvgMs", mean(queue, completed)).put("executionAvgMs", mean(execution, completed))
                    .put("streamCount", streams).put("streamAvgMs", mean(streamDuration, streams)).put("firstByteAvgMs", mean(first, firstCount))
                    .put("queuedPeak", queuedPeak).put("runningPeak", runningPeak);
        }
    }
}
