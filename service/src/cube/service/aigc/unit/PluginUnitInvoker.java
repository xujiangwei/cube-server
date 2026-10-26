package cube.service.aigc.unit;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.spi.PluginCallException;
import cube.common.Packet;
import cube.common.entity.AIGCUnit;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCService;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Exact-name plugin dispatch. Reservations survive caller timeouts. */
public final class PluginUnitInvoker {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private final AIGCService service;
    private final Map<Long, Reservation> pending = new HashMap<>();
    private final Map<AIGCUnit, ReportedStatus> statuses = new IdentityHashMap<>();
    private final Map<TalkContext, Long> sequences = new IdentityHashMap<>();
    private final LongSupplier clock;
    private static final long STATUS_TTL_MS = 30000;
    public PluginUnitInvoker(AIGCService service) { this(service, System::currentTimeMillis); }
    public PluginUnitInvoker(AIGCService service, LongSupplier clock) { this.service = service; this.clock = clock; }

    public static void validateName(String name) {
        if (name == null || name.length() > 64 || !NAME.matcher(name).matches())
            throw new PluginCallException(AIGCStateCode.InvalidParameter, 400, "Invalid plugin unit name");
    }

    private static boolean same(TalkContext a, TalkContext b) {
        return a == b || (a != null && b != null && a.getSession() != null && a.getSession() == b.getSession());
    }

    private synchronized void clean() {
        List<AIGCUnit> current = service.getAllUnits();
        statuses.entrySet().removeIf(entry -> !current.contains(entry.getKey())
                || !same(entry.getValue().context, entry.getKey().getContext()));
        sequences.keySet().removeIf(context -> !context.isValid()
                || current.stream().noneMatch(unit -> same(context, unit.getContext())));
        Iterator<Reservation> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Reservation value = iterator.next();
            boolean registered = false;
            for (AIGCUnit unit : current) if (unit == value.unit) { registered = true; break; }
            if (!value.context.isValid() || !registered
                    || !same(value.context, value.unit.getContext())) {
                value.unit.setRunning(false);
                iterator.remove();
            }
        }
    }

    /** A new setup must report readiness again, including reconnects. */
    public synchronized void onSetup(List<AIGCUnit> units) {
        for (AIGCUnit unit : units) statuses.remove(unit);
    }

    private static long integer(JSONObject data, String name, long low, long high) {
        Object value = data.get(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid " + name);
        long number = new java.math.BigDecimal(value.toString()).longValueExact();
        if (number < low || number > high)
            throw new IllegalArgumentException("Invalid " + name);
        return number;
    }

    /** Validate the entire snapshot before applying anything. Trust only its registered connection. */
    public synchronized boolean report(TalkContext context, JSONObject snapshot) {
        if (context == null || !context.isValid()) return false;
        try {
            clean();
            long sequence = integer(snapshot, "sequence", 1, Long.MAX_VALUE);
            for (Map.Entry<TalkContext, Long> entry : sequences.entrySet())
                if (same(context, entry.getKey()) && sequence <= entry.getValue()) return false;
            JSONArray array = snapshot.getJSONArray("units");
            Map<String, AIGCUnit> connected = new HashMap<>();
            for (AIGCUnit unit : service.getAllUnits())
                if (same(context, unit.getContext())) connected.put(unit.getCapability().getName(), unit);
            if (connected.isEmpty()) return false;
            Map<AIGCUnit, ReportedStatus> updates = new IdentityHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject data = array.getJSONObject(i);
                AIGCUnit unit = connected.get(data.getString("unit"));
                if (unit == null || updates.containsKey(unit)) return false;
                ReportedStatus previous = statuses.get(unit);
                if (previous != null && same(context, previous.context) && sequence <= previous.sequence) return false;
                String state = data.getString("state");
                if (!Arrays.asList("registered", "initializing", "ready", "failed", "stopping", "stopped").contains(state))
                    return false;
                if (!(data.get("ready") instanceof Boolean) || (data.getBoolean("ready") && !state.equals("ready"))) return false;
                int capacity = (int) integer(data, "maxConcurrency", 1, 1024);
                int inFlight = (int) integer(data, "inFlight", 0, capacity);
                JSONObject copy = new JSONObject(data.toString());
                JSONArray requests = copy.getJSONArray("activeRequests");
                Set<Long> active = new HashSet<>();
                for (int j = 0; j < requests.length(); j++) {
                    long sn = integer(new JSONObject().put("sn", requests.get(j)), "sn", 1, Long.MAX_VALUE);
                    if (!active.add(sn)) return false;
                }
                if (active.size() != inFlight) return false;
                copy.getString("stage");
                if (copy.getString("error").length() > 2048) return false;
                JSONArray actions = copy.getJSONArray("actions");
                for (int j = 0; j < actions.length(); j++) validateName(actions.getString(j));
                updates.put(unit, new ReportedStatus(context, sequence, clock.getAsLong(), copy, capacity, active));
            }
            statuses.entrySet().removeIf(entry -> same(context, entry.getValue().context) && !updates.containsKey(entry.getKey()));
            statuses.putAll(updates);
            sequences.keySet().removeIf(previous -> same(context, previous));
            sequences.put(context, sequence);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private ReportedStatus fresh(AIGCUnit unit) {
        ReportedStatus status = statuses.get(unit);
        return status != null && same(status.context, unit.getContext())
                && clock.getAsLong() - status.receivedAt < STATUS_TTL_MS ? status : null;
    }

    private int available(AIGCUnit unit, ReportedStatus status) {
        if (status == null || !status.data.getBoolean("ready")) return 0;
        Set<Long> occupied = new HashSet<>(status.activeRequests);
        for (Map.Entry<Long, Reservation> entry : pending.entrySet())
            if (entry.getValue().unit == unit) occupied.add(entry.getKey());
        return Math.max(0, status.capacity - occupied.size());
    }

    public synchronized JSONObject status(String name) {
        validateName(name);
        clean();
        int providers = 0, ready = 0, capacity = 0;
        JSONArray details = new JSONArray();
        for (AIGCUnit unit : service.getAllUnits()) {
            if (!name.equals(unit.getCapability().getName()) || unit.getContext() == null || !unit.getContext().isValid()) continue;
            providers++;
            ReportedStatus status = fresh(unit);
            JSONObject item = status == null ? new JSONObject().put("state", "unknown").put("stage", "unknown")
                    .put("ready", false).put("error", "Readiness missing or expired").put("actions", new JSONArray())
                    .put("maxConcurrency", 0).put("inFlight", 0).put("activeRequests", new JSONArray()) : new JSONObject(status.data.toString());
            int free = available(unit, status);
            if (status != null && status.data.getBoolean("ready")) ready++;
            capacity += free;
            item.put("availableCapacity", free).put("updatedAt", status == null ? 0 : status.receivedAt)
                    .put("contactId", unit.getContact().getId()).put("domain", unit.getContact().getDomain().getName());
            details.put(item);
        }
        return new JSONObject().put("online", providers > 0).put("providers", providers).put("ready", ready > 0)
                .put("readyProviders", ready).put("availableCapacity", capacity).put("providerStatuses", details)
                .put("readiness", "reported");
    }

    public synchronized int count(String name) {
        validateName(name);
        int count = 0;
        for (AIGCUnit unit : service.getAllUnits())
            if (name.equals(unit.getCapability().getName()) && unit.getContext() != null && unit.getContext().isValid()) count++;
        return count;
    }

    private synchronized Reservation reserve(String name, long sn) {
        clean();
        List<AIGCUnit> units = service.getAllUnits();
        units.sort(Comparator.comparingLong(AIGCUnit::getLastRunningTimestamp));
        boolean known = false, ready = false;
        for (AIGCUnit unit : units) {
            if (!name.equals(unit.getCapability().getName())) continue;
            known = true;
            TalkContext context = unit.getContext();
            if (context == null || !context.isValid()) continue;
            ReportedStatus status = fresh(unit);
            if (status == null || !status.data.getBoolean("ready")) continue;
            ready = true;
            if (available(unit, status) <= 0) continue;
            Reservation result = new Reservation(unit, context);
            pending.put(sn, result);
            unit.setRunning(true);
            return result;
        }
        throw new PluginCallException(!known ? AIGCStateCode.NotFound : ready ? AIGCStateCode.Processing : AIGCStateCode.UnitNoReady,
                !known ? 404 : ready ? 429 : 503,
                !known ? "Plugin unit not registered" : ready ? "All plugin providers are busy" : "Plugin provider is not ready");
    }

    public ActionDialect invoke(String name, JSONObject input, long timeoutMs) {
        validateName(name);
        if (input == null || timeoutMs <= 0 || timeoutMs > 3600000)
            throw new PluginCallException(AIGCStateCode.InvalidParameter, 400, "Invalid plugin call input or timeout");
        JSONObject data = new JSONObject(input.toString()).put("unit", name);
        Packet packet = new Packet("pluginInvoke", data);
        Reservation reservation = reserve(name, packet.sn);
        ActionDialect response;
        try {
            response = service.getCellet().transmit(reservation.context, packet.toDialect(), timeoutMs);
        } catch (RuntimeException error) {
            throw new PluginCallException(AIGCStateCode.UnitError, 503, "Plugin transport failed");
        }
        if (response == null)
            throw new PluginCallException(AIGCStateCode.UnitError, 504, "Plugin call timed out; execution may still be running");
        complete(reservation.context, response);
        Packet reply = new Packet(response);
        if (!"pluginInvoke".equals(reply.name) || !packet.sn.equals(reply.sn))
            throw new PluginCallException(AIGCStateCode.UnitError, 502, "Invalid plugin response correlation");
        if (Packet.extractCode(reply) == 0) {
            JSONObject payload = Packet.extractDataPayload(reply);
            if (!(payload.opt("response") instanceof String) || !(payload.opt("thought") instanceof String)
                    || payload.optJSONObject("resultPayload") == null || payload.optJSONObject("performance") == null)
                throw new PluginCallException(AIGCStateCode.UnitError, 502, "Invalid plugin response shape");
        }
        return response;
    }

    /** Includes responses arriving after the HTTP timeout. */
    public synchronized void complete(TalkContext context, ActionDialect dialect) {
        if (!"pluginInvoke".equals(dialect.getName())) return;
        Packet packet = new Packet(dialect);
        Reservation value = pending.get(packet.sn);
        if (value != null && same(context, value.context)) {
            pending.remove(packet.sn);
            value.unit.setRunning(false);
        }
    }

    private static final class Reservation {
        final AIGCUnit unit;
        final TalkContext context;
        Reservation(AIGCUnit unit, TalkContext context) { this.unit = unit; this.context = context; }
    }

    private static final class ReportedStatus {
        final TalkContext context;
        final long sequence, receivedAt;
        final JSONObject data;
        final int capacity;
        final Set<Long> activeRequests;
        ReportedStatus(TalkContext context, long sequence, long receivedAt, JSONObject data, int capacity, Set<Long> activeRequests) {
            this.context = context; this.sequence = sequence; this.receivedAt = receivedAt;
            this.data = data; this.capacity = capacity; this.activeRequests = activeRequests;
        }
    }
}
