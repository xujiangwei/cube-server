package cube.dispatcher.test;

import cell.api.*;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.SpecializedDetectionException;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.Contact;
import cube.dispatcher.*;
import cube.dispatcher.aigc.Manager;
import cube.dispatcher.aigc.handler.SpecializedDetectionHandler;
import cube.util.HttpServer;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Performer routing, Manager and HTTP handlers against simulated service connections. */
public final class SpecializedDetectionRoutingTest {
    static final String TOKEN="0123456789abcdef0123456789abcdef", OTHER="abcdef0123456789abcdef0123456789";
    static final String FILE=String.join("",Collections.nCopies(64,"a"));
    static int assertions;
    public static void main(String[] args)throws Exception {
        try(Fixture f=new Fixture()) {
            Node old=f.add("legacy",false), a=f.add("a",true), b=f.add("b",true);
            CapabilityRoutingTest.until(()->f.performer.getCapabilityRouter().candidates().size()==3);
            for(int i=0;i<8;i++) {
                JSONObject result=invoke(f,TOKEN,start());String id=result.getJSONObject("resultPayload").getString("streamId");
                JSONObject status=invoke(f,TOKEN,control("status",id));
                check(result.getString("service").equals(status.getString("service")),"immediate owner control");
            }
            check(a.calls.get()>0&&b.calls.get()>0&&old.calls.get()==0,"two services split traffic; old version excluded");
            JSONObject response=invoke(f,TOKEN,start());String id=response.getJSONObject("resultPayload").getString("streamId");
            Node owner=response.getString("service").equals("a")?a:b;
            Object state=((Map<?,?>)field(f.performer.getCapabilityRouter(),"states")).get(owner.director);
            set(state,"received",0L);
            check(invoke(f,TOKEN,control("stop",id)).getString("service").equals(owner.name),"controls ignore expired capability metadata");
            f.performer.getCapabilityRouter().onTick(System.currentTimeMillis()+3600000);
            check(invoke(f,TOKEN,control("status",id)).getString("service").equals(owner.name),"active routes survive idle tick");
            f.performer.getCapabilityRouter().disconnected(owner.speaker);
            expect(3,()->invoke(f,TOKEN,control("status",id)));
            Node replacement=new Node(f,owner.director,"replacement",true);owner.director.speaker=replacement.speaker;
            f.performer.getCapabilityRouter().connected(owner.director,replacement.speaker);
            expect(3,()->invoke(f,TOKEN,control("status",id)));
        }
        http(); System.out.println("SpecializedDetectionRoutingTest: "+assertions+" assertions passed");
    }

    static void http()throws Exception {
        try(Fixture f=new Fixture()) {
            f.add("http-a",true);f.add("http-b",true);
            CapabilityRoutingTest.until(()->f.performer.getCapabilityRouter().candidates().size()==2);
            Manager manager=Manager.getInstance();Server server=new Server();ServerConnector connector=new ServerConnector(server);
            connector.setHost("127.0.0.1");connector.setPort(0);server.addConnector(connector);
            ContextHandlerCollection handlers=new ContextHandlerCollection();
            handlers.setHandlers(new Handler[]{new SpecializedDetectionHandler(false),new SpecializedDetectionHandler(true)});server.setHandler(handlers);
            try {
                manager.start(f.performer);server.start();String root="http://127.0.0.1:"+connector.getLocalPort();
                JSONObject detected=post(root,false,TOKEN,image(),200);
                check(f.lastTimeout==190000,"default gateway data deadline without properties");
                check(detected.getJSONObject("resultPayload").getJSONArray("refs").length()==0,"direct success without content");
                JSONObject started=post(root,true,TOKEN,start(),200);String id=started.getJSONObject("resultPayload").getString("streamId");
                JSONObject status=post(root,true,TOKEN,control("status",id),200);
                check(f.lastTimeout==40000,"default gateway control deadline without properties");
                check(status.getString("service").equals(started.getString("service")),"HTTP start immediately pinned");
                post(root,true,OTHER,control("stop",id),403);
                for(int[] pair:new int[][]{{1,400},{3,404},{7,429},{30,503},{31,502},{32,422}}){f.error=pair[0];JSONObject error=post(root,false,TOKEN,image(),pair[1]).getJSONObject("error");check(error.getInt("code")==pair[0],"Python code preserved");}
                for(String profile:Arrays.asList("uav-fire-smoke","future-profile")) {
                    f.error=1;JSONObject unsupported=image();unsupported.getJSONObject("option").put("profile",profile);
                    JSONObject error=post(root,false,TOKEN,unsupported,400).getJSONObject("error");
                    check(error.getInt("code")==1&&profile.equals(f.lastProfile),"Python rejects unsupported profile through HTTP");
                    unsupported=start();unsupported.getJSONObject("option").put("profile",profile);
                    post(root,true,TOKEN,unsupported,400);
                    check(profile.equals(f.lastProfile),"unsupported start reaches service");
                }
                f.error=0;post(root,false,TOKEN,start(),400);post(root,true,TOKEN,image(),400);
                post(root,false,TOKEN,image().put("files",new JSONArray().put("/etc/private.png")),400);
                post(root,true,TOKEN,control("status","unknown"),404);
                f.performer.getProperties().setProperty("specialized.detection.data.timeout.ms","10");f.silent=true;
                JSONObject timeout=post(root,false,TOKEN,image(),504).getJSONObject("error");
                check(timeout.getString("reason").equals("DETECTION_TIMEOUT"),"transport timeout remains 504");
            } finally {server.stop();manager.stop();}
        }
    }

    static JSONObject invoke(Fixture f,String token,JSONObject input){
        Packet p=new Packet("specializedDetection",new JSONObject().put("channel","channel").put("input",input));ActionDialect d=p.toDialect();d.addParam("token",token);
        ActionDialect response=f.performer.syncTransmit("AIGC",d,2000);check(response!=null,"service responded");return Packet.extractDataPayload(new Packet(response));
    }
    static JSONObject post(String root,boolean stream,String token,JSONObject input,int status)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(root+"/specialized-detection/"+(stream?"stream/":"base/")+token).openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestProperty("Content-Type","application/json");
        byte[] bytes=new JSONObject().put("channel","channel").put("input",input).toString().getBytes(StandardCharsets.UTF_8);
        try {
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}int actual=c.getResponseCode();
            try(InputStream in=actual>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                check(actual==status,"HTTP "+status+" got "+actual+": "+result);
                if(status>=400){JSONObject error=result.getJSONObject("error");check(error.getInt("state")==status&&error.has("code")&&error.has("reason")&&error.has("message"),"complete error envelope");}return result;
            }
        }finally{c.disconnect();}
    }
    static JSONObject image(){return new JSONObject().put("files",new JSONArray().put(FILE)).put("option",new JSONObject().put("profile","fire-smoke"));}
    static JSONObject start(){return image().put("files",new JSONArray().put("rtsp://127.0.0.1/test")).put("option",new JSONObject().put("operation","start").put("profile","fire-smoke").put("callbackUrl","http://localhost/callback"));}
    static JSONObject control(String operation,String id){return new JSONObject().put("files",new JSONArray()).put("option",new JSONObject().put("operation",operation).put("streamId",id));}
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static void expect(int code,Runnable job){try{job.run();throw new AssertionError("Expected "+code);}catch(SpecializedDetectionException e){check(e.code==code,"route code "+code);}}
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);assertions++;}
    static final class TestScope extends Scope{TestScope(){cellets.add("AIGC");}}
    static final class Fixture implements AutoCloseable {
        final Performer performer=new Performer(new Nucleus()) {
            final HttpServer http=new HttpServer();
            final Properties config=new Properties();
            @Override public Properties getProperties(){return config;}
            @Override public HttpServer getHttpServer(){return http;}
            @Override public ActionDialect syncTransmit(String cellet,ActionDialect request,long timeout){
                if(AIGCAction.SpecializedDetection.name.equals(request.getName()))lastTimeout=timeout;
                return super.syncTransmit(cellet,request,timeout);
            }
            @Override public ActionDialect syncTransmit(String cellet,ActionDialect request){
                if(!AIGCAction.CheckToken.name.equals(request.getName()))return super.syncTransmit(cellet,request);
                String code=new Packet(request).data.optString("token",TOKEN);long now=System.currentTimeMillis();
                JSONObject data=new JSONObject().put("token",new AuthToken(code,"domain","app",TOKEN.equals(code)?1L:2L,now,now+3600000,false).toJSON()).put("contact",new Contact(1L,"domain","test").toJSON());
                return new Packet(request.getParamAsLong("sn"),request.getName(),new JSONObject().put("code",0).put("data",data)).toDialect();
            }
        };
        int port;volatile int error;volatile boolean silent;volatile long lastTimeout;volatile String lastProfile;
        Fixture(){performer.getCapabilityRouter().start();}
        Node add(String name,boolean enabled){Director d=performer.addDirector("127.0.0.1",6300+port++,"127.0.0.1",6080,new TestScope());Node n=new Node(this,d,name,enabled);d.speaker=n.speaker;performer.getCapabilityRouter().connected(d,n.speaker);return n;}
        public void close(){performer.getCapabilityRouter().stop();}
    }
    static final class Node {
        final Director director;final Speakable speaker;final String name;final AtomicInteger calls=new AtomicInteger();
        final Map<String,String> owners=new ConcurrentHashMap<>();
        Node(Fixture f,Director d,String name,boolean enabled){this.director=d;this.name=name;
            speaker=(Speakable)java.lang.reflect.Proxy.newProxyInstance(Speakable.class.getClassLoader(),new Class<?>[]{Speakable.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "isReady":return true;case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];
                    case "getRemoteAddress":return new InetSocketAddress("127.0.0.1",d.endpoint.getPort());
                    case "speak":
                        ActionDialect request=(ActionDialect)args[1];Packet p=new Packet(request);JSONObject data;int code=0;
                        if(AIGCAction.GetCapabilitySnapshot.name.equals(p.name)){
                            data=CapabilityRoutingTest.snapshot(name,new JSONArray().put(CapabilityRoutingTest.unit("SpecializedDetection","Detection",false,false)));
                            if(enabled)data.put("specializedDetection",new JSONObject().put("version",1).put("providers",new JSONArray().put(new JSONObject().put("availableSessions",2).put("availableImages",1))));
                        } else {
                            calls.incrementAndGet();if(f.silent)return true;
                            JSONObject option=p.data.getJSONObject("input").getJSONObject("option");String op=option.optString("operation","detect");
                            f.lastProfile=option.optString("profile",null);
                            String id=option.optString("streamId",name+"-"+p.sn);String token=request.getParamAsString("token");code=f.error;
                            if("start".equals(op))owners.put(id,token);
                            else if(!"detect".equals(op)){if(!owners.containsKey(id))code=3;else if(!owners.get(id).equals(token))code=25;}
                            JSONObject body="detect".equals(op)?new JSONObject().put("profile","fire-smoke").put("width",1).put("height",1).put("refs",new JSONArray()):new JSONObject().put("streamId",id).put("status","stop".equals(op)?"stopping":"running");
                            data=new JSONObject().put("response","").put("thought","").put("resultPayload",body).put("performance",new JSONObject().put("inputTokens",0).put("outputTokens",0)).put("service",name);
                        }
                        ActionDialect response=new Packet(p.sn,p.name,new JSONObject().put("code",code).put("data",data)).toDialect();response.addParam("_performer",request.getParamAsJson("_performer"));f.performer.onListened((Speakable)proxy,"AIGC",response);return true;
                    default:throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }
}
