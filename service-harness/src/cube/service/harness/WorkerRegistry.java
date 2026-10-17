package cube.service.harness;

import org.json.*;
import java.util.*;

/** All transitions are serialized. Transport identity and lease nonce fence old sessions. */
public final class WorkerRegistry {
    private static final class Worker {
        String id, instance, role, nonce; long transport, deadline; boolean draining;
        JSONArray capabilities;
        Worker(JSONObject request, long transport, long deadline) {
            id=request.getString("workerId"); instance=request.getString("instanceId"); role=request.getString("role");
            capabilities=request.getJSONArray("capabilities"); nonce=UUID.randomUUID().toString();
            this.transport=transport; this.deadline=deadline;
        }
    }
    private final Map<String, Worker> workers = new HashMap<>();
    private final long leaseMillis;
    public WorkerRegistry(long leaseMillis) { this.leaseMillis=leaseMillis; }
    private long now() { return System.nanoTime()/1000000; }
    private void expire() { long time=now(); workers.values().removeIf(w -> w.deadline <= time); }
    public synchronized JSONObject apply(JSONObject request, long transport) {
        return apply(request,transport,() -> true);
    }
    public synchronized JSONObject apply(JSONObject request,long transport,java.util.function.BooleanSupplier valid) {
        if(!valid.getAsBoolean())fail("STALE_SESSION");
        expire(); String id=request.getString("workerId"), action=request.getString("action"); Worker w=workers.get(id);
        if (action.equals("register")) {
            if (request.has("sessionId") || request.has("payload")) fail("SCHEMA_INVALID");
            if (w != null && (w.transport != transport || !w.instance.equals(request.getString("instanceId")))) fail("DUPLICATE_WORKER");
            if (w == null) {
                if (workers.size() >= 64) fail("REGISTRY_FULL");
                w=new Worker(request,transport,now()+leaseMillis); workers.put(id,w);
            }
        } else {
            if (w==null || w.transport!=transport || !w.instance.equals(request.getString("instanceId")) ||
                !w.nonce.equals(request.optString("sessionId"))) fail("STALE_SESSION");
        }
        if (!w.role.equals(request.getString("role")) || !w.capabilities.toString().equals(request.getJSONArray("capabilities").toString())) fail("IDENTITY_MISMATCH");
        if (!action.equals("echo") && request.has("payload")) fail("SCHEMA_INVALID");
        if (action.equals("echo") && (!request.has("payload") || w.draining || w.capabilities.length()==0)) fail("WORKER_NOT_READY");
        if (action.equals("drain")) w.draining=true;
        w.deadline=now()+leaseMillis;
        JSONObject response=new JSONObject().put("sessionId",w.nonce).put("leaseMillis",leaseMillis)
                .put("registered",!action.equals("unregister")).put("ready",!w.draining && w.capabilities.length()>0 && !action.equals("unregister"));
        if (action.equals("echo")) response.put("payload",request.getJSONObject("payload"));
        if (action.equals("unregister")) workers.remove(id);
        return response;
    }
    public synchronized void disconnect(long transport) { workers.values().removeIf(w -> w.transport==transport); }
    public synchronized void clear() { workers.clear(); }
    public synchronized JSONArray snapshot() {
        expire(); JSONArray result=new JSONArray();
        for (Worker w:workers.values()) result.put(new JSONObject().put("workerId",w.id).put("instanceId",w.instance)
            .put("role",w.role).put("registered",true).put("ready",!w.draining && w.capabilities.length()>0).put("capabilities",w.capabilities));
        return result;
    }
    public synchronized boolean ready() {
        expire(); boolean model=false,runner=false;
        for(Worker w:workers.values()) if(!w.draining && w.capabilities.length()>0) {
            if(w.role.equals("model"))model=true; if(w.role.equals("runner"))runner=true;
        }
        return model && runner;
    }
    private static void fail(String code) { throw new IllegalArgumentException(code); }
}
