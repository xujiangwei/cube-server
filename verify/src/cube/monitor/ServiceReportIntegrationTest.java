package cube.monitor;
import cell.api.Nucleus;
import cube.core.*;
import cube.common.*;
import cube.service.Daemon;
import cube.report.ReportService;
import cube.console.Console;
import cube.console.container.Handlers;
import org.eclipse.jetty.server.*;
import org.json.*;
import java.net.*;
import java.util.concurrent.*;

/** Real Task -> service Daemon -> HTTP /report -> console /server-report. */
public final class ServiceReportIntegrationTest {
 public static void main(String[] args) throws Exception {
  Nucleus testNucleus=new Nucleus();Kernel kernel=new Kernel(testNucleus);kernel.setNodeName("integration#service#7000");testNucleus.setParameter("kernel",kernel);
  Console console=new Console();Server receiver=new Server();ServerConnector connector=new ServerConnector(receiver);connector.setHost("127.0.0.1");connector.setPort(0);receiver.addConnector(connector);receiver.setHandler(Handlers.createHandlerList(receiver,console));
  AbstractCellet cellet=new AbstractCellet("Integration"){@Override public Nucleus getNucleus(){return testNucleus;}@Override public boolean install(){return true;}@Override public void uninstall(){}};
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try {
   receiver.start();
   Task task=new Task(cellet,null,new Packet("business",new JSONObject()).toDialect()){@Override public void run(){monitoringResult("succeeded");}};task.executeObserved(pool);pool.submit(()->{}).get();
   ReportService.getInstance().addHost("127.0.0.1",connector.getLocalPort());new Daemon(kernel,testNucleus,"").submitPerformanceReport(System.currentTimeMillis());
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(console.queryLastPerformanceReport(kernel.getNodeName())==null&&System.nanoTime()<end)Thread.sleep(5);
   HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+connector.getLocalPort()+"/server-report?report=PerfReport&time=0&detail=true&name=integration%23service%237000").openConnection();c.setReadTimeout(5000);
   java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=c.getInputStream().read(bytes))!=-1)body.write(bytes,0,n);c.disconnect();
   JSONObject report=new JSONObject(body.toString("UTF-8")), snapshot=report.getJSONObject("report").getJSONObject("items").getJSONObject("Service");
   JSONObject total=snapshot.getJSONObject("execution").getJSONObject("total");
   if(total.getInt("requests")!=1||total.getInt("succeeded")!=1||snapshot.getJSONObject("execution").getInt("running")!=0||report.getLong("reportAgeMs")>30000||!snapshot.has("jvm")||!snapshot.has("topology"))throw new AssertionError(report.toString());
   System.out.println("ServiceReportIntegrationTest: Task -> report -> console query passed");
  }finally{pool.shutdownNow();receiver.stop();receiver.join();testNucleus.destroy();}
 }
}
