package cube.monitor;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import cell.api.Nucleus;
import cell.core.talk.*;
import cube.core.*;
import cube.common.*;

/** Deterministic clock tests plus the real Task executor bridge. Run on JDK 8. */
public final class ServiceMetricsTest {
    private static int checks;
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); ++checks; }
    private static JSONObject total(ServiceMetrics m) { return m.snapshot().getJSONObject("total"); }
    public static void main(String[] args) throws Exception {
        AtomicLong time = new AtomicLong(); ServiceMetrics m = new ServiceMetrics(time::get, () -> 1000);
        ServiceMetrics.Sample a = m.submit("M", "A", false);
        check(m.snapshot().getInt("queued") == 1, "queued before start");
        time.set(3_000_000); m.start(a); m.start(a);
        check(m.snapshot().getInt("running") == 1 && m.snapshot().getInt("queued") == 0, "start once");
        time.set(27_000_000); m.complete(a,"succeeded"); m.complete(a,"failed");
        check(total(m).getInt("completed") == 1 && total(m).getInt("succeeded") == 1, "terminal exactly once");
        check(total(m).getDouble("avgMs") == 27 && total(m).getDouble("queueAvgMs") == 3 && total(m).getDouble("executionAvgMs") == 24, "queue/execution/total");
        check(m.snapshot().getJSONObject("window").getInt("requests") == 0, "open bucket excluded");
        time.set(10_000_000_000L); JSONObject w=m.snapshot().getJSONObject("window");
        check(w.getDouble("rps")==.1 && w.getInt("requests")==1,"RPS over elapsed completed bucket");
        for(String outcome:Arrays.asList("failed","timed_out","cancelled","rejected","accepted","unknown")) {
            ServiceMetrics.Sample s=m.submit("M","A",false); if(!outcome.equals("rejected"))m.start(s);m.complete(s,outcome);
        }
        time.set(20_000_000_000L); w=m.snapshot().getJSONObject("window");
        check(w.getInt("completed")==6 && w.getInt("rejected")==1 && w.getInt("knownCompleted")==4, "outcome denominators");
        check(w.getDouble("failureRate")==50 && m.snapshot().getInt("running")==0,"failed+timeout over known terminal");
        ServiceMetrics.Sample stream=m.submit("M","stream",false);m.start(stream);m.streaming(stream);
        time.addAndGet(5_000_000);m.first(stream);m.first(stream);time.addAndGet(20_000_000_000L);m.complete(stream,"succeeded");
        JSONObject t=total(m);check(t.getInt("streamCount")==1 && t.getDouble("firstByteAvgMs")==5,"stream first response once");
        check(t.getDouble("streamAvgMs")==20005 && t.getDouble("avgMs")<100,"stream excluded from ordinary average");
        ServiceMetrics.Sample control=m.submit("Harness","worker-message",true);m.start(control);m.complete(control,"unknown");
        check(total(m).getInt("requests")==8 && m.snapshot().getJSONObject("control").getJSONObject("total").getInt("requests")==1,"control separate from business");
        check(ServiceMetrics.control("latency")&&!ServiceMetrics.control("status")&&!ServiceMetrics.control("register"),"business names not classified as protocol");
        m.submit("a\nb","c\nd",false);check(m.snapshot().getJSONArray("actions").toString().contains("a_b"),"bounded safe keys");
        time.set(2_000_000_000_000L);check(m.snapshot().getJSONArray("series").length()==180,"30 minute retention");
        check(m.snapshot().getJSONObject("window").getInt("requests")==0&&total(m).getInt("requests")==9,"window expiry retains lifetime");
        check(new ServiceMetrics().snapshot().getJSONObject("total").getInt("requests")==0,"JVM restart resets counters");
        AtomicLong c=new AtomicLong(); ServiceMetrics p=new ServiceMetrics(c::get,()->0);
        for(int i=0;i<100;i++){ServiceMetrics.Sample s=p.submit("M","fast",false);p.start(s);c.addAndGet(1_000_000);p.complete(s,"succeeded");}
        c.set(10_000_000_000L);
        for(int i=0;i<5;i++){ServiceMetrics.Sample s=p.submit("M","slow",false);p.start(s);c.addAndGet(100_000_000);p.complete(s,"succeeded");}
        c.set(20_000_000_000L);check(p.snapshot().getJSONObject("window").getDouble("p95Ms")==1,"P95 weighted samples across buckets");
        ServiceMetrics.Sample slow=p.submit("M","slow",false);p.start(slow);c.addAndGet(100_000_000);p.complete(slow,"succeeded");c.set(30_000_000_000L);
        check(p.snapshot().getJSONObject("window").getDouble("p95Ms")==100,"P95 rank transitions");
        ServiceMetrics.Sample queued=p.submit("M","long",false);c.set(100_000_000_000L);
        check(p.snapshot().getJSONObject("window").getInt("queuedPeak")==1,"gauge spans quiet skipped buckets");p.complete(queued,"cancelled");
        LifecycleMetrics jobs=new LifecycleMetrics();jobs.observe("one","queued",1);jobs.observe("one","running",2);jobs.observe("one","waiting_approval",3);jobs.observe("one","running",2);jobs.observe("one","succeeded",4);jobs.observe("one","succeeded",4);
        check(jobs.snapshot().getJSONObject("total").getInt("succeeded")==1&&jobs.snapshot().getInt("running")==0,"committed state idempotency and ordered terminal");
        jobs.observe("recovered","running",4);check(jobs.snapshot().getLong("recovered")==1,"recovered observation explicit");jobs.observe("recovered","cancelled",5);
        JSONArray workers=new JSONArray().put(worker("a")).put(worker("b"));m.rememberWorkers(workers,Collections.emptySet());
        JSONArray disconnected=m.rememberWorkers(new JSONArray(),Collections.emptySet());check(disconnected.length()==2&&!disconnected.getJSONObject(0).getBoolean("connected"),"same role workers retained disconnected");
        JSONArray unknown=m.rememberWorkers(new JSONArray(),Collections.singleton("Harness"));check(unknown.getJSONObject(0).isNull("connected"),"provider error is unknown, not disconnected");
        check(m.rememberWorkers(workers,Collections.emptySet()).getJSONObject(0).getBoolean("connected"),"worker reconnect");
        FakeContext old=new FakeContext(5000);cell.core.talk.dialect.ActionDialect d=new Packet("test",new JSONObject()).toDialect();d.addParam("_performer",new JSONObject().put("node","dispatcher"));
        m.observePeer(old,d);old.valid=false;FakeContext replacement=new FakeContext(6000);m.observePeer(replacement,d);
        check(m.upstreams().length()==1&&m.upstreams().getJSONObject(0).getBoolean("ready")&&m.upstreams().getJSONObject(0).getString("address").endsWith("6000"),"upstream reconnect replaces session");
        time.addAndGet(1_800_001_000_000L);check(m.upstreams().length()==0&&m.rememberWorkers(new JSONArray(),Collections.emptySet()).length()==0,"topology retention expiry");
        testTasks();System.out.println("ServiceMetricsTest: "+checks+" checks passed");
    }
    private static JSONObject worker(String id){return new JSONObject().put("module","Harness").put("workerId",id).put("instanceId","i").put("role","model").put("ready",true);}
    static class FakeContext extends TalkContext {boolean valid=true;int port;FakeContext(int p){super(null,null);port=p;}@Override public boolean isValid(){return valid;}@Override public String getSessionHost(){return "127.0.0.1";}@Override public int getSessionPort(){return port;}}
    private static void testTasks() throws Exception {
        Nucleus testNucleus=new Nucleus(); Kernel kernel=new Kernel(testNucleus);testNucleus.setParameter("kernel",kernel);
        AbstractCellet cellet=new AbstractCellet("Test"){@Override public Nucleus getNucleus(){return testNucleus;}@Override public boolean install(){return true;}@Override public void uninstall(){}};
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Task deferred=new Task(cellet,null,new Packet("async",new JSONObject()).toDialect()){@Override public void run(){deferMonitoring(false);monitoringResult("accepted");}};
            deferred.executeObserved(pool);pool.submit(()->{}).get();
            check(kernel.getServiceMetrics().snapshot().getInt("running")==1,"async return not completion");
            deferred.finishMonitoring("succeeded");deferred.finishMonitoring("failed");check(total(kernel.getServiceMetrics()).getInt("succeeded")==1,"callback completion once");
            Task error=new Task(cellet,null,new Packet("error",new JSONObject()).toDialect()){@Override public void run(){throw new IllegalStateException("expected");}};
            // Direct executor runs on caller; retain the production exception contract.
            ExecutorService direct=new java.util.concurrent.AbstractExecutorService(){public void shutdown(){}public java.util.List<Runnable> shutdownNow(){return Collections.emptyList();}public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}public boolean awaitTermination(long t,TimeUnit u){return true;}public void execute(Runnable r){r.run();}};
            try{error.executeObserved(direct);}catch(IllegalStateException expected){}
            check(total(kernel.getServiceMetrics()).getInt("failed")==1&&kernel.getServiceMetrics().snapshot().getInt("running")==0,"exception recovers concurrency");
            pool.shutdown();try{deferred.executeObserved(pool);}catch(RejectedExecutionException expected){}
            check(total(kernel.getServiceMetrics()).getInt("rejected")==1&&kernel.getServiceMetrics().snapshot().getInt("queued")==0,"executor rejection recovered");
        }finally{pool.shutdownNow();testNucleus.destroy();}
    }
}
