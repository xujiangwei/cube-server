package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import static cube.service.harness.test.RuntimeTest.*;

/** Real JDBC and HTTP boundaries, including commit uncertainty and historical evidence. */
public final class ToolTest {
    static ToolJobStore tools; static ToolBroker broker; static Properties p;
    static final String TOOL="test.status.query";
    static JSONObject args(){return new JSONObject().put("targets",new JSONArray().put("node-a")).put("fields",new JSONArray().put("cpuPercent"));}
    static void prepare(Path dir,String file)throws Exception {
        ExecutionTest.prepare(dir,file);
        runtime=new JSONObject().put("schemaVersion",2).put("id","cpu").put("version","1").put("kind","runtime").put("mode","trusted-python-v1")
            .put("capabilities",new JSONArray().put("resources").put("tool.execute")).put("maxWorkspaceBytes",4096).put("maxWorkspaceFiles",8).put("certification","offline-trusted-runner");
        profiles.publish(T,runtime);
        repo=new PackageRepository(catalog,new AssetFiles(root.resolve("repository"),8000000,"runtime-test-repo"),Collections.emptyMap(),true,true);
        resources=new ResourceTools(catalog,repo);
        Map<String,byte[]> e=entries("Activate this release, then call test.status.query for the requested nodes/fields. Only MockConsole is supported.");
        e.put("input.schema.json",RuntimeSupport.bytes(ToolBroker.parameters()));e.put("output.schema.json",RuntimeSupport.bytes(ToolBroker.outputSchema()));
        e.put("main.py",bytes("from runner_sdk import context,query_status,result\nimport time\ntime.sleep(0.3)\nresult(query_status(**context()['arguments']))\n"));
        JSONObject m=manifest(e).put("schemaVersion",6).put("name","test/status").put("runtime",ref("cpu"));
        for(int i=0;i<m.getJSONArray("files").length();i++){JSONObject f=m.getJSONArray("files").getJSONObject(i);if(f.getString("path").equals("main.py"))f.put("role","entrypoint");}
        m.getJSONArray("tools").put(new JSONObject().put("name",TOOL).put("kind","executable").put("entrypoint","main.py").put("inputSchema","input.schema.json").put("outputSchema","output.schema.json")
            .put("permission","console.status.read").put("effect","read-only").put("brokerOperation","mockconsole.query_status").put("runnerType", "tool"));
        release=upload(zip(m,e)).getString("digest");catalog.lifecycle(T,release,1,"ready");
        JSONObject t=task("1",release,"scripted",4096).put("id","tools").put("schemaVersion",2).put("runtime",ref("cpu")).put("toolSetVersion","broker-tools")
            .put("requiredCapabilities",new JSONArray().put("resources").put("tool.execute")).put("executionLimits",new JSONObject().put("maxRounds",8).put("maxTools",16).put("maxRepairs",1).put("maxLifetimeMillis",60000));
        t.getJSONObject("contextPolicy").put("maxInputBytes",48000);profiles.publish(T,t);catalog.bind(T,"task","tools","tools@1",0);
        Files.write(root.resolve("tool-fixture.json"),RuntimeSupport.bytes(new JSONObject().put("release",release).put("tenant",T).put("subject",S)));
    }
    static final class Case {String run,id;JdbcRunStore.Lease lease;JSONObject intent,job,wire;}
    static Case make()throws Exception {
        Case c=new Case();c.run=profiles.createRun(identity,key(),"tools",request("status").put("taskProfileId","tools")).getString("runId");c.lease=running(c.run,60000);
        resources.activate(identity,c.run,release);c.id="call";
        intent(c.lease,"call",c.id,new JSONObject().put("proposalId",c.id).put("tool",TOOL).put("argumentsJson",JdbcRunStore.canonical(args())));
        c.intent=row(c.run,"call",c.id);c.job=broker.enqueue(c.lease,c.intent,resources.executable(identity,c.run,TOOL));return c;
    }
    static void assign(Case c,String worker,String runtime){tools.identity(worker,runtime,1);c.wire=tools.poll(worker,runtime,"instance",true,broker::wire);check(c.wire.getString("jobId").equals(c.job.getString("job_id")),"wrong claim");c.job=tools.job(c.wire.getString("jobId"));}
    static JSONObject report(Case c,boolean ended,String effect,String value){return new JSONObject().put("jobId",c.wire.getString("jobId")).put("jobHash",c.wire.getString("jobHash")).put("actualFinished",ended).put("effectStatus",effect).put("resultJson",value);}
    static void accepted(Case c){broker.authorize(c.job,c.wire.getJSONObject("grant"));tools.accepted(c.wire.getString("jobId"),c.wire.getString("jobHash"));c.job=tools.job(c.wire.getString("jobId"));}
    static JSONObject query(Case c)throws Exception {
        long end=System.currentTimeMillis()+5000;JSONObject out;
        do{out=broker.query(c.job,c.wire.getJSONObject("grant"),args().getJSONArray("targets"),args().getJSONArray("fields"));if(out.getString("status").equals("finished"))return new JSONObject(out.getString("dataJson"));Thread.sleep(20);}while(System.currentTimeMillis()<end);
        throw new AssertionError("Broker did not settle");
    }
    static void checks()throws Exception {
        AtomicInteger upstream=new AtomicInteger();HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/status",x->{try{check(x.getRequestHeaders().getFirst("Authorization").equals("Bearer "+p.getProperty("broker.mock.token")),"missing server credential");upstream.incrementAndGet();RuntimeSupport.read(x.getRequestBody(),4096);
            byte[] b=bytes("{\"nodes\":[{\"target\":\"node-a\",\"queriedAt\":1234,\"cpuPercent\":7,\"memoryMiB\":1024,\"cookie\":\"never-forward\"},{\"target\":\"other-node\",\"queriedAt\":1,\"cpuPercent\":99}],\"token\":\"never-forward\"}");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);}finally{x.close();}});http.start();
        p=new Properties();p.setProperty("runner.grant.key",key()+key());p.setProperty("broker.node.id","test-node");p.setProperty("broker.mock.enabled","true");p.setProperty("broker.mock.url","http://127.0.0.1:"+http.getAddress().getPort()+"/status");p.setProperty("broker.mock.token",key());p.setProperty("broker.mock.targets","node-a");
        tools=new ToolJobStore(store,config.mysql());broker=new ToolBroker(tools,identity,p);
        try {
            Case a=make();assign(a,"a","runtime-a");
            check(JdbcRunStore.hash(a.wire).equals(JdbcRunStore.hash(tools.poll("a","runtime-a","replacement",true,broker::wire))),"lost dispatch changed attempt");
            fail("RUNNER_IDENTITY_CONFLICT",()->tools.identity("a","replacement-journal",1));
            fail("TOOL_EVIDENCE_MISMATCH",()->tools.evidence("b","runtime-a",a.wire.getString("jobId"),a.wire.getString("jobHash")));
            JSONObject forged=RuntimeSupport.copy(a.wire.getJSONObject("grant")).put("signature",new String(new char[64]).replace('\0','0'));
            fail("GRANT_INVALID",()->broker.authorize(a.job,forged));
            ToolBroker foreign=new ToolBroker(tools,new ProfileRegistry.Identity("foreign",S,identity.permissions),copyNode("foreign-node"));try{fail("GRANT_REVOKED_OR_STALE",()->foreign.authorize(a.job,a.wire.getJSONObject("grant")));}finally{foreign.close();}
            accepted(a);accepted(a);
            fail("BROKER_ARGUMENT_CONFLICT",()->broker.query(a.job,a.wire.getJSONObject("grant"),new JSONArray().put("other-node"),args().getJSONArray("fields")));
            fail("BROKER_ARGUMENT_CONFLICT",()->broker.query(a.job,a.wire.getJSONObject("grant"),args().getJSONArray("targets"),new JSONArray().put("memoryMiB")));
            JSONObject data=query(a);check(data.toString().indexOf("never-forward")==-1&&!data.toString().contains("memoryMiB")&&!data.toString().contains("other-node"),"filter ran after child boundary");query(a);check(upstream.get()==1,"transport retry repeated upstream");
            JSONObject good=report(a,true,"completed",new JSONObject().put("status","success").put("dataJson",data.toString()).put("artifacts",new JSONArray()).toString());
            JSONObject saved=broker.report(a.job,good);check(saved.getString("report_hash").equals(JdbcRunStore.hash(good)),"ACK digest differs");broker.report(a.job,good);
            fail("TOOL_RESULT_CONFLICT",()->broker.report(a.job,RuntimeSupport.copy(good).put("resultJson",ToolJobStore.error("CHANGED").toString())));
            pass("durable dispatch/accepted/result replay, ACK hash, conflict, runtime identity, tenant/grant/target/field binding and pre-script filtering; upstream once");
            Case bad=make();assign(bad,"a","runtime-a");accepted(bad);JSONObject invalid=report(bad,true,"completed","{\"status\":\"success\",\"status\":\"error\",\"artifacts\":[]}");
            JSONObject rejected=broker.report(bad.job,invalid);check(new JSONObject(rejected.getString("result_json")).getString("errorCode").equals("TOOL_OUTPUT_INVALID"),"invalid raw output escaped");check(rejected.getString("report_hash").equals(JdbcRunStore.hash(invalid)),"bad output not ACKable");
            Case unknown=make();assign(unknown,"a","runtime-a");accepted(unknown);broker.report(unknown.job,report(unknown,false,"unknown",ToolJobStore.error("RUNNER_UNKNOWN").put("status","unknown").toString()));
            check(tools.occupied("a")==1&&tools.poll("a","runtime-a","new-instance",true,broker::wire)==null,"unknown freed capacity");
            tools.cancel(unknown.lease,unknown.job.getString("job_id"));check(tools.occupied("a")==1,"cancel released physical slot");
            JSONObject late=report(unknown,true,"none",ToolJobStore.error("TOOL_CANCELLED").toString());broker.report(unknown.job,late);check(tools.occupied("a")==0,"actual finish did not release slot");
            store.release(unknown.lease,version(unknown.run));JdbcRunStore.Lease next=store.acquire(T,S,unknown.run,"new-owner",version(unknown.run),60000);
            store.confirmIntent(next,version(unknown.run),"call",unknown.id,1,unknown.intent.getLong("origin_fence"),unknown.intent.getString("intent_hash"),new JSONObject(tools.job(unknown.job.getString("job_id")).getString("result_json")));
            pass("invalid output persisted and ACKed; unknown/cancel retains capacity; late historical result reconciles under a new fence");
            Case stale=make();assign(stale,"a","runtime-a");accepted(stale);store.release(stale.lease,version(stale.run));store.acquire(T,S,stale.run,"new-owner",version(stale.run),60000);
            fail("GRANT_REVOKED_OR_STALE",()->broker.query(stale.job,stale.wire.getJSONObject("grant"),args().getJSONArray("targets"),args().getJSONArray("fields")));
            broker.report(stale.job,report(stale,true,"none",ToolJobStore.error("TOOL_REVOKED").toString()));
            Case revoked=make();assign(revoked,"a","runtime-a");accepted(revoked);tools.revoke(T,S,revoked.run,"test-revocation");
            fail("GRANT_REVOKED_OR_STALE",()->broker.authorize(tools.job(revoked.job.getString("job_id")),revoked.wire.getJSONObject("grant")));
            check(tools.occupied("a")==1,"revocation released capacity");broker.report(revoked.job,report(revoked,true,"completed",good.getString("resultJson")));
            check(new JSONObject(tools.job(revoked.job.getString("job_id")).getString("result_json")).getString("errorCode").equals("TOOL_REVOKED"),"cancel/result race lost revocation");
            pass("old fence rejected before upstream; durable revoke and result race cannot restore authority");
            Case ttl=make();assign(ttl,"a","runtime-a");
            JSONObject grant=ttl.wire.getJSONObject("grant"),claims=new JSONObject(grant.getString("bodyJson")).put("expiresAt",0);
            String body=JdbcRunStore.canonical(claims);javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(bytes(p.getProperty("runner.grant.key")),"HmacSHA256"));StringBuilder signature=new StringBuilder();for(byte b:mac.doFinal(bytes(body)))signature.append(String.format("%02x",b&255));
            grant.put("bodyJson",body).put("signature",signature.toString());
            try(java.sql.Connection db=config.connect();java.sql.PreparedStatement sql=db.prepareStatement("UPDATE harness_tool_job SET wire_json=? WHERE job_id=?")){sql.setString(1,JdbcRunStore.canonical(ttl.wire));sql.setString(2,ttl.job.getString("job_id"));sql.executeUpdate();}
            ttl.job=tools.job(ttl.job.getString("job_id"));fail("GRANT_REVOKED_OR_STALE",()->broker.authorize(ttl.job,grant));
            broker.report(ttl.job,report(ttl,true,"none",ToolJobStore.error("TOOL_REVOKED").toString()));
            pass("correctly signed but expired grant rejected (test-only stored grant expiry fixture)");
            Case commit=make();assign(commit,"a","runtime-a");AtomicBoolean once=new AtomicBoolean(true);
            ToolJobStore faulty=new ToolJobStore(new JdbcRunStore(config,b->{if(b.equals("afterCommit")&&once.compareAndSet(true,false))throw new RuntimeException("lost DB commit response");}),config.mysql());
            fail("STORE_COMMIT_OUTCOME_UNKNOWN",()->faulty.accepted(commit.job.getString("job_id"),commit.wire.getString("jobHash")));
            check(tools.job(commit.job.getString("job_id")).getString("status").equals("accepted"),"commit evidence lost");accepted(commit);
            AtomicBoolean rollback=new AtomicBoolean(true);ToolJobStore failSave=new ToolJobStore(new JdbcRunStore(config,b->{if(b.equals("beforeCommit")&&rollback.compareAndSet(true,false))throw new RuntimeException("fail save");}),config.mysql());
            JSONObject evidence=report(commit,true,"none",ToolJobStore.error("TOOL_CANCELLED").toString());
            fail("STORE_UNAVAILABLE",()->failSave.report(commit.job,evidence,ToolJobStore.error("TOOL_CANCELLED")));check(tools.occupied("a")==1,"failed result commit ACKed");broker.report(commit.job,evidence);broker.report(commit.job,evidence);
            Case crash=make();assign(crash,"a","runtime-a");accepted(crash);tools.brokerExchange(crash.job.getString("job_id"),JdbcRunStore.hash(args()),"crashed-node",true);tools.brokerRestart("crashed-node");
            fail("BROKER_OUTCOME_UNKNOWN",()->broker.query(crash.job,crash.wire.getJSONObject("grant"),args().getJSONArray("targets"),args().getJSONArray("fields")));check(upstream.get()==1,"unknown request replayed HTTP");
            pass("after-commit loss replays durable accepted; before-commit result failure withholds ACK; Broker send-window restart stays unknown without HTTP replay");
            Case retry=make();assign(retry,"b","runtime-b");accepted(retry);
            AtomicInteger failures=new AtomicInteger(2);ToolJobStore delayed=new ToolJobStore(new JdbcRunStore(config,b->{if(b.equals("beforeCommit")&&Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getMethodName().equals("brokerFinish"))&&failures.getAndDecrement()>0)throw new RuntimeException("result DB unavailable");}),config.mysql());
            ToolBroker retryBroker=new ToolBroker(delayed,identity,copyNode("retry-node"));
            try {
                int initial=upstream.get();JSONObject response=retryBroker.query(retry.job,retry.wire.getJSONObject("grant"),args().getJSONArray("targets"),args().getJSONArray("fields"));check(response.getString("status").equals("pending"),"network executed in Cell request");
                long end=System.currentTimeMillis()+4000;while(System.currentTimeMillis()<end&&!tools.brokerExchange(retry.job.getString("job_id"),JdbcRunStore.hash(args()),"retry-node",false).getString("status").equals("finished"))Thread.sleep(30);
                check(tools.brokerExchange(retry.job.getString("job_id"),JdbcRunStore.hash(args()),"retry-node",false).getString("status").equals("finished"),"DB-only retry did not settle");check(upstream.get()==initial+1,"result persistence retry reissued HTTP");
            }finally{retryBroker.close();}
            pass("asynchronous Broker result DB failures reconcile from bounded memory without a second HTTP request");
            Case quarantine=make();tools.identity("q","runtime-q",2);quarantine.wire=tools.poll("q","runtime-q","iq",true,broker::wire);quarantine.job=tools.job(quarantine.wire.getString("jobId"));accepted(quarantine);
            broker.report(quarantine.job,report(quarantine,false,"unknown",ToolJobStore.error("RUNNER_UNKNOWN").put("status","unknown").toString()));
            make();check(tools.occupied("q")==1&&!tools.healthy("q")&&tools.poll("q","runtime-q","replacement",true,broker::wire)==null,"healthy claim bypassed durable quarantine with spare capacity");
            pass("durable unknown quarantines a capacity-two Runner even when its heartbeat claims healthy");
            for(String w:Arrays.asList("r1","r2")){p.setProperty("worker."+w+".role","runner");p.setProperty("worker."+w+".capacity","1");}
            RunnerDispatcher registry=new RunnerDispatcher(broker,p,150);JSONObject e=new JSONObject().put("runnerType", "tool").put("requestId",key()).put("credential",key()).put("workerId","r1").put("instanceId","i1").put("role","runner").put("capabilities",new JSONArray().put("tool.execute")).put("action","describe")
                .put("descriptor",new JSONObject().put("runtimeId","registry-runtime").put("capacity",1).put("runtime","trusted-python-v1").put("sourceDigest",ToolProtocol.runtime().getString("digest")));
            String session=registry.apply(e,100,()->true).getString("sessionId");
            fail("DUPLICATE_WORKER",()->registry.apply(RuntimeSupport.copy(e).put("instanceId","other"),101,()->true));
            JSONObject heartbeat=RuntimeSupport.copy(e);
            heartbeat.remove("descriptor");heartbeat.put("action","heartbeat").put("sessionId",session);
            fail("STALE_SESSION",()->registry.apply(heartbeat,101,()->true));
            registry.apply(RuntimeSupport.copy(heartbeat).put("action","drain"),100,()->true);check(!registry.snapshot().getJSONObject(0).getBoolean("ready"),"drain still ready");
            Thread.sleep(170);fail("STALE_SESSION",()->registry.apply(heartbeat,100,()->true));
            pass("Runner lease binds instance/transport/nonce; duplicate session, drain and expiry enforced");
        }finally{broker.close();http.stop(0);}
    }
    static Properties copyNode(String node){Properties x=new Properties();x.putAll(p);x.setProperty("broker.node.id",node);return x;}
    public static void main(String[] values)throws Exception {
        if(values.length>0&&values[0].equals("prepare")){prepare(Files.createDirectory(Paths.get(values[1])),"");return;}
        prepare(Files.createTempDirectory("harness-tools-"),System.getProperty("tools.test.config",""));checks();System.out.println("PASS: N08/N09 real JDBC/MockConsole on "+config.dialect);
    }
}
