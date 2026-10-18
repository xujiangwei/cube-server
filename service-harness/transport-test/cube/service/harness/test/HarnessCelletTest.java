package cube.service.harness.test;

import cell.api.Nucleus;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.core.Kernel;
import cube.service.harness.HarnessCellet;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;

/** Actual Cell replies while the service business executor is full or stopped. */
public final class HarnessCelletTest {
    public static void main(String[] args) throws Exception {
        Path config = Files.createTempFile("harness-cellet-", ".properties");
        Properties p = new Properties();
        p.setProperty("mode", "development"); p.setProperty("http.port", "0");
        p.setProperty("worker.credential", "worker-private-cellet-fixture-1234");
        p.setProperty("api.credential", "api-private-cellet-fixture-1234567");
        try (OutputStream out = Files.newOutputStream(config)) { p.store(out, "Private test-only configuration"); }
        String previous = System.getProperty("harness.config");
        System.setProperty("harness.config", config.toString());
        Nucleus server = new Nucleus();
        Kernel kernel = new Kernel(server); server.setParameter("kernel", kernel);
        HarnessCellet cellet = new HarnessCellet();
        CountDownLatch gate = new CountDownLatch(1), running = new CountDownLatch(4);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try {
            server.getTalkService().startServer("127.0.0.1", port);
            HarnessSessionTest.check(server.getCelletService().installCellet(cellet), "Cellet installation failed");
            server.getCelletService().activateCellet(port, cellet);
            cellet.getService().start();
            try (HarnessSessionTest.Client client = new HarnessSessionTest.Client(port, p.getProperty("api.credential"))) {
                ThreadPoolExecutor executor = (ThreadPoolExecutor) cellet.getService().businessExecutor();
                for (int i = 0; i < 4; i++) executor.execute(() -> {
                    running.countDown();
                    try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                HarnessSessionTest.check(running.await(5, TimeUnit.SECONDS), "Business workers did not start");
                for (int i = 0; i < 32; i++) executor.execute(() -> {
                    try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 503);
                gate.countDown();
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 200);
                client.call(HarnessAction.DecideApproval, new JSONObject().put("role", "approver"), null, 403);
                client.call(HarnessAction.Trace, new JSONObject().put("role", "trace"), null, 403);
                client.call(HarnessAction.CreateRun, new JSONObject().put("key", "invalid-json").put("contentType", "application/json"),
                        "{\"taskProfileId\":\"a\",\"taskProfileId\":\"b\"}".getBytes("UTF-8"), 400);
                ActionDialect malformed = new Packet(HarnessAction.Capabilities.name, new JSONObject()).toDialect();
                long sn = malformed.getParamAsLong("sn");
                malformed.addParam("data", "malformed packet data");
                ActionDialect response = client.performer.syncTransmit(client.director, "Harness", malformed, 5000);
                HarnessSessionTest.check(response != null && new Packet(response).data.getInt("status") == 500
                        && new Packet(response).sn == sn, "Internal error lost request correlation");
                cellet.getService().stop();
                client.call(HarnessAction.Capabilities, new JSONObject(), null, 503);
            }
            System.out.println("PASS: real Cell correlated service queue-full, raw JSON validation, internal error and stopped-service replies");
        } finally {
            gate.countDown(); server.destroy();
            if (previous == null) System.clearProperty("harness.config"); else System.setProperty("harness.config", previous);
            Files.deleteIfExists(config);
        }
    }
}
