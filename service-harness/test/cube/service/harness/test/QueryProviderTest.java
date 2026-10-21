package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.execution.*;
import cube.service.harness.protocol.Contract;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** A different business domain proves the registry has no Console-only dispatch or permissions. */
public final class QueryProviderTest {
  public static final class Weather implements QueryProvider {
    static AtomicInteger queries = new AtomicInteger();

    public Weather(Properties p) {
      check(
          p.getProperty("private-test-key").equals("provider-only"),
          "private provider config missing");
    }

    public String operation() {
      return "example.weather.query.v1";
    }

    public String permission() {
      return "weather.read";
    }

    public JSONObject inputSchema() {
      JSONObject s = ToolBroker.parameters();
      s.getJSONObject("properties")
          .getJSONObject("fields")
          .getJSONObject("items")
          .put("enum", new JSONArray().put("tomorrow"));
      return s;
    }

    public JSONObject resultSchema() {
      return new JSONObject()
          .put("type", "object")
          .put(
              "properties",
              new JSONObject()
                  .put("forecast", new JSONObject().put("type", "string").put("maxLength", 128)))
          .put("required", new JSONArray().put("forecast"))
          .put("additionalProperties", false);
    }

    public void validate(JSONObject a) {
      Contract.validate(a, inputSchema());
      if (!RuntimeSupport.strings(a.getJSONArray("targets"))
          .equals(Collections.singleton("Beijing")))
        throw new StoreException("BROKER_SCOPE_FORBIDDEN");
    }

    public JSONObject query(JSONObject a) {
      validate(a);
      queries.incrementAndGet();
      return new JSONObject().put("forecast", "fixture-only");
    }

    public void validateResult(JSONObject a, JSONObject result) {
      Contract.validate(result, resultSchema());
    }

    public void close() {}
  }

  public static void main(String[] ignored) throws Exception {
    ToolTest.prepare(Files.createTempDirectory("harness-query-provider-"), "");
    Weather weather =
        new Weather(
            new Properties() {
              {
                setProperty("private-test-key", "provider-only");
              }
            });
    String name = "example.weather.query";
    Map<String, byte[]> e =
        entries("Activate the weather SKILL before querying Beijing tomorrow. Fixture only.");
    e.put("input.schema.json", RuntimeSupport.bytes(weather.inputSchema()));
    e.put("output.schema.json", RuntimeSupport.bytes(weather.resultSchema()));
    e.put(
        "main.py",
        bytes(
            "from runner_sdk import context,query,result\nresult(query(**context()['arguments']))\n"));
    JSONObject m =
        manifest(e)
            .put("schemaVersion", 6)
            .put("name", "example/weather")
            .put("runtime", ref("cpu"))
            .put(
                "permissions",
                new JSONArray()
                    .put("skill.list")
                    .put("skill.activate")
                    .put("resource.list")
                    .put("resource.read")
                    .put("resource.search")
                    .put("weather.read"));
    for (int i = 0; i < m.getJSONArray("files").length(); i++) {
      JSONObject f = m.getJSONArray("files").getJSONObject(i);
      if (f.getString("path").equals("main.py")) f.put("role", "entrypoint");
    }
    m.getJSONArray("tools")
        .put(
            new JSONObject()
                .put("name", name)
                .put("kind", "executable")
                .put("entrypoint", "main.py")
                .put("inputSchema", "input.schema.json")
                .put("outputSchema", "output.schema.json")
                .put("permission", "weather.read")
                .put("effect", "read-only")
                .put("brokerOperation", weather.operation()).put("runnerType", "tool"));
    String digest = upload(zip(m, e)).getString("digest");
    catalog.lifecycle(T, digest, 1, "ready");
    Set<String> scopes = new HashSet<>(TEST_PERMISSIONS);
    scopes.add("weather.read");
    scopes.add("unused.read");
    JSONObject task =
        task("1", digest, "scripted", 4096)
            .put("id", "weather")
            .put("schemaVersion", 2)
            .put("runtime", ref("cpu"))
            .put("permissions", new JSONArray(scopes))
            .put("toolSetVersion", "weather-tools-v1")
            .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"))
            .put(
                "executionLimits",
                new JSONObject()
                    .put("maxRounds", 8)
                    .put("maxTools", 16)
                    .put("maxRepairs", 1)
                    .put("maxLifetimeMillis", 60000));
    profiles.publish(T, task);
    catalog.bind(T, "task", "weather", "weather@1", 0);
    ProfileRegistry.Identity actor = new ProfileRegistry.Identity(T, S, scopes);
    String run =
        profiles
            .createRun(
                actor,
                key(),
                "weather",
                request("Beijing tomorrow").put("taskProfileId", "weather"))
            .getString("runId");
    Set<String> pinned =
        RuntimeSupport.strings(profiles.pinned(actor, run).getJSONArray("permissions"));
    check(
        pinned.contains("weather.read") && !pinned.contains("unused.read"),
        "extension permission intersection");
    resources.activate(actor, run, digest);
    JdbcRunStore.Lease lease = running(run, 60000);
    JSONObject a =
        new JSONObject()
            .put("targets", new JSONArray().put("Beijing"))
            .put("fields", new JSONArray().put("tomorrow"));
    intent(
        lease,
        "call",
        "call",
        new JSONObject()
            .put("proposalId", "call")
            .put("tool", name)
            .put("argumentsJson", JdbcRunStore.canonical(a)));
    JSONObject intent = row(run, "call", "call"),
        definition = resources.executable(actor, run, name);
    ToolJobStore jobs = new ToolJobStore(store, config.mysql());
    Properties p = new Properties();
    p.setProperty("runner.grant.key", key() + key());
    p.setProperty("broker.node.id", "generic-registry");
    try (ToolBroker empty = new ToolBroker(jobs, actor, p)) {
      fail("TOOL_PROVIDER_UNAVAILABLE", () -> empty.enqueue(lease, intent, definition));
    }
    Path privateConfig = root.resolve("weather.local.properties");
    Files.write(privateConfig, bytes("private-test-key=provider-only\n"));
    p.setProperty("broker.providers", "weather");
    p.setProperty("broker.provider.weather.class", Weather.class.getName());
    p.setProperty("broker.provider.weather.config", privateConfig.toAbsolutePath().toString());
    try (ToolBroker broker = new ToolBroker(jobs, actor, p)) {
      fail(
          "TOOL_REGISTRY_SCHEMA_MISMATCH",
          () ->
              broker.enqueue(
                  lease,
                  intent,
                  RuntimeSupport.copy(definition).put("parameters", ToolBroker.parameters())));
      try (ToolBroker denied = new ToolBroker(jobs, identity, p)) {
        fail("TOOL_PROFILE_FORBIDDEN", () -> denied.enqueue(lease, intent, definition));
      }
      JSONObject job = broker.enqueue(lease, intent, definition);
      jobs.identity("weather-runner", "weather-runtime", 1);
      JSONObject wire =
          jobs.poll("weather-runner", "weather-runtime", "instance", true, broker::wire);
      check(!wire.toString().contains("provider-only"), "private provider config leaked");
      jobs.accepted(job.getString("job_id"), wire.getString("jobHash"));
      job = jobs.job(job.getString("job_id"));
      JSONObject out = null;
      long end = System.currentTimeMillis() + 5000;
      do {
        out =
            broker.query(
                job,
                wire.getJSONObject("grant"),
                a.getJSONArray("targets"),
                a.getJSONArray("fields"));
        if (out.getString("status").equals("finished")) break;
        Thread.sleep(20);
      } while (System.currentTimeMillis() < end);
      check(
          out.getString("status").equals("finished")
              && new JSONObject(out.getString("dataJson"))
                  .getString("forecast")
                  .equals("fixture-only"),
          "generic query dispatch");
      broker.query(
          job, wire.getJSONObject("grant"), a.getJSONArray("targets"), a.getJSONArray("fields"));
      check(Weather.queries.get() == 1, "provider replayed");
    }
    JSONObject legacy = RuntimeSupport.copy(m).put("schemaVersion", 3);
    fail(
        "UNKNOWN_PACKAGE_SCHEMA",
        () -> repo.validate(new java.io.ByteArrayInputStream(zip(legacy, e))));
    pass(
        "generic weather SKILL: optional provider, custom scope intersection, private configuration, schema pin, authorization and durable query replay");
  }
}
