package cube.service.harness.execution;

import cube.service.harness.protocol.*;
import cube.service.harness.runtime.RuntimeSupport;
import cube.service.harness.store.StoreException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Console-only allowlisted read adapter. No deploy path, URL or credential is model selectable. */
public final class ConsoleStatusAdapter implements AutoCloseable {
  private final java.util.concurrent.ScheduledExecutorService deadlines =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "console-deadline");
            t.setDaemon(true);
            return t;
          });

  @Override
  public void close() {
    deadlines.shutdownNow();
  }

  private final URI base;
  private final String cookie;
  private final Properties config;
  private final Set<String> targets = new TreeSet<>();
  private final long maxAge;
  private final int timeout, qps;
  private long window, openedUntil;
  private int count, failures;

  public ConsoleStatusAdapter(Properties p) throws Exception {
    config = p;
    base = new URI(p.getProperty("broker.console.baseUrl", ""));
    if (!Arrays.asList("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null)
      throw new IllegalArgumentException("INVALID_CONSOLE_ENDPOINT");
    cookie = p.getProperty("broker.console.cookie", "");
    if (!cookie.matches("CubeConsoleToken=[A-Za-z0-9_.:-]{16,512}"))
      throw new IllegalArgumentException("CONSOLE_COOKIE_REQUIRED");
    for (String target : p.getProperty("broker.console.targets", "").split(","))
      if (!target.isEmpty()) {
        RuntimeSupport.id(target);
        String kind = p.getProperty("broker.console.target." + target + ".kind", "");
        if (!Arrays.asList("service", "dispatcher", "console-host").contains(kind))
          throw new IllegalArgumentException("CONSOLE_TARGET_KIND_REQUIRED");
        if (!kind.equals("console-host")
            && (p.getProperty("broker.console.target." + target + ".tag", "").isEmpty()
                || p.getProperty("broker.console.target." + target + ".path", "").isEmpty()
                || p.getProperty("broker.console.target." + target + ".reportName", "").isEmpty()))
          throw new IllegalArgumentException("CONSOLE_TARGET_MAPPING_REQUIRED");
        targets.add(target);
      }
    if (targets.isEmpty()) throw new IllegalArgumentException("CONSOLE_SCOPE_REQUIRED");
    timeout = Integer.parseInt(p.getProperty("broker.console.timeout.millis", "1500"));
    qps = Integer.parseInt(p.getProperty("broker.console.qps", "2"));
    maxAge = Long.parseLong(p.getProperty("broker.console.report.max.age.millis", "120000"));
    if (timeout < 100
        || timeout > 3000
        || qps < 1
        || qps > 10
        || maxAge < 1000
        || maxAge > 86400000) throw new IllegalArgumentException("INVALID_CONSOLE_BUDGET");
  }

  public Set<String> targets() {
    return Collections.unmodifiableSet(targets);
  }

  private static JSONObject str() {
    return new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 128);
  }

  private static JSONObject integer() {
    return new JSONObject()
        .put("type", "integer")
        .put("minimum", 0)
        .put("maximum", 9007199254740991L);
  }

  public static JSONObject parameters() {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "targets",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", str().put("pattern", "^[A-Za-z0-9_.:-]{1,128}$"))
                        .put("minItems", 1)
                        .put("maxItems", 32))
                .put(
                    "fields",
                    new JSONObject()
                        .put("type", "array")
                        .put(
                            "items",
                            str()
                                .put(
                                    "enum",
                                    new JSONArray(
                                        Arrays.asList("running", "version", "jvm", "host"))))
                        .put("minItems", 1)
                        .put("maxItems", 4)))
        .put("required", new JSONArray().put("targets").put("fields"))
        .put("additionalProperties", false);
  }

  public static JSONObject outputSchema() {
    JSONObject jvm =
        new JSONObject()
            .put("type", "object")
            .put(
                "properties",
                new JSONObject()
                    .put("maxMemoryMiB", integer())
                    .put("totalMemoryMiB", integer())
                    .put("freeMemoryMiB", integer())
                    .put("usedMemoryMiB", integer()))
            .put(
                "required",
                new JSONArray(
                    Arrays.asList(
                        "maxMemoryMiB", "totalMemoryMiB", "freeMemoryMiB", "usedMemoryMiB")))
            .put("additionalProperties", false);
    JSONObject host =
        new JSONObject()
            .put("type", "object")
            .put(
                "properties",
                new JSONObject()
                    .put("scope", str().put("enum", new JSONArray().put("console-host")))
                    .put(
                        "cpuPercent",
                        new JSONObject()
                            .put("type", "number")
                            .put("minimum", 0)
                            .put("maximum", 100))
                    .put("totalMemoryMiB", integer())
                    .put("usedMemoryMiB", integer()))
            .put("required", new JSONArray().put("scope"))
            .put("additionalProperties", false);
    JSONObject row =
        new JSONObject()
            .put("type", "object")
            .put(
                "properties",
                new JSONObject()
                    .put("target", str())
                    .put("queriedAt", integer())
                    .put("source", new JSONObject().put("type", "string").put("maxLength", 2048))
                    .put("running", new JSONObject().put("type", "boolean"))
                    .put("version", new JSONObject().put("type", "string").put("maxLength", 128))
                    .put("jvm", jvm)
                    .put("host", host)
                    .put("reportTimestamp", integer())
                    .put(
                        "reportState",
                        str()
                            .put(
                                "enum",
                                new JSONArray(
                                    Arrays.asList("fresh", "stale", "placeholder", "missing"))))
                    .put("errorCode", str()))
            .put("required", new JSONArray(Arrays.asList("target", "queriedAt", "source")))
            .put("additionalProperties", false);
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "nodes",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", row)
                        .put("minItems", 1)
                        .put("maxItems", 32)))
        .put("required", new JSONArray().put("nodes"))
        .put("additionalProperties", false);
  }

  public void validate(JSONObject args) {
    Contract.validate(args, parameters());
    Set<String> t = RuntimeSupport.strings(args.getJSONArray("targets")),
        fields = RuntimeSupport.strings(args.getJSONArray("fields"));
    if (!targets.containsAll(t)) throw new StoreException("BROKER_SCOPE_FORBIDDEN");
    for (String target : t) {
      boolean host =
          config.getProperty("broker.console.target." + target + ".kind").equals("console-host");
      if (host != fields.equals(Collections.singleton("host")))
        throw new StoreException("CONSOLE_HOST_SCOPE_FORBIDDEN");
    }
  }

  private String value(String target, String key) {
    return config.getProperty("broker.console.target." + target + "." + key);
  }

  private synchronized void budget() {
    long now = System.currentTimeMillis();
    if (now < openedUntil) throw new StoreException("CONSOLE_CIRCUIT_OPEN");
    if (now - window >= 1000) {
      window = now;
      count = 0;
    }
    if (++count > qps) throw new StoreException("CONSOLE_RATE_LIMITED");
  }

  private JSONObject http(String route, String form) throws Exception {
    budget();
    URL url = new URL(base.toString().replaceAll("/+$", "") + route);
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    java.util.concurrent.atomic.AtomicBoolean expired =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.ScheduledFuture<?> deadline =
        deadlines.schedule(
            () -> {
              expired.set(true);
              c.disconnect();
            },
            timeout,
            java.util.concurrent.TimeUnit.MILLISECONDS);
    try {
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(timeout);
      c.setReadTimeout(timeout);
      c.setRequestProperty("Cookie", cookie);
      if (form != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream out = c.getOutputStream()) {
          out.write(form.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = c.getResponseCode();
      if (status != 200)
        throw new StoreException(
            status == 401 || status == 403
                ? "CONSOLE_UNAUTHORIZED"
                : status == 404 ? "CONSOLE_NODE_NOT_FOUND" : "CONSOLE_UPSTREAM_REJECTED");
      JSONObject raw;
      try (InputStream in = c.getInputStream()) {
        raw = StrictJson.object(RuntimeSupport.read(in, 65536));
      }
      synchronized (this) {
        failures = 0;
      }
      return raw;
    } catch (Exception e) {
      synchronized (this) {
        if (++failures >= 4) openedUntil = System.currentTimeMillis() + 30000;
      }
      if (expired.get() || e instanceof java.net.SocketTimeoutException)
        throw new StoreException("CONSOLE_TIMEOUT");
      throw e;
    } finally {
      deadline.cancel(false);
      c.disconnect();
    }
  }

  private static String encode(String value) throws Exception {
    return URLEncoder.encode(value, "UTF-8");
  }

  public JSONObject query(JSONObject args) {
    validate(args);
    JSONArray nodes = new JSONArray();
    Set<String> fields = RuntimeSupport.strings(args.getJSONArray("fields"));
    for (String target : RuntimeSupport.strings(args.getJSONArray("targets"))) {
      String kind = value(target, "kind");
      JSONObject row =
          new JSONObject()
              .put("target", target)
              .put("queriedAt", System.currentTimeMillis())
              .put(
                  "source",
                  base.toString()
                      + (kind.equals("console-host") ? "/host/metrics" : "/" + kind + "/status"));
      try {
        if (kind.equals("console-host")) {
          JSONObject raw = http("/host/metrics", null);
          JSONObject host = new JSONObject().put("scope", "console-host");
          JSONObject cpu = raw.optJSONObject("cpu"), memory = raw.optJSONObject("memory");
          if (cpu != null && cpu.has("usage")) host.put("cpuPercent", cpu.getDouble("usage") * 100);
          if (memory != null && memory.has("totalBytes") && memory.has("usedBytes")) {
            host.put("totalMemoryMiB", memory.getLong("totalBytes") / 1048576)
                .put("usedMemoryMiB", memory.getLong("usedBytes") / 1048576);
          }
          row.put("host", host);
        } else {
          JSONObject raw =
              http(
                  "/" + kind + "/status",
                  "tag=" + encode(value(target, "tag")) + "&path=" + encode(value(target, "path")));
          if (!value(target, "tag").equals(raw.getString("tag"))
              || !value(target, "path").equals(raw.getString("deployPath")))
            throw new StoreException("CONSOLE_NODE_IDENTITY_MISMATCH");
          if (fields.contains("running")) row.put("running", raw.getBoolean("running"));
          if (fields.contains("version")) row.put("version", raw.getString("version"));
          if (fields.contains("jvm")) {
            JSONObject report =
                http(
                    "/server-report?name="
                        + encode(value(target, "reportName"))
                        + "&report=JVMReport&num=1&time=0",
                    null);
            JSONArray list = report.getJSONArray("list");
            if (!value(target, "reportName").equals(report.getString("name")))
              throw new StoreException("CONSOLE_REPORT_IDENTITY_MISMATCH");
            if (list.length() == 0) row.put("reportState", "missing");
            else {
              JSONObject j = list.getJSONObject(0);
              long stamp = j.getLong("timestamp"),
                  max = j.getLong("maxMemory"),
                  total = j.getLong("totalMemory"),
                  free = j.getLong("freeMemory");
              if (free < 0
                  || total < free
                  || max < total
                  || stamp < 0
                  || stamp > System.currentTimeMillis() + 60000)
                throw new StoreException("CONSOLE_REPORT_INVALID");
              row.put("reportTimestamp", stamp)
                  .put(
                      "reportState",
                      stamp == 0 || total == 0
                          ? "placeholder"
                          : System.currentTimeMillis() - stamp > maxAge ? "stale" : "fresh")
                  .put(
                      "jvm",
                      new JSONObject()
                          .put("maxMemoryMiB", max)
                          .put("totalMemoryMiB", total)
                          .put("freeMemoryMiB", free)
                          .put("usedMemoryMiB", total - free));
            }
          }
        }
      } catch (Exception e) {
        row.put(
            "errorCode",
            e instanceof StoreException
                ? ((StoreException) e).code
                : e instanceof java.net.SocketTimeoutException
                    ? "CONSOLE_TIMEOUT"
                    : "CONSOLE_UNAVAILABLE");
      }
      row.put("queriedAt", System.currentTimeMillis());
      nodes.put(row);
    }
    JSONObject result = new JSONObject().put("nodes", nodes);
    Contract.validate(result, outputSchema());
    return result;
  }
}
