package cube.service.harness.test;

import cube.aigc.completion.*;
import cube.service.harness.execution.*;
import cube.service.harness.store.*;
import cube.service.harness.runtime.RuntimeSupport;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.sql.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Real JDBC reservations and Java HTTP decoding; inference hardware is deliberately simulated. */
public final class InferenceTest {
    static void migration()throws Exception {
        java.nio.file.Path dir=Files.createTempDirectory("harness-v3-upgrade-");Properties p=new Properties();
        p.setProperty("store.dialect","sqlite");p.setProperty("store.database","harness_upgrade");
        p.setProperty("store.identity","inference-upgrade-identity-123456");p.setProperty("store.sqlite.root",dir.toString());p.setProperty("store.sqlite.file",dir.resolve("harness_upgrade.db").toString());
        StoreConfig cfg=new StoreConfig(p);java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
        JSONObject oldDescriptor=ExecutionTest.descriptor("old-backend");String oldHash=JdbcRunStore.hash(oldDescriptor);
        try(Connection c=cfg.connect();Statement s=c.createStatement()) {
            for(int n=1;n<=3;n++) {
                byte[] raw;try(java.io.InputStream in=InferenceTest.class.getResourceAsStream("/store/migrations/sqlite/00"+n+".sql")){raw=RuntimeSupport.read(in,262144);}
                sha.update(raw);for(String sql:new String(raw,StandardCharsets.UTF_8).split(";"))if(!sql.trim().isEmpty())s.execute(sql);
            }
            StringBuilder digest=new StringBuilder();for(byte b:sha.digest())digest.append(String.format("%02x",b&255));
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO harness_store_identity VALUES (1,?,3,?)")){insert.setString(1,cfg.identity);insert.setString(2,digest.toString());insert.executeUpdate();}
            s.execute("INSERT INTO harness_run VALUES ('retained','tenant','subject','profile','running',1,1,NULL,0,0,'pending','settled',1,1)");
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO harness_model_backend VALUES ('old-backend',?,1)")){insert.setString(1,oldHash);insert.executeUpdate();}
            s.execute("INSERT INTO harness_model_job VALUES ('old-job','retained','tenant','subject','round',1,1,'intent','Baize','profile','request','{\"before\":true}','old-backend','old-worker','old-instance','session','unknown',0,0,NULL,1)");
        }
        StoreMigrations.migrate(cfg,false);StoreMigrations.migrate(cfg,false);
        JdbcRunStore upgraded=new JdbcRunStore(cfg);ModelJobStore jobs=new ModelJobStore(upgraded,false);
        JSONObject retained=jobs.jobById("old-job");
        check(retained.getInt("execution_protocol")==2&&retained.getString("request_json").equals("{\"before\":true}"),"v3 in-flight job changed during upgrade");
        jobs.backend("old-backend",new JSONObject().put("backendId","old-backend").put("type","unit"),1);
        jobs.backend("old-backend",oldDescriptor,1);
        check(jobs.capacity().getJSONArray("backends").getJSONObject(0).getInt("occupied")==1,"Migration released old unknown physical execution");
        pass("explicit v3-to-v4 migration retains raw job, old identity and unknown capacity alongside the new routing identity");
    }
    static void until(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
        check(condition.getAsBoolean(),"Timed out waiting for inference evidence");
    }
    static JSONObject result() {return new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","{\"kind\":\"final\",\"text\":\"ok\"}"))
        .put("finish_reason","stop").put("usage",new JSONObject().put("prompt_tokens",17).put("completion_tokens",5).put("total_tokens",22));}
    static JSONObject wire(){return new JSONObject().put("model","Baize").put("stream",true).put("max_tokens",256)
        .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")));}
    static JSONObject envelope(String worker,String action,String session){JSONObject e=ExecutionTest.envelope(worker,"i"+worker,action).put("protocolVersion",3);if(session!=null)e.put("sessionId",session);return e;}
    static JSONObject descriptor(){return new JSONObject().put("model","Baize").put("adapter","openai-json-v2").put("nativeTools",false)
        .put("cancellation","observe-only").put("budget","transport-bytes-v1").put("profileDigest",ModelProfiles.adapterDigest("openai-json-v2"));}
    static JSONObject submit(JSONObject job,String session){return envelope(job.getString("workerId"),"inference_submit",session)
        .put("jobId",job.getString("jobId")).put("jobInstanceId",job.getString("instanceId")).put("wireAttempt",1)
        .put("requestHash",JdbcRunStore.hash(wire())).put("requestJson",wire().toString());}
    static final class Provider implements CompletionProvider {
        final Map<String,Listener> listeners=new ConcurrentHashMap<>();
        public JSONArray completionModels(String token){return new JSONArray().put(new JSONObject().put("id","Baize").put("capabilities",new JSONObject().put("text",true)));}
        public JSONObject completionCapacity(String token,String model,boolean tools){return new JSONObject().put("registered",2).put("available",2);}
        public void submitCompletion(String token,String id,JSONObject body,long timeout,Listener listener){check(listeners.putIfAbsent(id,listener)==null,"Duplicate physical inference");}
        void emit(String id,boolean finished){listeners.get(id).onEvent(new JSONObject().put("kind",finished?"completed":"error").put("actualFinished",finished)
            .put("payload",finished?new JSONObject().put("result",result()):new JSONObject().put("error",new JSONObject().put("code","MODEL_COMPLETION_UNKNOWN"))));}
    }
    static void ledger()throws Exception {
        JSONObject vector=new JSONObject(new String(cube.service.harness.protocol.Contract.resource("inference/canonical-vectors.json"),StandardCharsets.UTF_8));
        check(JdbcRunStore.canonical(vector.getJSONObject("value")).equals(vector.getString("canonical")),"Cross-language canonical digest vector failed");
        ExecutionTest.prepare(Files.createTempDirectory("harness-inference-"),System.getProperty("inference.test.config",""));
        profiles.publish(T,ModelProfiles.model("Baize"));
        JSONObject task=task("2",release,"Baize",1024).put("schemaVersion",2)
            .put("executionLimits",new JSONObject().put("maxRounds",8).put("maxTools",16).put("maxRepairs",1).put("maxLifetimeMillis",60000));
        task.getJSONObject("model").put("version","2");task.getJSONObject("contextPolicy").put("budgetVersion","transport-bytes-v1").put("maxInputBytes",48000);
        profiles.publish(T,task);catalog.bind(T,"task","task","task@2",1);
        ModelJobStore jobs=new ModelJobStore(store,config.mysql());InferenceStore ledger=new InferenceStore(store,config.mysql());
        jobs.backend("legacy",ExecutionTest.descriptor("legacy"),1);
        jobs.backend("legacy",new JSONObject().put("backendId","legacy").put("type","unit"),1);
        jobs.backend("legacy",ExecutionTest.descriptor("legacy"),1);
        Properties p=new Properties();for(String worker:Arrays.asList("a","b")){p.setProperty("worker."+worker+".role","model");p.setProperty("worker."+worker+".model","Baize");p.setProperty("worker."+worker+".backend","shared");}
        p.setProperty("backend.shared.capacity","1");Provider provider=new Provider();
        try(InferenceGateway gateway=new InferenceGateway(ledger,p,()->provider);RunCoordinator coordinator=new RunCoordinator(store,jobs,catalog,identity,resources)) {
            ModelDispatcher dispatcher=new ModelDispatcher(jobs,p,60000,gateway);
            String sa=dispatcher.apply(envelope("a","describe",null).put("descriptor",descriptor()),1,()->true).getString("sessionId");
            String sb=dispatcher.apply(envelope("b","describe",null).put("descriptor",descriptor()),2,()->true).getString("sessionId");
            String r1=ExecutionTest.create("first"),r2=ExecutionTest.create("second");
            for(int n=0;n<3;n++){coordinator.step(r1);coordinator.step(r2);}
            JSONObject a=dispatcher.apply(envelope("a","poll",sa),1,()->true).getJSONObject("job");
            JSONObject b=dispatcher.apply(envelope("b","poll",sb),2,()->true).getJSONObject("job");
            check(sharedOccupancy(jobs)==0,"Python slots consumed physical capacity");
            JSONObject first=dispatcher.apply(submit(a,sa),1,()->true).getJSONObject("inference");
            check(dispatcher.apply(submit(a,sa),1,()->true).getJSONObject("inference").getString("inferenceId").equals(first.getString("inferenceId")),"Replay created another inference");
            fail("INFERENCE_CONFLICT",()->ledger.submit(a.getString("jobId"),"a","ia","ia",1,JdbcRunStore.hash(wire().put("temperature",1)),wire().put("temperature",1)));
            fail("JOB_EVIDENCE_MISMATCH",()->ledger.page(a.getString("jobId"),"b","ia",1,0,false));
            String second=dispatcher.apply(submit(b,sb),2,()->true).getJSONObject("inference").getString("inferenceId");
            String id=first.getString("inferenceId");gateway.start();until(()->provider.listeners.size()==1);
            // Whichever row wins ordering, the shared physical capacity still permits exactly one.
            String active=provider.listeners.keySet().iterator().next();provider.emit(active,false);Thread.sleep(120);
            check(provider.listeners.size()==1,"Unknown inference released physical capacity");
            ledger.recover("harness-7088","new-process");
            provider.emit(active,true);provider.emit(active,true);until(()->provider.listeners.size()==2);
            provider.emit(active.equals(id)?second:id,true);
            JSONObject page=ledger.page(a.getString("jobId"),"a","ia",1,0,false);
            check(page.getBoolean("actualFinished")&&page.has("response"),"Late result not reconciled");
            JSONObject report=ExecutionTest.report(a,new JSONObject().put("kind","final").put("text","ok"),true);
            dispatcher.apply(envelope("a","actual_finished",sa).put("report",report),1,()->true);
            dispatcher.apply(envelope("a","actual_finished",sa).put("report",report),1,()->true);
            JSONObject outcome=new JSONObject(jobs.jobById(a.getString("jobId")).getString("outcome_json"));
            check(outcome.getJSONObject("usage").getInt("input")==17,"Python usage overrode authoritative usage");
            for(int n=0;n<4;n++)coordinator.step(a.getString("runId"));
            check(store.read(T,S,a.getString("runId")).getJSONObject("snapshot").getString("state").equals("succeeded"),"v3 coordinator failed");
            check(sharedOccupancy(jobs)==0,"Finished inference leaked capacity");
            fail("INVALID_INFERENCE_CURSOR",()->ledger.page(a.getString("jobId"),"a","ia",1,1,false));
            gateway.close(); // Model a JVM crash after durable claim, before the provider call.
            String r3=ExecutionTest.create("crash window");for(int n=0;n<3;n++)coordinator.step(r3);
            JSONObject crash=dispatcher.apply(envelope("a","poll",sa),1,()->true).getJSONObject("job");
            JSONObject pending=ledger.submit(crash.getString("jobId"),"a","ia","ia",1,JdbcRunStore.hash(wire()),wire());
            String crashId=pending.getString("inference_id");check(ledger.claim(crashId,"stable","old"),"Crash reservation missing");
            ledger.recover("stable","new");
            JSONObject unknown=ledger.page(crash.getString("jobId"),"a","ia",1,0,true);
            check(unknown.getString("status").equals("unknown")&&!unknown.getBoolean("actualFinished"),"Crash/cancel falsely released capacity");
            check(sharedOccupancy(jobs)==1,"Crash window lost the physical reservation");
            check(ledger.submit(crash.getString("jobId"),"a","ia","new-instance",1,JdbcRunStore.hash(wire()),wire()).getString("inference_id").equals(crashId),"New instance cannot read original inference");
            ledger.finish(crashId,OpenAICompletionProtocol.result(result(),false,crashId,"Baize",1),null,true,null);
            fail("INFERENCE_NOT_ADMISSIBLE",()->ledger.submit(crash.getString("jobId"),"a","ia","new-instance",2,JdbcRunStore.hash(wire()),wire()));
            ledger.submit(crash.getString("jobId"),"a","ia","ia",2,JdbcRunStore.hash(wire()),wire());
            check(ledger.page(crash.getString("jobId"),"a","ia",2,0,true).getBoolean("actualFinished"),"Queued cancellation failed");
        }
        pass("Cell v3 dispatch, separate processing slots, shared inference capacity, idempotency, foreign identity, unknown quarantine, late completion and authoritative usage");
    }
    static int sharedOccupancy(ModelJobStore jobs) {
        JSONArray backends=jobs.capacity().getJSONArray("backends");
        for(int n=0;n<backends.length();n++)if(backends.getJSONObject(n).getString("backend_id").equals("shared"))return backends.getJSONObject(n).getInt("occupied");
        throw new AssertionError("Shared backend missing");
    }
    static void http()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        List<JSONObject> received=new CopyOnWriteArrayList<>();
        server.createContext("/v1/chat/completions",exchange->{
            byte[] raw=cube.service.harness.runtime.RuntimeSupport.read(exchange.getRequestBody(),131072);
            JSONObject body=new JSONObject(new String(raw,StandardCharsets.UTF_8));received.add(body);
            String mode=body.getJSONArray("messages").getJSONObject(0).getString("content");
            if(mode.equals("rejected")){exchange.sendResponseHeaders(400,-1);exchange.close();return;}
            if(mode.equals("slow")) {
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                try {for(int n=0;n<20;n++){exchange.getResponseBody().write('x');exchange.getResponseBody().flush();Thread.sleep(50);}}
                catch(Exception expected){}finally{exchange.close();}return;
            }
            String response;
            if(mode.startsWith("json")) {
                JSONObject r=result();if(mode.equals("json-refusal"))r.getJSONObject("message").put("refusal","declined");
                JSONObject output=OpenAICompletionProtocol.result(r,false,"fixture","actual",1);
                if(mode.equals("json-no-usage"))output.remove("usage");response=output.toString();
            }
            else {
                JSONArray parts=new JSONArray().put(new JSONObject().put("index",0).put("id","call-1").put("type","function").put("function",new JSONObject().put("name","lookup").put("arguments","{\"x\":")));
                JSONObject delta=new JSONObject().put("tool_calls",parts);
                JSONObject chunk=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL)));
                response="data: "+chunk+"\n\n";
                parts.getJSONObject(0).remove("id");parts.getJSONObject(0).getJSONObject("function").remove("name");parts.getJSONObject(0).getJSONObject("function").put("arguments","1}");
                chunk.getJSONArray("choices").getJSONObject(0).put("finish_reason","tool_calls");
                response+="data: "+chunk+"\n\ndata: "+new JSONObject().put("choices",new JSONArray()).put("usage",result().getJSONObject("usage"))+"\n\n";
                if(!mode.equals("truncated"))response+="data: [DONE]\n\n";
            }
            exchange.getResponseHeaders().set("Content-Type",mode.startsWith("json")?"application/json":"text/event-stream");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try(OpenAIHttpProvider provider=new OpenAIHttpProvider("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","actual","secret",2)) {
            for(String mode:Arrays.asList("json","sse","truncated","json-refusal","json-no-usage","rejected","slow")) {
                JSONObject w=wire().put("stream",!mode.startsWith("json"));w.getJSONArray("messages").getJSONObject(0).put("content",mode);
                List<JSONObject> events=new CopyOnWriteArrayList<>();long start=System.nanoTime();provider.submitCompletion("",mode,OpenAICompletionProtocol.normalize(w,false),mode.equals("slow")?200:2000,events::add);
                until(()->!events.isEmpty()&&Arrays.asList("completed","error").contains(events.get(events.size()-1).getString("kind")));
                JSONObject last=events.get(events.size()-1);
                if(mode.equals("truncated"))check(!last.getBoolean("actualFinished")&&last.getString("kind").equals("error"),"Truncated SSE falsely finished");
                else if(mode.equals("slow"))check(!last.getBoolean("actualFinished")&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500,"SSE activity bypassed absolute deadline");
                else if(mode.equals("rejected"))check(last.getBoolean("actualFinished")&&last.getJSONObject("payload").getJSONObject("error").getString("code").equals("MODEL_HTTP_400"),"HTTP rejection did not safely release");
                else if(mode.equals("json-no-usage"))check(last.getBoolean("actualFinished")&&last.getJSONObject("payload").getJSONObject("error").getString("code").equals("MODEL_USAGE_MISSING"),"Missing usage fabricated or confused with ongoing inference");
                else {
                    JSONObject r=last.getJSONObject("payload").getJSONObject("result");check(r.getJSONObject("usage").getInt("total_tokens")==22,"Usage missing");
                    if(mode.equals("sse"))check(r.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments").equals("{\"x\":1}"),"Argument delta reconstruction");
                    if(mode.equals("json-refusal"))check(r.getJSONObject("message").getString("refusal").equals("declined"),"Refusal discarded");
                }
            }
            check(received.size()==7&&received.get(0).getString("model").equals("actual"),"Java external model routing failed");
            try{OpenAICompletionProtocol.normalize(wire().put("unknown_parameter",1),false);throw new AssertionError("Unknown option accepted");}catch(CompletionError expected){}
        }finally{server.stop(0);pool.shutdownNow();}
        pass("Java external HTTP JSON/SSE, native tool argument fragments, finish reason, exact usage, truncation and unsupported parameter rejection");
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].equals("prepare")) {
            ExecutionTest.prepare(Files.createDirectory(java.nio.file.Paths.get(args[1])),"");
            for(String name:Arrays.asList("Baize","Baize2")) {
                profiles.publish(T,ModelProfiles.model(name));
                JSONObject t=task("2",release,name,1024).put("id","inference-"+name).put("schemaVersion",2)
                    .put("executionLimits",new JSONObject().put("maxRounds",8).put("maxTools",16).put("maxRepairs",1).put("maxLifetimeMillis",60000));
                t.getJSONObject("model").put("version","2");t.getJSONObject("contextPolicy").put("budgetVersion","transport-bytes-v1").put("maxInputBytes",48000);
                profiles.publish(T,t);catalog.bind(T,"task","inference-"+name,"inference-"+name+"@2",0);
            }
            return;
        }
        migration();ledger();sharedCapacity();http();System.out.println("PASS: inference gateway offline integration");
    }

    static void sharedCapacity() throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-relay-capacity-"), "");
        Properties p = new Properties();
        for (String worker : Arrays.asList("relay-a", "relay-b", "relay-c", "relay-d")) {
            p.setProperty("worker." + worker + ".role", "model");
            p.setProperty("worker." + worker + ".model", worker.equals("relay-c") || worker.equals("relay-d") ? "Baize2" : "Baize");
            p.setProperty("worker." + worker + ".backend", worker.equals("relay-d") ? "other" : "shared");
        }
        p.setProperty("backend.shared.capacity", "4"); p.setProperty("backend.other.capacity", "4");
        final java.util.concurrent.atomic.AtomicInteger budget = new java.util.concurrent.atomic.AtomicInteger(1);
        final java.util.concurrent.atomic.AtomicBoolean firstAvailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicBoolean detailed = new java.util.concurrent.atomic.AtomicBoolean(true);
        CompletionProvider provider = new CompletionProvider() {
            public JSONArray completionModels(String token) { return new JSONArray()
                    .put(new JSONObject().put("id", "Baize").put("capabilities", new JSONObject().put("text", true)))
                    .put(new JSONObject().put("id", "Baize2").put("capabilities", new JSONObject().put("text", true))); }
            public JSONObject completionCapacity(String token, String model, boolean tools) {
                int free = model.equals("Baize") && !firstAvailable.get() ? 0 : 1;
                if (!detailed.get()) return new JSONObject().put("registered", 1).put("available", 1).put("capacityGroup", "shared-relay");
                return new JSONObject().put("registered", 1).put("available", free).put("capacityGroup", "shared-relay")
                        .put("capacityGroups", new JSONArray().put(new JSONObject().put("capacityGroup", "shared-relay").put("route", model)
                                .put("registered", 1).put("available", free).put("groupCapacity", budget.get()).put("groupAvailable", budget.get())));
            }
            public void submitCompletion(String token, String id, JSONObject request, long timeout, Listener listener) {}
        };
        ModelJobStore jobs = new ModelJobStore(store, false);
        try (InferenceGateway gateway = new InferenceGateway(new InferenceStore(store, false), p, () -> provider)) {
            ModelDispatcher dispatcher = new ModelDispatcher(jobs, p, 60000, gateway);
            int transport = 0;
            for (String worker : Arrays.asList("relay-a", "relay-b", "relay-c", "relay-d")) {
                JSONObject d = descriptor().put("model", p.getProperty("worker." + worker + ".model"));
                dispatcher.apply(envelope(worker, "describe", null).put("descriptor", d), ++transport, () -> true);
            }
            JSONObject capacity = dispatcher.capacity();
            check(capacity.getInt("registeredUnits") == 4 && capacity.getInt("available") == 1
                    && capacity.getInt("executableConcurrency") == 1, "Relay capacity duplicated across models/workers/backends");
            budget.set(2); capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 2 && capacity.getInt("executableConcurrency") == 2,
                    "Distinct Relay model routes cannot use configured shared capacity");
            budget.set(1); firstAvailable.set(false); capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 1, "Unavailable alias hid another model's free shared route");
            assertCapacityBounds(capacity);
            budget.set(1); detailed.set(false); capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 1 && capacity.getInt("executableConcurrency") == 1,
                    "Aggregate capacityGroup without route detail was counted multiple times");
            assertCapacityBounds(capacity);
            budget.set(2); firstAvailable.set(true); detailed.set(true);
            // A Python worker can be busy processing a response while physical inference is
            // free. The same model on another backend must retain its executable slot.
            try (Connection c = config.connect(); Statement s = c.createStatement()) {
                s.execute("INSERT INTO harness_run VALUES ('capacity-run','tenant','subject','profile','running',1,1,NULL,0,0,'pending','settled',1,1)");
                s.execute("INSERT INTO harness_model_job (job_id,run_id,tenant,subject_id,round_id,attempt,origin_fence,intent_hash,model_id,profile_digest,request_hash,request_json,backend_id,worker_id,instance_id,session_id,status,actual_finished,cancel_requested,created_at,execution_protocol) "
                        + "VALUES ('capacity-job','capacity-run','tenant','subject','round',1,1,'intent','Baize2','profile','request','{}','shared','relay-c','irelay-c','session','assigned',0,0,1,3)");
            }
            capacity = dispatcher.capacity();
            check(capacity.getInt("available") == 2 && capacity.getInt("executableConcurrency") == 2,
                    "Busy processing alias consumed another backend's registered inference route");
            assertCapacityBounds(capacity);
        }
        pass("Relay shared capacity across multiple workers, models and backend IDs");
    }

    static void assertCapacityBounds(JSONObject capacity) {
        JSONArray backends = capacity.getJSONArray("backends");
        for (int i = 0; i < backends.length(); i++) {
            JSONObject backend = backends.getJSONObject(i);
            check(backend.getInt("available") <= backend.getInt("executableConcurrency"),
                    "Backend available capacity exceeds its executable concurrency: " + backend.getString("backend_id"));
        }
    }
}
