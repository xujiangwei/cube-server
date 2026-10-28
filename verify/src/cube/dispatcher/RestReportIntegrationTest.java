package cube.dispatcher;

import cell.api.Nucleus;
import cube.console.Console;
import cube.console.container.Handlers;
import cube.dispatcher.monitor.RestMetricsListener;
import cube.report.ReportService;
import cube.util.HttpConfig;
import cube.util.HttpServer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/** Real HTTP + HTTPS -> performer snapshot -> report sender -> console -> REST query. */
public final class RestReportIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("cube-rest-report-");
        Path keyStore = directory.resolve("test.jks");
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path keytoolPath = javaHome.resolve("bin").resolve(executable);
        if (!Files.exists(keytoolPath)) keytoolPath = javaHome.getParent().resolve("bin").resolve(executable);
        Process keytool = new ProcessBuilder(keytoolPath.toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-keystore", keyStore.toString(), "-storepass", "test-only", "-keypass", "test-only")
                .redirectErrorStream(true).start();
        while (keytool.getInputStream().read() != -1) { }
        if (keytool.waitFor() != 0) throw new AssertionError("test keytool failed");
        int securePort;
        try (ServerSocket socket = new ServerSocket(0)) { securePort = socket.getLocalPort(); }
        Performer performer = new Performer(new Nucleus()) {
            @Override public String getNodeName() { return "integration#dispatcher#7000"; }
        };
        HttpConfig config = new HttpConfig(); config.httpPort = 0; config.httpsPort = securePort;
        config.keystore = keyStore.toString(); config.storePassword = "test-only"; config.managerPassword = "test-only";
        performer.configHttpServer(config);
        HttpServer http = performer.getHttpServer();
        ContextHandler endpoint = new ContextHandler("/integration");
        endpoint.setAttribute(RestMetricsListener.MODULE_ATTRIBUTE, "Integration");
        endpoint.setHandler(new AbstractHandler() {
            @Override public void handle(String target, Request base, HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
                base.setHandled(true); response.getWriter().write("observed");
            }
        });
        http.addContextHandler(endpoint);
        Console console = new Console(); Server receiver = new Server();
        ServerConnector connector = new ServerConnector(receiver); connector.setHost("127.0.0.1"); connector.setPort(0);
        receiver.addConnector(connector); receiver.setHandler(Handlers.createHandlerList(receiver, console));
        try {
            http.start(); receiver.start();
            Field serverField = HttpServer.class.getDeclaredField("server"); serverField.setAccessible(true);
            Server source = (Server) serverField.get(http);
            int plainPort = ((ServerConnector) source.getConnectors()[0]).getLocalPort();
            read((HttpURLConnection) new URL("http://127.0.0.1:" + plainPort + "/integration/private-token?secret=hidden").openConnection());
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                public void checkServerTrusted(X509Certificate[] chain, String auth) { }
            }}, null);
            HttpsURLConnection tls = (HttpsURLConnection) new URL("https://127.0.0.1:" + securePort + "/integration/another-id").openConnection();
            tls.setSSLSocketFactory(ssl.getSocketFactory()); tls.setHostnameVerifier((name, session) -> true); read(tls);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (performer.getDispatcherSnapshot().getJSONObject("http").getJSONObject("total").getInt("completed") < 2
                    && System.nanoTime() < deadline) Thread.sleep(5);
            ReportService sender = ReportService.getInstance(); sender.addHost("127.0.0.1", connector.getLocalPort());
            new Daemon(performer).submitPerformanceReport(1000);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (console.queryLastPerformanceReport(performer.getNodeName()) == null && System.nanoTime() < deadline) Thread.sleep(5);
            String response = read((HttpURLConnection) new URL("http://127.0.0.1:" + connector.getLocalPort()
                    + "/server-report?report=PerfReport&time=0&detail=true&name=integration%23dispatcher%237000").openConnection());
            JSONObject received = new JSONObject(response);
            JSONObject metrics = received.getJSONObject("report").getJSONObject("items").getJSONObject("Dispatcher").getJSONObject("http");
            if (metrics.getJSONObject("total").getInt("requests") != 2 || metrics.getJSONObject("total").getInt("completed") != 2
                    || metrics.getInt("inFlight") != 0 || metrics.getJSONArray("routes").length() != 1
                    || received.getLong("reportAgeMs") > 30_000 || metrics.toString().contains("private-token")) {
                throw new AssertionError("end-to-end counters, normalization or freshness: " + response);
            }
            System.out.println("RestReportIntegrationTest: HTTP + HTTPS -> report -> console query passed");
        } finally {
            http.stop(); receiver.stop(); receiver.join(); Files.deleteIfExists(keyStore); Files.deleteIfExists(directory);
        }
    }
    private static String read(HttpURLConnection connection) throws Exception {
        connection.setReadTimeout(5000); connection.setConnectTimeout(5000);
        try {
            if (connection.getResponseCode() != 200) throw new AssertionError("HTTP status " + connection.getResponseCode());
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int length;
            while ((length = connection.getInputStream().read(buffer)) != -1) body.write(buffer, 0, length);
            return body.toString("UTF-8");
        } finally { connection.disconnect(); }
    }
}
