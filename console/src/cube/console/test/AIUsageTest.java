package cube.console.test;

import cube.aigc.usage.*;
import cube.auth.AuthToken;
import cube.console.mgmt.*;
import org.json.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated JDBC accounting, federation, authorization and export regression. */
public final class AIUsageTest {
    private static int assertions;
    static void check(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    static void rejects(Runnable r,String message){boolean rejected=false;try{r.run();}catch(RuntimeException e){rejected=true;}check(rejected,message);}
    public static void main(String[] args)throws Exception{
        Class.forName("org.sqlite.JDBC");
        Path root=Files.createTempDirectory("cube-ai-usage-");
        boolean mysql=args.length>0;
        UsageLedger.Connections connection=mysql?()->DriverManager.getConnection(args[0],"root",""):()->DriverManager.getConnection("jdbc:sqlite:"+root.resolve("usage.db"));
        UsageLedger ledger=new UsageLedger(connection,"aigc_ai_usage_",mysql);ledger.initialize();ledger.initialize();
        long month=java.time.YearMonth.of(2026,10).atDay(1).atStartOfDay(AIUsageManager.ZONE).toInstant().toEpochMilli();
        ledger.transaction(c -> UsageLedger.update(c,"UPDATE aigc_ai_usage_store SET collected_at=? WHERE singleton=1",month));
        UsageContext a=UsageContext.run("alpha",10000001,"run-a","request-a"),b=UsageContext.run("beta",10000001,"run-b","request-b");
        try(UsageWriter writer=new UsageWriter(ledger,root.resolve("outbox"))){
            writer.begin(a,"call-1","model-a","worker-a",month+1000);
            JSONObject actual=UsageLedger.evidence("call-1",2,"succeeded","actual",100L,20L,month+2000);
            writer.finish(actual);writer.finish(actual);writer.finish(new JSONObject(actual.toString()).put("finished",month+3000));
            writer.begin(a,"call-1","model-a","worker-a",month+4000);
            rejects(()->ledger.begin(b,"call-1","model-a","worker-a",month),"cannot reassign accounting identity");
            writer.begin(b,"call-2","model-a","worker-b",month+3600001);
            writer.finish(UsageLedger.evidence("call-2",1,"unknown","unknown",null,null,month+4000000));
            writer.finish(UsageLedger.evidence("call-2",2,"failed","actual",30L,0L,month+5000000));
            writer.begin(a,"estimate","model-b","worker-a",month+900000);
            writer.finish(UsageLedger.evidence("estimate",2,"succeeded","estimated",40L,10L,month+1000000));
            writer.begin(a,"pending","model-b","worker-a",month+10000000);
            ledger.begin(UsageContext.system("AIGC"),"system","model-system","unit-system",month+2000);
            ledger.settle(UsageLedger.evidence("system",2,"succeeded","actual",10000L,10000L,month+3000));
        }
        try(Connection c=connection.open()){
            JSONObject sums=UsageLedger.one(c,"SELECT SUM(calls) AS calls,SUM(input_tokens) AS input,SUM(estimated_input) AS estimated FROM aigc_ai_usage_hourly");
            check(sums.getLong("calls")==5,"one per actual attempt including system");check(sums.getLong("input")==10130,"duplicates excluded, late failure consumption included");check(sums.getLong("estimated")==40,"estimate separate");
        }
        // Correction moves the original bucket, rather than the month it arrived.
        ledger.settle(UsageLedger.evidence("call-1",3,"succeeded","actual",110L,25L,month+40L*86400000));
        rejects(()->ledger.settle(UsageLedger.evidence("call-1",3,"succeeded","actual",900L,25L,month)),"revision conflict rolls back");
        ExecutorService pool=Executors.newFixedThreadPool(6);List<Future<?>> jobs=new ArrayList<>();
        for(int i=0;i<36;i++){final int n=i;jobs.add(pool.submit(()->{UsageLedger peer=new UsageLedger(connection,"aigc_ai_usage_",mysql);peer.begin(a,"parallel-"+n,"model-a","worker-a",month+7200000);peer.settle(UsageLedger.evidence("parallel-"+n,2,"succeeded","actual",1L,2L,month+8000000));}));}
        for(Future<?> job:jobs)job.get();pool.shutdown();
        JSONObject src=new JSONObject().put("name","main").put("kind","aigc").put("type",mysql?"mysql":"sqlite");
        if(mysql){src.put("host","127.0.0.1").put("port",33316).put("database","ai_usage_test").put("user","root").put("password","");}
        else src.put("file",root.resolve("usage.db").toString());
        AIUsageManager manager=new AIUsageManager(new JSONArray().put(src).put(new JSONObject(src.toString()).put("name","same-db")));
        User admin=new User(1L,"admin","","Admin",1,"alpha"),scoped=new User(2L,"user","","User",2,"alpha");
        Map<String,String> params=new HashMap<>();params.put("from",Long.toString(month));params.put("to",Long.toString(month+31L*86400000));params.put("group","contact");
        JSONObject result=manager.query("calls",new AIUsageManager.Filter(params,admin));
        Map<String,String> uncovered=new HashMap<>(params);uncovered.put("from",Long.toString(month-1));
        check(!manager.query("summary",new AIUsageManager.Filter(uncovered,admin)).getBoolean("complete"),"pre-collection history marked incomplete");
        uncovered.put("from",Long.toString(month-86400000));uncovered.put("to",Long.toString(month));
        check(manager.query("summary",new AIUsageManager.Filter(uncovered,admin)).isNull("summary"),"entirely uncollected period is not zero traffic");
        check(result.getBoolean("complete"),"federated stores available");check(result.getJSONArray("sources").getJSONObject(1).getString("state").equals("duplicate"),"shared database de-duplicated");
        JSONObject sum=result.getJSONObject("summary");check(sum.getLong("calls")==40,"only visitor calls in bill");check(sum.getLong("input_tokens")==176,"correction and all parallel attempts included");check(sum.getLong("output_tokens")==97,"actual outputs exact");check(sum.getLong("estimated_input")==40,"estimated not billed as actual");check(sum.getLong("pending_calls")==1,"unfinished call explicit");
        JSONObject scopedResult=manager.query("summary",new AIUsageManager.Filter(params,scoped));check(scopedResult.getJSONObject("summary").getLong("input_tokens")==146,"domain scoped server side");
        params.put("domain","beta");rejects(()->new AIUsageManager.Filter(params,scoped),"ordinary admin cannot cross domains");params.put("domain","alpha");params.put("contact","10000001");params.put("month","2026-10");
        JSONObject bill=manager.query("bill",new AIUsageManager.Filter(params,admin));check(bill.getJSONObject("summary").getLong("calls")==39,"same Contact domain-specific bill");check(bill.getJSONArray("models").length()==2,"model subtotals");
        params.put("offset","0");params.put("limit","7");check(manager.query("calls",new AIUsageManager.Filter(params,admin)).getJSONArray("calls").length()==7,"bounded detail pagination");
        JSONObject export=manager.query("export",new AIUsageManager.Filter(params,admin));String csv=AIUsageManager.csv(export);check(csv.contains("generated_at")&&csv.contains("Asia/Shanghai")&&csv.contains("call-1"),"CSV metadata and per-call evidence");check(export.getJSONArray("calls").length()==39,"export includes all matching pages");
        params.clear();params.put("from",Long.toString(month+1500));params.put("to",Long.toString(month+3600000));JSONObject boundary=manager.query("summary",new AIUsageManager.Filter(params,admin));check(boundary.getJSONObject("summary").getLong("calls")==1,"partial hours use detail without boundary double count");
        params.put("from","0");params.put("to",Long.toString(367L*86400000));rejects(()->new AIUsageManager.Filter(params,admin),"range bounded");
        JSONObject missing=new JSONObject(src.toString()).put("name","missing").put("kind","harness");JSONObject partial=new AIUsageManager(new JSONArray().put(src).put(missing)).query("summary",new AIUsageManager.Filter(Collections.emptyMap(),admin));check(!partial.getBoolean("complete"),"old or unavailable source not zero traffic");
        JSONObject absent=new AIUsageManager(new JSONArray()).query("summary",new AIUsageManager.Filter(Collections.emptyMap(),admin));check(absent.isNull("summary")&&!absent.getBoolean("complete"),"uncollected is not zero");
        // DB outage: reject new dispatch, fsync terminal evidence, replay after restart.
        AtomicBoolean down=new AtomicBoolean(false);UsageLedger faulted=new UsageLedger(()->{if(down.get())throw new SQLException("injected outage");return connection.open();},"aigc_ai_usage_",mysql);
        Path outbox=root.resolve("replay");UsageWriter first=new UsageWriter(faulted,outbox);first.begin(a,"crash","model-a","worker-a",month+9000000);down.set(true);
        first.finish(UsageLedger.evidence("crash",2,"cancelled","actual",7L,3L,month+10000000));rejects(()->first.begin(a,"must-not-dispatch","m","u",month),"outage pauses new model calls");first.close();
        check(Files.list(outbox).anyMatch(p->p.toString().endsWith(".json")),"terminal evidence durable while DB down");down.set(false);
        try(UsageWriter recovered=new UsageWriter(faulted,outbox)){check(recovered.healthy(),"restart replays evidence");}
        try(Connection c=connection.open()){check(UsageLedger.one(c,"SELECT input_tokens FROM aigc_ai_usage_call WHERE meter_id='crash'").getLong("input_tokens")==7,"recovered accounting exact");}
        check(UsageTokens.read(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",1)))==null,"missing output not zero");check(UsageTokens.read(new JSONObject().put("inputTokens",1.5).put("outputTokens",2))==null,"fractional tokens rejected");check(UsageTokens.read(new JSONObject().put("inputTokens",0).put("outputTokens",0))[0]==0,"actual zero retained");
        rejects(()->UsageContext.visitor(new AuthToken("unbound","alpha","app",(Long)null,0,Long.MAX_VALUE,false),"AIGC","r"),"unbound Token rejected explicitly");
        UsageContext firstToken=UsageContext.visitor(new AuthToken("token-1","alpha","app",10000001L,0,Long.MAX_VALUE,false),"AIGC","r");
        UsageContext secondToken=UsageContext.visitor(new AuthToken("token-2","alpha","app",10000001L,0,Long.MAX_VALUE,false),"AIGC","s");
        check(firstToken.contactId==secondToken.contactId && firstToken.domain.equals(secondToken.domain),"multiple Tokens use one settlement identity");
        ledger.begin(firstToken,"unsent","m","u",month+5000);ledger.settle(UsageLedger.evidence("unsent",1,"not_dispatched","unknown",null,null,month+5001));
        try(Connection c=connection.open()){check(UsageLedger.one(c,"SELECT calls FROM aigc_ai_usage_hourly WHERE model='m'").getLong("calls")==0,"definite transport rejection excluded from model calls");}
        ledger.begin(firstToken,"snapshot","snapshot-model","u",month+6000);
        ledger.settle(UsageLedger.evidence("snapshot",2,"unknown","actual",5L,2L,month+7000));
        try(Connection c=connection.open()){check(UsageLedger.one(c,"SELECT pending_calls FROM aigc_ai_usage_hourly WHERE model='snapshot-model'").getLong("pending_calls")==1,"confirmed partial usage still requires reconciliation");}
        ledger.settle(UsageLedger.evidence("snapshot",3,"succeeded","actual",7L,3L,month+8000));
        try(Connection c=connection.open()){JSONObject r=UsageLedger.one(c,"SELECT * FROM aigc_ai_usage_hourly WHERE model='snapshot-model'");check(r.getLong("input_tokens")==7&&r.getLong("output_tokens")==3&&r.getLong("calls")==1&&r.getLong("pending_calls")==0,"cumulative snapshot replaces previous evidence");}
        System.out.println("AIUsageTest: "+assertions+" assertions passed ("+(mysql?"MySQL":"SQLite")+")");
    }
}
