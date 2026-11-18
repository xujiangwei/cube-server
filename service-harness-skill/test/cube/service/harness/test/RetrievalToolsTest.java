package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;
import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import com.sun.net.httpserver.*;
import org.json.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Real SQLite, signed HTTP, durable host clarification and two independent Runner identities. */
public final class RetrievalToolsTest {
    static final String SKILL="my-business-system/resource-retrieval",SOURCE="source-my-business-system@1";
    static ToolBroker broker;
    static ProviderStore metadata;
    static ControlStore controls;
    static RunCoordinator coordinator;
    static Properties settings;
    static String business,tool;
    static int dispatches,requests;
    static boolean correctPage,changeSnapshot,incompleteDirectory;

    static void restart() throws Exception {
        if(coordinator!=null)coordinator.close();
        if(broker!=null)broker.close();
        broker=new ToolBroker(new ToolJobStore(store,false),identity,settings).files(CommonToolsTest.files).context(resources,metadata,()->null);
        coordinator=new RunCoordinator(store,new ModelJobStore(store,false),catalog,identity,resources,broker).controls(controls,null,settings);
        for(String worker:Arrays.asList("retrieval-a","retrieval-b"))broker.jobs.identity(worker,"runtime-"+worker,1);
    }
    static String create(String text) throws Exception {
        String run=profiles.createRun(identity,key(),"retrieval",request(text)).getString("runId");
        resources.activate(identity,run,business);resources.activate(identity,run,tool);return run;
    }
    static JSONObject state(String run){return store.read(T,S,run);}
    static JSONObject append(String run,String text){return controls.messages.append(T,S,run,key(),new JSONObject().put("kind","instruction").put("input",request(text).getJSONObject("input")));}
    static JSONObject tick(String run) throws Exception {
        coordinator.step(run);
        JSONObject data=dispatch(run);
        if(data!=null)check(!data.has("errorCode"),"retrieval provider: "+data);
        return data;
    }
    static JSONObject dispatch(String run) throws Exception {
        String worker=dispatches%2==0?"retrieval-a":"retrieval-b";
        JSONObject wire=broker.jobs.poll(worker,"runtime-"+worker,"instance-"+worker,true,"tool",broker::wire);
        if(wire==null)return null;
        dispatches++;broker.jobs.accepted(wire.getString("jobId"),wire.getString("jobHash"));
        JSONObject job=broker.jobs.job(wire.getString("jobId")),args=new JSONObject(job.getString("arguments_json"));
        JSONObject exchange=broker.invokeArguments(job,wire.getJSONObject("grant"),args);
        long deadline=System.currentTimeMillis()+8000;
        while(exchange.getString("status").equals("pending")&&System.currentTimeMillis()<deadline){Thread.sleep(10);exchange=broker.invokeArguments(job,wire.getJSONObject("grant"),args);}
        check(exchange.getString("status").equals("finished"),"retrieval exchange timeout");
        JSONObject data=new JSONObject(exchange.getString("dataJson"));
        if(correctPage&&data.getString("kind").equals("page")){correctPage=false;append(run,"改成文档，其他不变");}
        JSONObject normalized=data.has("errorCode")?data:new JSONObject().put("status","success").put("dataJson",data.toString()).put("artifacts",new JSONArray());
        JSONObject report=new JSONObject().put("jobId",wire.getString("jobId")).put("jobHash",wire.getString("jobHash"))
                .put("actualFinished",true).put("effectStatus",data.has("errorCode")?"none":"completed").put("resultJson",normalized.toString());
        JSONObject saved=broker.report(job,report);check(broker.report(job,report).getString("report_hash").equals(saved.getString("report_hash")),"retrieval report replay");
        return data;
    }
    static JSONObject manual(JdbcRunStore.Lease lease,JSONObject args) throws Exception {
        String id=key();intent(lease,"call",id,new JSONObject().put("proposalId",id).put("tool","common.retrieval.query").put("argumentsJson",args.toString()));
        JSONObject job=broker.enqueue(lease,row(lease.runId,"call",id),resources.executable(identity,lease.runId,"common.retrieval.query"));
        JSONObject data=dispatch(lease.runId);check(data!=null,"manual retrieval dispatch missing");
        confirm(lease,"call",id,new JSONObject(broker.jobs.job(job.getString("job_id")).getString("result_json")));return data;
    }
    static JSONObject args(String action){return new JSONObject().put("skill",SKILL).put("action",action);}
    static JSONObject finish(String run) throws Exception {
        for(int i=0;i<60;i++) {
            tick(run);JSONObject value=state(run);String status=value.getJSONObject("snapshot").getString("state");
            if(status.equals("succeeded")){check(store.intents(T,S,run,"round").length()==0,"host retrieval unexpectedly used a model");return value.getJSONObject("result");}
            check(!Arrays.asList("failed","waiting_input","timed_out").contains(status),"unexpected retrieval state: "+value);
        }
        throw new AssertionError("retrieval made no progress: "+state(run));
    }
    static JSONObject delivery(JSONObject result){return result.getJSONObject("result").getJSONObject("retrieval").getJSONArray("results").getJSONObject(0);}
    static void upstream(HttpExchange x) throws java.io.IOException {
        StringBuilder canonical=new StringBuilder("GET\n").append(x.getRequestURI().toASCIIString());
        for(String name:Arrays.asList("Source","Tenant","Subject","Issued-At","Nonce"))canonical.append('\n').append(x.getRequestHeaders().getFirst("X-Harness-"+name));
        if(!MediaToolsTest.signature(canonical.toString()).equals(x.getRequestHeaders().getFirst("X-Harness-Signature"))||!S.equals(x.getRequestHeaders().getFirst("X-Harness-Subject"))){MediaToolsTest.send(x,403,"application/json",bytes("{}"));return;}
        String path=x.getRequestURI().getPath();
        if(path.endsWith("regions")) {
            JSONArray regions=new JSONArray().put(new JSONObject().put("id","plant-1").put("name","厂区1").put("aliases",new JSONArray().put("一号厂区")).put("parentId",""))
                    .put(new JSONObject().put("id","plant-1-workshop").put("name","厂区1车间").put("aliases",new JSONArray()).put("parentId","plant-1"));
            MediaToolsTest.send(x,200,"application/json",bytes(new JSONObject().put("data",new JSONObject().put("complete",!incompleteDirectory).put("items",regions)).toString()));return;
        }
        requests++;String query=x.getRequestURI().getRawQuery();boolean second=query.contains("cursor=");
        JSONArray items=new JSONArray();String type=path.endsWith("video")?"video":path.endsWith("image")?"image":"document";
        if(second)items.put(new JSONObject().put("id",type+"-1").put("version","v1").put("title","业务资源")
                .put("mediaType",type.equals("video")?"video/mp4":type.equals("image")?"image/jpeg":"application/pdf")
                .put("regionId","plant-1-workshop").put("summary","签名接口元数据").put("status","published")
                .put("createdAt","2020-01-02T15:15:00+08:00").put("startedAt","2020-01-02T14:50:00+08:00").put("endedAt","2020-01-02T15:30:00+08:00"));
        JSONObject body=new JSONObject().put("data",new JSONObject().put("items",items).put("nextCursor",second?"":"page-2").put("snapshotId",second&&changeSnapshot?"changed":"stable-1"));
        MediaToolsTest.send(x,200,"application/json",bytes(body.toString()));
    }
    public static void main(String[] args) throws Exception {
        Path packages=Paths.get(args[0]),source=Paths.get(args[1]);
        TEST_PERMISSIONS.add("resource.retrieve");
        CommonToolsTest.prepare(Files.createTempDirectory(packages.getParent(),"retrieval-test-"),packages,"http://127.0.0.1:1/search");
        settings=CommonToolsTest.settings;settings.setProperty("interaction.enabled","true");
        Properties pc=new Properties();pc.setProperty("secret.fixture",MediaToolsTest.SECRET);pc.setProperty("result.max.bytes","12288");
        Path provider=root.resolve("retrieval.properties"),guard=root.resolve("workflow.properties");
        Files.write(provider,bytes(ExecutionTest.properties(pc)));Files.write(guard,bytes("workflow.max.repairs=1\n"));
        settings.setProperty("broker.providers",settings.getProperty("broker.providers")+",retrieval");
        settings.setProperty("broker.provider.retrieval.class","cube.service.harness.skill.RetrievalProvider");
        settings.setProperty("broker.provider.retrieval.config",provider.toString());
        settings.setProperty("broker.completion.guard.class","cube.service.harness.skill.WorkflowGuard");
        settings.setProperty("broker.completion.guard.config",guard.toString());
        tool=upload(Files.readAllBytes(packages.resolve("retrieval-v1.zip"))).getString("digest");catalog.lifecycle(T,tool,1,"ready");
        Map<String,byte[]> entries=new TreeMap<>();for(String name:Arrays.asList("SKILL.md","retrieval-profiles.json","workflow-requirements.json"))entries.put(name,Files.readAllBytes(source.resolve(name)));
        JSONObject retrieval=new JSONObject(new String(entries.get("retrieval-profiles.json"),"UTF-8"));
        retrieval.put("regions",new JSONObject().put("kind","http").put("sourceRef",SOURCE)
                .put("request",new JSONObject().put("method","GET").put("path","/api/resources/regions").put("query",new JSONObject()))
                .put("response",new JSONObject().put("items","/data/items").put("complete","/data/complete").put("fields",new JSONObject().put("id","/id").put("name","/name").put("aliases","/aliases").put("parentId","/parentId"))));
        entries.put("retrieval-profiles.json",bytes(retrieval.toString()));
        entries.put("dependencies.lock.json",bytes(new JSONObject().put("schemaVersion",1).put("dependencies",new JSONArray().put(new JSONObject().put("name","common/retrieval").put("sha256",tool))).toString()));
        byte[] zip=zip(manifest(entries).put("name",SKILL).put("runtime",ref("trusted-cpu")),entries);
        business=upload(zip).getString("digest");catalog.lifecycle(T,business,1,"ready");
        JSONObject task=task("1",business,"scripted",8192).put("id","retrieval").put("runtime",ref("trusted-cpu"))
                .put("skillReleases",new JSONArray().put(tool).put(business)).put("toolSetVersion","broker-tools")
                .put("requiredCapabilities",new JSONArray().put("resources").put("tool.execute"));
        profiles.publish(T,task);profiles.bind(T,"task","retrieval","retrieval@1",0);
        metadata=new ProviderStore(store,false);controls=new ControlStore(store,false).configure(settings);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/resources/",RetrievalToolsTest::upstream);server.start();
        metadata.putSource(T,"source-my-business-system","1",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort())
                .put("secretRef","fixture").put("identityMapping","cube-subject-hmac-v1").put("allowedPaths",new JSONArray().put("/api/resources/video").put("/api/resources/image").put("/api/resources/document").put("/api/resources/regions")));
        metadata.grant(T,S,SOURCE,true);
        try {
            restart();broker.validatePackage(repo.validate(new java.io.ByteArrayInputStream(zip)));
            String run=create("调出2020-01-02下午3点到4点的厂区1内的巡检录像");
            JSONObject first=delivery(finish(run));check(first.getJSONArray("resources").getJSONObject(0).getString("resourceType").equals("video"),"video result");
            check(first.getJSONObject("coverage").getInt("pages")==2&&requests==2,"empty first page must continue");
            pass("signed retrieval plan/search/final without model rounds, empty-page continuation and two Runner identities");

            String waiting=create("查询2020-01-02下午3点到4点的图片");
            for(int i=0;i<12&&controls.current(T,S,waiting)==null;i++)tick(waiting);
            JSONObject wait=controls.current(T,S,waiting);check(wait!=null,"durable retrieval clarification missing");
            restart();controls.messages.append(T,S,waiting,key(),new JSONObject().put("kind","answer").put("waitId",wait.getString("waitId"))
                    .put("interactionVersion",wait.getLong("interactionVersion")).put("input",request("").getJSONObject("input"))
                    .put("answers",new JSONArray().put(new JSONObject().put("questionId","retrieval.region").put("optionId","plant-1"))));
            check(delivery(finish(waiting)).getJSONObject("task").getJSONObject("region").getString("id").equals("plant-1"),"selected region lost after restart");
            check(controls.messages.list(T,S,waiting,0,10).getJSONArray("messages").getJSONObject(0).getString("status").equals("applied"),"host input watermark");
            pass("persisted question/answer and trusted host input adoption across Broker restart");

            String scope=create("查询2020-01-02下午3点到4点厂区1的4K录像");
            for(int i=0;i<12&&controls.current(T,S,scope)==null;i++)tick(scope);
            JSONObject question=controls.current(T,S,scope);check(question!=null,"unknown predicate silently ignored");
            controls.messages.append(T,S,scope,key(),new JSONObject().put("kind","answer").put("waitId",question.getString("waitId"))
                    .put("interactionVersion",question.getLong("interactionVersion")).put("input",request("按以下回答继续").getJSONObject("input"))
                    .put("answers",new JSONArray().put(new JSONObject().put("questionId","retrieval.scope").put("text","查询2020-01-02下午3点到4点厂区1的图片"))));
            check(delivery(finish(scope)).getJSONObject("task").getString("resourceType").equals("image"),"scope restatement not adopted");
            pass("unknown conditions require a user-authored complete scope restatement before search");

            String corrected=create("查询2020-01-02下午3点到4点厂区1的录像");correctPage=true;
            JSONObject latest=delivery(finish(corrected));check(latest.getJSONArray("resources").getJSONObject(0).getString("resourceType").equals("document"),"stale video delivery after correction");
            check(latest.getJSONObject("task").getLong("inputRevision")==1,"latest input revision not adopted");
            pass("in-flight old result cannot satisfy corrected resource type");

            String revoked=create("查询2020-01-02下午3点到4点厂区1的录像");
            for(int i=0;i<10;i++){JSONObject data=tick(revoked);if(data!=null&&data.getString("kind").equals("plan"))break;}
            int before=requests;metadata.grant(T,S,SOURCE,false);
            for(int i=0;i<8&&!state(revoked).getJSONObject("snapshot").getString("state").equals("failed");i++)coordinator.step(revoked);
            check(state(revoked).getJSONObject("snapshot").getString("state").equals("failed")&&before==requests,"revoked source executed a search");
            metadata.grant(T,S,SOURCE,true);pass("source revocation rechecked before search and final delivery");

            String isolated=create("查询2020-01-02下午3点到4点厂区1的录像");JdbcRunStore.Lease lease=running(isolated,60000);
            JSONObject original=manual(lease,args("plan")),page=manual(lease,args("search").put("planRef",original.getString("planRef")));
            append(isolated,"改成图片，其他不变");catalog.adoptWorkflowInput(lease,version(isolated),1);
            check(manual(lease,args("search").put("planRef",original.getString("planRef"))).getString("errorCode").equals("RETRIEVAL_PLAN_STALE"),"old plan not rejected");
            JSONObject updated=manual(lease,args("plan"));
            check(manual(lease,args("search").put("planRef",updated.getString("planRef")).put("cursor",page.getString("nextCursor"))).getString("errorCode").equals("RETRIEVAL_CURSOR_CONFLICT"),"cursor escaped its plan");
            String foreign=create("查询2020-01-02下午3点到4点厂区1的录像");JdbcRunStore.Lease other=running(foreign,60000);
            check(manual(other,args("search").put("planRef",updated.getString("planRef"))).has("errorCode"),"cross-run plan accepted");
            store.release(lease,version(isolated));store.release(other,version(foreign));
            pass("stale plans, foreign-run references and cursors from another plan rejected through real Broker");
            String directory=create("查询2020-01-02下午3点到4点厂区1的录像");JdbcRunStore.Lease dl=running(directory,60000);incompleteDirectory=true;
            check(manual(dl,args("plan")).getString("errorCode").equals("RETRIEVAL_REGION_DIRECTORY_INCOMPLETE"),"partial authorized region directory used for planning");
            incompleteDirectory=false;store.release(dl,version(directory));pass("authenticated dynamic region directory is complete or planning fails closed");
        } finally {if(coordinator!=null)coordinator.close();if(broker!=null)broker.close();server.stop(0);CommonToolsTest.files.close();}
        System.out.println("PASS retrieval integration: "+dispatches+" durable tool dispatches.");
    }
}
