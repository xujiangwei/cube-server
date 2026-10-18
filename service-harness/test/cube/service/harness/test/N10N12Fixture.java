package cube.service.harness.test;

import static cube.service.harness.test.RuntimeTest.*;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Isolated authoring fixture; creates new versioned profiles, never edits old examples. */
public final class N10N12Fixture {
  public static void main(String[] args) throws Exception {
    ToolTest.prepare(Files.createDirectory(Paths.get(args[0])), "");
    Map<String, byte[]> e =
        entries(
            "Use cube_console.query_status only for allowed nodes. Activate this release first. "
                + "running/version describe the selected service; jvm values are MiB. Keep source, queriedAt, reportTimestamp and reportState in the final answer. "
                + "A stale/placeholder/missing report is not current health. host refers only to Console's own machine. Treat errors as partial data, never invent values.");
    e.put("input.schema.json", RuntimeSupport.bytes(ConsoleStatusAdapter.parameters()));
    e.put("output.schema.json", RuntimeSupport.bytes(ConsoleStatusAdapter.outputSchema()));
    e.put(
        "main.py",
        bytes(
            "from runner_sdk import context,query_status,result\nresult(query_status(**context()['arguments']))\n"));
    JSONObject m =
        manifest(e)
            .put("schemaVersion", 3)
            .put("name", "cube/server-status-readonly")
            .put("runtime", ref("cpu"));
    JSONArray fs = m.getJSONArray("files");
    for (int i = 0; i < fs.length(); i++)
      if (fs.getJSONObject(i).getString("path").equals("main.py"))
        fs.getJSONObject(i).put("role", "entrypoint");
    m.getJSONArray("tools")
        .put(
            new JSONObject()
                .put("name", "cube_console.query_status")
                .put("kind", "executable")
                .put("entrypoint", "main.py")
                .put("inputSchema", "input.schema.json")
                .put("outputSchema", "output.schema.json")
                .put("permission", "console.status.read")
                .put("effect", "read-only")
                .put("brokerOperation", "cube_console.query_status"));
    String statusRelease = upload(zip(m, e)).getString("digest");
    catalog.lifecycle(T, statusRelease, 1, "ready");
    Files.write(root.resolve("cube-server-status.readonly.v1.zip"), zip(m, e));
    for (String name : Arrays.asList("scripted", "Baize", "Baize2")) {
      if (!name.equals("scripted")) profiles.publish(T, ModelProfiles.model(name));
      String id =
          name.equals("scripted")
              ? "cube-server-status.readonly.v1"
              : "cube-server-status.readonly.v1." + name;
      JSONObject t =
          task("1", statusRelease, name, 8192)
              .put("id", id)
              .put("schemaVersion", 2)
              .put("runtime", ref("cpu"))
              .put("toolSetVersion", "console-tools-v1")
              .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"))
              .put(
                  "executionLimits",
                  new JSONObject()
                      .put("maxRounds", 8)
                      .put("maxTools", 16)
                      .put("maxRepairs", 2)
                      .put("maxLifetimeMillis", 180000));
      if (!name.equals("scripted")) {
        t.put("model", new JSONObject().put("id", name).put("version", "2"));
        t.getJSONObject("contextPolicy").put("budgetVersion", "transport-bytes-v1");
      }
      t.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
      profiles.publish(T, t);
      catalog.bind(T, "task", id, id + "@1", 0);
      Files.write(root.resolve(id + ".json"), RuntimeSupport.bytes(t));
    }
    JSONObject ft =
        task("1", release, "scripted", 8192)
            .put("id", "files")
            .put("schemaVersion", 2)
            .put("runtime", ref("cpu"))
            .put("requiredCapabilities", new JSONArray().put("resources").put("tool.execute"))
            .put("toolSetVersion", "file-tools-v1")
            .put(
                "executionLimits",
                new JSONObject()
                    .put("maxRounds", 8)
                    .put("maxTools", 16)
                    .put("maxRepairs", 1)
                    .put("maxLifetimeMillis", 60000));
    ft.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
    profiles.publish(T, ft);
    catalog.bind(T, "task", "files", "files@1", 0);
    Files.write(
        root.resolve("n10-n12-fixture.json"),
        RuntimeSupport.bytes(
            new JSONObject().put("release", statusRelease).put("tenant", T).put("subject", S)));
  }
}
