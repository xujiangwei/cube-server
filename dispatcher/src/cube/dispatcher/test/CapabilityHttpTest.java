package cube.dispatcher.test;

import cell.api.Nucleus;
import cell.core.talk.dialect.ActionDialect;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.Contact;
import cube.common.state.AIGCStateCode;
import cube.dispatcher.Performer;
import cube.dispatcher.aigc.Manager;
import cube.dispatcher.aigc.handler.AutomaticSpeechRecognition;
import cube.dispatcher.aigc.handler.Channel;
import cube.dispatcher.aigc.handler.Chat;
import cube.util.HttpServer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Actual REST handlers must report capability failures as 503 after authentication. */
public final class CapabilityHttpTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Performer performer = new Performer(new Nucleus()) {
            final HttpServer http = new HttpServer();
            @Override public HttpServer getHttpServer() { return http; }
            @Override public ActionDialect syncTransmit(String cellet, ActionDialect request) {
                if (!AIGCAction.CheckToken.name.equals(request.getName())) return super.syncTransmit(cellet, request);
                long now = System.currentTimeMillis();
                JSONObject data = new JSONObject().put("token", new AuthToken(TOKEN, "test", "app", 42L,
                        now, now + 3600000, false).toJSON()).put("contact", new Contact(42L, "test", "Tester").toJSON());
                return new Packet(request.getParamAsLong("sn"), request.getName(),
                        new JSONObject().put("code", 0).put("data", data)).toDialect();
            }
        };
        Manager manager = Manager.getInstance();
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1"); connector.setPort(0); server.addConnector(connector);
        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.setHandlers(new Handler[]{new AutomaticSpeechRecognition(), new Channel(), new Chat()});
        server.setHandler(handlers);
        try {
            manager.start(performer);
            server.start();
            String root = "http://127.0.0.1:" + connector.getLocalPort();
            expectUnavailable(root + "/aigc/speech/recognition/" + TOKEN, new JSONObject().put("fileCode", "sync").put("sync", true));
            expectUnavailable(root + "/aigc/speech/recognition/" + TOKEN, new JSONObject().put("fileCode", "async"));
            expectUnavailable(root + "/aigc/channel/" + TOKEN, new JSONObject().put("participant", "user"));
            expectUnavailable(root + "/aigc/chat/" + TOKEN, new JSONObject().put("channel", "unknown").put("content", "hello"));
            System.out.println("CapabilityHttpTest: " + assertions + " assertions passed");
        } finally {
            server.stop(); manager.stop();
        }
    }

    private static void expectUnavailable(String url, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(payload); }
            if (connection.getResponseCode() != 503) throw new AssertionError("Expected 503 for " + url + ": " + connection.getResponseCode());
            assertions++;
            try (InputStream input = connection.getErrorStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                JSONObject error = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)).getJSONObject("error");
                if (error.getInt("code") != AIGCStateCode.UnitNoReady.code) throw new AssertionError("Missing capability error code");
                assertions++;
            }
        } finally { connection.disconnect(); }
    }
}
