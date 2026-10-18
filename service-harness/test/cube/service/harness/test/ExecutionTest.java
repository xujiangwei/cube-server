package cube.service.harness.test;
import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Actual JDBC coordinator/scheduler tests, plus fixture preparation for the real Cell/Python smoke. */
public final class ExecutionTest {
    static ModelJobStore jobs;static RunCoordinator coordinator;static ModelDispatcher dispatcher;
    static JSONObject descriptor(String backend){return new JSONObject().put("model","scripted").put("backendId",backend).put("adapter","scripted-v1").put("capacity",1).put("nativeTools",false).put("cancellation","observe-only").put("budget","transport-bytes-v1").put("profileDigest",ModelProfiles.adapterDigest("scripted-v1"));}
    static JSONObject envelope(String worker,String instance,String action){JSONObject e=new JSONObject().put("protocolVersion",2).put("requestId",key()).put("credential","test-worker-credential-123456").put("workerId",worker).put("instanceId",instance).put("role","model").put("capabilities",new JSONArray().put("model.round")).put("action",action);if(!action.equals("describe"))e.put("healthy",true);return e;}
    static void prepare(Path directory,String file)throws Exception {
        root=directory;Properties p=new Properties();if(!file.isEmpty()){try(InputStream in=Files.newInputStream(Paths.get(file))){p.load(in);}check("true".equals(p.getProperty("store.test.disposable")),"Disposable store required");}
        else {p.setProperty("store.dialect","sqlite");p.setProperty("store.database","harness_execution");p.setProperty("store.identity","execution-test-identity-123456");p.setProperty("store.sqlite.root",root.toString());p.setProperty("store.sqlite.file",root.resolve("harness_execution.db").toString());}
        config=new StoreConfig(p);StoreMigrations.migrate(config,true);
        String runtimeConfig=System.getProperty("execution.test.runtime","");
        if(!runtimeConfig.isEmpty()){Properties rp=new Properties();try(InputStream in=Files.newInputStream(Paths.get(runtimeConfig))){rp.load(in);}check("true".equals(rp.getProperty("store.test.disposable")),"Disposable runtime required");p=rp;config=new StoreConfig(p);}
        setup();
        Map<String,byte[]> entries=entries("A resource used by the real Harness coordinator.");
        release=upload(zip(manifest(entries),entries)).getString("digest");catalog.lifecycle(T,release,1,"ready");
        JSONObject t=task("1",release,"scripted",1024).put("schemaVersion",2)
            .put("executionLimits",new JSONObject().put("maxRounds",8).put("maxTools",16).put("maxRepairs",1).put("maxLifetimeMillis",60000));
        t.getJSONObject("contextPolicy").put("maxInputBytes",48000);profiles.publish(T,t);catalog.bind(T,"task","task","task@1",0);
        // Strict decision, round/tool budget and waiting_input variants share the pinned resource/model profiles.
        for(String variant:Arrays.asList("one-round","no-tools","lifetime")) {
            JSONObject v=RuntimeSupport.copy(t).put("id",variant);v.getJSONObject("executionLimits").put(variant.equals("one-round")?"maxRounds":variant.equals("no-tools")?"maxTools":"maxLifetimeMillis",variant.equals("lifetime")?1000:variant.equals("no-tools")?0:1);
            profiles.publish(T,v);catalog.bind(T,"task",variant,variant+"@1",0);
        }
        Files.write(root.resolve("store.local.properties"),bytes(properties(p)),StandardOpenOption.CREATE_NEW);
        String slow=store.create(T,S,key(),"events-only",new JSONObject().put("fixture","slow-consumer")).getString("runId");JdbcRunStore.Lease sl=store.acquire(T,S,slow,"events-fixture",version(slow),60000);
        String large=new String(new char[60000]).replace('\0','x');for(int i=0;i<80;i++)store.transition(sl,version(slow),"running",new JSONObject().put("text",large));
        store.finish(sl,version(slow),"succeeded","confirmed",new JSONObject().put("fixture",true),new JSONArray());
        Files.write(root.resolve("fixture.json"),bytes(new JSONObject().put("release",release).put("tenant",T).put("subject",S).put("slowRun",slow).toString(2)),StandardOpenOption.CREATE_NEW);
    }
    static String properties(Properties p){StringBuilder b=new StringBuilder();for(String k:new TreeSet<>(p.stringPropertyNames()))b.append(k).append('=').append(p.getProperty(k).replace("\\","/")).append('\n');return b.toString();}
    static JSONObject report(JSONObject j,JSONObject decision,boolean finished){JSONObject outcome=new JSONObject().put("status",finished?"success":"unknown").put("audit",new JSONArray()).put("cancellationObserved",false);if(finished)outcome.put("decision",decision).put("usage",new JSONObject().put("input",10).put("output",5));else outcome.put("errorCode","MODEL_COMPLETION_UNKNOWN");return new JSONObject().put("jobId",j.getString("jobId")).put("requestHash",j.getString("requestHash")).put("instanceId",j.getString("instanceId")).put("actualFinished",finished).put("outcomeJson",outcome.toString());}
    static String create(String text){return profiles.createRun(identity,key(),"task",request(text)).getString("runId");}
    static JSONObject poll(String w,String instance,String session){return dispatcher.apply(envelope(w,instance,"poll").put("sessionId",session),w.equals("a")?1:2,()->true);}
    static void checks()throws Exception {
        jobs=new ModelJobStore(store,config.mysql());coordinator=new RunCoordinator(store,jobs,catalog,identity,resources);
        Properties p=new Properties();for(String w:Arrays.asList("a","b")){p.setProperty("worker."+w+".role","model");p.setProperty("worker."+w+".model","scripted");p.setProperty("worker."+w+".backend","shared");}p.setProperty("backend.shared.capacity","1");
        dispatcher=new ModelDispatcher(jobs,p,1000);
        String sa=dispatcher.apply(envelope("a","ia","describe").put("descriptor",descriptor("shared")),1,()->true).getString("sessionId");
        String sb=dispatcher.apply(envelope("b","ib","describe").put("descriptor",descriptor("shared")),2,()->true).getString("sessionId");
        String r1=create("first"),r2=create("second");for(int i=0;i<3;i++){coordinator.step(r1);coordinator.step(r2);}
        JSONObject a=poll("a","ia",sa).getJSONObject("job");check(!poll("b","ib",sb).has("job"),"Shared capacity counted twice");
        check(poll("a","ia",sa).getJSONObject("job").toString().equals(a.toString()),"Lost dispatch not replayed");
        dispatcher.disconnect(1);check(!poll("b","ib",sb).has("job"),"Disconnect released slot");
        String sa2=dispatcher.apply(envelope("a","ia","describe").put("descriptor",descriptor("shared")),1,()->true).getString("sessionId");
        JSONObject unknown=report(a,null,false);dispatcher.apply(envelope("a","ia","actual_finished").put("sessionId",sa2).put("report",unknown),1,()->true);
        check(!poll("b","ib",sb).has("job"),"Unknown completion released slot");
        JSONObject complete=report(a,new JSONObject().put("kind","final").put("text","ok"),true);
        dispatcher.apply(envelope("a","ia","actual_finished").put("sessionId",sa2).put("report",complete),1,()->true);
        dispatcher.apply(envelope("a","ia","actual_finished").put("sessionId",sa2).put("report",complete),1,()->true);
        JSONObject second=poll("b","ib",sb).getJSONObject("job");
        String completedRun=a.getString("runId");for(int i=0;i<4;i++)coordinator.step(completedRun);
        check(store.read(T,S,completedRun).getJSONObject("snapshot").getString("state").equals("succeeded"),"Coordinator final missing");
        long seq=store.read(T,S,completedRun).getJSONObject("snapshot").getLong("eventSeq");coordinator.step(completedRun);check(seq==store.read(T,S,completedRun).getJSONObject("snapshot").getLong("eventSeq"),"Final repeated");
        fail("JOB_RESULT_CONFLICT",()->jobs.report("a",report(a,new JSONObject().put("kind","final").put("text","changed"),true)));
        check(jobs.capacity().getJSONArray("backends").getJSONObject(0).getInt("occupied")==1,"Wrong actual occupancy");
        fail("LEASE_BUSY",()->store.acquire(T,S,completedRun,"other-owner",version(completedRun),1000));
        for(String m:new String[]{"Baize","Baize2"})profiles.publish(T,ModelProfiles.model(m));
        jobs.report("b",report(second,new JSONObject().put("kind","final").put("text","second"),true));for(int i=0;i<4;i++)coordinator.step(second.getString("runId"));
        String waiting=create("question");JSONObject question=dispatch(waiting,"b","ib",sb);
        jobs.report("b",report(question,new JSONObject().put("kind","need_input").put("question","Which file?"),true));for(int i=0;i<4;i++)coordinator.step(waiting);
        check(store.read(T,S,waiting).getJSONObject("snapshot").getString("state").equals("waiting_input"),"need_input not durable");
        long waitSeq=store.read(T,S,waiting).getJSONObject("snapshot").getLong("eventSeq");coordinator.step(waiting);check(waitSeq==store.read(T,S,waiting).getJSONObject("snapshot").getLong("eventSeq"),"Waiting emitted busy checkpoints");
        JSONObject req=request("late").put("taskProfileId","lifetime");String late=profiles.createRun(identity,key(),"lifetime",req).getString("runId");JSONObject running=dispatch(late,"b","ib",sb);
        Thread.sleep(1100);coordinator.step(late);JSONObject terminal=store.read(T,S,late);check(terminal.getJSONObject("snapshot").getString("state").equals("timed_out"),"Lifetime not enforced");
        check(jobs.capacity().getJSONArray("backends").getJSONObject(0).getInt("occupied")==1,"Logical timeout freed physical slot");
        jobs.report("b",report(running,new JSONObject().put("kind","final").put("text","late physical completion"),true));
        // Explicit test-only release avoids waiting ten seconds; the next step must acquire a new fence.
        store.release(new JdbcRunStore.Lease(T,S,late,terminal.getString("owner"),terminal.getLong("fence")),version(late));
        for(int i=0;i<4;i++)coordinator.step(late);
        JSONObject settled=store.read(T,S,late);check(settled.getJSONObject("snapshot").getString("state").equals("timed_out")&&settled.getString("usageSettlement").equals("settled")&&settled.getJSONObject("snapshot").getString("executionSettlement").equals("confirmed"),"Late terminal reconciliation lost");
        pass("need_input releases capacity without busy-loop events; logical timeout retains slot; late physical completion and usage reconcile under a new fence without changing final state");
        coordinator.close();pass("N06/N07 JDBC: shared capacity, immutable dispatch, disconnect/unknown quarantine, late actual_finished, duplicate/conflicting evidence, unique final/owner, Baize/Baize2 v2 profiles");
    }
    static JSONObject dispatch(String run,String worker,String instance,String session)throws Exception {for(int i=0;i<3;i++)coordinator.step(run);JSONObject response=poll(worker,instance,session);check(response.has("job"),"No dispatch");JSONObject job=response.getJSONObject("job");check(job.getString("runId").equals(run),"Wrong run selected");return job;}
    public static void main(String[] args)throws Exception {
        if(args.length>=2&&args[0].equals("prepare")){prepare(Files.createDirectory(Paths.get(args[1])),args.length>2?args[2]:"");return;}
        prepare(Files.createTempDirectory("harness-execution-"),System.getProperty("execution.test.config",""));checks();
        System.out.println("PASS: N06/N07 execution on "+config.dialect+" at "+root);
    }
}
