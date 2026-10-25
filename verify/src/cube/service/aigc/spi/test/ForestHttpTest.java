package cube.service.aigc.spi.test;

import cell.api.Nucleus;
import cell.core.talk.dialect.ActionDialect;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.Contact;
import cube.dispatcher.Performer;
import cube.dispatcher.aigc.Manager;
import cube.service.forest.dispatcher.ForestEndpoints;
import cube.util.HttpServer;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Real HTTP/authentication/forwarding/error envelope with isolated backend responses. */
public final class ForestHttpTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static int code, http, assertions;
    static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); assertions++; }
    public static void main(String[] args) throws Exception {
        Performer performer = new Performer(new Nucleus()) {
            final HttpServer httpServer = new HttpServer();
            @Override public HttpServer getHttpServer() { return httpServer; }
            @Override public ActionDialect syncTransmit(String cellet, ActionDialect request) {
                if (!AIGCAction.CheckToken.name.equals(request.getName())) return super.syncTransmit(cellet, request);
                if (!TOKEN.equals(new Packet(request).data.optString("token"))) return null;
                long now = System.currentTimeMillis();
                JSONObject identity = new JSONObject().put("token", new AuthToken(TOKEN, "test", "app", 42L,
                        now, now + 3600000, false).toJSON()).put("contact", new Contact(42L, "test", "Tester").toJSON());
                return new Packet(request.getParamAsLong("sn"), request.getName(),
                        new JSONObject().put("code", 0).put("data", identity)).toDialect();
            }
            @Override public ActionDialect syncTransmit(String cellet, ActionDialect request, long timeout) {
                check("forest".equals(request.getParamAsString("moduleName")), "module route forwarded");
                check((request.getName().equals("forestDetect") ? "forest-detection" : "")
                        .equals(request.getParamAsString("moduleUnit")), "required unit forwarded");
                JSONObject result = code == 0 ? new JSONObject().put("accepted", true)
                        : new JSONObject().put("message", "isolated failure").put("httpStatus", http);
                return new Packet(request.getParamAsLong("sn"), request.getName(),
                        new JSONObject().put("code", code).put("data", result)).toDialect();
            }
        };
        Manager manager = Manager.getInstance();
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1"); connector.setPort(0); server.addConnector(connector);
        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.setHandlers(new ForestEndpoints().getEndpointHandlers().toArray(new Handler[0])); server.setHandler(handlers);
        try {
            manager.start(performer); server.start();
            String root = "http://127.0.0.1:" + connector.getLocalPort() + "/aigc/forest/";
            expect(root + "detect/" + TOKEN, "POST", "{}", 200, 0);
            expect(root + "capability/" + TOKEN, "GET", null, 200, 0);
            expect(root + "detect/" + TOKEN, "GET", null, 405, 1);
            expect(root + "capability/" + TOKEN, "POST", "{}", 405, 1);
            expect(root + "detect/" + TOKEN, "POST", "{broken", 400, 1);
            expect(root + "detect/" + TOKEN.replace('0', 'f'), "POST", "{}", 401, 21);
            code = 31; http = 504;
            expect(root + "detect/" + TOKEN, "POST", "{}", 504, 31);
            code = 7; http = 429;
            expect(root + "detect/" + TOKEN, "POST", "{}", 429, 7);
            System.out.println("ForestHttpTest: " + assertions + " assertions passed");
        } finally { server.stop(); manager.stop(); }
    }
    static void expect(String url, String method, String body, int status, int expectedCode) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setRequestMethod(method);
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            check(connection.getResponseCode() == status, "HTTP " + status + " at " + url);
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                JSONObject response = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                check(response.getInt("code") == expectedCode && response.getJSONObject("data") != null, "forest envelope");
            }
        } finally { connection.disconnect(); }
    }
}
