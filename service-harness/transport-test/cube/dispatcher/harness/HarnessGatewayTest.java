package cube.dispatcher.harness;

import cell.api.*;
import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.dispatcher.*;
import cube.util.HttpServer;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.Server;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real Jetty admission plus deterministic Performer failures and stream identity checks. */
public final class HarnessGatewayTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static final String TOKEN = "harness-api-fixture-123456789";
    static class TestScope extends Scope { TestScope(String cellet, int value) { cellets.add(cellet); weight = value; } }
    static class Speaker implements Speakable {
        boolean ready = true;
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public boolean isReady() { return ready; }
        public boolean speak(String c, Primitive p) { return true; }
        public boolean speak(String c, Primitive p, boolean f) { return true; }
        public boolean speakWithAck(String c, Primitive p) { return true; }
        public boolean speakWithoutAck(String c, Primitive p) { return true; }
        public PrimitiveOutputStream speakStream(String c, String n) { return null; }
    }
    static class Input extends PrimitiveInputStream {
        boolean closed;
        Input(String name) { super(name, null); }
        @Override public void close() { closed = true; }
    }
    static class Backend extends Performer {
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile boolean available = true;
        final Director harness, unrelated;
        Backend(Nucleus nucleus) {
            super(nucleus);
            harness = addDirector("127.0.0.1", 1, "127.0.0.1", 0, new TestScope("Harness", 2));
            unrelated = addDirector("127.0.0.1", 2, "127.0.0.1", 0, new TestScope("AIGC", 10));
            harness.speaker = new Speaker(); unrelated.speaker = new Speaker();
        }
        @Override public ActionDialect syncTransmit(Director node, String cellet, ActionDialect request, long timeout) {
            check(node == harness && cellet.equals("Harness"), "Wrong node/cellet");
            if (!available) return null;
            try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            JSONObject data = new Packet(request).data;
            check(data.getString("credential").equals(TOKEN), "Bearer credential was changed");
            check(!data.has("role"), "Dispatcher assigned a caller role");
            JSONObject body = new JSONObject().put("id", data.optString("id", ""));
            if (request.containsParam("body")) body.put("raw", new String(request.getParam("body"), StandardCharsets.UTF_8));
            return new Packet(new Packet(request).sn, request.getName(), new JSONObject().put("status", 200)
                    .put("body", body).put("headers", new JSONObject())).toDialect();
        }
    }
    static String get(String url, int expected) throws Exception {
        return get(url, expected, false);
    }
    static String get(String url, int expected, boolean sse) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setRequestProperty("Authorization", "Bearer " + TOKEN); c.setReadTimeout(12000);
        if (sse) c.setRequestProperty("Accept", "text/event-stream");
        int status = c.getResponseCode();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            byte[] bytes = new byte[4096]; int n; while (in != null && (n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        } finally { c.disconnect(); }
        check(status == expected, "HTTP status " + status + " expected " + expected);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Nucleus nucleus = new Nucleus(); Backend performer = new Backend(nucleus);
        check(performer.selectDirectorForCellet("Harness") == performer.harness, "Global weight leaked into Harness selection");
        ((Speaker) performer.harness.speaker).ready = false;
        check(performer.selectDirectorForCellet("Harness") == null, "Unready/other service fallback");
        ((Speaker) performer.harness.speaker).ready = true;
        HttpServer host = new HttpServer();
        HarnessGateway gateway = HarnessGateway.install(host, performer);
        check(host.getContextHandlers().contains(gateway) && gateway.getContextPath().equals("/harness"),
                "Harness endpoint not installed without configuration");
        Server server = new Server(new InetSocketAddress("127.0.0.1", 0)); server.setHandler(gateway);
        ExecutorService clients = Executors.newFixedThreadPool(16);
        try {
            server.start(); String url = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            check(new JSONObject(get(url + "/harness/v1/runs/first", 200)).getString("id").equals("first"), "Resource id lost");
            performer.available = false; get(url + "/harness/v1/capabilities", 503); performer.available = true;
            performer.gate = new CountDownLatch(1);
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final String id = "run-" + i;
                pending.add(clients.submit(() -> get(url + "/harness/v1/runs/" + id, 200)));
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gateway.inflight.availablePermits() != 0 && System.nanoTime() < end) Thread.sleep(10);
            check(gateway.inflight.availablePermits() == 0, "Did not fill bounded admission");
            get(url + "/harness/v1/capabilities", 503);
            performer.gate.countDown();
            for (int i = 0; i < pending.size(); i++) check(new JSONObject(pending.get(i).get()).getString("id").equals("run-" + i), "Concurrent replies crossed");
            HarnessGateway.Receiver receiver = new HarnessGateway.Receiver(performer.harness.speaker);
            gateway.receivers.put("owned", receiver);
            Input wrong = new Input("owned"); gateway.onReceivedStream(performer.unrelated.speaker, "Harness", wrong);
            check(wrong.closed && !receiver.input.isDone(), "Wrong connection acquired stream");
            Input correct = new Input("owned"); gateway.onReceivedStream(performer.harness.speaker, "Harness", correct);
            check(receiver.input.get() == correct, "Matching stream was not delivered");
            receiver.close(); gateway.receivers.remove("owned"); check(correct.closed, "Receiver close leaked stream");
            Input unknown = new Input("missing"); gateway.onReceivedStream(performer.harness.speaker, "Harness", unknown);
            check(unknown.closed, "Unknown stream leaked");
            performer.gate = new CountDownLatch(1);
            Future<String> slowA = clients.submit(() -> get(url + "/harness/v1/runs/slow/events", 503, true));
            Future<String> slowB = clients.submit(() -> get(url + "/harness/v1/runs/slow/events", 503, true));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (gateway.streams.availablePermits() != 0 && System.nanoTime() < end) Thread.sleep(10);
            check(gateway.streams.availablePermits() == 0, "SSE slots were not acquired");
            get(url + "/harness/v1/runs/slow/events", 429, true);
            slowA.get(3, TimeUnit.SECONDS); slowB.get(3, TimeUnit.SECONDS);
            performer.gate.countDown();
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (gateway.streams.availablePermits() != 2 && System.nanoTime() < end) Thread.sleep(10);
            check(gateway.streams.availablePermits() == 2, "Timed-out SSE slots leaked");
            System.out.println("PASS: Harness-only ready routing, direct syncTransmit, bounded queue recovery, concurrent ids, stream ownership, two SSE slots/deadline release, unconditional installation and unchanged Bearer");
        } finally { performer.gate.countDown(); clients.shutdownNow(); server.stop(); nucleus.destroy(); }
    }
}
