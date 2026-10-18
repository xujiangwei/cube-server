package cube.service.harness.test;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated actual Jetty proxy test; no Cube model/Chat/Console services. */
public final class HarnessGatewayTest {
    static final String CLIENT="harness-client-fixture-123456789",BACKEND="harness-backend-fixture-987654321";
    static int port;static String seenKey,seenBody;static AtomicInteger calls=new AtomicInteger();
    static String request(String path,String auth,String body,int expected)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(Proxy.NO_PROXY);c.setReadTimeout(5000);c.setConnectTimeout(2000);
        if(auth!=null)c.setRequestProperty("Authorization","Bearer "+auth);
        c.setRequestProperty("Accept","text/event-stream");
        if(body!=null){c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Idempotency-Key","fixture-key");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
        int status=c.getResponseCode();if(status!=expected)throw new AssertionError("Expected "+expected+" got "+status);
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){byte[] b=new byte[4096];int n;while(in!=null&&(n=in.read(b))!=-1)out.write(b,0,n);}finally{c.disconnect();}return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception {
        Class<?> gateway=Class.forName("cube.dispatcher.harness.HarnessGateway");
        if(gateway.getMethod("install",cube.util.HttpServer.class,Properties.class).invoke(null,null,new Properties())!=null)throw new AssertionError("Gateway enabled by default");
        HttpServer backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
        backend.createContext("/",x->{calls.incrementAndGet();if(!("Bearer "+BACKEND).equals(x.getRequestHeaders().getFirst("Authorization")))throw new AssertionError("Wrong upstream credential");
            seenKey=x.getRequestHeaders().getFirst("Idempotency-Key");ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=x.getRequestBody().read(b))!=-1)data.write(b,0,n);seenBody=new String(data.toByteArray(),StandardCharsets.UTF_8);
            byte[] payload=(x.getRequestURI().getPath().endsWith("events")?"id: 2\nevent: run.created\ndata: {}\n\n":"{\"fixture\":true}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type",x.getRequestURI().getPath().endsWith("events")?"text/event-stream":"application/json");x.sendResponseHeaders(x.getRequestMethod().equals("POST")?202:200,payload.length);try(OutputStream out=x.getResponseBody()){out.write(payload);}});backend.start();
        Properties p=new Properties();p.setProperty("harness.rest.mode","development");p.setProperty("harness.rest.backend","http://127.0.0.1:"+backend.getAddress().getPort());p.setProperty("harness.rest.client.credential",CLIENT);p.setProperty("harness.rest.backend.credential",BACKEND);
        Server server=new Server(new InetSocketAddress("127.0.0.1",0));ContextHandlerCollection handlers=new ContextHandlerCollection();handlers.addHandler((ContextHandler)gateway.getConstructor(Properties.class).newInstance(p));
        ContextHandler legacy=new ContextHandler("/v1");legacy.setHandler(new AbstractHandler(){public void handle(String t,Request b,javax.servlet.http.HttpServletRequest r,javax.servlet.http.HttpServletResponse s)throws IOException{b.setHandled(true);s.getWriter().write("legacy-route");}});handlers.addHandler(legacy);server.setHandler(handlers);
        try{server.start();port=((ServerConnector)server.getConnectors()[0]).getLocalPort();
            request("/harness/v1/capabilities",null,null,401);if(calls.get()!=0)throw new AssertionError("Unauthorized forwarded");
            if(!request("/v1/models",null,null,200).equals("legacy-route"))throw new AssertionError("Old route intercepted");
            if(!request("/harness/v1/runs",CLIENT,"{\"taskProfileId\":\"task\"}",202).contains("fixture"))throw new AssertionError("Proxy failed");
            if(!"fixture-key".equals(seenKey)||!seenBody.contains("taskProfileId"))throw new AssertionError("Body/idempotency lost");
            if(!request("/harness/v1/runs/run/events?after=1&limit=3",CLIENT,null,200).startsWith("id: 2"))throw new AssertionError("SSE replay lost");
            request("/harness/v1/runs/run/events?after=http://external",CLIENT,null,400);request("/harness/admin",CLIENT,null,404);
            System.out.println("PASS: real Jetty Harness gateway opt-in, separate authentication/backend, body/idempotency, SSE, bounded routes and legacy route coexistence");
        }finally{server.stop();backend.stop(0);}
    }
}
