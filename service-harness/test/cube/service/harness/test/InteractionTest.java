package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Delayed model results exercise the real JDBC/coordinator acceptance boundaries. */
public final class InteractionTest {
    static ControlStore controls;
    static String sessionA, sessionB;
    static JSONObject instruction(String text) { return new JSONObject().put("kind", "instruction").put("input", request(text).getJSONObject("input")); }
    static JSONObject append(String run, String text) { return controls.messages.append(T, S, run, key(), instruction(text)); }
    static void advance(String run) throws Exception { for (int i=0;i<4;i++) ExecutionTest.coordinator.step(run); }
    static JSONObject dispatch(String run, boolean second) throws Exception {
        return ExecutionTest.dispatch(run, second ? "b" : "a", second ? "ib" : "ia", second ? sessionB : sessionA);
    }
    static void complete(JSONObject job, JSONObject decision) {
        ExecutionTest.jobs.report(job.getString("workerId"), ExecutionTest.report(job, decision, true));
    }
    static JSONObject finalAnswer(String text) { return new JSONObject().put("kind", "final").put("text", text); }
    static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Accepted " + code); }
        catch (StoreException e) { check(e.code.equals(code), e.code + " != " + code); }
    }
    static void messagesAndLateFinal() throws Exception {
        String run = ExecutionTest.create("original");
        JSONObject old = dispatch(run, false);
        String key = key(); JSONObject body = instruction("only this month");
        JSONObject accepted = controls.messages.append(T,S,run,key,body);
        check(accepted.getString("messageId").equals(controls.messages.append(T,S,run,key,body).getString("messageId")), "message replay");
        expect("IDEMPOTENCY_CONFLICT", () -> controls.messages.append(T,S,run,key,instruction("other")));
        expect("RUN_NOT_FOUND", () -> controls.messages.append(T,"other",run,key(),body));
        append(run,"group by region");
        check(controls.messages.list(T,S,run,0,100).getJSONArray("messages").length()==2,"lost queued input");
        // Old model finished after the correction: settle it, then dispatch on another worker.
        complete(old,finalAnswer("obsolete"));
        JSONObject latest = dispatch(run,true);
        String context = latest.getString("requestJson");
        check(context.contains("only this month") && context.contains("group by region"),"corrections not in context");
        check(!context.contains("obsolete"),"old final reused");
        check(controls.messages.list(T,S,run,0,100).getJSONArray("messages").getJSONObject(0).getString("status").equals("applied"),"watermark");
        complete(latest,finalAnswer("current")); advance(run);
        check(store.read(T,S,run).getJSONObject("snapshot").getString("state").equals("succeeded"),"new final not committed");
        expect("RUN_TERMINAL",()->append(run,"too late"));
        check(controls.messages.append(T,S,run,key,body).getString("messageId").equals(accepted.getString("messageId")),"terminal replay lost");
    }
    static void obsoleteTools() throws Exception {
        String run=ExecutionTest.create("read resources"); JSONObject old=dispatch(run,false);
        JSONArray calls=new JSONArray().put(new JSONObject().put("proposalId","old-proposal").put("tool","resource.list").put("argumentsJson","{}"));
        append(run,"answer directly"); complete(old,new JSONObject().put("kind","tool_calls").put("calls",calls));
        JSONObject latest=dispatch(run,true);
        check(store.intents(T,S,run,"call").length()==0,"obsolete proposal executed");
        check(!latest.getString("requestJson").contains("old-proposal"),"obsolete call leaked into model history");
        complete(latest,finalAnswer("without tools")); advance(run);
    }
    static void questions() throws Exception {
        String run=ExecutionTest.create("choose a direction"); JSONObject job=dispatch(run,false);
        JSONObject question=new JSONObject().put("kind","need_input").put("question","Which region?")
                .put("questions",new JSONArray().put(new JSONObject().put("questionId","region").put("prompt","Select region")
                        .put("options",new JSONArray().put(new JSONObject().put("id","north").put("label","North")))
                        .put("allowFreeText",false)).put(new JSONObject().put("questionId","period").put("prompt","Which period?")
                        .put("options",new JSONArray()).put("allowFreeText",true)));
        complete(job,question); advance(run);
        JSONObject wait=controls.current(T,S,run);
        check(wait!=null && wait.getJSONObject("details").has("questions"),"structured wait missing");
        expect("ANSWER_REQUIRED",()->controls.apply(T,S,S,run,key(),"resume",wait.getString("waitId"),
                request("bypass choice").put("waitId",wait.getString("waitId")).put("expectedVersion",version(run)),false));
        MessageStore restarted=new MessageStore(new JdbcRunStore(config),config.mysql()); Properties p=new Properties();p.setProperty("interaction.enabled","true");restarted.configure(p);
        JSONObject answer=new JSONObject().put("kind","answer").put("waitId",wait.getString("waitId"))
                .put("interactionVersion",wait.getLong("interactionVersion")).put("input",request("use this choice").getJSONObject("input"))
                .put("answers",new JSONArray().put(new JSONObject().put("questionId","region").put("optionId","invalid"))
                        .put(new JSONObject().put("questionId","period").put("text","this month")));
        expect("ANSWER_OPTION_CONFLICT",()->restarted.append(T,S,run,key(),answer));
        answer.getJSONArray("answers").getJSONObject(0).put("optionId","north");
        JSONObject incomplete=new JSONObject(answer.toString());incomplete.getJSONArray("answers").remove(1);
        expect("ANSWER_REQUIRED",()->restarted.append(T,S,run,key(),incomplete));
        restarted.append(T,S,run,key(),answer);
        expect("WAIT_STATE_CONFLICT",()->restarted.append(T,S,run,key(),answer));
        JSONObject next=dispatch(run,true);
        check(next.getString("requestJson").contains("North")&&next.getString("requestJson").contains("this month"),"multiple answers not in user input");
        complete(next,finalAnswer("North result"));advance(run);
        // A change of goal invalidates the old question, rather than answering another wait.
        String changed=ExecutionTest.create("ask");JSONObject asking=dispatch(changed,false);complete(asking,question);advance(changed);
        JSONObject previous=controls.current(T,S,changed);append(changed,"different goal");
        answer.put("waitId",previous.getString("waitId")).put("interactionVersion",previous.getLong("interactionVersion"));
        expect("WAIT_STATE_CONFLICT",()->restarted.append(T,S,changed,key(),answer));
        JSONObject changedJob=dispatch(changed,true);complete(changedJob,finalAnswer("changed"));advance(changed);
    }
    static void queuedAndRace() throws Exception {
        String run=ExecutionTest.create("queued");append(run,"before dispatch");JSONObject j=dispatch(run,false);
        check(j.getString("requestJson").contains("before dispatch"),"queued message lost");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> a=pool.submit(()->append(run,"one"));Future<?> b=pool.submit(()->append(run,"two"));a.get();b.get();
        } finally {pool.shutdownNow();}
        JSONObject list=controls.messages.list(T,S,run,0,100);check(list.getJSONArray("messages").length()==3,"concurrent message lost");
        complete(j,finalAnswer("late"));JSONObject latest=dispatch(run,true);complete(latest,finalAnswer("all three"));advance(run);
    }
    static void conversation() throws Exception {
        String parent=ExecutionTest.create("original scope");JSONObject j=dispatch(parent,false);
        append(parent,"only north");complete(j,finalAnswer("old"));j=dispatch(parent,true);complete(j,finalAnswer("north report"));advance(parent);
        JSONObject body=request("explain previous report").put("parentRunId",parent);String receipt=key();
        String child=profiles.createRun(identity,receipt,"task",body).getString("runId");
        check(child.equals(profiles.createRun(identity,receipt,"task",body).getString("runId")),"continuation replay");
        JSONObject next=dispatch(child,false);String wire=next.getString("requestJson");
        check(wire.contains("original scope")&&wire.contains("only north")&&wire.contains("north report")&&wire.contains("explain previous report"),"conversation history absent");
        complete(next,finalAnswer("explanation"));advance(child);
        ProfileRegistry.Identity other=new ProfileRegistry.Identity(T,"someone-else",identity.permissions);
        expect("RUN_NOT_FOUND",()->profiles.createRun(other,key(),"task",body));
        String active=ExecutionTest.create("still active");
        expect("PARENT_RUN_ACTIVE",()->profiles.createRun(identity,key(),"task",request("continue").put("parentRunId",active)));
        controls.apply(T,S,S,active,key(),"cancel","",new JSONObject().put("expectedVersion",version(active)),false);advance(active);
    }
    static void finalAcceptanceRace() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try { for(int i=0;i<8;i++) {
            String run=ExecutionTest.create("finish race");JdbcRunStore.Lease lease=running(run,60000);long version=version(run);
            CountDownLatch start=new CountDownLatch(1);
            Future<Boolean> accepted=pool.submit(()->{start.await();try{append(run,"correction");return true;}catch(StoreException e){check(e.code.equals("RUN_TERMINAL"),e.code);return false;}});
            Future<Boolean> finished=pool.submit(()->{start.await();try{store.finish(lease,version,"succeeded","confirmed",finalAnswer("original"),new JSONArray());return true;}catch(StoreException e){check(Arrays.asList("VERSION_CONFLICT","INPUT_REVISION_CONFLICT").contains(e.code),e.code);return false;}});
            start.countDown();boolean a=accepted.get(),f=finished.get();check(a!=f,"both input and obsolete final committed");
            if(a)store.finish(lease,version(run),"cancelled","confirmed",new JSONObject(),new JSONArray());
        }}finally{pool.shutdownNow();}
    }
    static void admissionAndBudgets() throws Exception {
        String run=ExecutionTest.create("tool boundaries");JdbcRunStore.Lease lease=running(run,60000);
        resources.activate(identity,run,release);
        for(String id:Arrays.asList("already-admitted","not-dispatched"))intent(lease,"call",id,new JSONObject().put("proposalId",id).put("tool","resource.list").put("argumentsJson",new JSONObject().put("release",release).toString()));
        JSONObject admitted=row(run,"call","already-admitted"),queued=row(run,"call","not-dispatched");
        store.admitCall(lease,admitted);append(run,"change direction");
        store.admitCall(lease,admitted); // Reconciliation keeps original execution identity.
        expect("INPUT_REVISION_CONFLICT",()->store.admitCall(lease,queued));
        store.release(lease,version(run));
        // Recover with a new owner after admission but before local result confirmation.
        ExecutionTest.coordinator.step(run);ExecutionTest.coordinator.step(run);
        check(new JSONObject(row(run,"call","already-admitted").getString("result_json")).getString("status").equals("success"),"admitted local call lost during recovery");
        check(new JSONObject(row(run,"call","not-dispatched").getString("result_json")).getString("errorCode").equals("TOOL_SUPERSEDED"),"unadmitted local call executed after correction");
        controls.apply(T,S,S,run,key(),"cancel","",new JSONObject().put("expectedVersion",version(run)),false);advance(run);
        String waiting=ExecutionTest.create("wait budget");JdbcRunStore.Lease wl=running(waiting,60000);
        controls.waitFor(wl,version(waiting),"input",new JSONObject().put("question","Clarify").put("binding","budget"),System.currentTimeMillis()+60000);
        try(java.sql.Connection c=config.connect();java.sql.PreparedStatement q=c.prepareStatement("UPDATE harness_run SET created_at=?,wait_started_at=? WHERE run_id=?")){
            long now=System.currentTimeMillis();q.setLong(1,now-20000);q.setLong(2,now-15000);q.setString(3,waiting);q.executeUpdate();
        }
        ModelJobStore restarted=new ModelJobStore(new JdbcRunStore(config),config.mysql());
        check(!restarted.activeExpired(T,S,waiting,10000)&&restarted.expired(T,S,waiting,10000),"waiting charged as active or hard deadline reset");
        JSONObject wait=controls.current(T,S,waiting);
        controls.apply(T,S,S,waiting,key(),"resume",wait.getString("waitId"),request("answer").put("waitId",wait.getString("waitId")).put("expectedVersion",version(waiting)),false);
        check(!restarted.activeExpired(T,S,waiting,10000)&&restarted.activeExpired(T,S,waiting,4000),"accumulated wait lost on resume");
        store.finish(wl,version(waiting),"cancelled","confirmed",new JSONObject(),new JSONArray());
    }
    static void switchesAndLimits() throws Exception {
        String run=ExecutionTest.create("bounded input");
        MessageStore disabled=new MessageStore(store,config.mysql()).configure(new Properties());
        expect("INTERACTION_NOT_READY",()->disabled.append(T,S,run,key(),instruction("disabled")));
        Properties p=new Properties();p.setProperty("interaction.enabled","true");p.setProperty("interaction.max.messages","2");p.setProperty("interaction.max.pending","1");
        MessageStore bounded=new MessageStore(store,config.mysql()).configure(p);
        String receipt=key();JSONObject body=instruction("first");bounded.append(T,S,run,receipt,body);
        check(disabled.append(T,S,run,receipt,body).getLong("inputRevision")==1,"disabled switch hid durable receipt");
        expect("MESSAGE_QUEUE_FULL",()->bounded.append(T,S,run,key(),instruction("pending overflow")));
        JSONObject old=dispatch(run,false);
        bounded.append(T,S,run,key(),instruction("second"));
        expect("MESSAGE_QUEUE_FULL",()->bounded.append(T,S,run,key(),instruction("total overflow")));
        complete(old,finalAnswer("old"));JSONObject latest=dispatch(run,true);complete(latest,finalAnswer("bounded"));advance(run);
        check(!ExecutionTest.dispatcher.ready(new JSONObject().put("id","scripted").put("adapter","scripted-v1")
                .put("generation",new JSONObject().put("structuredInput",true))),"legacy worker claimed structured input readiness");
    }
    static void legacyWaitAndExpiry() throws Exception {
        String run=ExecutionTest.create("upgrade pending question");JdbcRunStore.Lease lease=running(run,60000);
        controls.waitFor(lease,version(run),"input",new JSONObject().put("question","Clarify").put("binding","legacy"),System.currentTimeMillis()+60000);
        JSONObject wait=controls.current(T,S,run),details=wait.getJSONObject("details");details.remove("interactionVersion");details.remove("inputRevision");
        try(java.sql.Connection c=config.connect();java.sql.PreparedStatement q=c.prepareStatement("UPDATE harness_wait SET payload_json=? WHERE wait_id=?")){
            q.setString(1,JdbcRunStore.canonical(details));q.setString(2,wait.getString("waitId"));q.executeUpdate();
        }
        wait=controls.current(T,S,run);
        controls.messages.append(T,S,run,key(),instruction("answer after upgrade").put("kind","answer")
                .put("waitId",wait.getString("waitId")).put("interactionVersion",wait.getLong("interactionVersion")));
        store.finish(lease,version(run),"cancelled","confirmed",new JSONObject(),new JSONArray());
        String expired=ExecutionTest.create("expired question");JdbcRunStore.Lease el=running(expired,60000);
        controls.waitFor(el,version(expired),"input",new JSONObject().put("question","Clarify").put("binding","expired"),System.currentTimeMillis()+60000);
        try(java.sql.Connection c=config.connect();java.sql.PreparedStatement q=c.prepareStatement("UPDATE harness_wait SET expires_at=0 WHERE run_id=?")){q.setString(1,expired);q.executeUpdate();}
        expect("WAIT_EXPIRED",()->append(expired,"late correction"));
        store.finish(el,version(expired),"timed_out","confirmed",new JSONObject(),new JSONArray());
    }
    static void resumeLimits() throws Exception {
        String run=ExecutionTest.create("legacy resume limits");JdbcRunStore.Lease lease=running(run,60000);
        Properties p=new Properties();p.setProperty("interaction.max.messages","1");p.setProperty("interaction.max.pending","1");
        ControlStore bounded=new ControlStore(store,config.mysql()).configure(p);
        for(int i=0;i<2;i++){
            bounded.waitFor(lease,version(run),"input",new JSONObject().put("question","Clarify").put("binding","limit-"+i),System.currentTimeMillis()+60000);
            JSONObject w=bounded.current(T,S,run),body=request("answer").put("waitId",w.getString("waitId")).put("expectedVersion",version(run));
            if(i==0){
                bounded.apply(T,S,S,run,key(),"resume",w.getString("waitId"),body,false);
                try(java.sql.Connection c=config.connect();java.sql.PreparedStatement q=c.prepareStatement("UPDATE harness_run SET applied_input_revision=input_revision WHERE run_id=?")){q.setString(1,run);q.executeUpdate();}
            }else expect("MESSAGE_QUEUE_FULL",()->bounded.apply(T,S,S,run,key(),"resume",w.getString("waitId"),body,false));
        }
        store.finish(lease,version(run),"cancelled","confirmed",new JSONObject(),new JSONArray());
    }
    static void invalidQuestions() throws Exception {
        String run=ExecutionTest.create("invalid choices");JSONObject job=dispatch(run,false);
        JSONObject q=new JSONObject().put("questionId","same").put("prompt","Choose").put("options",new JSONArray()).put("allowFreeText",true);
        complete(job,new JSONObject().put("kind","need_input").put("question","Choose")
                .put("questions",new JSONArray().put(q).put(new JSONObject(q.toString()))));
        advance(run);
        JSONObject read=store.read(T,S,run);
        check(read.getJSONObject("snapshot").getString("state").equals("failed")&&read.toString().contains("QUESTION_ID_CONFLICT"),"invalid questions left run stuck");
    }
    static void boundedPages() throws Exception {
        String run=ExecutionTest.create("large messages");String text=new String(new char[30000]).replace('\0','林');
        for(int i=0;i<8;i++)append(run,text+i);
        long cursor=0;int count=0;
        do{
            JSONObject page=controls.messages.list(T,S,run,cursor,100);
            check(RuntimeSupport.bytes(page).length<512*1024+4096,"message page exceeds transport budget");
            check(page.getLong("nextCursor")>cursor,"message page did not advance");
            count+=page.getJSONArray("messages").length();cursor=page.getLong("nextCursor");
        }while(cursor<8);
        check(count==8,"bounded pagination dropped messages");
        JdbcRunStore.Lease lease=running(run,60000);
        store.finish(lease,version(run),"cancelled","confirmed",new JSONObject(),new JSONArray());
        Properties p=new Properties();try(java.io.InputStream in=Files.newInputStream(root.resolve("store.local.properties"))){p.load(in);}
        p.setProperty("execution.repository",root.resolve("repository").toString());p.setProperty("execution.repository.namespace","runtime-test-repo");
        p.setProperty("execution.permissions",String.join(",",TEST_PERMISSIONS));
        try(ExecutionRuntime runtime=new ExecutionRuntime(store,p,3000)){
            cursor=0;int events=0;long total=store.read(T,S,run).getJSONObject("snapshot").getLong("eventSeq");
            do{
                JSONObject page=runtime.events(identity,run,cursor,100);
                check(RuntimeSupport.bytes(page).length<512*1024+4096,"event page exceeds transport budget");
                check(page.getLong("nextCursor")>cursor,"event page did not advance");
                events+=page.getJSONArray("events").length();cursor=page.getLong("nextCursor");
            }while(cursor<total);
            check(events==total,"bounded pagination dropped events");
        }
    }
    static void largeModelDecisions() throws Exception {
        String text=new String(new char[32768]).replace('\0','林');
        String run=ExecutionTest.create("long final");JSONObject job=dispatch(run,false);complete(job,finalAnswer(text));advance(run);
        check(store.read(T,S,run).getJSONObject("result").getJSONObject("result").getString("text").equals(text),"valid Unicode final did not persist");
        String prompt=new String(new char[4096]).replace('\0','林'),description=new String(new char[1024]).replace('\0','林');
        JSONArray questions=new JSONArray();
        for(int i=0;i<3;i++){
            JSONArray options=new JSONArray();for(int j=0;j<8;j++)options.put(new JSONObject().put("id","o"+j).put("label","Option "+j).put("description",description));
            questions.put(new JSONObject().put("questionId","q"+i).put("prompt",prompt).put("allowFreeText",false).put("options",options));
        }
        String asking=ExecutionTest.create("long structured question");job=dispatch(asking,false);
        complete(job,new JSONObject().put("kind","need_input").put("question","Choose").put("questions",questions));advance(asking);
        check(controls.current(T,S,asking).getJSONObject("details").getJSONArray("questions").length()==3,"valid large question did not reach waiting state");
        append(asking,"answer directly instead");job=dispatch(asking,true);complete(job,finalAnswer("current"));advance(asking);
    }
    static void cancelWinsLateFinal() throws Exception {
        String run=ExecutionTest.create("cancel versus final");JdbcRunStore.Lease lease=running(run,60000);
        append(run,"new requirement");
        expect("INPUT_REVISION_CONFLICT",()->store.finish(lease,version(run),"failed","confirmed",new JSONObject().put("errorCode","STALE_CHECK"),new JSONArray(),0));
        controls.apply(T,S,S,run,key(),"cancel","",new JSONObject().put("expectedVersion",version(run)),false);
        expect("VERSION_CONFLICT",()->store.finish(lease,version(run),"succeeded","confirmed",finalAnswer("too late"),new JSONArray()));
        expect("VERSION_CONFLICT",()->store.finish(lease,version(run),"failed","confirmed",new JSONObject().put("errorCode","LATE_CHECK"),new JSONArray(),1));
        check(store.read(T,S,run).getJSONObject("snapshot").getString("state").equals("cancelling"),"late final overrode accepted cancellation");
        store.finish(lease,version(run),"cancelled","confirmed",new JSONObject(),new JSONArray());
    }
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-interaction-"),System.getProperty("execution.test.config",""));
        Properties p=new Properties();p.setProperty("interaction.enabled","true");
        controls=new ControlStore(store,config.mysql()).configure(p);
        ExecutionTest.jobs=new ModelJobStore(store,config.mysql());
        ExecutionTest.coordinator=new RunCoordinator(store,ExecutionTest.jobs,catalog,identity,resources).controls(controls,null,p);
        for(String w:Arrays.asList("a","b")){p.setProperty("worker."+w+".role","model");p.setProperty("worker."+w+".model","scripted");p.setProperty("worker."+w+".backend","shared");}
        p.setProperty("backend.shared.capacity","1");
        ExecutionTest.dispatcher=new ModelDispatcher(ExecutionTest.jobs,p,60000);
        sessionA=ExecutionTest.dispatcher.apply(ExecutionTest.envelope("a","ia","describe").put("descriptor",ExecutionTest.descriptor("shared")),1,()->true).getString("sessionId");
        sessionB=ExecutionTest.dispatcher.apply(ExecutionTest.envelope("b","ib","describe").put("descriptor",ExecutionTest.descriptor("shared")),2,()->true).getString("sessionId");
        try { legacyWaitAndExpiry();resumeLimits();invalidQuestions();boundedPages();largeModelDecisions();cancelWinsLateFinal();messagesAndLateFinal();obsoleteTools();questions();queuedAndRace();conversation();finalAcceptanceRace();admissionAndBudgets();switchesAndLimits(); }
        finally {ExecutionTest.coordinator.close();}
        System.out.println("PASS: durable ordered messages, cross-worker continuation, stale finals/tools, structured answers, restart, concurrent acceptance and terminal replay");
    }
}
