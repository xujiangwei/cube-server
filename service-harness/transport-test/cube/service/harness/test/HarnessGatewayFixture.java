package cube.service.harness.test;

import cell.api.Nucleus;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import cube.dispatcher.Scope;
import cube.dispatcher.harness.HarnessGateway;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Isolated actual Performer + Jetty host for transport tests and scripted Python smokes. */
public final class HarnessGatewayFixture {
    private static final class HarnessScope extends Scope {
        HarnessScope() { cellets.add("Harness"); }
    }

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) { p.load(in); }
        Nucleus nucleus = new Nucleus();
        Performer performer = new Performer(nucleus);
        nucleus.getTalkService().addListener(performer);
        String serviceHost = p.getProperty("service.host", "127.0.0.1");
        Director director = performer.addDirector(serviceHost, Integer.parseInt(p.getProperty("service.port")),
                "127.0.0.1", 0, new HarnessScope());
        director.speaker = nucleus.getTalkService().call(serviceHost, director.endpoint.getPort());
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1"); connector.setPort(Integer.parseInt(p.getProperty("http.port")));
        server.addConnector(connector);
        server.setHandler(new HarnessGateway(performer));
        try {
            server.start();
            System.out.println("HARNESS_DISPATCHER_READY");
            while (!Files.exists(Paths.get(args[1]))) Thread.sleep(100);
        } finally {
            server.stop();
            nucleus.destroy();
        }
    }
}
