package cube.monitor;

import org.json.JSONObject;
import java.util.*;

/** Local observation of committed job states. Keys never leave this bounded collector. */
public final class LifecycleMetrics {
    private static final Set<String> TERMINAL = new HashSet<>(Arrays.asList("succeeded", "failed", "cancelled", "timed_out"));
    private final ServiceMetrics metrics;
    private final Map<String, Entry> active = new LinkedHashMap<>();
    private final Map<String, Long> finished = new LinkedHashMap<>();
    private long recovered, untracked;
    public LifecycleMetrics() { this(new ServiceMetrics()); }
    public LifecycleMetrics(ServiceMetrics metrics) { this.metrics = metrics; }
    private static final class Entry {
        ServiceMetrics.Sample sample; long version; String state;
    }
    public synchronized void observe(String id, String state, long version) {
        if (finished.containsKey(id)) return;
        Entry e = active.get(id);
        if (e != null && version <= e.version) return;
        if (e == null) {
            if (active.size() >= 8192) { ++untracked; return; }
            e = new Entry(); e.sample = metrics.submit("Harness", "run", false); active.put(id, e);
            if (!"queued".equals(state)) ++recovered;
        }
        e.version = version; e.state = state;
        if (!"queued".equals(state)) metrics.start(e.sample);
        if (TERMINAL.contains(state)) {
            metrics.complete(e.sample, state); active.remove(id); finished.put(id, version);
            if (finished.size() > 8192) finished.remove(finished.keySet().iterator().next());
        }
    }
    public synchronized JSONObject snapshot() {
        JSONObject states = new JSONObject();
        for (Entry e : active.values()) states.put(e.state, states.optInt(e.state) + 1);
        return metrics.snapshot().put("states", states).put("recovered", recovered).put("untracked", untracked);
    }
}
