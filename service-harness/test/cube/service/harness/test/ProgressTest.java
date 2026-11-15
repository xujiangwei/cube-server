package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/** SQLite/restart tests for bounded progression, paired context retirement and original evidence reads. */
public final class ProgressTest {
    static JSONObject success(String text) {
        return new JSONObject().put("status", "success").put("artifacts", new JSONArray()).put("dataJson", text);
    }
    static void step(JdbcRunStore.Lease lease, int n, String tool, JSONObject args, JSONObject result) throws Exception {
        JSONObject call = call("p" + n, args.toString()).put("tool", tool);
        intent(lease, "round", "round-" + n, new JSONObject().put("request", new JSONObject()));
        confirm(lease, "round", "round-" + n, new JSONObject().put("kind", "tool_calls").put("calls", new JSONArray().put(call)));
        JSONObject job = new ModelJobStore(store,config.mysql()).enqueue(lease,row(lease.runId,"round","round-"+n),catalog.pinned(T,S,lease.runId).getJSONObject("model"));
        try(java.sql.Connection c=config.connect(); java.sql.PreparedStatement p=c.prepareStatement("UPDATE harness_model_job SET actual_finished=1,status='finished',outcome_json=? WHERE job_id=?")) {
            p.setString(1,new JSONObject().put("status","success").put("audit",new JSONArray()).put("cancellationObserved",false)
                    .put("decision",new JSONObject().put("kind","tool_calls").put("calls",new JSONArray().put(call))).toString());
            p.setString(2,job.getString("job_id"));p.executeUpdate();
        }
        intent(lease, "call", "p" + n, call);
        if (result != null) confirm(lease, "call", "p" + n, result);
    }
    static JSONObject args(int page) { return new JSONObject().put("release", release).put("path", "guide.txt").put("offset", page).put("limit", 10); }
    static JSONObject state(String run) throws Exception { return new ContextEngine(catalog, resources).assemble(identity, run, new JSONArray()); }
    static void checkLoop(String tool, boolean expected, boolean error, boolean changed) throws Exception {
        String run = run("progress", "Resolve dependencies and report confirmed facts");
        JdbcRunStore.Lease lease = running(run, 60000);
        for (int i = 0; i < 3; i++) step(lease, i, tool, args(changed ? i : 0), error
                ? new JSONObject().put("status", "error").put("artifacts", new JSONArray()).put("errorCode", "SCHEMA_INVALID") : success("same"));
        check(state(run).getJSONObject("taskState").has("blocked") == expected, "wrong repeated-call policy: " + tool);
        if (expected) {
            store.release(lease, version(run));
            try (RunCoordinator coordinator = new RunCoordinator(store, new ModelJobStore(store, config.mysql()), catalog, identity, resources)) {
                coordinator.step(run);
                check(store.read(T,S,run).toString().contains("TASK_NO_PROGRESS"), "restart did not stop repeated calls");
                check(store.intents(T,S,run,"round").length() == 3, "no-progress dispatched a fourth round");
            }
        }
    }
    static void history() throws Exception {
        String run = run("progress", "Read evidence then calculate dependent answer");
        JdbcRunStore.Lease lease = running(run,60000);
        JSONObject original = success(new JSONObject().put("answer", "中文😀观测").put("nextCursor", "opaque-complete-cursor")
                .put("coverage", new JSONObject().put("complete",false)).put("references",new JSONArray().put("exact:reference"))
                .put("text", new String(new char[3000]).replace('\0','x')).toString());
        step(lease,0,"resource.read",args(0),original);
        step(lease,1,"resource.read",args(1),success("second"));
        step(lease,2,"common.calculator.evaluate",new JSONObject().put("expression","2+3"),success("5"));
        JSONObject snapshot = state(run);
        check(snapshot.getJSONObject("historySelection").getJSONArray("archived").length()==2,"older groups not archived");
        check(snapshot.getJSONObject("historySelection").getJSONArray("observations").getJSONObject(0).getJSONObject("data")
                .getString("nextCursor").equals("opaque-complete-cursor"),"confirmed cursor summary lost");
        JSONArray messages = snapshot.getJSONObject("request").getJSONArray("messages");
        Set<String> proposals = new HashSet<>();
        for (int i=0;i<messages.length();i++) {
            JSONObject m=messages.getJSONObject(i), c=m.optJSONObject("content");
            if (m.getString("provenance").equals("tool-evidence-projection")) check(m.getString("role").equals("user"),"untrusted evidence summary promoted to policy");
            if (m.getString("role").equals("assistant") && c!=null && c.optString("kind").equals("tool_calls"))
                proposals.add(c.getJSONArray("calls").getJSONObject(0).getString("proposalId"));
            if (m.getString("role").equals("tool")) check(proposals.remove(m.getString("proposalId")),"orphan tool result");
        }
        check(proposals.isEmpty(),"missing tool result");
        check(snapshot.getJSONArray("facts").length()==6,"ledger facts were removed");
        check(!snapshot.getJSONObject("taskState").has("blocked"),"dependent calculation blocked");
        StringBuilder recovered = new StringBuilder(); int offset=0;
        do {
            JSONObject page=(JSONObject)resources.invoke(identity,run,"context.read_result",new JSONObject().put("intent","p0/1").put("offset",offset).put("limit",7));
            recovered.append(page.getString("text"));
            check(page.getString("resultHash").equals(JdbcRunStore.hash(original)),"readback hash changed");
            offset=page.isNull("nextOffset")?-1:page.getInt("nextOffset");
        } while(offset>=0);
        check(recovered.toString().equals(JdbcRunStore.canonical(original)),"Unicode/original fields lost on readback");
        JSONObject readArgs=new JSONObject().put("intent","p0/1").put("offset",0).put("limit",8);
        fail("RESOURCE_FORBIDDEN",()->resources.invoke(new ProfileRegistry.Identity(T,S,Collections.emptySet()),run,"context.read_result",readArgs));
        String other=run("progress","other run");
        fail("CONTEXT_RESULT_NOT_FOUND",()->resources.invoke(identity,other,"context.read_result",readArgs));
        fail("RUN_NOT_FOUND",()->resources.invoke(new ProfileRegistry.Identity("other",S,identity.permissions),run,"context.read_result",readArgs));
        JSONObject saved=contexts.checkpoint(lease,version(run),snapshot);
        check(JdbcRunStore.hash(contexts.restore(identity,run,saved.getString("digest"))).equals(JdbcRunStore.hash(snapshot)),"checkpoint changed");
        check(JdbcRunStore.hash(state(run).getJSONObject("request")).equals(snapshot.getString("requestDigest")),"rebuild changed model input");
        step(lease,3,"resource.read",args(3),null);
        check(!state(run).getBoolean("ready") && !state(run).getJSONObject("taskState").has("blocked"),"pending execution retired/stopped");
        fail("CONTEXT_RESULT_PENDING",()->resources.invoke(identity,run,"context.read_result",new JSONObject(readArgs.toString()).put("intent","p3/1")));
    }
    static void readbackContext() throws Exception {
        String run=run("progress","Recover original evidence without skipping any page text");
        JdbcRunStore.Lease lease=running(run,60000);
        JSONObject original=success(new String(new char[5000]).replace("\0","证😀"));
        step(lease,0,"resource.read",args(0),original);
        StringBuilder recovered=new StringBuilder(); int offset=0, n=1;
        do {
            JSONObject args=new JSONObject().put("intent","p0/1").put("offset",offset).put("limit",8192);
            JSONObject page=(JSONObject)resources.invoke(identity,run,"context.read_result",args);
            step(lease,n,"context.read_result",args,success(page.toString()));
            JSONObject snapshot=state(run), visible=null;
            JSONArray messages=snapshot.getJSONObject("request").getJSONArray("messages");
            for(int i=0;i<messages.length();i++) {
                JSONObject message=messages.getJSONObject(i);
                if(message.optString("role").equals("tool") && message.optString("proposalId").equals("p"+n))
                    visible=new JSONObject(message.getJSONObject("content").getString("dataJson"));
            }
            check(visible!=null && JdbcRunStore.canonical(visible).equals(JdbcRunStore.canonical(page)),
                    "context compacted a bounded readback page while retaining its original nextOffset");
            recovered.append(visible.getString("text"));
            offset=visible.isNull("nextOffset")?-1:visible.getInt("nextOffset"); n++;
        } while(offset>=0);
        check(recovered.toString().equals(JdbcRunStore.canonical(original)),"model-visible readback skipped original evidence");
        JSONObject task=task("readback-small",release,"large",128);
        task.getJSONObject("contextPolicy").put("historyWindow",1).put("maxInputBytes",16384);
        profiles.publish(T,task);
        String small=run("readback-small","Read a bounded page within the configured budget");
        JdbcRunStore.Lease smallLease=running(small,60000);
        step(smallLease,0,"resource.read",args(0),original);
        state(small); // The original compactable evidence fits before requesting a full page.
        JSONObject args=new JSONObject().put("intent","p0/1").put("offset",0).put("limit",8192);
        JSONObject page=(JSONObject)resources.invoke(identity,small,"context.read_result",args);
        step(smallLease,1,"context.read_result",args,success(page.toString()));
        fail("CONTEXT_BUDGET_EXCEEDED:context-v1:full-json-bytes-v1",()->state(small));
    }
    static void resumedAndChangingEvidence() throws Exception {
        String run=run("progress","Read"); JdbcRunStore.Lease lease=running(run,60000);
        for(int i=0;i<3;i++) step(lease,i,"resource.read",args(0),success("same"));
        JSONObject basis=catalog.contextBasis(T,S,run), snapshot=state(run);
        basis.getJSONArray("events").put(new JSONObject().put("event_type","control.resume").put("event_seq",100000)
                .put("data_json",new JSONObject().put("payload",new JSONObject().put("input",new JSONObject().put("instruction","new facts"))).toString()));
        check(!TaskState.derive(basis,snapshot.getJSONArray("facts"),new JSONArray(),new JSONArray()).has("blocked"),"new user facts did not reset loop");
        step(lease,3,"resource.read",args(0),success("changed-evidence"));
        check(!state(run).getJSONObject("taskState").has("blocked"),"changed evidence blocked");
    }
    static void withoutReadback() throws Exception {
        JSONObject task=task("no-readback",release,"large",128);
        task.getJSONObject("contextPolicy").put("historyWindow",1);
        JSONArray permissions=new JSONArray();
        for(String permission:TEST_PERMISSIONS) if(!permission.equals("resource.read")) permissions.put(permission);
        task.put("permissions",permissions);profiles.publish(T,task);
        String run=run("no-readback","Keep original complete pairs");JdbcRunStore.Lease lease=running(run,60000);
        step(lease,0,"common.time.query",new JSONObject(),success("first"));
        step(lease,1,"common.time.query",new JSONObject(),success("second"));
        JSONObject context=state(run);
        check(context.getJSONObject("historySelection").getJSONArray("archived").length()==0,"retired evidence without authorized readback");
    }
    static void relevantSkills() throws Exception {
        Map<String,byte[]> entries=entries("Second skill with immutable evidence");
        String other=upload(zip(manifest(entries).put("name","test/other"),entries)).getString("digest");
        catalog.lifecycle(T,other,1,"ready");
        JSONObject task=task("skills",release,"large",128).put("skillReleases",new JSONArray().put(release).put(other));
        task.getJSONObject("contextPolicy").put("skillWindow",1);profiles.publish(T,task);
        String run=run("skills","Read the requested skill");JdbcRunStore.Lease lease=running(run,60000);
        resources.activate(identity,run,release);resources.activate(identity,run,other);
        step(lease,0,"resource.read",args(0).put("release",other),success("second skill evidence"));
        JSONArray messages=state(run).getJSONObject("request").getJSONArray("messages");int count=0;
        for(int i=0;i<messages.length();i++) if(messages.getJSONObject(i).getString("provenance").equals("skill-resource")) {
            count++;check(messages.getJSONObject(i).getJSONObject("content").getString("release").equals(other),"recent skill not selected");
        }
        check(count==1,"skillWindow did not bound automatic pages");
        JSONArray handles=new JSONArray().put(new JSONObject().put("release",release).put("path","SKILL.md").put("offset",0).put("limit",8192));
        messages=contexts.assemble(identity,run,handles).getJSONObject("request").getJSONArray("messages");count=0;
        for(int i=0;i<messages.length();i++) if(messages.getJSONObject(i).getString("provenance").equals("skill-resource")) count++;
        check(count==2,"explicit source was retired by skill selection");
    }
    static void budgets() throws Exception {
        JSONObject model=ModelProfiles.model("generic-json",false,20000);
        RuntimeSupport.validate("model-profile",model);
        check(model.getString("adapter").equals("openai-json-v2"),"generic JSON adapter");
        invalid(()->ModelProfiles.model("implicit-unknown"));
        JSONObject pin=new JSONObject().put("model",model).put("execution",new JSONObject().put("contextPolicy",new JSONObject()
                .put("maxInputBytes",500).put("finalWireGuard",true)));
        JSONObject wire=new JSONObject().put("max_tokens",10).put("messages",new JSONArray());
        new ModelBudgets().admitWire(pin,wire);
        fail("INFERENCE_CONTEXT_BUDGET_EXCEEDED",()->new ModelBudgets().admitWire(pin,new JSONObject(wire.toString()).put("text",new String(new char[600]))));
        model.getJSONObject("tokenizer").put("id","fixture-tokenizer");
        fail("MODEL_COUNTER_UNAVAILABLE",()->new ModelBudgets().admitWire(pin,wire));
        ModelBudgets.Counter counter=new ModelBudgets.Counter(){
            public boolean supports(JSONObject m){return m.getJSONObject("tokenizer").getString("id").equals("fixture-tokenizer");}
            public long countContext(JSONObject m,JSONObject r){return 13;}
            public long countWire(JSONObject m,JSONObject r){return r.optBoolean("overflow")?20000:13;}
        };
        ModelBudgets exact=new ModelBudgets(counter);
        check(exact.context(model,new JSONObject(),400)==13,"exact context counter ignored");
        exact.admitWire(pin,wire);
        fail("INFERENCE_CONTEXT_BUDGET_EXCEEDED",()->exact.admitWire(pin,new JSONObject(wire.toString()).put("overflow",true)));
        fail("MODEL_COUNTER_AMBIGUOUS",()->new ModelBudgets(counter,counter).require(model));
    }
    public static void main(String[] ignored) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-progress-"),"");
        JSONObject large=RuntimeSupport.copy(model).put("id","large").put("maxContextTokens",200000);
        profiles.publish(T,large);
        JSONObject task=task("progress",release,"large",128);
        task.getJSONObject("contextPolicy").put("taskState",true).put("historyWindow",1);
        task.getJSONObject("executionLimits").put("maxRepeatedCalls",3);
        profiles.publish(T,task);
        try {
            checkLoop("resource.read",true,false,false);
            checkLoop("anything.read",true,true,false);
            checkLoop("resource.read",false,false,true);
            checkLoop("common.time.query",false,false,false);
            checkLoop("common.data.query",false,false,false);
            history(); readbackContext(); resumedAndChangingEvidence(); withoutReadback(); relevantSkills(); budgets();
            System.out.println("PASS: persisted no-progress stop, refresh/poll/dependency exceptions, paired history, Unicode original-result readback, scope/ACL, pending, deterministic restore and exact/estimated budget gates");
        } finally { /* Each fixture uses its own disposable database and short JDBC transactions. */ }
    }
}
