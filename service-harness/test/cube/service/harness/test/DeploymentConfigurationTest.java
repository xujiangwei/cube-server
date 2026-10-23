package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Real JDBC regression: deployment changes apply to every tenant without publishing tenant execution profiles. */
public final class DeploymentConfigurationTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject request(String text) {
        return new JSONObject().put("input", new JSONObject().put("instruction", text).put("attachmentIds", new JSONArray()));
    }
    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("Client deployment selector was accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("harness-deployment-policy-");
        Path exported = root.resolve("exported");
        ExecutionAdmin.main(new String[]{exported.toString()});
        Properties exportedSettings = new Properties();
        exportedSettings.setProperty("execution.profiles", exported.resolve("harness-execution").toString());
        check(ExecutionProfiles.load(exportedSettings).model().getString("version").equals("1"), "Exported Baize default did not preserve revision 1");
        Path deployment = Files.createDirectory(root.resolve("execution"));
        try (java.util.stream.Stream<Path> entries = Files.list(RuntimeTest.deploymentDirectory())) {
            for (Iterator<Path> i = entries.iterator(); i.hasNext();) {
                Path file = i.next(); if (file.toString().endsWith(".json")) Files.copy(file, deployment.resolve(file.getFileName()));
            }
        }
        JSONObject secondRuntime = DefaultProfiles.runtime().put("version", "2").put("maxWorkspaceBytes", 2097152);
        Files.write(deployment.resolve("resources-v2.json"), RuntimeSupport.bytes(secondRuntime));
        Path defaults = deployment.resolve("execution.json");
        JSONObject execution = new JSONObject(new String(Files.readAllBytes(defaults), "UTF-8"));
        execution.put("activeVersions", new JSONObject().put("runtime", new JSONObject().put("resources", "1")));
        Files.write(defaults, RuntimeSupport.bytes(execution));
        Properties p = new Properties();
        p.setProperty("store.dialect", "sqlite"); p.setProperty("store.database", "harness_deployment_policy");
        p.setProperty("store.identity", "deployment-policy-test"); p.setProperty("store.sqlite.root", root.toString());
        p.setProperty("store.sqlite.file", root.resolve("harness_deployment_policy.db").toString());
        p.setProperty("execution.profiles", deployment.toString());
        p.setProperty("execution.tenant.templates", RuntimeTest.templateDirectory().toString());
        p.setProperty("execution.repository", Files.createDirectory(root.resolve("resources")).toString());
        p.setProperty("inference.node.id", "deployment-policy-test");
        StoreConfig config = new StoreConfig(p); StoreBootstrap.selfCheck(config);
        JdbcRunStore store = new JdbcRunStore(config); CatalogStore catalog = new CatalogStore(store, false);
        ProfileRegistry.Identity a = new ProfileRegistry.Identity("tenant-a", "10001", ProfileRegistry.PLATFORM_PERMISSIONS);
        ProfileRegistry.Identity b = new ProfileRegistry.Identity("tenant-b", "10002", ProfileRegistry.PLATFORM_PERMISSIONS);
        String first, second; JSONObject receipt;
        try (ExecutionRuntime runtime = new ExecutionRuntime(store, p, 60000, SelectionWorkers::provider)) {
            RuntimeTest.fail("MODEL_NOT_READY", () -> runtime.create(a, "offline", request("hello")));
            SelectionWorkers.online(runtime, p);
            receipt = runtime.create(a, "stable-key", request("hello")); first = receipt.getString("runId");
            second = runtime.create(b, "stable-key", request("hello")).getString("runId");
            check(catalog.pinned(a.tenant, a.subject, first).getJSONObject("model").getString("id").equals("Baize"), "Server default model was ignored");
            check(!first.equals(second), "Tenant idempotency boundaries were lost");
            JSONObject explicit = runtime.create(a, "client-model", request("hello").put("model", "Baize2"));
            check(explicit.getJSONObject("selection").getJSONObject("model").getString("id").equals("Baize2"), "Explicit model was ignored");
            cube.service.harness.protocol.Contract.parse("profiles-response", RuntimeSupport.bytes(runtime.taskProfiles(a)));
            reject(() -> runtime.create(a, "client-profile", request("hello").put("taskProfileId", "resources-Baize2")));
            check(runtime.create(a, "client-runtime", request("hello").put("runtime", "resources")).getJSONObject("selection").getJSONObject("runtime").getString("version").equals("1"), "Named runtime was ignored");
            runtime.coordinator.step(a, first);
            runtime.coordinator.step(b, second);
            runtime.coordinator.step(a, explicit.getString("runId"));
            int transport = 101;
            for (String worker : Arrays.asList("baize-a", "baize-b", "baize2-a")) {
                JSONObject assigned = runtime.workers.apply(InferenceTest.envelope(worker, "poll", SelectionWorkers.sessions.get(worker)), transport++, () -> true).getJSONObject("job");
                check(assigned.getString("model").equals(worker.startsWith("baize2") ? "Baize2" : "Baize"), "Model request was routed to the wrong worker");
            }
            check(runtime.workers.availability(runtime.deployment.model()).getInt("available") == 0, "Busy model capacity was reported as available");
            runtime.create(a, "busy-but-online", request("queue behind busy nodes").put("model", "Baize2"));
            check(catalog.pinned(a.tenant, a.subject, first).has("execution"), "Run did not pin server execution configuration");
        }
        Properties diagnostics = new Properties(); diagnostics.putAll(p); diagnostics.setProperty("trace.enabled", "true");
        JSONObject traced = new TraceStore(store, diagnostics).export(a.tenant, a.subject, first);
        check(traced.getJSONObject("config").has("execution") && traced.getJSONObject("config").has("tenantPolicy"), "Trace did not export the independent execution and business policy commitments");
        String policyBefore = JdbcRunStore.canonical(catalog.tenantPolicy(a.tenant));
        p.setProperty("execution.model", "Baize2@2"); p.setProperty("execution.runtime", "resources@2");
        try (ExecutionRuntime changed = new ExecutionRuntime(store, p, 60000, SelectionWorkers::provider)) {
            SelectionWorkers.online(changed, p);
            check(JdbcRunStore.canonical(changed.create(a, "stable-key", request("hello"))).equals(JdbcRunStore.canonical(receipt)), "Deployment switch broke idempotent replay");
            for (ProfileRegistry.Identity identity : Arrays.asList(a,b)) {
                String run = changed.create(identity, "after-switch", request("new model")).getString("runId");
                JSONObject pin = catalog.pinned(identity.tenant, identity.subject, run);
                check(pin.getJSONObject("model").getString("id").equals("Baize2"), "Existing tenant retained an old model");
                check(pin.getJSONObject("runtime").getString("version").equals("2"), "Existing tenant retained an old executor");
                check(!pin.has("task"), "New run retained tenant task-profile coupling");
            }
            check(catalog.pinned(a.tenant, a.subject, first).getJSONObject("model").getString("id").equals("Baize"), "In-flight model snapshot changed");
            check(catalog.pinned(b.tenant, b.subject, second).getJSONObject("runtime").getString("version").equals("1"), "In-flight executor snapshot changed");
            check(policyBefore.equals(JdbcRunStore.canonical(catalog.tenantPolicy(a.tenant))), "Deployment change rewrote tenant policy");
            JSONObject restricted = catalog.tenantPolicy(a.tenant).put("version", "2").put("permissions", new JSONArray().put("skill.list"));
            restricted.getJSONObject("businessLimits").put("maxLifetimeMillis", 1000).put("maxActiveRuns", changed.jobs.active(a.tenant) + 1);
            new ProfileRegistry(catalog).publish(a.tenant, restricted);
            catalog.bind(a.tenant, "policy", "default", "default@2", 1);
            String run = changed.create(a, "restricted", request("limited")).getString("runId");
            JSONObject pin = catalog.pinned(a.tenant, a.subject, run);
            check(pin.getJSONObject("execution").getJSONObject("executionLimits").getLong("maxLifetimeMillis") == 1000, "Business lifetime limit was ignored");
            check(pin.getJSONArray("permissions").length() == 1, "Tenant authorization was ignored");
            try { changed.create(a, "over-limit", request("limited")); throw new AssertionError("Business admission limit was ignored"); }
            catch (StoreException expected) { check(expected.code.equals("HARNESS_BUSY"), expected.code); }
            check(changed.create(a, "restricted", request("limited")).getString("runId").equals(run), "Admission limit blocked idempotent replay");
        }
        try (Connection c = config.connect(); Statement q = c.createStatement(); ResultSet rows = q.executeQuery("SELECT COUNT(*) FROM harness_profile WHERE profile_kind<>'policy'")) {
            check(rows.next() && rows.getInt(1) == 0, "Server models or executors were stored under tenants");
        }
        RuntimeTest.fail("EXECUTION_CONFIGURATION_IS_SERVER_MANAGED", () -> new ProfileRegistry(catalog).publish(a.tenant, DefaultProfiles.runtime()));
        reject(() -> catalog.putProfile(a.tenant, DefaultProfiles.runtime()));
        RuntimeTest.fail("INVALID_BINDING_KIND", () -> catalog.bind(a.tenant, "task", "default", "default@1", 0));
        executableSwitch();
        System.out.println("PASS: input-only creation, server model/executor switch across existing tenants, pinned in-flight runs, stable replay and tenant-only authorization/business limits");
    }
    private static void executableSwitch() throws Exception {
        ToolTest.prepare(Files.createTempDirectory("harness-executor-switch-"), "");
        Properties settings = new Properties();
        try (java.io.InputStream input = Files.newInputStream(RuntimeTest.root.resolve("store.local.properties"))) { settings.load(input); }
        settings.setProperty("execution.repository", RuntimeTest.root.resolve("repository").toString());
        settings.setProperty("execution.repository.namespace", "runtime-test-repo");
        CatalogStore catalog = RuntimeTest.catalog;
        ProfileRegistry.Identity actor = RuntimeTest.identity;
        String before = JdbcRunStore.canonical(catalog.tenantPolicy(actor.tenant));
        SelectionWorkers.tools(settings);
        String old;
        try (ExecutionRuntime runtime = new ExecutionRuntime(RuntimeTest.store, settings, 60000)) {
            SelectionWorkers.online(runtime, settings);
            old = runtime.create(actor, "executor-before", request("query")).getString("runId");
        }
        JSONObject executor = RuntimeSupport.copy(RuntimeTest.runtime).put("id", "new-executor").put("version", "2").put("toolSetVersion", "broker-tools");
        Files.write(Paths.get(settings.getProperty("execution.profiles")).resolve("runtime.json"), RuntimeSupport.bytes(executor));
        settings.setProperty("execution.runtime", "new-executor@2");
        try (ExecutionRuntime runtime = new ExecutionRuntime(RuntimeTest.store, settings, 60000)) {
            SelectionWorkers.online(runtime, settings);
            String run = runtime.create(actor, "executor-after", request("query")).getString("runId");
            JSONObject pin = catalog.pinned(actor.tenant, actor.subject, run);
            check(pin.getJSONObject("runtime").getString("id").equals("new-executor"), "Executable skill retained an executor binding");
            check(pin.getJSONArray("releases").getJSONObject(0).getString("digest").equals(RuntimeTest.release), "Executor switch required republishing the tenant skill");
            check(before.equals(JdbcRunStore.canonical(catalog.tenantPolicy(actor.tenant))), "Executor switch rewrote tenant policy");
            check(catalog.pinned(actor.tenant, actor.subject, old).getJSONObject("runtime").getString("id").equals("cpu"), "Old executable run lost its executor snapshot");
        }
        System.out.println("PASS: executable skill survives server executor id/version change without tenant or package updates");
    }

}
