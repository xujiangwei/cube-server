package cube.monitor;
import cell.api.Nucleus;
import cell.core.talk.*;
import cube.core.Kernel;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.aigc.spi.*;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.spi.ActionRunner;
import org.json.*;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;

/** Exercise the actual SPI bridge, including terminal fragments and old modules. */
public final class ServiceActionMonitoringTest {
 private static int checks;
 private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);++checks;}
 public static void main(String[] args)throws Exception{
  Nucleus testNucleus=new Nucleus();Kernel kernel=new Kernel(testNucleus);testNucleus.setParameter("kernel",kernel);
  AIGCCellet cellet=new AIGCCellet(){@Override public Nucleus getNucleus(){return testNucleus;}@Override public boolean speak(TalkContext context,Primitive value){return true;}};
  ActionModule module=(ActionModule)Proxy.newProxyInstance(ActionModule.class.getClassLoader(),new Class[]{ActionModule.class},(p,m,a)->m.getName().equals("getName")?"Extension":null);
  ExecutorService pool=Executors.newSingleThreadExecutor();
  try{
   ActionRunner delayed=runner(cellet,module,"deferred",ctx->{ctx.deferResponse();return AIGCStateCode.Ok;},false);
   delayed.executeObserved(pool);pool.submit(()->{}).get();check(kernel.getServiceMetrics().snapshot().getInt("running")==1,"deferred return remains active");
   delayed.respondEmpty(AIGCStateCode.Ok);delayed.respondEmpty(AIGCStateCode.Failure);
   check(total(kernel).getInt("succeeded")==1&&total(kernel).getInt("completed")==1,"single deferred response and duplicate guard");
   ActionRunner stream=runner(cellet,module,"stream",ctx->{ctx.deferResponse();return AIGCStateCode.Ok;},true);
   stream.executeObserved(pool);pool.submit(()->{}).get();stream.respondEmpty(AIGCStateCode.Processing);stream.respondEmpty(AIGCStateCode.Ok);
   check(kernel.getServiceMetrics().snapshot().getInt("running")==1,"stream fragments do not finish observation");
   stream.completeResponse(AIGCStateCode.Ok);stream.completeResponse(AIGCStateCode.Failure);
   check(total(kernel).getInt("streamCount")==1&&total(kernel).getInt("succeeded")==2,"stream explicit terminal once");
   ActionRunner legacy=runner(cellet,module,"legacy",ctx->{ctx.respondEmpty(AIGCStateCode.Ok);return AIGCStateCode.Ok;},true);
   legacy.executeObserved(pool);pool.submit(()->{}).get();check(total(kernel).getInt("unknown")==1,"legacy stream unknown instead of premature success");
   ActionRunner broken=runner(cellet,module,"broken",ctx->{ctx.deferResponse();throw new IllegalStateException("expected");},true);
   broken.executeObserved(pool);pool.submit(()->{}).get();check(total(kernel).getInt("failed")==1&&kernel.getServiceMetrics().snapshot().getInt("running")==0,"deferred exception closes observation");
   ActionRunner cancelled=runner(cellet,module,"cancel",ctx->{ctx.deferResponse();return AIGCStateCode.Ok;},false);
   cancelled.executeObserved(pool);pool.submit(()->{}).get();cancelled.cancel();cancelled.respondEmpty(AIGCStateCode.Ok);
   check(total(kernel).getInt("cancelled")==1&&kernel.getServiceMetrics().snapshot().getInt("running")==0,"cancel terminal once");
   check(kernel.getServiceMetrics().snapshot().getJSONArray("modules").getJSONObject(0).getString("module").equals("Extension"),"extension registration name");
   System.out.println("ServiceActionMonitoringTest: "+checks+" checks passed");
  }finally{pool.shutdownNow();testNucleus.destroy();}
 }
 private static JSONObject total(Kernel kernel){return kernel.getServiceMetrics().snapshot().getJSONObject("total");}
 private static ActionRunner runner(AIGCCellet cellet,ActionModule module,String name,AIGCActionTask task,boolean stream){return new ActionRunner(cellet,null,new Packet(name,new JSONObject()).toDialect(),null,null,new ActionBinding(name,task,false,null,stream),module);}
}
