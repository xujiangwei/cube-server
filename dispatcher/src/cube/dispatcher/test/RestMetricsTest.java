package cube.dispatcher.test;

import cell.api.Nucleus;
import cell.api.Speakable;
import cell.core.talk.dialect.ActionDialect;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import cube.dispatcher.Scope;
import cube.dispatcher.monitor.RestMetrics;
import cube.dispatcher.monitor.RestMetricsListener;
import cube.util.HttpServer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** JDK 8 regression tests: bounded aggregation and the production connector lifecycle. */
public final class RestMetricsTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        ++checks;
    }
    private static JSONObject total(RestMetrics metrics) { return metrics.snapshot().getJSONObject("total"); }
    private static void advance(AtomicLong clock, long ms) { clock.addAndGet(ms * 1_000_000L); }
    private static RestMetrics collector(AtomicLong clock) { return new RestMetrics(clock::get, () -> 100_000L); }

    public static void main(String[] args) throws Exception {
        aggregates(); routes(); http(); topology();
        System.out.println("RestMetricsTest: " + checks + " checks passed");
    }

    private static void aggregates() throws Exception {
        AtomicLong clock = new AtomicLong();
        RestMetrics metrics = collector(clock);
        check(total(metrics).isNull("avgMs"), "no sample has no latency");
        RestMetrics.Sample ordinary = metrics.begin("Auth", "/auth", "GET");
        advance(clock, 100); metrics.complete(ordinary, 200, false); metrics.complete(ordinary, 500, false);
        RestMetrics.Sample stream = metrics.begin("Harness", "/events", "GET");
        advance(clock, 25); metrics.firstByte(stream); advance(clock, 975); metrics.complete(stream, 200, true);
        RestMetrics.Sample failed = metrics.begin("Auth", "/auth", "GET");
        metrics.failure(failed); metrics.failure(failed); metrics.complete(failed, 503, false);
        JSONObject all = total(metrics);
        check(all.getInt("requests") == 3 && all.getInt("completed") == 3, "completion counted once");
        check(metrics.snapshot().getInt("inFlight") == 0, "concurrency reclaimed exactly once");
        check(all.getInt("failures") == 1 && all.getInt("transportFailures") == 1, "HTTP and transport failure deduplicate");
        check(all.getDouble("avgMs") == 50 && all.getInt("normalCount") == 2, "SSE excluded from ordinary latency");
        check(all.getDouble("streamAvgMs") == 1000 && all.getDouble("firstByteAvgMs") == 25, "SSE total and first body durations");
        check(all.getJSONObject("statusCodes").getInt("5xx") == 1, "status classes retained");
        clock.set(10_000_000_000L);
        JSONObject window = metrics.snapshot().getJSONObject("window");
        check(window.getDouble("rps") == .3 && metrics.snapshot().getInt("windowSeconds") == 10, "arrival RPS uses covered window");
        check(window.getDouble("failureRate") > 33 && window.getDouble("failureRate") < 34, "failure rate uses completions");
        clock.set(70_000_000_000L);
        check(metrics.snapshot().getJSONObject("window").getInt("requests") == 0, "one minute expires");
        check(total(metrics).getInt("requests") == 3, "lifetime survives window expiry");
        clock.set(1_810_000_000_000L);
        JSONArray series = metrics.snapshot().getJSONArray("series");
        check(series.length() == 180 && series.getJSONObject(0).getInt("requests") == 0, "30 minute retention evicts old bucket");
        check(total(collector(clock)).getInt("requests") == 0, "process restart resets cumulative counters");

        // One slow bucket must not receive the same weight as a bucket with 99 fast samples.
        clock.set(0); RestMetrics weighted = collector(clock);
        for (int i = 0; i < 99; i++) {
            RestMetrics.Sample s = weighted.begin("A", "/a", "GET"); advance(clock, 1); weighted.complete(s, 200, false);
        }
        clock.set(10_000_000_000L);
        RestMetrics.Sample slow = weighted.begin("A", "/a", "GET"); advance(clock, 1000); weighted.complete(slow, 404, false);
        clock.set(20_000_000_000L);
        check(weighted.snapshot().getJSONObject("window").getDouble("p95Ms") == 1, "P95 histogram merged by sample count");
        check(weighted.snapshot().getJSONObject("window").getInt("failures") == 1, "4xx counts as failure");
        RestMetrics.Sample active = weighted.begin("A", "/a", "GET");
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                weighted.complete(active, 200, false);
                for (int j = 0; j < 100; j++) weighted.complete(weighted.begin("A", "/a", "GET"), 200, false);
            }); threads[i].start();
        }
        start.countDown(); for (Thread thread : threads) thread.join();
        check(total(weighted).getInt("completed") == 901 && weighted.snapshot().getInt("inFlight") == 0, "concurrent exactly-once completion");
        weighted.sent("peer:7000", true); weighted.sent("peer:7000", false);
        check(weighted.downstream("peer:7000").getInt("totalCalls") == 2
                && weighted.downstream("peer:7000").getInt("totalSendFailures") == 1, "separate downstream counters");

        AtomicLong longClock = new AtomicLong(); RestMetrics longRequest = collector(longClock);
        RestMetrics.Sample pending = longRequest.begin("A", "/long", "POST");
        longClock.set(65_000_000_000L);
        check(longRequest.snapshot().getInt("inFlight") == 1, "long request stays concurrent across expired buckets");
        longRequest.complete(pending, 500, false); longClock.set(70_000_000_000L);
        JSONObject completedWindow = longRequest.snapshot().getJSONObject("window");
        check(completedWindow.getInt("requests") == 0 && completedWindow.getInt("completed") == 1,
                "arrival and completion use their own windows");
        check(completedWindow.getDouble("rps") == 0 && completedWindow.getDouble("failureRate") == 100,
                "RPS uses arrivals while failure rate uses completed requests");
    }

    private static ContextHandler context(String path, String module) {
        ContextHandler handler = new ContextHandler(path);
        handler.setAttribute(RestMetricsListener.MODULE_ATTRIBUTE, module); return handler;
    }
    private static void routes() {
        RestMetricsListener listener = new RestMetricsListener(new RestMetrics(), () -> Arrays.asList(
                context("/v1", "AIGC"), context("/harness", "Harness"), context("/custom", "ExtensionName"),
                context("/contact/get/", "Contact"), new ContextHandler()));
        check(listener.resolve("/v1/models/private-model")[1].equals("/v1/models/{id}"), "model ID normalized");
        check(listener.resolve("/harness/v1/runs/run-secret/events")[1].equals("/harness/v1/runs/{id}/events"), "Harness ID normalized");
        check(listener.resolve("/harness/v1/runs/r/approvals/a/decision")[1].equals("/harness/v1/runs/{id}/approvals/{id}/decision"), "multiple route captures normalized");
        check(listener.resolve("/contact/get/private-token")[1].equals("/contact/get"), "token suffix removed from key");
        check(listener.resolve("/custom/id")[0].equals("ExtensionName"), "registered extension owns module");
        check(listener.resolve("/random-secret")[1].equals("未匹配路由"), "unknown paths share bounded key");
        check(listener.resolve("/static/app.js") == null && listener.resolve("/doc/api") == null, "static and docs excluded");
    }

    private static void awaitCompleted(RestMetrics metrics, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (total(metrics).getInt("completed") < count && System.nanoTime() < deadline) Thread.sleep(5);
        check(total(metrics).getInt("completed") == count, "HTTP completion count " + count);
        check(metrics.snapshot().getInt("inFlight") == 0, "HTTP concurrency reclaimed " + count);
    }
    private static int get(int port, String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method); connection.setReadTimeout(5000);
        try {
            int status = connection.getResponseCode();
            if (status < 400) { while (connection.getInputStream().read() != -1) { } }
            return status;
        } finally { connection.disconnect(); }
    }
    private static void http() throws Exception {
        RestMetrics metrics = new RestMetrics();
        HttpServer http = new HttpServer();
        CountDownLatch asyncEntered = new CountDownLatch(1), asyncRelease = new CountDownLatch(1), abortRelease = new CountDownLatch(1);
        ContextHandler test = context("/test", "Test");
        test.setHandler(new AbstractHandler() {
            @Override public void handle(String target, Request base, HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
                base.setHandled(true);
                if (target.equals("/exception")) throw new IllegalStateException("expected test failure");
                if (target.equals("/error")) { response.sendError(422); return; }
                if (target.equals("/async") || target.equals("/sse") || target.equals("/abort")) {
                    AsyncContext async = request.startAsync(); async.setTimeout(5000);
                    async.start(() -> {
                        try {
                            if (target.equals("/async")) { asyncEntered.countDown(); asyncRelease.await(5, TimeUnit.SECONDS); }
                            if (!target.equals("/async")) response.setContentType("text/event-stream;charset=UTF-8");
                            response.getOutputStream().write("data: first\n\n".getBytes("UTF-8")); response.flushBuffer();
                            if (target.equals("/abort")) {
                                abortRelease.await(5, TimeUnit.SECONDS);
                                for (int i = 0; i < 64; i++) { response.getOutputStream().write(new byte[65536]); response.flushBuffer(); }
                            } else if (target.equals("/sse")) {
                                Thread.sleep(35); response.getOutputStream().write("data: last\n\n".getBytes("UTF-8"));
                            }
                        } catch (Exception expected) { /* The abort intentionally breaks the output transport. */ }
                        finally { async.complete(); }
                    });
                    return;
                }
                response.getWriter().write("ok");
            }
        });
        http.addContextHandler(test);
        http.setRequestListener(new RestMetricsListener(metrics, http::getContextHandlers));
        http.start(0);
        Field field = HttpServer.class.getDeclaredField("server"); field.setAccessible(true);
        Server server = (Server) field.get(http);
        check(server != null && server.isStarted(), "production HTTP container started");
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        try {
            check(get(port, "/test/sync?token=secret", "GET") == 200, "synchronous HTTP"); awaitCompleted(metrics, 1);
            check(get(port, "/test/error", "GET") == 422, "HTTP error"); awaitCompleted(metrics, 2);
            check(get(port, "/test/exception", "GET") == 500, "uncaught exception"); awaitCompleted(metrics, 3);
            Thread asyncClient = new Thread(() -> { try { get(port, "/test/async", "GET"); } catch (Exception e) { throw new AssertionError(e); } });
            asyncClient.start(); check(asyncEntered.await(5, TimeUnit.SECONDS), "async handler entered");
            check(metrics.snapshot().getInt("inFlight") == 1 && total(metrics).getInt("completed") == 3, "async remains concurrent after dispatch returns");
            asyncRelease.countDown(); asyncClient.join(5000); awaitCompleted(metrics, 4);
            check(get(port, "/test/sse", "GET") == 200, "multi-chunk SSE"); awaitCompleted(metrics, 5);
            JSONObject stats = total(metrics);
            check(stats.getInt("streamCount") == 1 && stats.getDouble("streamAvgMs") >= 30
                    && stats.getDouble("firstByteAvgMs") < stats.getDouble("streamAvgMs"), "SSE first body precedes total duration");
            Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /test/abort HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("UTF-8"));
            check(socket.getInputStream().read() >= 0, "stream connected before abort");
            socket.setSoLinger(true, 0); socket.close(); abortRelease.countDown(); awaitCompleted(metrics, 6);
            check(total(metrics).getInt("transportFailures") >= 1, "broken connection recorded");
            get(port, "/static/app.js", "GET"); get(port, "/doc/api", "GET"); get(port, "/test/sync", "OPTIONS");
            check(total(metrics).getInt("requests") == 6, "excluded requests not collected");
            check(get(port, "/unknown/private-id", "GET") == 404, "unknown route HTTP status"); awaitCompleted(metrics, 7);
            check(metrics.snapshot().getJSONArray("routes").length() == 2, "query and unknown IDs never become route keys");
        } finally { asyncRelease.countDown(); abortRelease.countDown(); http.stop(); }
    }

    private static final class TestScope extends Scope { TestScope() { cellets.add("Auth"); cellets.add("Contact"); } }
    private static void topology() throws Exception {
        Performer performer = new Performer(new Nucleus());
        Director a = performer.addDirector("127.0.0.1", 6100, "127.0.0.1", 6080, new TestScope());
        Director b = performer.addDirector("127.0.0.2", 6100, "127.0.0.2", 6080, new TestScope());
        AtomicBoolean ready = new AtomicBoolean(true);
        a.speaker = (Speakable) Proxy.newProxyInstance(Speakable.class.getClassLoader(), new Class<?>[] {Speakable.class}, (proxy, method, args) -> {
            if (method.getName().equals("isReady")) return ready.get();
            if (method.getName().equals("speak")) return true;
            throw new UnsupportedOperationException(method.getName());
        });
        JSONArray peers = performer.getDispatcherSnapshot().getJSONObject("topology").getJSONArray("services");
        check(peers.length() == 2 && peers.getJSONObject(0).getJSONArray("cellets").toString()
                .equals(peers.getJSONObject(1).getJSONArray("cellets").toString()), "same modules on multiple endpoints");
        check(peers.getJSONObject(0).getBoolean("ready") && !peers.getJSONObject(1).getBoolean("ready"), "live readiness per endpoint");
        check(performer.tryTransmit(a, "Auth", new ActionDialect("test")), "connected downstream send");
        check(!performer.tryTransmit(b, "Auth", new ActionDialect("test")), "disconnected downstream send fails");
        ready.set(false);
        peers = performer.getDispatcherSnapshot().getJSONObject("topology").getJSONArray("services");
        check(peers.length() == 2 && !peers.getJSONObject(0).getBoolean("ready"), "disconnect retains endpoint");
        check(peers.getJSONObject(0).getInt("totalCalls") == 1 && peers.getJSONObject(1).getInt("totalSendFailures") == 1, "downstream totals per endpoint");
        ready.set(true);
        check(performer.getDispatcherSnapshot().getJSONObject("topology").getJSONArray("services").getJSONObject(0).getBoolean("ready"), "reconnected endpoint recovers readiness");
        Thread pending = new Thread(() -> performer.syncTransmit(a, "Auth", new ActionDialect("pending"), 5000));
        pending.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (performer.getDispatcherSnapshot().getJSONObject("topology").getJSONArray("services").getJSONObject(0).getInt("totalCalls") < 2
                    && System.nanoTime() < deadline) Thread.sleep(5);
            check(pending.isAlive() && performer.getDispatcherSnapshot().getJSONObject("topology").getJSONArray("services").getJSONObject(0).getInt("totalCalls") == 2,
                    "sync downstream call counted when sent, before response completes");
        } finally { pending.interrupt(); pending.join(1000); }
    }
}
