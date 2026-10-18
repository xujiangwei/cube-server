package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import com.sun.net.httpserver.HttpServer;
import cube.service.harness.execution.ConsoleStatusAdapter;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Protocol fixture only. This does not certify a live Console or model. */
public final class ConsoleAdapterTest {
  static JSONObject args(String target, String... fields) {
    return new JSONObject()
        .put("targets", new JSONArray().put(target))
        .put("fields", new JSONArray(Arrays.asList(fields)));
  }

  static Properties config(int port) {
    Properties p = new Properties();
    p.setProperty("broker.console.baseUrl", "http://127.0.0.1:" + port);
    p.setProperty("broker.console.cookie", "CubeConsoleToken=test-cookie-0123456789");
    p.setProperty("broker.console.qps", "10");
    p.setProperty("broker.console.targets", "node-a,node-b,host");
    for (String node : Arrays.asList("node-a", "node-b")) {
      String prefix = "broker.console.target." + node + ".";
      p.setProperty(prefix + "kind", "service");
      p.setProperty(prefix + "tag", node);
      p.setProperty(prefix + "path", "C:/test path/" + node);
      p.setProperty(prefix + "reportName", node);
    }
    p.setProperty("broker.console.target.host.kind", "console-host");
    return p;
  }

  public static void main(String[] args) throws Exception {
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger mode = new AtomicInteger(), requests = new AtomicInteger();
    AtomicBoolean validForm = new AtomicBoolean(true);
    fixture.createContext(
        "/",
        x -> {
          try {
            requests.incrementAndGet();
            if (!"CubeConsoleToken=test-cookie-0123456789"
                .equals(x.getRequestHeaders().getFirst("Cookie")))
              throw new AssertionError("cookie");
            JSONObject response;
            if (x.getRequestURI().getPath().equals("/service/status")) {
              String form =
                  URLDecoder.decode(
                      new String(
                          cube.service.harness.runtime.RuntimeSupport.read(
                              x.getRequestBody(), 4096),
                          "UTF-8"),
                      "UTF-8");
              validForm.set(
                  validForm.get()
                      && x.getRequestMethod().equals("POST")
                      && form.startsWith("tag=node-")
                      && form.contains("&path=C:/test path/node-"));
              if (mode.get() == 4) {
                x.sendResponseHeaders(401, -1);
                return;
              }
              if (mode.get() == 5) {
                try {
                  Thread.sleep(350);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              String node = form.contains("node-b") ? "node-b" : "node-a";
              if (node.equals("node-b")) {
                x.sendResponseHeaders(404, -1);
                return;
              }
              response =
                  new JSONObject()
                      .put("tag", node)
                      .put("deployPath", "C:/test path/" + node)
                      .put("running", true)
                      .put("version", "3.0")
                      .put("storage", new JSONObject().put("password", "never-deliver"))
                      .put("cache", "never-deliver");
            } else if (x.getRequestURI().getPath().equals("/server-report")) {
              long timestamp =
                  mode.get() == 1
                      ? System.currentTimeMillis() - 300000
                      : mode.get() == 2 ? 0 : System.currentTimeMillis();
              response =
                  new JSONObject()
                      .put("name", "node-a")
                      .put(
                          "list",
                          mode.get() == 3
                              ? new JSONArray()
                              : new JSONArray()
                                  .put(
                                      new JSONObject()
                                          .put("timestamp", timestamp)
                                          .put("maxMemory", 1024)
                                          .put("totalMemory", 512)
                                          .put("freeMemory", 128)));
            } else
              response =
                  new JSONObject()
                      .put("cpu", new JSONObject().put("usage", 0.25))
                      .put(
                          "memory",
                          new JSONObject()
                              .put("totalBytes", 1073741824)
                              .put("usedBytes", 268435456));
            byte[] body = bytes(response.toString());
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
          } finally {
            x.close();
          }
        });
    fixture.start();
    try {
      for (int i = 0; i < 4; i++)
        try (ConsoleStatusAdapter adapter =
            new ConsoleStatusAdapter(config(fixture.getAddress().getPort()))) {
          System.out.println("Console fixture report mode=" + i);
          mode.set(i);
          JSONObject out = adapter.query(args("node-a", "running", "version", "jvm")),
              node = out.getJSONArray("nodes").getJSONObject(0);
          check(validForm.get(), "status requires form tag/path");
          check(
              !out.toString().contains("never-deliver") && !out.toString().contains("storage"),
              "sensitive data leaked");
          check(
              node.getString("reportState")
                  .equals(new String[] {"fresh", "stale", "placeholder", "missing"}[i]),
              "report age semantics");
          if (i < 3)
            check(
                node.getJSONObject("jvm").getLong("usedMemoryMiB") == 384, "MiB double conversion");
          check(node.getLong("queriedAt") > 0 && node.has("source"), "source/time missing");
        }
      try (ConsoleStatusAdapter adapter =
          new ConsoleStatusAdapter(config(fixture.getAddress().getPort()))) {
        System.out.println("Console fixture host");
        mode.set(0);
        JSONObject host =
            adapter
                .query(args("host", "host"))
                .getJSONArray("nodes")
                .getJSONObject(0)
                .getJSONObject("host");
        check(
            host.getString("scope").equals("console-host")
                && host.getInt("totalMemoryMiB") == 1024
                && host.getDouble("cpuPercent") == 25,
            "host units/scope");
        fail("CONSOLE_HOST_SCOPE_FORBIDDEN", () -> adapter.query(args("node-a", "host")));
        fail("BROKER_SCOPE_FORBIDDEN", () -> adapter.query(args("evil", "running")));
        invalid(() -> adapter.query(args("http://evil", "running")));
        System.out.println("Console fixture partial");
        JSONObject partial =
            adapter.query(
                new JSONObject()
                    .put("targets", new JSONArray().put("node-a").put("node-b"))
                    .put("fields", new JSONArray().put("running")));
        check(
            partial.getJSONArray("nodes").getJSONObject(0).getBoolean("running")
                && partial
                    .getJSONArray("nodes")
                    .getJSONObject(1)
                    .getString("errorCode")
                    .equals("CONSOLE_NODE_NOT_FOUND"),
            "partial failure lost");
      }
      try (ConsoleStatusAdapter adapter =
          new ConsoleStatusAdapter(config(fixture.getAddress().getPort()))) {
        mode.set(4);
        for (int i = 0; i < 4; i++)
          check(
              adapter.query(args("node-a", "running")).toString().contains("CONSOLE_UNAUTHORIZED"),
              "auth error");
        int n = requests.get();
        check(
            adapter.query(args("node-a", "running")).toString().contains("CONSOLE_CIRCUIT_OPEN")
                && n == requests.get(),
            "circuit sent request");
      }
      pass(
          "Console fixture: form/Cookie allowlist; projection; MiB/time/host meaning; fresh/stale/placeholder/missing; partial/401/circuit; malicious scope");
      Properties limited = config(fixture.getAddress().getPort());
      limited.setProperty("broker.console.qps", "1");
      try (ConsoleStatusAdapter adapter = new ConsoleStatusAdapter(limited)) {
        mode.set(0);
        adapter.query(args("node-a", "running"));
        check(
            adapter.query(args("node-a", "running")).toString().contains("CONSOLE_RATE_LIMITED"),
            "QPS bound missing");
      }
      limited.setProperty("broker.console.timeout.millis", "100");
      try (ConsoleStatusAdapter adapter = new ConsoleStatusAdapter(limited)) {
        mode.set(5);
        long start = System.nanoTime();
        check(
            adapter.query(args("node-a", "running")).toString().contains("CONSOLE_TIMEOUT"),
            "timeout not explicit");
        check(
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500,
            "deadline exceeded");
      }
      pass("Console fixture: bounded QPS and explicit total timeout");
    } finally {
      fixture.stop(0);
    }
  }
}
