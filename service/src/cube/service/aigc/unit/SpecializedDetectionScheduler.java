package cube.service.aigc.unit;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.SpecializedDetectionException;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.entity.*;
import cube.service.aigc.AIGCService;
import cube.service.aigc.event.Event;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/** Java admission budgets, scoped to a registered provider connection. Python remains authoritative. */
public final class SpecializedDetectionScheduler {
    private final AIGCService service;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private final Map<Long, SpecializedDetectionUnitMeta> pending = new HashMap<>();
    private final LinkedHashMap<String, Provider> earlyStops = new LinkedHashMap<>();
    private volatile Properties config = new Properties();
    private ScheduledExecutorService timer;
    private boolean closed;
    private long cursor;

    static final class Provider {
        final AIGCUnit unit;
        final TalkContext context;
        int starting, images;
        Provider(AIGCUnit unit) { this.unit = unit; this.context = unit.getContext(); }
    }

    static final class Session {
        final String id;
        final Provider provider;
        final SpecializedDetectionUnitMeta origin;
        JSONObject snapshot;
        boolean terminal, polling, orphan;
        long lastPoll;
        Session(String id, Provider provider, SpecializedDetectionUnitMeta origin, JSONObject snapshot) {
            this.id = id; this.provider = provider; this.origin = origin; this.snapshot = snapshot;
        }
    }

    public static final class Reservation {
        final Provider provider;
        final Session session;
        final String operation;
        boolean released;
        Reservation(Provider provider, Session session, String operation) {
            this.provider = provider; this.session = session; this.operation = operation;
        }
        public AIGCUnit unit() { return provider.unit; }
        public TalkContext context() { return provider.context; }
    }

    public SpecializedDetectionScheduler(AIGCService service) { this.service = service; }

    public void configure(Properties properties) {
        Properties copy = new Properties(); copy.putAll(properties);
        // Validate defaults before publishing a hot-reloaded configuration.
        positive(copy, "specialized.detection.max.sessions", 2, 64);
        positive(copy, "specialized.detection.data.timeout.ms", 180000, 3600000);
        positive(copy, "specialized.detection.control.timeout.ms", 30000, 3600000);
        config = copy;
    }

    private static int positive(Properties p, String key, int fallback, int max) {
        int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
        if (value < 1 || value > max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    public long timeout(SpecializedDetectionInput input) {
        return positive(config, "specialized.detection." + (input.isControl() ? "control" : "data") + ".timeout.ms",
                input.isControl() ? 30000 : 180000, 3600000);
    }

    private String setting(Provider provider, String key, String fallback) {
        String prefix = "specialized.detection.node." + provider.unit.getContact().getDomain().getName()
                + "." + provider.unit.getContact().getId() + ".";
        return config.getProperty(prefix + key, config.getProperty("specialized.detection." + key, fallback));
    }

    private int max(Provider provider) {
        try { int n = Integer.parseInt(setting(provider, "max.sessions", "2")); return Math.max(1, Math.min(64, n)); }
        catch (NumberFormatException error) { return 2; }
    }

    private Set<String> profiles(Provider provider) {
        Set<String> result = new HashSet<>();
        for (String name : setting(provider, "profiles", "uav-fire-smoke,fire-smoke").split(",")) result.add(name.trim());
        result.retainAll(Arrays.asList("uav-fire-smoke", "fire-smoke"));
        return result;
    }

    static boolean same(TalkContext a, TalkContext b) {
        return a == b || (a != null && b != null && a.getSession() != null && a.getSession() == b.getSession());
    }

    private boolean live(Provider p, List<AIGCUnit> units) {
        return units.stream().anyMatch(unit -> unit == p.unit) && p.context != null && p.context.isValid()
                && same(p.context, p.unit.getContext());
    }

    private void clean() {
        List<AIGCUnit> units = service.getAllUnits();
        Set<Provider> removed = new HashSet<>();
        Iterator<Provider> iterator = providers.values().iterator();
        while (iterator.hasNext()) {
            Provider p = iterator.next();
            if (!live(p, units)) { removed.add(p); iterator.remove(); }
        }
        sessions.values().removeIf(s -> removed.contains(s.provider));
        earlyStops.values().removeIf(removed::contains);
        pending.values().removeIf(meta -> removed.contains(meta.reservation.provider));
        for (AIGCUnit unit : units) if (SpecializedDetectionInput.UNIT.equals(unit.getCapability().getName())
                && unit.getContext() != null && unit.getContext().isValid())
            providers.computeIfAbsent(unit.getQueryKey(), key -> new Provider(unit));
    }

    private int active(Provider p) {
        int count = 0;
        for (Session s : sessions.values()) if (s.provider == p && !s.terminal) count++;
        return count;
    }

    private synchronized void startTimer() {
        if (timer != null || closed) return;
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, job -> {
            Thread t = new Thread(job, "specialized-detection-reconcile"); t.setDaemon(true); return t;
        });
        scheduled.setRemoveOnCancelPolicy(true);
        timer = scheduled;
        timer.scheduleWithFixedDelay(() -> {
            try { reconcile(); }
            catch (RuntimeException error) { cell.util.log.Logger.w(getClass(), "Detection reconciliation failed", error); }
        }, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized ScheduledFuture<?> watch(SpecializedDetectionUnitMeta meta, long delay) {
        startTimer();
        if (closed) throw SpecializedDetectionException.unit(30);
        return timer.schedule(meta::expire, Math.max(0, delay), TimeUnit.MILLISECONDS);
    }

    public synchronized Reservation reserve(SpecializedDetectionInput input, AuthToken token) {
        if (closed) throw SpecializedDetectionException.unit(30);
        clean(); startTimer();
        if (input.isControl()) {
            Session s = sessions.get(input.streamId);
            if (s == null) throw SpecializedDetectionException.unit(3);
            if (!s.origin.usageContext.domain.equals(token.getDomain()) || s.origin.usageContext.contactId != token.getContactId())
                throw SpecializedDetectionException.unit(25);
            return new Reservation(s.provider, s, input.operation);
        }
        List<Provider> candidates = new ArrayList<>();
        boolean supported = false;
        for (Provider p : providers.values()) if (profiles(p).contains(input.profile)) {
            supported = true;
            if ("start".equals(input.operation) ? active(p) + p.starting < max(p) : p.images == 0) candidates.add(p);
        }
        if (candidates.isEmpty()) throw SpecializedDetectionException.unit(supported ? 7 : 30);
        // Rotate before sorting, giving equal loads a deterministic round-robin tie break.
        Collections.rotate(candidates, -(int) Math.floorMod(cursor++, (long) candidates.size()));
        candidates.sort(Comparator.comparingInt(p -> active(p) + p.starting + p.images));
        Provider p = candidates.get(0);
        if ("start".equals(input.operation)) p.starting++; else p.images++;
        return new Reservation(p, null, input.operation);
    }

    public synchronized boolean valid(Reservation r) { clean(); return !closed && providers.get(r.provider.unit.getQueryKey()) == r.provider; }

    public synchronized void sent(SpecializedDetectionUnitMeta meta) {
        if (!valid(meta.reservation)) throw SpecializedDetectionException.unit(30);
        pending.put(meta.sn, meta);
    }

    /** Called before removing a synchronous responder, including late replies. */
    public void onResponse(TalkContext context, ActionDialect dialect) {
        if (!"specializedDetection".equals(dialect.getName())) return;
        Packet packet;
        try { packet = new Packet(dialect); } catch (RuntimeException error) { return; }
        SpecializedDetectionUnitMeta meta;
        synchronized (this) { meta = pending.get(packet.sn); }
        if (meta != null && same(context, meta.reservation.context()) && valid(meta.reservation)) meta.captureResponse(packet);
    }

    public synchronized void release(Reservation r) {
        if (r.released) return;
        r.released = true;
        if ("start".equals(r.operation)) r.provider.starting = Math.max(0, r.provider.starting - 1);
        else if ("detect".equals(r.operation)) r.provider.images = Math.max(0, r.provider.images - 1);
    }

    public synchronized void unsent(SpecializedDetectionUnitMeta meta) { pending.remove(meta.sn); release(meta.reservation); }

    public synchronized void forgetControl(SpecializedDetectionUnitMeta meta) {
        // Control timeouts do not create unknown sessions; reconciliation supplies later evidence.
        if (meta.input.isControl()) pending.remove(meta.sn);
    }

    /** Apply only a fully correlated response; uncertain starts keep their reservation. */
    public synchronized void completed(SpecializedDetectionUnitMeta meta, int code, JSONObject payload) {
        Reservation r = meta.reservation;
        if (!valid(r)) { pending.remove(meta.sn); return; }
        if ("start".equals(r.operation) && code == 0) {
            String id = payload.getString("streamId");
            Session old = sessions.get(id);
            if (old != null && old.origin != meta) throw new IllegalArgumentException("Duplicate stream identity");
            Session s = old == null ? new Session(id, r.provider, meta, new JSONObject(payload.toString())) : old;
            s.orphan = meta.isTimedOut();
            s.terminal = terminal(payload) || earlyStops.get(id) == r.provider;
            earlyStops.remove(id);
            sessions.put(id, s);
            release(r);
            trim(r.provider);
        } else if (r.session != null) {
            if (code == 0) update(r.session, payload);
            else if (code == 3) r.session.terminal = true;
            trim(r.provider);
        } else if ("detect".equals(r.operation) || Arrays.asList(1, 3, 7, 30, 32).contains(code)) release(r);
        // An internal start failure cannot prove that no background session was created.
        if (!"start".equals(r.operation) || r.released) pending.remove(meta.sn);
    }

    private static boolean terminal(JSONObject payload) {
        return "stopped".equals(payload.optString("status")) || "failed".equals(payload.optString("status"));
    }

    private void update(Session s, JSONObject payload) {
        if (!s.id.equals(payload.getString("streamId"))) throw new IllegalArgumentException("Stream identity changed");
        if (!s.terminal) s.snapshot = new JSONObject(payload.toString());
        s.terminal |= terminal(payload);
    }

    private void trim(Provider p) {
        int count = 0;
        for (Session s : sessions.values()) if (s.provider == p && s.terminal) count++;
        Iterator<Session> iterator = sessions.values().iterator();
        while (count > 20 && iterator.hasNext()) {
            Session s = iterator.next();
            if (s.provider == p && s.terminal) { iterator.remove(); count--; }
        }
    }

    public synchronized void event(TalkContext context, Event event) {
        if (!SpecializedDetectionInput.UNIT.equals(event.source) || !SpecializedDetectionInput.UNIT.equals(event.payload.optString("unit"))
                || !("streamStarted".equals(event.name) || "streamStopped".equals(event.name))) return;
        clean();
        String id = event.payload.optString("streamId", "");
        if (id.isEmpty()) return;
        Session s = sessions.get(id);
        if (s != null) {
            if (!same(context, s.provider.context)) return;
            if ("streamStopped".equals(event.name)) { s.terminal = true; trim(s.provider); }
        } else if ("streamStopped".equals(event.name)) {
            for (Provider p : providers.values()) if (same(context, p.context) && p.starting > 0) {
                earlyStops.put(id, p);
                while (earlyStops.size() > 1024) earlyStops.remove(earlyStops.keySet().iterator().next());
                break;
            }
        }
    }

    public synchronized JSONObject snapshot() {
        clean();
        JSONArray nodes = new JSONArray();
        for (Provider p : providers.values()) nodes.put(new JSONObject().put("profiles", new JSONArray(profiles(p)))
                .put("maxSessions", max(p)).put("availableSessions", Math.max(0, max(p) - active(p) - p.starting))
                .put("activeSessions", active(p)).put("pendingStarts", p.starting).put("availableImages", p.images == 0 ? 1 : 0));
        return new JSONObject().put("version", 1).put("readiness", "unknown").put("budgetSource", "java-config")
                .put("providers", nodes);
    }

    public void reconcile() {
        List<SpecializedDetectionUnitMeta> late;
        List<Session> poll = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            clean();
            late = new ArrayList<>(pending.values());
            long now = System.currentTimeMillis();
            for (Session s : sessions.values()) if (!s.terminal && !s.polling && now - s.lastPoll >= 5000) {
                s.polling = true; s.lastPoll = now; poll.add(s);
            }
        }
        for (SpecializedDetectionUnitMeta meta : late) meta.drainLateResponse();
        for (Session s : poll) {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    positive(config, "specialized.detection.control.timeout.ms", 30000, 3600000));
            if (!service.submitDetectionControl(() -> poll(s, deadline))) synchronized (this) { s.polling = false; }
        }
    }

    private void poll(Session s, long deadline) {
        try {
            synchronized (this) { clean(); if (closed || sessions.get(s.id) != s || s.terminal) return; }
            JSONObject option = new JSONObject().put("operation", s.orphan ? "stop" : "status").put("streamId", s.id);
            Packet request = new Packet("specializedDetection", new JSONObject().put("unit", SpecializedDetectionInput.UNIT)
                    .put("files", new JSONArray()).put("option", option));
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) return;
            ActionDialect dialect = service.getCellet().transmit(s.provider.context, request.toDialect(), remaining, request.sn);
            if (dialect == null) return;
            Packet reply = new Packet(dialect);
            if (!request.sn.equals(reply.sn) || !request.name.equals(reply.name)) return;
            synchronized (this) {
                if (!live(s.provider, service.getAllUnits()) || sessions.get(s.id) != s) return;
                int code = Packet.extractCode(reply);
                if (code == 3) s.terminal = true;
                else if (code == 0) {
                    JSONObject payload = Packet.extractDataPayload(reply);
                    new SpecializedDetectionOutput(request.sn, payload);
                    update(s, payload.getJSONObject("resultPayload"));
                }
                trim(s.provider);
            }
        } catch (RuntimeException error) {
            cell.util.log.Logger.w(getClass(), "Detection status reconciliation failed");
        } finally { synchronized (this) { s.polling = false; } }
    }

    public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
        sessions.clear(); providers.clear(); pending.clear(); earlyStops.clear();
    }
}
