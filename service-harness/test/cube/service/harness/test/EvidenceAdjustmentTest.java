package cube.service.harness.test;

import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import cube.service.harness.execution.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Real SQLite/checkpoint/trace tests; scripted outcomes do not measure model quality. */
public final class EvidenceAdjustmentTest {
    static JSONObject error() {
        return new JSONObject().put("status", "error").put("errorCode", "SCHEMA_INVALID").put("artifacts", new JSONArray());
    }
    static JSONObject memory(String run) throws Exception { return ProgressTest.state(run).getJSONObject("taskState").getJSONObject("workingMemory"); }

    static void recovery() throws Exception {
        String run = run("adjustment", "Correct failed parameters without losing original failures");
        JdbcRunStore.Lease lease = running(run, 60000);
        ProgressTest.step(lease, 0, "resource.read", ProgressTest.args(0), error());
        ProgressTest.step(lease, 1, "resource.read", ProgressTest.args(0), error());
        ProgressTest.step(lease, 2, "resource.read", ProgressTest.args(1), ProgressTest.success("private-corrected-result"));
        JSONObject memory = memory(run);
        check(memory.getJSONArray("errorResolutions").length() == 2, "earlier failure was overwritten");
        check(memory.getJSONArray("unresolvedErrors").length() == 2, "different arguments falsely proved recovery");
        check(memory.getJSONArray("unresolvedErrors").getJSONObject(0).getJSONArray("successfulFollowUps").getJSONObject(0)
                .getString("intent").equals("p2/1"), "changed-argument success not linked");
        ProgressTest.step(lease, 3, "resource.read", ProgressTest.args(0), ProgressTest.success("same-args-recovered"));
        check(memory(run).getJSONArray("unresolvedErrors").length() == 0, "exact successful retry not linked to all failures");
        JSONObject snapshot = ProgressTest.state(run);
        JSONObject saved = contexts.checkpoint(lease, version(run), snapshot);
        check(JdbcRunStore.hash(contexts.restore(identity, run, saved.getString("digest"))).equals(JdbcRunStore.hash(snapshot)), "restore lost observation");
        check(snapshot.getJSONObject("decisionObservation").getJSONObject("previousModelDecision").getString("intent").equals("round-3/1"), "previous decision lost");
        check(snapshot.getJSONObject("decisionObservation").getJSONArray("recentResults").length() == 4, "result history incomplete");
        JSONObject basis = catalog.contextBasis(T, S, run);
        JSONArray facts = new JSONArray(snapshot.getJSONArray("facts").toString());
        // A success from a newer input revision cannot resolve an older request's failure.
        for (Object entry : facts) if (((JSONObject)entry).getString("intent").equals("p3/1")) ((JSONObject)entry).put("inputRevision", 1);
        basis.put("inputRevision", 1);
        JSONObject revised = TaskState.derive(basis, facts, new JSONArray(), new JSONArray()).getJSONObject("workingMemory");
        check(revised.getJSONArray("unresolvedErrors").length() == 0, "superseded errors remained actionable");
        check(!revised.getJSONArray("errorResolutions").getJSONObject(0).has("resolvedBy"), "new user scope falsely resolved old error");
    }

    static void policyAndBudgets() throws Exception {
        fail("EVIDENCE_ADJUSTMENT_POLICY_INVALID", () -> ContextPolicy.validate(new JSONObject().put("evidenceAdjustment", true)));
        String run = run("adjustment", "Use original evidence without changing authorization or budgets");
        JdbcRunStore.Lease lease = running(run, 60000);
        ProgressTest.step(lease, 0, "resource.read", ProgressTest.args(0), error());
        ProgressTest.step(lease, 1, "resource.read", ProgressTest.args(1), ProgressTest.success("private-corrected-result"));
        JSONObject snapshot = ProgressTest.state(run), remaining = snapshot.getJSONObject("taskState").getJSONObject("remaining");
        check(remaining.getInt("rounds") == 6 && remaining.getInt("tools") == 30, "adjustment reset budgets");
        int policies = 0;
        for (Object entry : snapshot.getJSONObject("request").getJSONArray("messages")) {
            JSONObject message = (JSONObject)entry;
            if (message.getString("provenance").equals("platform:evidence-adjustment")) policies++;
            if (message.getString("provenance").equals("task-working-memory")) check(message.getString("role").equals("user"), "evidence elevated to system");
        }
        check(policies == 1, "candidate instruction missing");
        String baseline = run("adjustment-baseline", "Same measurement, existing behavior");
        JSONObject base = ProgressTest.state(baseline);
        check(base.has("decisionObservation"), "baseline instrumentation missing");
        check(!base.getJSONObject("request").toString().contains("platform:evidence-adjustment"), "baseline received candidate policy");
        // Normal coordinator writes observations durably, independent of model worker memory.
        store.release(lease, version(run));
        try (RunCoordinator coordinator = new RunCoordinator(store, new ModelJobStore(store, config.mysql()), catalog, identity, resources)) {
            coordinator.step(run);
        }
        Properties settings = new Properties(); settings.setProperty("trace.enabled", "true");
        JSONObject trace = new TraceStore(store, settings).export(T, S, run);
        check(trace.getJSONObject("metrics").getInt("observedModelRounds") == 1, "coordinator observation was not exported");
        check(!trace.toString().contains("private-corrected-result"), "observation leaked tool content");
        check(trace.getJSONObject("metrics").getString("adjustmentControlDigest").equals(
                new TraceStore(store, settings).export(T, S, baseline).getJSONObject("metrics").getString("adjustmentControlDigest")), "paired controls changed beyond switch/version");
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1) {
            try (java.io.InputStream in = Files.newInputStream(Paths.get(arguments[0]))) {
                PackageRepository.Validated checked = new PackageRepository(null, null, Collections.emptyMap(), false).validate(in);
                check(checked.manifest.getString("name").equals("evaluation/evidence-adjustment")
                        && checked.manifest.getJSONArray("tools").length() == 0, "evaluation package was not resource-only");
            }
        }
        ExecutionTest.prepare(Files.createTempDirectory("harness-adjustment-"), "");
        profiles.publish(T, RuntimeSupport.copy(model).put("id", "adjustment-large").put("maxContextTokens", 200000));
        JSONObject candidate = task("adjustment", release, "adjustment-large", 128);
        candidate.getJSONObject("contextPolicy").put("taskState", true).put("evidenceAdjustment", true).put("historyWindow", 1);
        profiles.publish(T, candidate);
        JSONObject baseline = RuntimeSupport.copy(candidate).put("version", "adjustment-baseline");
        baseline.getJSONObject("contextPolicy").put("evidenceAdjustment", false);
        profiles.publish(T, baseline);
        recovery(); policyAndBudgets();
        System.out.println("PASS: failure retention, conservative recovery linkage, revision isolation, shared budgets, policy switch, durable observations, trace redaction and matched controls");
    }
}
