package cube.service.harness.execution;
import cube.service.harness.WorkerRegistry;
import cube.service.harness.store.*;
import org.json.*;
import java.util.*;

/** Worker pull transport with instance/session fencing; only trusted configuration grants capacity. */
public final class ModelDispatcher {
    private final WorkerRegistry sessions;
    private final ModelJobStore jobs;
    private final Properties config;
    private final Map<String,JSONObject> descriptions=new HashMap<>();
    private final Map<String,Boolean> health=new HashMap<>();
    public ModelDispatcher(ModelJobStore jobs,Properties config,long lease) {this.jobs=jobs;this.config=config;this.sessions=new WorkerRegistry(lease);}
    public synchronized JSONObject apply(JSONObject request,long transport,java.util.function.BooleanSupplier valid) {
        String action=request.getString("action"),worker=request.getString("workerId");
        JSONObject descriptor=descriptions.get(worker);
        if(action.equals("describe")) {
            descriptor=request.getJSONObject("descriptor");String prefix="worker."+worker+".";
            if(!"model".equals(config.getProperty(prefix+"role"))||!descriptor.getString("model").equals(config.getProperty(prefix+"model"))
                ||!descriptor.getString("backendId").equals(config.getProperty(prefix+"backend")))throw new IllegalArgumentException("FORBIDDEN");
            String model=descriptor.getString("model"),adapter=model.equals("scripted")?"scripted-v1":model.equals("Baize")?"baize-json-v1":"baize2-tools-v1";
            if(!adapter.equals(descriptor.getString("adapter"))||!ModelProfiles.adapterDigest(adapter).equals(descriptor.getString("profileDigest"))
                ||descriptor.getBoolean("nativeTools")!=model.equals("Baize2"))throw new IllegalArgumentException("MODEL_PROFILE_MISMATCH");
            int capacity=Integer.parseInt(config.getProperty("backend."+descriptor.getString("backendId")+".capacity","0"));
            if(capacity<1||capacity>32||descriptor.getInt("capacity")!=capacity)throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
            jobs.backend(descriptor.getString("backendId"),descriptor,capacity);
        }
        JSONObject normalized=new JSONObject(request.toString());normalized.remove("descriptor");normalized.remove("report");normalized.remove("healthy");normalized.put("action",action.equals("describe")?"register":action.equals("drain")?"drain":action.equals("unregister")?"unregister":"heartbeat");
        JSONObject response=sessions.apply(normalized,transport,valid);
        if(action.equals("describe"))descriptions.put(worker,new JSONObject(descriptor.toString()));
        if(descriptor==null)throw new IllegalArgumentException("STALE_SESSION");
        health.put(worker,request.optBoolean("healthy",true));response.put("ready",response.getBoolean("ready")&&health.get(worker));
        if(action.equals("actual_finished"))jobs.report(worker,request.getJSONObject("report"));
        if(action.equals("poll")) {
            JSONObject job=jobs.poll(worker,request.getString("instanceId"),request.getString("sessionId"),descriptor,1,response.getBoolean("ready"));
            if(job!=null)response.put("job",job);
        }
        return response.put("protocolVersion",2).put("requestId",request.getString("requestId")).put("ok",true)
            .put("cancellations",jobs.cancellations(worker));
    }
    public synchronized JSONArray snapshot(){JSONArray out=sessions.snapshot();for(int i=0;i<out.length();i++){JSONObject w=out.getJSONObject(i);boolean healthy=Boolean.TRUE.equals(health.get(w.getString("workerId")));w.put("healthy",healthy).put("ready",w.getBoolean("ready")&&healthy).put("descriptor",descriptions.get(w.getString("workerId")));}return out;}
    public synchronized void disconnect(long id){sessions.disconnect(id);}
    public synchronized boolean ready(){JSONArray list=snapshot();for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getBoolean("ready"))return true;return false;}
    public synchronized JSONObject capacity(){JSONObject view=jobs.capacity();JSONArray workers=snapshot(),backend=view.getJSONArray("backends"),occupied=view.getJSONArray("workers");int available=0,total=0;
        Set<String> busy=new HashSet<>();for(int i=0;i<occupied.length();i++)busy.add(occupied.getJSONObject(i).getString("worker_id"));
        for(int i=0;i<backend.length();i++){JSONObject b=backend.getJSONObject(i);int ready=0,idle=0;for(int j=0;j<workers.length();j++){JSONObject w=workers.getJSONObject(j);if(w.getJSONObject("descriptor").getString("backendId").equals(b.getString("backend_id"))&&w.getBoolean("ready")){ready++;if(!busy.contains(w.getString("workerId")))idle++;}}
            int free=Math.min(idle,Math.max(0,b.getInt("capacity")-b.getInt("occupied")));b.put("readyUnits",ready).put("available",free).put("executableConcurrency",Math.min(ready,b.getInt("capacity")));available+=free;total+=b.getInt("executableConcurrency");}
        view.remove("workers");return view.put("registeredUnits",workers.length()).put("available",available).put("executableConcurrency",total);}
}
