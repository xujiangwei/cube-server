package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import cube.service.harness.protocol.Contract;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Real store, registry, worker admission and tool boundary regressions. */
public final class RequestSelectionTest {
    private static JSONObject request() { return new JSONObject().put("input", new JSONObject().put("instruction", "query status").put("attachmentIds", new JSONArray())); }

    private static void policy(JSONObject value) {
        long revision = catalog.active(T, "policy", "default").getLong("version");
        value = RuntimeSupport.copy(value).put("version", "selection-" + (revision + 1));
        new ProfileRegistry(catalog).publish(T, value);
        catalog.bind(T, "policy", "default", value.getString("id") + "@" + value.getString("version"), revision);
    }

    private static void dependencies(ExecutionRuntime service) throws Exception {
        JSONObject original = catalog.tenantPolicy(T);
        Map<String, byte[]> entries = entries("Depends on the executable status skill.");
        entries.put("dependencies.lock.json", RuntimeSupport.bytes(new JSONObject().put("schemaVersion", 1)
                .put("dependencies", new JSONArray().put(new JSONObject().put("name", "test/status").put("sha256", release)))));
        String parent = upload(zip(manifest(entries).put("name", "test/dependent"), entries)).getString("digest");
        catalog.lifecycle(T, parent, 1, "ready");
        policy(RuntimeSupport.copy(original).put("skillReleases", new JSONArray().put(release).put(parent)));
        JSONObject selected = service.create(identity, "dependencies", request().put("skills", new JSONArray().put("test/dependent")));
        check(selected.getJSONObject("selection").getJSONArray("skills").length() == 2, "Authorized dependency was not pinned");
        check(catalog.activeSkills(T, S, selected.getString("runId")).length() == 2, "Dependency was not immediately activated");
        check(service.create(identity, "dependency-filter", request().put("runtime", "resources")).getJSONObject("selection").getJSONArray("skills").length() == 0,
                "Default skills ignored transitive runtime compatibility");
        JSONArray directorySkills = service.taskProfiles(identity).getJSONArray("skills");
        for (int i = 0; i < directorySkills.length(); i++) {
            JSONObject skill = directorySkills.getJSONObject(i);
            if (skill.getString("name").equals("test/dependent")) for (int j = 0; j < skill.getJSONArray("compatibleRuntimes").length(); j++)
                check(!skill.getJSONArray("compatibleRuntimes").getJSONObject(j).getString("id").equals("resources"), "Directory ignored dependency compatibility");
        }
        fail("SKILL_RUNTIME_MISMATCH", () -> service.create(identity, "dependency-mismatch", request().put("runtime", "resources").put("skills", new JSONArray().put("test/dependent"))));
        policy(RuntimeSupport.copy(original).put("skillReleases", new JSONArray().put(parent)));
        fail("DEPENDENCY_NOT_PINNED", () -> service.create(identity, "dependency-forbidden", request().put("skills", new JSONArray().put("test/dependent"))));
        ProfileRegistry.Identity noPermissions = new ProfileRegistry.Identity(T, S, Collections.emptySet());
        check(JdbcRunStore.canonical(service.create(noPermissions, "dependencies", request().put("skills", new JSONArray().put("test/dependent"))))
                .equals(JdbcRunStore.canonical(selected)), "Changed authorization blocked replay");
        JSONArray duplicates = new JSONArray();
        for (String name : Arrays.asList("collision/root", "collision/root.child")) {
            Map<String, byte[]> files = entries("Resource tool collision.");
            files.put("input.schema.json", bytes("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"));
            files.put("output.schema.json", bytes("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"));
            JSONObject duplicateManifest = manifest(files).put("name", name).put("tools", new JSONArray().put(new JSONObject()
                    .put("name", "collision.root.child.read").put("kind", "resource").put("permission", "resource.read")
                    .put("inputSchema", "input.schema.json").put("outputSchema", "output.schema.json").put("resourcePath", "guide.txt")));
            String duplicate = upload(zip(duplicateManifest, files)).getString("digest");
            catalog.lifecycle(T, duplicate, 1, "ready");
            duplicates.put(duplicate);
        }
        policy(RuntimeSupport.copy(original).put("skillReleases", duplicates));
        fail("TOOL_NAME_COLLISION", () -> service.create(identity, "duplicate-tool", request()));
        service.create(identity, "excluded-duplicate", request().put("skills", new JSONArray().put("collision/root")));
        policy(original);
    }

    private static void directEnqueue(String run, String excludedRun) throws Exception {
        JSONObject definition = resources.executable(identity, run, ToolTest.TOOL);
        JdbcRunStore.Lease lease = running(run, 60000);
        intent(lease, "call", "forged", new JSONObject().put("proposalId", "forged").put("tool", "unselected.tool").put("argumentsJson", "{}"));
        ToolJobStore jobs = new ToolJobStore(store, config.mysql());
        fail("RESOURCE_FORBIDDEN", () -> jobs.enqueue(lease, row(run, "call", "forged"), definition));
        intent(lease, "call", "mismatched", new JSONObject().put("proposalId", "mismatched").put("tool", ToolTest.TOOL).put("argumentsJson", JdbcRunStore.canonical(ToolTest.args())));
        JSONObject forged = RuntimeSupport.copy(definition);
        forged.getJSONObject("tool").put("runnerType", "sandbox");
        fail("TOOL_DEFINITION_MISMATCH", () -> jobs.enqueue(lease, row(run, "call", "mismatched"), forged));
        JSONObject job = jobs.enqueue(lease, row(run, "call", "mismatched"), definition);
        ControlFileTest.sql("UPDATE harness_tool_job SET tool_name='unselected.tool' WHERE job_id=?", job.getString("job_id"));
        ToolJobStore recovered = new ToolJobStore(store, config.mysql());
        recovered.identity("selection-direct", "selection-direct-runtime", 1);
        fail("RESOURCE_FORBIDDEN", () -> recovered.poll("selection-direct", "selection-direct-runtime", "instance", true,
                (j, w, r, i) -> { throw new AssertionError("Excluded tool was dispatched after recovery"); }));
        ControlFileTest.sql("UPDATE harness_tool_job SET tool_name=? WHERE job_id=?", ToolTest.TOOL, job.getString("job_id"));
        JdbcRunStore.Lease excludedLease = running(excludedRun, 60000);
        intent(excludedLease, "call", "excluded", new JSONObject().put("proposalId", "excluded").put("tool", ToolTest.TOOL).put("argumentsJson", JdbcRunStore.canonical(ToolTest.args())));
        fail("RESOURCE_FORBIDDEN", () -> jobs.enqueue(excludedLease, row(excludedRun, "call", "excluded"), definition));
    }

    public static void main(String[] args) throws Exception {
        ToolTest.prepare(Files.createTempDirectory("harness-request-selection-"), "");
        Properties settings = new Properties();
        try (java.io.InputStream in = Files.newInputStream(root.resolve("store.local.properties"))) { settings.load(in); }
        settings.setProperty("execution.repository", root.resolve("repository").toString());
        settings.setProperty("execution.repository.namespace", "runtime-test-repo");
        SelectionWorkers.tools(settings);
        Path directory = Paths.get(settings.getProperty("execution.profiles"));
        JSONObject policy = new JSONObject(new String(Files.readAllBytes(directory.resolve("execution.json")), "UTF-8"));
        policy.put("requiredCapabilities", new JSONArray().put("resources"));
        Files.write(directory.resolve("execution.json"), RuntimeSupport.bytes(policy));
        Files.write(directory.resolve("resources.json"), RuntimeSupport.bytes(DefaultProfiles.runtime()));
        JSONObject other = RuntimeSupport.copy(runtime).put("id", "another-python").put("version", "7").put("toolSetVersion", "broker-tools");
        Files.write(directory.resolve("another-python.json"), RuntimeSupport.bytes(other));
        String tenantBefore = JdbcRunStore.canonical(catalog.tenantPolicy(T));
        try (ExecutionRuntime service = new ExecutionRuntime(store, settings, 60000)) {
            JSONObject offline = service.taskProfiles(identity);
            Contract.parse("profiles-response", RuntimeSupport.bytes(offline));
            check(!offline.getJSONArray("models").getJSONObject(0).getBoolean("ready"), "Offline model was hidden or ready");
            fail("MODEL_NOT_READY", () -> service.create(identity, "offline", request()));
            SelectionWorkers.online(service, settings);
            JSONObject directoryView = service.taskProfiles(identity);
            Contract.parse("profiles-response", RuntimeSupport.bytes(directoryView));
            boolean found = false;
            for (int i = 0; i < directoryView.getJSONArray("tools").length(); i++) found |= directoryView.getJSONArray("tools").getJSONObject(i).getString("name").equals(ToolTest.TOOL);
            check(found, "Unactivated skill tool was absent from directory");
            JSONObject explicit = request().put("model", "scripted@1").put("runtime", "cpu")
                    .put("skills", new JSONArray().put("test/status"));
            JSONObject receipt = service.create(identity, "selected", explicit);
            Contract.parse("run-snapshot", RuntimeSupport.bytes(receipt));
            String run = receipt.getString("runId");
            JSONObject pin = catalog.pinned(T, S, run);
            check(catalog.activeSkills(T, S, run).length() == 1, "Explicit skill was not activated atomically");
            check(RunSelection.allowed(pin, ToolTest.TOOL), "Selected skill did not grant its authorized tool");
            check(resources.executable(identity, run, ToolTest.TOOL) != null, "Selected executable did not resolve");
            check(new ResourceTools(catalog, repo).tools(identity, run).length() == resources.tools(identity, run).length(), "Restarted tool facade lost immutable scope");
            JSONObject lazy = service.create(identity, "lazy", request());
            check(catalog.activeSkills(T, S, lazy.getString("runId")).length() == 0, "Omitted skills were activated eagerly");
            JSONObject none = service.create(identity, "none", request().put("skills", new JSONArray()));
            String excludedRun = none.getString("runId");
            check(none.getJSONObject("selection").getJSONArray("skills").length() == 0 && !RunSelection.allowed(catalog.pinned(T, S, excludedRun), ToolTest.TOOL), "Empty skill subset retained package tools");
            fail("RESOURCE_FORBIDDEN", () -> resources.executable(identity, excludedRun, ToolTest.TOOL));
            fail("RESOURCE_FORBIDDEN", () -> resources.activate(identity, excludedRun, release));
            fail("RESOURCE_FORBIDDEN", () -> catalog.activate(T, S, excludedRun, release));
            fail("RESOURCE_FORBIDDEN", () -> resources.read(identity, excludedRun, release, "SKILL.md", 0, 100));
            ProfileRegistry.Identity noPermissions = new ProfileRegistry.Identity(T, S, Collections.emptySet());
            check(service.create(noPermissions, "no-permissions", request().put("skills", new JSONArray())).getJSONObject("selection").getJSONArray("tools").length() == 0, "Tool permissions were expanded");
            invalid(() -> service.create(identity, "client-tools", request().put("tools", new JSONArray())));
            invalid(() -> service.create(identity, "client-named-tools", request().put("tools", new JSONArray().put(ToolTest.TOOL))));
            fail("SKILL_FORBIDDEN", () -> service.create(identity, "bad-skill", request().put("skills", new JSONArray().put("other/private"))));
            fail("MODEL_NOT_FOUND", () -> service.create(identity, "bad-model", request().put("model", "absent")));
            fail("RUNTIME_NOT_FOUND", () -> service.create(identity, "bad-runtime", request().put("runtime", "absent")));
            fail("SKILL_RUNTIME_MISMATCH", () -> service.create(identity, "incompatible", request().put("runtime", "resources").put("skills", new JSONArray().put("test/status"))));
            JSONObject resourceOnly = service.create(identity, "resource-only", request().put("runtime", "resources"));
            check(resourceOnly.getJSONObject("selection").getJSONArray("skills").length() == 0, "Default selection retained incompatible executable skill");
            JSONObject switched = service.create(identity, "other-runtime", request().put("runtime", "another-python"));
            check(switched.getJSONObject("selection").getJSONObject("runtime").getString("version").equals("7"), "Independent runtime selection failed");
            check(tenantBefore.equals(JdbcRunStore.canonical(catalog.tenantPolicy(T))), "Selection changed tenant policy");
            directEnqueue(run, excludedRun);
            dependencies(service);
            service.runners.disconnect(201);
            fail("RUNTIME_NOT_READY", () -> service.create(identity, "runner-offline", explicit));
            check(JdbcRunStore.canonical(service.create(identity, "selected", explicit)).equals(JdbcRunStore.canonical(receipt)), "Offline runner blocked replay");
            service.create(identity, "local-runtime-online", request().put("runtime", "resources"));
            service.workers.disconnect(101);
            check(JdbcRunStore.canonical(service.create(identity, "selected", explicit)).equals(JdbcRunStore.canonical(receipt)), "Offline model blocked replay");
            fail("IDEMPOTENCY_CONFLICT", () -> service.create(identity, "selected", RuntimeSupport.copy(explicit).put("skills", new JSONArray())));
            check(JdbcRunStore.canonical(pin).equals(JdbcRunStore.canonical(catalog.pinned(T, S, run))), "Existing snapshot changed");
        }
        JSONObject version2 = RuntimeSupport.copy(other).put("version", "8");
        Files.write(directory.resolve("another-python-v8.json"), RuntimeSupport.bytes(version2));
        try { ExecutionProfiles.load(settings); throw new AssertionError("Ambiguous current version accepted"); }
        catch (java.io.IOException expected) { }
        policy.put("activeVersions", new JSONObject().put("runtime", new JSONObject().put("another-python", "7")));
        Files.write(directory.resolve("execution.json"), RuntimeSupport.bytes(policy));
        ExecutionProfiles profiles = ExecutionProfiles.load(settings);
        check(profiles.resolve("runtime", "another-python").getString("version").equals("7"), "Current version was guessed");
        check(profiles.resolve("runtime", "another-python@8").getString("version").equals("8"), "Explicit version was ignored");
        System.out.println("PASS: model/runtime and skill selection, client tools rejected, server-derived tool scope, dependencies, collisions, atomic activation, direct/recovered tool gates, readiness, replay and current revisions");
    }
}
