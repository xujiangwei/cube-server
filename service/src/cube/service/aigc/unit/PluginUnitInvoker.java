package cube.service.aigc.unit;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.spi.PluginCallException;
import cube.common.Packet;
import cube.common.entity.AIGCUnit;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCService;
import org.json.JSONObject;
import java.util.*;
import java.util.regex.Pattern;

/** Exact-name plugin dispatch. Reservations survive caller timeouts. */
public final class PluginUnitInvoker {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private final AIGCService service;
    private final Map<Long, Reservation> pending = new HashMap<>();
    public PluginUnitInvoker(AIGCService service) { this.service = service; }

    public static void validateName(String name) {
        if (name == null || name.length() > 64 || !NAME.matcher(name).matches())
            throw new PluginCallException(AIGCStateCode.InvalidParameter, 400, "Invalid plugin unit name");
    }

    private static boolean same(TalkContext a, TalkContext b) {
        return a == b || (a != null && b != null && a.getSession() != null && a.getSession() == b.getSession());
    }

    private synchronized void clean() {
        List<AIGCUnit> current = service.getAllUnits();
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
        boolean known = false, online = false;
        for (AIGCUnit unit : units) {
            if (!name.equals(unit.getCapability().getName())) continue;
            known = true;
            TalkContext context = unit.getContext();
            if (context == null || !context.isValid()) continue;
            online = true;
            boolean occupied = false;
            for (Reservation value : pending.values()) if (value.unit == unit) { occupied = true; break; }
            if (occupied) continue;
            Reservation result = new Reservation(unit, context);
            pending.put(sn, result);
            unit.setRunning(true);
            return result;
        }
        throw new PluginCallException(!known ? AIGCStateCode.NotFound : online ? AIGCStateCode.Processing : AIGCStateCode.UnitError,
                !known ? 404 : online ? 429 : 503,
                !known ? "Plugin unit not registered" : online ? "All plugin providers are busy" : "Plugin provider unavailable");
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
}
