package cube.console.test;

import cube.console.Console;
import cube.console.container.Handlers;
import cube.console.mgmt.ServiceManager;
import cube.report.JVMReport;
import cube.report.LogLine;
import cube.report.LogReport;
import cube.report.PerformanceReport;
import cube.report.ReportService;
import cube.report.UnitReport;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises reporting through the production HTTP handlers and asynchronous sender. */
public class ReportSubmissionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "http".equals(args[0])) http();
        if (args.length == 0 || "retry".equals(args[0])) retry();
        System.out.println("ReportSubmissionTest: " + checks + " checks passed");
    }

    private static void http() throws Exception {
        Console console = new Console();
        Server server = new Server();
        LocalConnector local = new LocalConnector(server);
        ServerConnector network = new ServerConnector(server);
        network.setHost("127.0.0.1");
        network.setPort(0);
        server.addConnector(local);
        server.addConnector(network);
        server.setHandler(Handlers.createHandlerList(server, console));
        server.start();
        try {
            String reporter = "report-test#service#6000";
            long now = System.currentTimeMillis();
            JVMReport jvm = new JVMReport(reporter, now);
            jvm.totalMemory = 128 * 1048576L;
            jvm.freeMemory = 32 * 1048576L;
            for (String path : new String[] {"/report", "/report/"}) {
                check(post(local, path, jvm.toJSON()).startsWith("HTTP/1.1 200"), path + " accepts without redirect");
            }
            check(console.queryJVMReport(reporter, 1, now).get(0).totalMemory == 128, "received JVM data is queryable in MB");
            PerformanceReport performance = new PerformanceReport(reporter, now);
            check(post(local, "/report", performance.toJSON()).startsWith("HTTP/1.1 200"), "performance accepted");
            check(console.queryLastPerformanceReport(reporter) != null, "performance stored");
            String query = "GET /server-report?report=PerfReport&time=0&name=" + URLEncoder.encode(reporter, "UTF-8")
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            check(local.getResponse(query).contains("\"report\":"), "received performance returned by HTTP query");
            LogReport log = new LogReport(reporter);
            log.addLogs(Arrays.asList(new LogLine(1, "report-test", "报告接收验证", now)));
            check(post(local, "/report", log.toJSON()).startsWith("HTTP/1.1 200"), "log accepted");
            check("报告接收验证".equals(console.queryLogs(reporter, 0, 10).get(0).text), "UTF-8 log content preserved");
            check(post(local, "/report", new UnitReport(reporter).toJSON()).startsWith("HTTP/1.1 200"), "empty unit snapshot accepted");

            ReportService sender = sender(network.getLocalPort());
            JVMReport sent = new JVMReport("async#dispatcher#7000", now);
            sender.submitReport(sent);
            idle(sender);
            check(console.queryJVMReport(sent.getReporter(), 1, now).get(0).totalMemory > 0, "real sender reaches full console handler chain");

            JSONObject malformed = new JSONObject().put("name", PerformanceReport.NAME)
                    .put("reporter", reporter).put("timestamp", now);
            check(post(local, "/report", malformed).startsWith("HTTP/1.1 400"), "malformed performance must not acknowledge success");
            check(post(local, "/report", new JSONObject().put("name", "UnknownReport")
                    .put("reporter", reporter).put("timestamp", now)).startsWith("HTTP/1.1 400"), "unknown report rejected");
            check(post(local, "/report", new JSONObject(jvm.toJSON().toString()).put("reporter", " "))
                    .startsWith("HTTP/1.1 400"), "blank reporter rejected");
            JSONObject missingMemory = new JSONObject(jvm.toJSON().toString());
            missingMemory.remove("totalMemory");
            check(post(local, "/report", missingMemory).startsWith("HTTP/1.1 400"), "incomplete JVM report rejected");
            check(console.queryJVMReport(reporter, 1, now).get(0).totalMemory == 128, "invalid JVM cannot overwrite valid metrics");
            JSONObject missingLines = new JSONObject(log.toJSON().toString());
            missingLines.remove("lines");
            check(post(local, "/report", missingLines).startsWith("HTTP/1.1 400"), "incomplete log rejected");
            check(post(local, "/report", new JSONObject().put("name", UnitReport.NAME).put("reporter", reporter)
                    .put("timestamp", now).put("units", "invalid")).startsWith("HTTP/1.1 400"), "invalid unit snapshot rejected");
            check(local.getResponse("GET /report HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .startsWith("HTTP/1.1 405"), "report endpoint requires POST");

            // A service may report before its deployment is loaded by the console.
            Set<String> knownNames = new HashSet<>();
            knownNames.add("console#service#6010");
            Field manager = Console.class.getDeclaredField("serviceManager");
            manager.setAccessible(true);
            manager.set(console, new ServiceManager("console") {
                @Override public Set<String> listServerNames() { return new HashSet<>(knownNames); }
            });
            JVMReport early = new JVMReport("remote#service#6000", now);
            early.totalMemory = 256 * 1048576L;
            check(post(local, "/report", early.toJSON()).startsWith("HTTP/1.1 200"), "early node report accepted");
            knownNames.add("console#service#6000");
            check(post(local, "/report", early.toJSON()).startsWith("HTTP/1.1 200"), "report after deployment discovery accepted");
            check(console.queryJVMReport("console#service#6000", 1, now).get(0).totalMemory == 256,
                    "unresolved early node name must not permanently hide later reports");
        } finally {
            server.stop();
            server.join();
        }
    }

    private static void retry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean available = new AtomicBoolean();
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new AbstractHandler() {
            @Override public void handle(String target, Request request, HttpServletRequest input,
                                         HttpServletResponse response) throws IOException {
                while (input.getInputStream().read() != -1) { }
                requests.incrementAndGet();
                response.setStatus(available.get() ? 200 : 503);
                request.setHandled(true);
            }
        });
        server.start();
        try {
            ReportService sender = sender(connector.getLocalPort());
            sender.submitReport(new JVMReport("retry#service#6000", System.currentTimeMillis()));
            idle(sender);
            check(requests.get() == 1, "failed reports wait for the next submission instead of immediate retries");
            check(sender.getQueueLength() == 1, "failed report retained for retry");
            available.set(true);
            sender.submitReport(new JVMReport("retry#service#6000", System.currentTimeMillis()));
            idle(sender);
            check(requests.get() == 3, "recovery delivers retained report and new report");
            check(sender.getQueueLength() == 0, "queue drained after recovery");
        } finally {
            server.stop();
            server.join();
        }
    }

    private static ReportService sender(int port) throws Exception {
        Constructor<ReportService> constructor = ReportService.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ReportService sender = constructor.newInstance();
        sender.addHost("127.0.0.1", port);
        return sender;
    }

    private static void idle(ReportService sender) throws Exception {
        Field field = ReportService.class.getDeclaredField("running");
        field.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) field.get(sender);
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (running.get() && System.nanoTime() < deadline) Thread.sleep(10);
        check(!running.get(), "submission completes within timeout");
    }

    private static String post(LocalConnector connector, String path, JSONObject json) throws Exception {
        String body = json.toString();
        String request = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json;charset=UTF-8\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
        return StandardCharsets.UTF_8.decode(connector.getResponse(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)))).toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        ++checks;
    }
}
