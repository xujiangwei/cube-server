package cube.service.test;

import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.usage.*;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.console.Console;
import cube.console.container.handler.*;
import cube.console.mgmt.*;
import cube.dispatcher.aigc.completion.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.json.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real REST handler -> service queue/events -> JDBC -> authenticated Console HTTP/CSV. */
public final class AIUsageIntegrationTest {
    static int assertions;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static final class Reply {final int status;final String body;Reply(int s,String b){status=s;body=b;}JSONObject json(){return new JSONObject(body);}}
    static Reply request(String base,String path,String body,String cookie,String token)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();c.setReadTimeout(10000);
        if(cookie!=null)c.setRequestProperty("Cookie","CubeConsoleToken="+cookie);
        if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
        if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
        int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if(in!=null)try(InputStream stream=in){byte[] b=new byte[4096];int n;while((n=stream.read(b))!=-1)bytes.write(b,0,n);}c.disconnect();return new Reply(status,bytes.toString("UTF-8"));
    }
    public static void main(String[] args)throws Exception{
        final ChatCompletionServiceTest.FakeService[] serviceRef=new ChatCompletionServiceTest.FakeService[1];
        final cell.api.Nucleus testNucleus=new cell.api.Nucleus();
        ChatCompletionServiceTest.FakeCellet cellet=new ChatCompletionServiceTest.FakeCellet(){
            @Override public cell.api.Nucleus getNucleus(){return testNucleus;}
            @Override public cube.service.aigc.AIGCService getService(){return serviceRef[0];}
            @Override public boolean speak(TalkContext context,Primitive primitive){
                boolean sent=super.speak(context,primitive);ActionDialect d=new ActionDialect(primitive);
                if(AIGCAction.ChatCompletion.name.equals(d.getName())){
                    String id=new Packet(d).data.getString("requestId");
                    ChatCompletionServiceTest.event(serviceRef[0],context,id,1,"started",new JSONObject());
                    ChatCompletionServiceTest.event(serviceRef[0],context,id,2,"delta",new JSONObject().put("delta",new JSONObject().put("content","answer")));
                    JSONObject payload=new JSONObject().put("result",ChatCompletionServiceTest.result());
                    ChatCompletionServiceTest.event(serviceRef[0],context,id,3,"completed",payload);
                    ChatCompletionServiceTest.event(serviceRef[0],context,id,3,"completed",payload);
                }
                return sent;
            }
        };
        ChatCompletionServiceTest.FakeService service=new ChatCompletionServiceTest.FakeService(cellet){
            @Override public cube.auth.AuthToken getToken(String code){
                return "token2".equals(code)?new cube.auth.AuthToken("token2","domain","app",1L,0,Long.MAX_VALUE,false):super.getToken(code);
            }
        };serviceRef[0]=service;service.selected.setTimestamp(0);
        String file=service.storage.accounting.ledger.transaction(c->UsageLedger.one(c,"PRAGMA database_list").getString("file"));
        JSONObject source=new JSONObject().put("name","AIGC fixture").put("kind","aigc").put("type","sqlite").put("file",file);
        AtomicReference<AIUsageManager> manager=new AtomicReference<>(new AIUsageManager(new JSONArray().put(source)));
        java.util.concurrent.atomic.AtomicBoolean queryFailure=new java.util.concurrent.atomic.AtomicBoolean();
        User admin=new User(1L,"admin","","测试管理员",1,"domain"),scoped=new User(2L,"scoped","","域管理员",2,"domain");
        UserManager users=new UserManager(){
            @Override public boolean checkToken(String token){return "admin".equals(token)||"scoped".equals(token);}
            @Override public UserToken getToken(String token){if(!checkToken(token))return null;UserToken result=new UserToken(1,token,0,Long.MAX_VALUE);result.user="admin".equals(token)?admin:scoped;return result;}
        };
        StatisticDataManager statistics=new StatisticDataManager(){@Override public JSONObject queryAIUsage(String op,Map<String,String> params,User user){if(queryFailure.get())throw new IllegalStateException("Injected query failure");return manager.get().query(op,new AIUsageManager.Filter(params,user));}};
        Console console=new Console();
        java.lang.reflect.Field userField=Console.class.getDeclaredField("userManager");userField.setAccessible(true);userField.set(console,users);
        java.lang.reflect.Field statsField=Console.class.getDeclaredField("statisticDataManager");statsField.setAccessible(true);statsField.set(console,statistics);
        CompletionBackend backend=new CompletionBackend(){
            public JSONArray models(String token,long timeout){return service.completionModels(token);}
            public Subscription generate(String token,String id,JSONObject request,long timeout,Listener listener){
                service.getCompletions().generateInternal(token,id,request,timeout,UsageContext.visitor(service.getToken(token),"AIGC",id),listener::onEvent);
                return ()->service.getCompletions().cancel(token,id,null);
            }
        };
        boolean serve=args.length>0&&args[0].equals("serve");
        org.eclipse.jetty.server.Server server=new org.eclipse.jetty.server.Server();ServerConnector connector=new ServerConnector(server);connector.setHost("127.0.0.1");connector.setPort(serve?8898:0);server.addConnector(connector);
        AbstractHandler fixture=new AbstractHandler(){public void handle(String target,Request base,HttpServletRequest req,HttpServletResponse res)throws IOException{
            if(target.equals("/signin")){base.setHandled(true);res.setContentType("application/json");res.setHeader("Set-Cookie","CubeConsoleToken=admin; Path=/; SameSite=Lax");res.getWriter().write(users.getToken("admin").toJSON().toString());}
            else if(target.startsWith("/servers/")){base.setHandled(true);res.setContentType("application/json");res.getWriter().write("{\"total\":0,\"list\":[]}");}
            else if(target.equals("/test/mode")){base.setHandled(true);String mode=req.getParameter("name");queryFailure.set("error".equals(mode));JSONArray sources=new JSONArray();if(!"none".equals(mode))sources.put(source);if("partial".equals(mode))sources.put(new JSONObject(source.toString()).put("name","未升级的 Harness").put("kind","harness"));manager.set(new AIUsageManager(sources));res.getWriter().write("ok");}
        }};
        ResourceHandler web=new ResourceHandler();web.setResourceBase("console/web");web.setWelcomeFiles(new String[]{"index.html"});
        HandlerList handlers=new HandlerList();handlers.setHandlers(new Handler[]{fixture,new CompletionHandler(backend,new CompletionConfig(new Properties())),new StatisticDataHandler(console),new SpaFallbackHandler("console/web"),web});server.setHandler(handlers);server.start();
        String base="http://127.0.0.1:"+connector.getLocalPort();
        try{
            JSONObject body=ChatCompletionServiceTest.request("model-a").put("stream",false);
            // CompletionHandler expects the public OpenAI wire format.
            body=new JSONObject().put("model","model-a").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","hello"))).put("user","forged-contact-9999");
            Reply normal=request(base,"/v1/chat/completions",body.toString(),null,"token");check(normal.status==200&&normal.json().getJSONObject("usage").getLong("total_tokens")==22,"REST completion returns actual usage: "+normal.status+" "+normal.body);
            body.put("stream",true).put("stream_options",new JSONObject().put("include_usage",true));Reply stream=request(base,"/v1/chat/completions",body.toString(),null,"token2");check(stream.status==200&&stream.body.contains("[DONE]")&&stream.body.contains("prompt_tokens"),"SSE completion and usage delivered with second Contact Token");
            check(request(base,"/statistic/ai-usage/summary",null,null,null).status==401,"Console requires login");
            check(request(base,"/statistic/ai-usage/summary?domain=other",null,"scoped",null).status==403,"HTTP domain restriction enforced");
            Reply summary=request(base,"/statistic/ai-usage/summary",null,"admin",null);JSONObject sums=summary.json().getJSONObject("summary");check(summary.status==200&&sums.getLong("calls")==2&&sums.getLong("total_tokens")==44,"REST consumption persisted and queried through Console");
            String month=YearMonth.now(AIUsageManager.ZONE).toString(),query="?domain=domain&contact=1&month="+month;
            JSONObject bill=request(base,"/statistic/ai-usage/bill"+query,null,"scoped",null).json();check(bill.getJSONObject("summary").getLong("input_tokens")==34,"Contact monthly bill exact");
            for(int i=0;i<bill.getJSONArray("calls").length();i++){JSONObject row=bill.getJSONArray("calls").getJSONObject(i);check(row.getLong("contact_id")==1&&!row.getString("unit_id").isEmpty(),"visitor differs from executing AI Contact");}
            Reply csv=request(base,"/statistic/ai-usage/export"+query,null,"admin",null);check(csv.status==200&&csv.body.contains("Asia/Shanghai")&&csv.body.contains("actual"),"real Console CSV download");
            check(request(base,"/statistic/ai-usage/bill?domain=domain",null,"admin",null).status==400,"bill dimensions mandatory");
            ActionDialect foreign=new Packet(AIGCAction.QueryUsages.name,new JSONObject().put("contactId",9999)).toDialect();foreign.addParam("token","token");
            new cube.service.aigc.task.QueryUsageTask(cellet,service.origin,foreign,null).run();
            Packet denied=new Packet(cellet.sent.get(cellet.sent.size()-1).dialect);check(Packet.extractCode(denied)!=0,"legacy usage cannot query another Contact");
            cube.service.aigc.event.EventCenter center=cube.service.aigc.event.EventCenter.getInstance();
            cube.service.aigc.unit.MultimodalUnitMeta meta=new cube.service.aigc.unit.MultimodalUnitMeta(service,service.selected,null,
                    new cube.common.entity.MultimodalInput(new JSONObject().put("content","").put("unit","Baize2")),null);
            meta.usageContext=UsageContext.visitor(service.token,"Multimodal","original-rest-request");center.putUnitMeta("stream-fixture",meta);
            JSONObject segment=new JSONObject().put("streamId","stream-fixture").put("sequence",0).put("contactId",9999);
            JSONObject event=new JSONObject().put("name","segmentBegin").put("source","fixture").put("payload",segment);
            new cube.service.aigc.task.UsageSegmentTask(cellet,service.other,new Packet("event",event).toDialect(),null).run();
            check(Packet.extractCode(new Packet(cellet.sent.get(cellet.sent.size()-1).dialect))!=0,"foreign Unit cannot admit stream inference");
            new cube.service.aigc.task.UsageSegmentTask(cellet,service.owner,new Packet("event",event).toDialect(),null).run();
            JSONObject ticket=Packet.extractDataPayload(new Packet(cellet.sent.get(cellet.sent.size()-1).dialect));
            JSONObject repeated=meta.beginSegment(segment);check(ticket.getString("meterId").equals(repeated.getString("meter_id"))&&ticket.getLong("startedAt")==repeated.getLong("started_at"),"stream preflight retry keeps durable identity and original start");
            center.stop();segment.put("meterId",ticket.getString("meterId")).put("timestamp",System.currentTimeMillis()).put("usage",new JSONObject().put("input",9).put("output",3));event.put("name","segmentUsage");
            for(int n=0;n<2;n++)new cube.service.aigc.task.UsageSegmentTask(cellet,service.owner,new Packet("event",event).toDialect(),null).run();
            JSONObject segmentCall=service.storage.accounting.ledger.transaction(c->UsageLedger.one(c,"SELECT * FROM aigc_ai_usage_call WHERE meter_id=?",ticket.getString("meterId")));
            check(segmentCall.getLong("contact_id")==1&&segmentCall.getLong("input_tokens")==9&&segmentCall.getString("request_id").equals("original-rest-request"),"stream terminal replay survives route eviction and preserves visitor");
            System.out.println("AIUsageIntegrationTest: "+assertions+" assertions passed; REST -> JDBC -> authenticated bill/CSV");
            if(serve){
                UsageLedger ledger=service.storage.accounting.ledger;long now=System.currentTimeMillis();
                for(int i=0;i<16;i++){String id="preview-"+i;ledger.begin(UsageContext.run("domain",10000001+i%3,"run-"+i,"request-"+i),id,i%2==0?"Baize2":"Baize",i%2==0?"worker-a":"worker-b",now-i*3600000L);ledger.settle(UsageLedger.evidence(id,2,"succeeded",i==15?"estimated":"actual",(long)(500+i*37),(long)(100+i*23),now));}
                System.out.println("AI_USAGE_PREVIEW="+base+"/ai-usage");System.out.flush();server.join();
            }
        }finally{server.stop();server.join();service.getCompletions().stop();service.queue.shutdown();service.storage.close();testNucleus.destroy();}
    }
}
