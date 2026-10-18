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
    private final InferenceGateway inference;
    private final Map<String,JSONObject> descriptions=new HashMap<>();
    private final Map<String,Boolean> health=new HashMap<>();
    public ModelDispatcher(ModelJobStore jobs,Properties config,long lease) {this(jobs,config,lease,null);}
    public ModelDispatcher(ModelJobStore jobs,Properties config,long lease,InferenceGateway inference) {this.jobs=jobs;this.config=config;this.sessions=new WorkerRegistry(lease);this.inference=inference;}
    public synchronized JSONObject apply(JSONObject request,long transport,java.util.function.BooleanSupplier valid) {
        String action=request.getString("action"),worker=request.getString("workerId");
        int protocol=request.getInt("protocolVersion");
        JSONObject descriptor=descriptions.get(worker);
        if(action.equals("describe")) {
            descriptor=new JSONObject(request.getJSONObject("descriptor").toString());String prefix="worker."+worker+".";
            if(!"model".equals(config.getProperty(prefix+"role"))||!descriptor.getString("model").equals(config.getProperty(prefix+"model")))throw new IllegalArgumentException("FORBIDDEN");
            String model=descriptor.getString("model");
            String adapter=protocol==3?(descriptor.getBoolean("nativeTools")?"openai-tools-v2":"openai-json-v2")
                    :model.equals("scripted")?"scripted-v1":model.equals("Baize")?"baize-json-v1":"baize2-tools-v1";
            if(!adapter.equals(descriptor.getString("adapter"))||!ModelProfiles.adapterDigest(adapter).equals(descriptor.getString("profileDigest")))throw new IllegalArgumentException("MODEL_PROFILE_MISMATCH");
            String backend=config.getProperty(prefix+"backend");
            if(backend==null)throw new IllegalArgumentException("BACKEND_REQUIRED");
            int capacity=Integer.parseInt(config.getProperty("backend."+backend+".capacity","0"));
            if(capacity<1||capacity>32)throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
            if(protocol==2) {
                if(!backend.equals(descriptor.getString("backendId"))||descriptor.getInt("capacity")!=capacity
                        ||descriptor.getBoolean("nativeTools")!=model.equals("Baize2"))throw new IllegalArgumentException("BACKEND_CAPACITY_MISMATCH");
                jobs.backend(backend,descriptor,capacity);
            } else {
                if(inference==null)throw new IllegalArgumentException("INFERENCE_REQUIRED");
                jobs.backend(backend,new JSONObject().put("backendId",backend).put("type",config.getProperty("backend."+backend+".type","unit")),capacity);
                descriptor.put("backendId",backend).put("capacity",capacity).put("protocolVersion",3);
            }
        }
        JSONObject normalized=new JSONObject(request.toString());normalized.remove("descriptor");normalized.remove("report");normalized.remove("healthy");for(String k:Arrays.asList("jobId","jobInstanceId","wireAttempt","requestHash","requestJson","after"))normalized.remove(k);normalized.put("action",action.equals("describe")?"register":action.equals("drain")?"drain":action.equals("unregister")?"unregister":"heartbeat");
        JSONObject response=sessions.apply(normalized,transport,valid);
        if(action.equals("describe"))descriptions.put(worker,new JSONObject(descriptor.toString()));
        if(descriptor==null)throw new IllegalArgumentException("STALE_SESSION");
        if(descriptor.optInt("protocolVersion",2)!=protocol)throw new IllegalArgumentException("PROTOCOL_MISMATCH");
        health.put(worker,request.optBoolean("healthy",true));response.put("ready",response.getBoolean("ready")&&health.get(worker)
                &&(protocol==3?inference.ready(descriptor):descriptor.getString("model").equals("scripted")));
        if(action.equals("actual_finished")) {
            JSONObject report=new JSONObject(request.getJSONObject("report").toString());
            if(protocol==3) {
                JSONObject bound=jobs.jobById(report.getString("jobId"));
                if(bound==null||!worker.equals(bound.optString("worker_id"))||!report.getString("instanceId").equals(bound.optString("instance_id")))throw new IllegalArgumentException("JOB_EVIDENCE_MISMATCH");
                JSONObject outcome=ModelProtocol.outcome(report.getString("outcomeJson"),report.getBoolean("actualFinished"));
                outcome=inference.ledger.reconcile(report.getString("jobId"),outcome);
                report.put("outcomeJson",outcome.toString()).put("actualFinished",!outcome.getString("status").equals("unknown"));
            }
            jobs.report(worker,report);
        }
        if(action.startsWith("inference_"))response.put("inference",inference.apply(request));
        if(action.equals("poll")) {
            JSONObject job=jobs.poll(worker,request.getString("instanceId"),request.getString("sessionId"),descriptor,1,response.getBoolean("ready"));
            if(job!=null)response.put("job",job);
        }
        return response.put("protocolVersion",protocol).put("requestId",request.getString("requestId")).put("ok",true)
            .put("cancellations",jobs.cancellations(worker));
    }
    public synchronized JSONArray snapshot(){JSONArray out=sessions.snapshot();for(int i=0;i<out.length();i++){JSONObject w=out.getJSONObject(i);boolean healthy=Boolean.TRUE.equals(health.get(w.getString("workerId")));JSONObject d=descriptions.get(w.getString("workerId"));boolean backendReady=d.optInt("protocolVersion",2)==3?inference.ready(d):d.getString("model").equals("scripted");w.put("healthy",healthy).put("ready",w.getBoolean("ready")&&healthy&&backendReady).put("descriptor",d);}return out;}
    public synchronized void disconnect(long id){sessions.disconnect(id);}
    public synchronized boolean ready(){JSONArray list=snapshot();for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getBoolean("ready"))return true;return false;}
    public synchronized JSONObject capacity() {
        JSONObject view=jobs.capacity();
        JSONArray workers=snapshot(),backends=view.getJSONArray("backends"),occupied=view.getJSONArray("workers");
        Set<String> busy=new HashSet<>();
        for(int n=0;n<occupied.length();n++)busy.add(occupied.getJSONObject(n).getString("worker_id"));
        int available=0,total=0;
        for(int n=0;n<backends.length();n++) {
            JSONObject backend=backends.getJSONObject(n);String id=backend.getString("backend_id");
            int ready=0,idle=0,live=0,registered=0;Set<String> models=new HashSet<>();
            for(int j=0;j<workers.length();j++) {
                JSONObject worker=workers.getJSONObject(j),d=worker.getJSONObject("descriptor");
                if(!d.getString("backendId").equals(id)||!worker.getBoolean("ready"))continue;
                ready++;if(!busy.contains(worker.getString("workerId")))idle++;
                if(d.optInt("protocolVersion",2)==3) {
                    if(!models.add(d.getString("model")))continue;
                    JSONObject capacity=inference.capacity(d);
                    if(config.getProperty("backend."+id+".type","unit").equals("unit")) {
                        live+=capacity.getInt("available");registered+=capacity.getInt("registered");
                    } else {
                        live=Math.max(live,capacity.getInt("available"));registered=Math.max(registered,capacity.getInt("registered"));
                    }
                } else {live=backend.getInt("capacity");registered=ready;}
            }
            int physical=Math.max(0,backend.getInt("capacity")-backend.getInt("occupied"));
            int free=Math.min(idle,Math.min(physical,live));
            int concurrency=Math.min(ready,Math.min(backend.getInt("capacity"),registered));
            backend.put("processingSlots",ready).put("processingAvailable",idle).put("inferenceUnits",registered)
                .put("inferenceAvailable",Math.min(physical,live)).put("readyUnits",ready).put("available",free).put("executableConcurrency",concurrency);
            available+=free;total+=concurrency;
        }
        view.remove("workers");
        return view.put("registeredUnits",workers.length()).put("available",available).put("executableConcurrency",total);
    }
}
