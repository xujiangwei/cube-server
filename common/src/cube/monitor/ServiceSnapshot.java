package cube.monitor;

import cell.core.cellet.Cellet;
import cube.core.AbstractCellet;
import cube.core.AbstractModule;
import cube.core.Kernel;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

/** Collect local runtime state; module providers never require a compile-time dependency on plugins. */
public final class ServiceSnapshot {
    private final Kernel kernel;
    private final java.util.ArrayDeque<JSONObject> runtimeSeries = new java.util.ArrayDeque<>();
    private long previousGcMillis = -1, previousGcCount = -1;
    public ServiceSnapshot(Kernel kernel) { this.kernel = kernel; }
    public synchronized JSONObject capture() {
        JSONArray modules = new JSONArray(), workers = new JSONArray(), pools = new JSONArray();
        Set<String> failedModules = new HashSet<>();
        Set<ExecutorService> seen = Collections.newSetFromMap(new IdentityHashMap<ExecutorService, Boolean>());
        for (Cellet cellet : kernel.getNucleus().getCelletService().getCellets()) {
            if (cellet instanceof AbstractCellet && !"Harness".equals(cellet.getName())) {
                ExecutorService executor = ((AbstractCellet) cellet).getExecutor();
                if (executor != null && seen.add(executor)) pools.put(pool("Cellet:" + cellet.getName(), executor));
            }
        }
        for (Map.Entry<String, AbstractModule> entry : kernel.getModuleMapSnapshot().entrySet()) {
            JSONObject module = new JSONObject().put("module", entry.getKey()).put("started", entry.getValue().isStarted());
            try {
                JSONObject details = entry.getValue().monitoringSnapshot();
                if (details != null) {
                    module.put("details", details);
                    JSONArray list = details.optJSONArray("workers");
                    if (list != null) for (int i = 0; i < list.length(); ++i) workers.put(new JSONObject(list.getJSONObject(i).toString()).put("module", entry.getKey()));
                    JSONArray modulePools = details.optJSONArray("pools");
                    if (modulePools != null) for (int i = 0; i < modulePools.length(); ++i) pools.put(modulePools.getJSONObject(i));
                }
            } catch (RuntimeException failure) { module.put("collectionError", true); failedModules.add(entry.getKey()); }
            modules.put(module);
        }
        JSONObject health = jvm();
        runtimeSeries.addLast(health);
        while (runtimeSeries.size() > 180 || (!runtimeSeries.isEmpty() && health.getLong("timestamp") - runtimeSeries.peekFirst().getLong("timestamp") >= 1800000)) runtimeSeries.removeFirst();
        return new JSONObject().put("schemaVersion", 1).put("execution", kernel.getServiceMetrics().snapshot())
                .put("modules", modules).put("pools", pools).put("jvm", health).put("runtimeSeries", new JSONArray(runtimeSeries))
                .put("topology", new JSONObject().put("dispatchers", kernel.getServiceMetrics().upstreams())
                        .put("workers", kernel.getServiceMetrics().rememberWorkers(workers, failedModules)));
    }
    public static JSONObject pool(String name, ExecutorService executor) {
        JSONObject result = new JSONObject().put("name", name).put("running", executor != null && !executor.isShutdown());
        if (executor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
            result.put("active", pool.getActiveCount()).put("threads", pool.getPoolSize()).put("maxThreads", pool.getMaximumPoolSize())
                    .put("queued", pool.getQueue().size()).put("completed", pool.getCompletedTaskCount());
        } else result.put("active", JSONObject.NULL).put("threads", JSONObject.NULL).put("maxThreads", JSONObject.NULL).put("queued", JSONObject.NULL).put("completed", JSONObject.NULL);
        return result;
    }
    private JSONObject jvm() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean(); long gcMillis = 0, gcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcMillis += Math.max(0, gc.getCollectionTime()); gcCount += Math.max(0, gc.getCollectionCount());
        }
        JSONObject result = new JSONObject().put("timestamp", System.currentTimeMillis()).put("heapUsed", heap.getUsed()).put("heapCommitted", heap.getCommitted())
                .put("heapMax", heap.getMax() < 0 ? JSONObject.NULL : heap.getMax()).put("threads", threads.getThreadCount()).put("daemonThreads", threads.getDaemonThreadCount())
                .put("peakThreads", threads.getPeakThreadCount()).put("gcCount", gcCount).put("gcMillis", gcMillis)
                .put("gcDeltaCount", previousGcCount < 0 ? JSONObject.NULL : Math.max(0, gcCount - previousGcCount))
                .put("gcDeltaMillis", previousGcMillis < 0 ? JSONObject.NULL : Math.max(0, gcMillis - previousGcMillis));
        previousGcCount = gcCount; previousGcMillis = gcMillis; return result;
    }
}
