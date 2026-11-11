package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import org.json.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Actual coordinator/JDBC/model ledger, with a controllable trusted completion extension. */
public final class WorkflowExecutionTest {
    public static class FixtureGuard implements CompletionGuard {
        static boolean satisfied;
        public FixtureGuard(Properties p) {}
        public void validate(Resources resources) throws Exception { resources.json(FILE); }
        public JSONObject check(Resources resources, JSONArray facts) {
            return new JSONObject().put("satisfied", satisfied).put("maxRepairs", 2)
                    .put("missing", satisfied ? new JSONArray() : new JSONArray().put(new JSONObject().put("code", "QUERY_REQUIRED")));
        }
    }
    public static final class ReportGuard extends FixtureGuard {
        static boolean valid;
        public ReportGuard(Properties p) { super(p); }
        public boolean supportsReports() { return true; }
        public void validateReport(Resources resources) throws Exception { resources.json(REPORT_FILE); }
        public JSONObject checkFinal(Resources resources, JSONArray facts, JSONObject decision, String skill, Set<String> skills) {
            JSONObject out = check(resources, facts).put("satisfied", valid);
            if (valid) out.put("report", new JSONObject().put("summary", "host-normalized fixture").put("datasets", new JSONArray()));
            else out.put("missing", new JSONArray().put(new JSONObject().put("code", "REPORT_CITATION_INVALID")));
            return out;
        }
    }
    private static ModelJobStore jobs;
    private static ToolBroker broker;
    private static RunCoordinator coordinator;
    private static String guarded;
    private static final JSONObject descriptor = ExecutionTest.descriptor("workflow-backend");
    private static JSONObject poll() { return jobs.poll("workflow-worker", "instance", "session", descriptor, 1, true); }
    private static JSONObject dispatch(String run) throws Exception {
        coordinator.step(run);
        JSONObject job = poll();
        check(job != null && job.getString("runId").equals(run), "model round dispatched for " + run);
        return job;
    }
    private static void complete(JSONObject job) {
        jobs.report("workflow-worker", ExecutionTest.report(job, new JSONObject().put("kind", "final").put("text", "fixture final"), true));
    }
    private static String state(String run) { return store.read(T, S, run).getJSONObject("snapshot").getString("state"); }
    private static String create() {
        String run = profiles.createRun(identity, key(), "workflow", request("required workflow")).getString("runId");
        resources.activate(identity, run, guarded);
        return run;
    }
    private static void restart(String run) throws Exception {
        coordinator.close();
        // Simulate the dead owner's expired lease without a wall-clock wait.
        try (Connection c = config.connect(); PreparedStatement p = c.prepareStatement("UPDATE harness_run SET lease_until=0 WHERE run_id=?")) {
            p.setString(1, run); p.executeUpdate();
        }
        coordinator = new RunCoordinator(store, jobs, catalog, identity, resources, broker);
    }
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-workflow-"), "");
        Map<String, byte[]> content = entries("Workflow execution fixture");
        content.put(CompletionGuard.FILE, bytes("{\"version\":\"1\"}"));
        JSONObject manifest = manifest(content).put("version", "3");
        byte[] zip = zip(manifest, content);
        guarded = upload(zip).getString("digest");
        catalog.lifecycle(T, guarded, 1, "ready");
        JSONObject task = task("1", guarded, "scripted", 1024).put("id", "workflow").put("schemaVersion", 2)
                .put("executionLimits", new JSONObject().put("maxRounds", 8).put("maxTools", 16).put("maxRepairs", 2).put("maxLifetimeMillis", 60000));
        task.getJSONObject("contextPolicy").put("maxInputBytes", 48000);
        profiles.publish(T, task);
        profiles.bind(T, "task", "workflow", "workflow@1", 0);
        for (String id : Arrays.asList("no-workflow-repairs", "one-workflow-round")) {
            JSONObject constrained = RuntimeSupport.copy(task).put("id", id);
            constrained.getJSONObject("executionLimits").put(id.equals("no-workflow-repairs") ? "maxRepairs" : "maxRounds", id.equals("no-workflow-repairs") ? 0 : 1);
            profiles.publish(T, constrained); profiles.bind(T, "task", id, id + "@1", 0);
        }
        Properties settings = new Properties();
        settings.setProperty("runner.grant.key", key() + key());
        settings.setProperty("broker.node.id", "workflow-node");
        Path privateConfig = root.resolve("guard.properties"); Files.write(privateConfig, bytes(""));
        try (ToolBroker missing = new ToolBroker(new ToolJobStore(store, false), identity, settings)) {
            fail("WORKFLOW_GUARD_UNAVAILABLE", () -> missing.validatePackage(repo.validate(new java.io.ByteArrayInputStream(zip))));
            check(missing.businessReadiness(identity, new JSONArray().put(new JSONObject().put("manifest", manifest).put("digest", guarded)), repo)
                    .getString(guarded).equals("WORKFLOW_GUARD_UNAVAILABLE"), "guard required at admission");
        }
        settings.setProperty("broker.completion.guard.class", FixtureGuard.class.getName());
        settings.setProperty("broker.completion.guard.config", privateConfig.toString());
        broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).context(resources, new ProviderStore(store, false), () -> null);
        jobs = new ModelJobStore(store, false); jobs.backend("workflow-backend", descriptor, 1);
        coordinator = new RunCoordinator(store, jobs, catalog, identity, resources, broker);
        try {
            FixtureGuard.satisfied = false;
            String run = create();
            complete(dispatch(run)); coordinator.step(run);
            check(state(run).equals("running") && catalog.workflowRepairs(T, S, run).length() == 1, "jumped final is withheld and repair durable");
            JSONObject repair = poll();
            check(repair.getString("requestJson").contains("platform:workflow-guard") && repair.getString("requestJson").contains("QUERY_REQUIRED"), "gap feedback enters next model context");
            FixtureGuard.satisfied = true;
            complete(repair); coordinator.step(run);
            check(state(run).equals("succeeded"), "completed workflow can finish");

            FixtureGuard.satisfied = false;
            run = create();
            for (int i = 0; i < 3; i++) {
                complete(dispatch(run)); coordinator.step(run);
                if (i < 2) restart(run);
            }
            check(state(run).equals("failed") && catalog.workflowRepairs(T, S, run).length() == 2, "restart never resets finite repair budget");
            check(store.read(T, S, run).toString().contains("WORKFLOW_REQUIREMENTS_UNMET"), "bounded failure is explicit");

            // Reproduce a crash precisely after repair event commit and before next round intent.
            run = create();
            JdbcRunStore.Lease lease = running(run, 60000);
            JSONObject context = contexts.assemble(identity, run, new JSONArray());
            contexts.checkpoint(lease, version(run), context);
            JSONObject request = ContextEngine.modelInput(context).put("maxRepairs", 1);
            store.intent(lease, version(run), "round", "round-0001", 1,
                    new JSONObject().put("contextDigest", JdbcRunStore.hash(context)).put("request", request), context.getLong("inputTokens"), context.getLong("outputReserve"));
            jobs.enqueue(lease, row(run, "round", "round-0001"), profiles.pinned(identity, run).getJSONObject("model"));
            complete(poll());
            confirm(lease, "round", "round-0001", new JSONObject().put("kind", "final").put("text", "fixture final"));
            JSONObject assessment = broker.completion(identity, run, profiles.pinned(identity, run), catalog.activeSkills(T, S, run));
            long expected = version(run);
            catalog.workflowRepair(lease, expected, "round-0001", assessment);
            catalog.workflowRepair(lease, expected, "round-0001", assessment);
            check(catalog.workflowRepairs(T, S, run).length() == 1, "repair commit replay is idempotent despite stale expected version");
            store.release(lease, version(run));
            restart(run);
            JSONObject resumed = dispatch(run);
            check(catalog.workflowRepairs(T, S, run).length() == 1 && resumed.getString("roundId").equals("round-0002"), "event-only recovery advances without double charging");
            FixtureGuard.satisfied = true; complete(resumed); coordinator.step(run);
            check(state(run).equals("succeeded"), "recovered workflow finishes");

            FixtureGuard.satisfied = false;
            String ordinary = ExecutionTest.create("ordinary unguarded task");
            complete(dispatch(ordinary)); coordinator.step(ordinary);
            check(state(ordinary).equals("succeeded") && catalog.workflowRepairs(T, S, ordinary).length() == 0, "legacy packages keep ordinary final behavior");
            for (String id : Arrays.asList("no-workflow-repairs", "one-workflow-round")) {
                String constrained = profiles.createRun(identity, key(), id, request("constrained workflow")).getString("runId");
                resources.activate(identity, constrained, guarded);
                complete(dispatch(constrained)); coordinator.step(constrained);
                check(state(constrained).equals("failed") && catalog.workflowRepairs(T, S, constrained).length() == 0, "M3 cannot expand " + id + " execution budget");
            }
            content.put(CompletionGuard.REPORT_FILE, bytes("{\"version\":\"1\"}"));
            JSONObject reportManifest = manifest(content).put("version", "4");
            byte[] reportZip = zip(reportManifest, content);
            fail("REPORT_GUARD_UNAVAILABLE", () -> broker.validatePackage(repo.validate(new java.io.ByteArrayInputStream(reportZip))));
            guarded = upload(reportZip).getString("digest"); catalog.lifecycle(T, guarded, 1, "ready");
            JSONObject reportTask = RuntimeSupport.copy(task).put("version", "2").put("skillReleases", new JSONArray().put(guarded));
            profiles.publish(T, reportTask); profiles.bind(T, "task", "workflow", "workflow@2", 1);
            coordinator.close(); broker.close();
            settings.setProperty("broker.completion.guard.class", ReportGuard.class.getName());
            broker = new ToolBroker(new ToolJobStore(store, false), identity, settings).context(resources, new ProviderStore(store, false), () -> null);
            broker.validatePackage(repo.validate(new java.io.ByteArrayInputStream(reportZip)));
            coordinator = new RunCoordinator(store, jobs, catalog, identity, resources, broker);
            ReportGuard.valid = false;
            run = create(); complete(dispatch(run)); coordinator.step(run);
            check(state(run).equals("running"), "bad report withheld after workflow completion");
            repair = poll(); check(repair.getString("requestJson").contains("REPORT_CITATION_INVALID"), "report feedback enters context");
            ReportGuard.valid = true; complete(repair); coordinator.step(run);
            JSONObject delivered = store.read(T, S, run).getJSONObject("result").getJSONObject("result");
            check(state(run).equals("succeeded") && delivered.getJSONObject("report").getString("format").equals("evidence-report-v1"), "normalized report delivered");
            check(new JSONObject(delivered.getString("text")).similar(delivered.getJSONObject("report")), "text mirrors normalized report");
            check(delivered.getJSONObject("report").getJSONArray("reports").getJSONObject(0).has("requirementsDigest"), "fixed requirements provenance retained");
            restart(run);
            check(store.read(T, S, run).getJSONObject("result").getJSONObject("result").similar(delivered), "terminal report survives restart unchanged");
            ReportGuard.valid = false; run = create();
            for (int i = 0; i < 3; i++) { complete(dispatch(run)); coordinator.step(run); if (i < 2) restart(run); }
            check(state(run).equals("failed") && catalog.workflowRepairs(T, S, run).length() == 2, "bad reports cannot reset repair budget across restart");
            check(store.read(T, S, run).toString().contains("REPORT_REQUIREMENTS_UNMET"), "bounded report failure explicit");
            System.out.println("PASS M3/M4 coordinator: workflow/report withholding, normalized durable report, finite shared repairs across restart, event-only crash recovery, legacy final compatibility.");
        } finally { coordinator.close(); broker.close(); }
    }
}
