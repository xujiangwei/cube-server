package cube.service.harness.execution;

import com.sun.net.httpserver.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** JDK 8 HTTP facade for the execution listener. SSE uses Servlet 3.1 nonblocking writes. */
public final class JettyExchange extends HttpExchange {
    public interface Handler {void handle(HttpExchange exchange)throws IOException;}
    private final HttpServletRequest request;private final HttpServletResponse response;
    private final Headers inputHeaders=new Headers(),outputHeaders=new Headers();
    private final InputStream input;private final ServletOutputStream output;
    private JettyExchange(HttpServletRequest request,HttpServletResponse response)throws IOException {
        this.request=request;this.response=response;input=request.getInputStream();output=response.getOutputStream();
        Enumeration<String> names=request.getHeaderNames();while(names.hasMoreElements()){String name=names.nextElement();inputHeaders.put(name,Collections.list(request.getHeaders(name)));}
    }
    public static Server start(String host,int port,Handler handler)throws Exception {
        QueuedThreadPool threads=new QueuedThreadPool(16,4,60000,new ArrayBlockingQueue<Runnable>(64));threads.setName("harness-http");threads.setDaemon(true);
        Server server=new Server(threads);ServerConnector connector=new ServerConnector(server,1,1);connector.setHost(host);connector.setPort(port);connector.setIdleTimeout(5000);server.addConnector(connector);
        ContextHandler context=new ContextHandler("/");context.setHandler(new AbstractHandler(){public void handle(String target,Request base,HttpServletRequest req,HttpServletResponse res)throws IOException{base.setHandled(true);handler.handle(new JettyExchange(req,res));}});server.setHandler(context);server.setStopTimeout(2000);server.start();return server;
    }
    private void headers(){for(Map.Entry<String,List<String>> e:outputHeaders.entrySet())for(String v:e.getValue())response.addHeader(e.getKey(),v);}
    public void stream(JSONObject page,Runnable release) throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream();data.write("retry: 500\n\n".getBytes(StandardCharsets.UTF_8));JSONArray list=page.getJSONArray("events");
        for(int i=0;i<list.length();i++){JSONObject e=list.getJSONObject(i);byte[] frame=("id: "+e.getLong("eventSeq")+"\nevent: "+e.getString("type")+"\ndata: "+e.toString()+"\n\n").getBytes(StandardCharsets.UTF_8);if(data.size()+frame.length>8*1024*1024)throw new IllegalArgumentException("EVENT_PAGE_TOO_LARGE");data.write(frame);}
        data.write(": replay-page-complete\n\n".getBytes(StandardCharsets.UTF_8));byte[] bytes=data.toByteArray();
        response.setStatus(200);response.setContentType("text/event-stream; charset=utf-8");response.setHeader("Cache-Control","no-store");
        AsyncContext async=request.startAsync();async.setTimeout(2000);AtomicBoolean released=new AtomicBoolean();
        Runnable done=()->{if(released.compareAndSet(false,true))release.run();};
        async.addListener(new AsyncListener(){public void onComplete(AsyncEvent e){done.run();}public void onTimeout(AsyncEvent e){done.run();try{async.complete();}catch(IllegalStateException ignored){}}public void onError(AsyncEvent e){done.run();try{async.complete();}catch(IllegalStateException ignored){}}public void onStartAsync(AsyncEvent e){}});
        output.setWriteListener(new WriteListener(){int offset;public void onWritePossible()throws IOException{while(output.isReady()&&offset<bytes.length){int n=Math.min(4096,bytes.length-offset);output.write(bytes,offset,n);offset+=n;}if(offset==bytes.length)async.complete();}public void onError(Throwable e){done.run();try{async.complete();}catch(IllegalStateException ignored){}}});
    }
    @Override public Headers getRequestHeaders(){return inputHeaders;}
    @Override public Headers getResponseHeaders(){return outputHeaders;}
    @Override public URI getRequestURI(){return URI.create(request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()));}
    @Override public String getRequestMethod(){return request.getMethod();}
    @Override public HttpContext getHttpContext(){throw new UnsupportedOperationException();}
    @Override public void close(){try{if(request.isAsyncStarted())request.getAsyncContext().complete();else output.close();}catch(IOException|IllegalStateException ignored){}}
    @Override public InputStream getRequestBody(){return input;}
    @Override public OutputStream getResponseBody(){return output;}
    @Override public void sendResponseHeaders(int status,long length){headers();response.setStatus(status);if(length>0)response.setContentLengthLong(length);}
    @Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress(request.getRemoteAddr(),request.getRemotePort());}
    @Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress(request.getLocalAddr(),request.getLocalPort());}
    @Override public String getProtocol(){return request.getProtocol();}
    @Override public int getResponseCode(){return response.getStatus();}
    @Override public Object getAttribute(String name){return request.getAttribute(name);}
    @Override public void setAttribute(String name,Object value){request.setAttribute(name,value);}
    @Override public void setStreams(InputStream in,OutputStream out){throw new UnsupportedOperationException();}
    @Override public HttpPrincipal getPrincipal(){return null;}
}
