package cube.service.harness.test;

import cube.service.harness.execution.*;
import cube.service.harness.runtime.*;
import cube.service.harness.store.*;
import cube.service.harness.protocol.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static cube.service.harness.test.RuntimeTest.*;

/** Regression tests for evidence survival, adaptive selection and final inference budgets. */
public final class ContextCompactionTest {
    static String text(int size) { char[] chars = new char[size]; Arrays.fill(chars, 'x'); return new String(chars); }
    static JSONObject visible(JSONObject snapshot, String proposal) {
        for (Object entry : snapshot.getJSONObject("request").getJSONArray("messages")) {
            JSONObject message = (JSONObject)entry;
            if (message.getString("role").equals("tool") && message.optString("proposalId").equals(proposal))
                return new JSONObject(message.getJSONObject("content").getString("dataJson"));
        }
        throw new AssertionError("missing tool result " + proposal);
    }
    static void rendering() throws Exception {
        JSONArray vectors = (JSONArray)StrictJson.parse(Contract.resource("inference/wire-vectors.json"));
        for (Object entry : vectors) {
            JSONObject vector = (JSONObject)entry;
            JSONObject actual = ModelWire.prepare(vector.getJSONObject("model"), vector.getJSONObject("request"));
            if (!JdbcRunStore.hash(actual).equals(vector.getString("digest"))) {
                String a = JdbcRunStore.canonical(actual), b = JdbcRunStore.canonical(vector.getJSONObject("wire")); int n = 0;
                while (n < Math.min(a.length(), b.length()) && a.charAt(n) == b.charAt(n)) n++;
                throw new AssertionError("Java/Python final wire mismatch at " + n + ": " + a.substring(Math.max(0,n-80), Math.min(a.length(),n+160))
                        + " versus " + b.substring(Math.max(0,n-80), Math.min(b.length(),n+160)));
            }
        }
        JSONObject summary = StrictJson.object(Contract.resource("inference/summary-vectors.json"));
        check(JdbcRunStore.hash(ContextSummary.wire(summary.getJSONObject("spec"))).equals(summary.getString("wireDigest")), "Java/Python summary wire mismatch");
        check(JdbcRunStore.hash(ContextSummary.validate(summary.getJSONObject("spec"), summary.getJSONObject("response")))
                .equals(JdbcRunStore.hash(summary.getJSONObject("summary"))), "Java/Python summary validation mismatch");
    }
    static void integrity() throws Exception {
        Map<String, byte[]> entries = entries("Read the complete resource and preserve exact links");
        StringBuilder randomText = new StringBuilder(); Random random = new Random(8);
        for (int i = 0; i < 12000; i++) randomText.append((char)('a' + random.nextInt(26)));
        entries.put("guide.txt", randomText.toString().getBytes(StandardCharsets.UTF_8));
        String source = upload(zip(manifest(entries), entries)).getString("digest"); catalog.lifecycle(T, source, 1, "ready");
        JSONObject task = task("context-integrity", source, "context-large", 4096);
        task.getJSONObject("contextPolicy").put("historyWindow", 3).put("taskState", true); profiles.publish(T, task);
        String run = run("context-integrity", "Read all pages"); JdbcRunStore.Lease lease = running(run, 60000);
        resources.activate(identity, run, source);
        JSONObject args = new JSONObject().put("release", source).put("path", "guide.txt").put("offset", 0).put("limit", 8192);
        JSONObject page = (JSONObject)resources.invoke(identity, run, "resource.read", args);
        ProgressTest.step(lease, 0, "resource.read", args, ProgressTest.success(page.toString()));
        JSONObject projected = visible(contexts.assemble(identity, run, new JSONArray()), "p0");
        check(projected.getInt("nextOffset") == projected.getString("text").codePointCount(0, projected.getString("text").length()), "resource cursor skipped omitted evidence");
        java.lang.reflect.Method compact = Class.forName("cube.service.harness.runtime.ContextData").getDeclaredMethod("compact", Object.class, int.class); compact.setAccessible(true);
        String url = "https://example.com/article?query=" + text(200);
        JSONArray hits = new JSONArray();
        for (int i = 0; i < 4; i++) hits.put(new JSONObject().put("snippet", text(1024)).put("url", url).put("sourceId", "source-" + i));
        JSONObject search = new JSONObject((String)compact.invoke(null, new JSONObject().put("results", hits), 4096));
        check(search.getJSONArray("results").getJSONObject(3).getString("url").equals(url), "long executable URL was shortened");
        String readRun = run("context-integrity", "Recover the entire original result"); JdbcRunStore.Lease readLease = running(readRun, 60000);
        JSONObject original = ProgressTest.success(text(5000));
        ProgressTest.step(readLease, 0, "resource.read", args, original);
        int offset = 0, n = 1;
        while (offset >= 0) {
            JSONObject read = new JSONObject().put("intent", "p0/1").put("offset", offset).put("limit", 1024);
            JSONObject data = (JSONObject)resources.invoke(identity, readRun, "context.read_result", read);
            ProgressTest.step(readLease, n++, "context.read_result", read, ProgressTest.success(data.toString()));
            offset = data.isNull("nextOffset") ? -1 : data.getInt("nextOffset");
        }
        JSONObject snapshot = contexts.assemble(identity, readRun, new JSONArray()); StringBuilder recovered = new StringBuilder();
        for (int i = 1; i < n; i++) recovered.append(visible(snapshot, "p" + i).getString("text"));
        check(recovered.toString().equals(JdbcRunStore.canonical(original)), "readback session lost earlier pages to history retirement");
        JSONObject reader = snapshot.getJSONObject("taskState").getJSONObject("workingMemory").getJSONArray("readingSessions").getJSONObject(0);
        check(reader.getBoolean("complete") && reader.isNull("nextOffset"), "completed readback session was not reconstructed");

        profiles.publish(T, ModelProfiles.model("context-wire", false, 40960).put("version", "1"));
        JSONObject online = task("context-wire", source, "context-wire", 4096);
        online.getJSONObject("contextPolicy").put("budgetVersion", "transport-bytes-v1").put("maxInputBytes", 48000).put("outputReserve", 2048).put("finalWireGuard", true);
        profiles.publish(T, online);
        String wireRun = run("context-wire", "Read and explain " + text(23000)); JdbcRunStore.Lease wireLease = running(wireRun, 60000);
        ProgressTest.step(wireLease, 0, "resource.read", args, ProgressTest.success(page.toString()));
        JSONObject wireSnapshot = contexts.assemble(identity, wireRun, new JSONArray()), pin = catalog.pinned(T, S, wireRun);
        JSONObject body = ModelWire.prepare(pin.getJSONObject("model"), wireSnapshot.getJSONObject("request"));
        new ModelBudgets().admitWire(pin, body);
        check(wireSnapshot.getJSONObject("request").getString("wireDigest").equals(JdbcRunStore.hash(body)), "measured wire digest changed");
    }
    static void workingState() throws Exception {
        String run = run("context-integrity", "Query the requested scope and correct failed parameters"); JdbcRunStore.Lease lease = running(run, 60000);
        JSONObject args = new JSONObject().put("path", "guide.txt");
        ProgressTest.step(lease, 0, "resource.read", args, new JSONObject().put("status", "error").put("artifacts", new JSONArray()).put("errorCode", "SCHEMA_INVALID"));
        ProgressTest.step(lease, 1, "resource.read", args, ProgressTest.success("corrected"));
        ProgressTest.step(lease, 2, "common.data.query", new JSONObject().put("skill", "test/demo").put("connectorId", "records")
                .put("argumentsJson", "{\"scope\":\"north\"}"), ProgressTest.success(new JSONObject().put("pageRef", "page-exact")
                        .put("nextCursor", "cursor-exact").put("complete", false).toString()));
        JSONObject snapshot = contexts.assemble(identity, run, new JSONArray()), memory = snapshot.getJSONObject("taskState").getJSONObject("workingMemory");
        check(memory.getJSONArray("errorResolutions").getJSONObject(0).getString("resolvedBy").equals("p1/1"), "successful correction did not resolve historical error");
        check(memory.getJSONArray("unresolvedErrors").length() == 0, "resolved error still blocks next work");
        check(memory.getJSONArray("queryRoutes").getJSONObject(0).getString("connectorId").equals("records"), "query route was forgotten");
        check(memory.getJSONArray("confirmedEvidence").getJSONObject(0).getJSONObject("data").getString("nextCursor").equals("cursor-exact"), "working evidence lost exact cursor");
        JSONObject cursorArgs = new JSONObject().put("cursor", "cursor-exact");
        ProgressTest.step(lease, 3, "common.data.query", cursorArgs, new JSONObject().put("status", "error").put("errorCode", "SCHEMA_INVALID").put("artifacts", new JSONArray()));
        JSONObject failedPage = contexts.assemble(identity, run, new JSONArray()).getJSONObject("taskState").getJSONObject("workingMemory");
        check(failedPage.getJSONArray("unresolvedDependencies").length() == 1, "failed pagination consumed the cursor");
        ProgressTest.step(lease, 4, "common.data.query", cursorArgs, ProgressTest.success(new JSONObject().put("nextCursor", "cursor-exact").toString()));
        JSONObject loopPage = contexts.assemble(identity, run, new JSONArray()).getJSONObject("taskState").getJSONObject("workingMemory");
        check(loopPage.getJSONArray("unresolvedDependencies").length() == 1, "earlier consumption hid the newly returned cursor");
        for (Object entry : snapshot.getJSONObject("request").getJSONArray("messages")) {
            JSONObject message = (JSONObject)entry;
            if (message.getString("provenance").equals("task-working-memory")) check(message.getString("role").equals("user"), "tool content elevated to platform instructions");
        }
        JSONObject basis = catalog.contextBasis(T, S, run);
        basis.getJSONArray("events").put(new JSONObject().put("event_type", "control.resume").put("event_seq", 100000)
                .put("data_json", new JSONObject().put("payload", new JSONObject().put("input", new JSONObject().put("instruction", "Use the south scope"))).toString()));
        JSONObject resumed = TaskState.derive(basis, snapshot.getJSONArray("facts"), new JSONArray(), new JSONArray());
        check(resumed.getJSONObject("workingMemory").getJSONArray("userInputs").length() == 2, "user correction lost from working state");
    }
    static void adaptiveHistory() throws Exception {
        String release = catalog.pinned(T, S, run("context-integrity", "fixture")).getJSONArray("releases").getJSONObject(0).getString("digest");
        JSONObject profile = task("context-adaptive", release, "context-large", 4096);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", true).put("taskState", true).put("maxInputBytes", 60000);
        profiles.publish(T, profile);
        String run = run("context-adaptive", "Use the first result's dataset and preserve pagination"); JdbcRunStore.Lease lease = running(run, 60000);
        ProgressTest.step(lease, 0, "common.data.query", new JSONObject().put("scope", "north"), ProgressTest.success(new JSONObject()
                .put("datasetRef", "dataset-essential").put("records", new JSONArray().put(new JSONObject().put("name", "a/b").put("id", "record-exact"))).toString()));
        for (int i = 1; i < 6; i++) ProgressTest.step(lease, i, "resource.read", new JSONObject().put("page", i),
                ProgressTest.success(new JSONObject().put("unknownExact", text(4500)).toString()));
        ProgressTest.step(lease, 6, "common.data.query", new JSONObject().put("datasetRef", "dataset-essential"),
                ProgressTest.success(new JSONObject().put("nextCursor", "cursor-unconsumed").put("partial", true).toString()));
        JSONObject full = contexts.assemble(identity, run, new JSONArray());
        check(full.getJSONObject("historySelection").getInt("retainedToolRounds") > 3, "ample budget still uses fixed recent-three history");
        JSONObject pointer = (JSONObject)resources.invoke(identity, run, "context.read_result", new JSONObject().put("intent", "p0/1")
                .put("offset", 0).put("limit", 1024).put("selector", "/records/0"));
        check(pointer.getBoolean("complete") && pointer.getJSONObject("value").getString("id").equals("record-exact"), "complete selected record was not recovered");
        fail("CONTEXT_RESULT_SELECTION_TOO_LARGE", () -> resources.invoke(identity, run, "context.read_result", new JSONObject().put("intent", "p1/1")
                .put("offset", 0).put("limit", 2).put("selector", "/unknownExact")));
        JSONObject tight = RuntimeSupport.copy(profile).put("version", "context-adaptive-tight"); tight.getJSONObject("contextPolicy").put("maxInputBytes", 22000); profiles.publish(T, tight);
        String limited = run("context-adaptive-tight", "Use the first result's dataset and preserve pagination"); JdbcRunStore.Lease limitedLease = running(limited, 60000);
        for (Object entry : full.getJSONArray("facts")) {
            JSONObject f = (JSONObject)entry; if (!f.getString("kind").equals("tool")) continue;
            int n = Integer.parseInt(f.getString("intent").substring(1, f.getString("intent").indexOf('/')));
            ProgressTest.step(limitedLease, n, f.getJSONObject("payload").getString("tool"), new JSONObject(f.getJSONObject("payload").getString("argumentsJson")), f.getJSONObject("result"));
        }
        JSONObject compact = contexts.assemble(identity, limited, new JSONArray());
        check(compact.getJSONObject("historySelection").getJSONArray("archived").length() > 0, "budget pressure did not archive consumed groups");
        check(visible(compact, "p0").getString("datasetRef").equals("dataset-essential"), "older referenced evidence was retired");
        check(visible(compact, "p6").getString("nextCursor").equals("cursor-unconsumed"), "unconsumed cursor was retired");
        check(compact.getString("requestDigest").equals(contexts.assemble(identity, limited, new JSONArray()).getString("requestDigest")), "adaptive reconstruction is not deterministic");
    }
    static void paginationChain() throws Exception {
        JSONObject profile = task("context-pagination", release, "context-large", 4096);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", true).put("taskState", true).put("maxInputBytes", 30000);
        profiles.publish(T, profile);
        String run = run("context-pagination", "Read all requested pages and summarize the records");
        JdbcRunStore.Lease lease = running(run, 60000);
        for (int n = 0; n < 6; n++) {
            JSONObject route = new JSONObject(); if (n > 0) route.put("cursor", "cursor-" + n);
            JSONObject args = new JSONObject().put("skill", "test/demo").put("connectorId", "records").put("argumentsJson", route.toString());
            JSONArray rows = new JSONArray();
            for (int i = 0; i < 120; i++) rows.put(new JSONObject().put("id", "record-" + n + "-" + i).put("value", i));
            JSONObject data = new JSONObject().put("records", rows).put("nextCursor", "cursor-" + (n + 1));
            ProgressTest.step(lease, n, "common.data.query", args, ProgressTest.success(data.toString()));
        }
        JSONObject snapshot = contexts.assemble(identity, run, new JSONArray());
        check(snapshot.getJSONObject("historySelection").getJSONArray("archived").length() > 0, "consumed cursor chain prevented retirement");
        check(visible(snapshot, "p5").getString("nextCursor").equals("cursor-6"), "active pagination cursor was lost");
        check(snapshot.getJSONObject("taskState").getJSONObject("workingMemory").getJSONArray("unresolvedDependencies").length() == 1,
                "consumed pagination cursors remained active");
        JSONObject recovered = (JSONObject)resources.invoke(identity, run, "context.read_result", new JSONObject().put("intent", "p0/1")
                .put("offset", 0).put("limit", 1024).put("selector", "/records/0"));
        check(recovered.getJSONObject("value").getString("id").equals("record-0-0"), "archived page could not be recovered");
    }
    static void interleavedReadback() throws Exception {
        for (boolean adaptive : new boolean[]{true, false}) interleavedReadback(adaptive);
    }
    static void interleavedReadback(boolean adaptive) throws Exception {
        String version = "context-interleaved-" + adaptive;
        JSONObject profile = task(version, release, "context-large", 4096);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", adaptive).put("historyWindow", 1).put("taskState", true).put("maxInputBytes", 24000);
        profiles.publish(T, profile);
        String run = run(version, "Reconstruct the original result while making an unrelated query between pages");
        JdbcRunStore.Lease lease = running(run, 60000);
        JSONObject original = ProgressTest.success(new JSONObject().put("unknownExact", text(1100)).toString());
        ProgressTest.step(lease, 0, "common.data.query", new JSONObject(), original);
        JSONObject firstArgs = new JSONObject().put("intent", "p0/1").put("offset", 0).put("limit", 1024);
        JSONObject first = (JSONObject)resources.invoke(identity, run, "context.read_result", firstArgs);
        ProgressTest.step(lease, 1, "context.read_result", firstArgs, ProgressTest.success(first.toString()));
        ProgressTest.step(lease, 2, "common.data.query", new JSONObject().put("independent", true), ProgressTest.success(new JSONObject().put("unknownExact", text(10000)).toString()));
        JSONObject middle = contexts.assemble(identity, run, new JSONArray());
        JSONObject hidden = middle.getJSONObject("taskState").getJSONObject("workingMemory").getJSONArray("readingSessions").getJSONObject(0);
        check(!hidden.getBoolean("visibleComplete"), "historical read coverage was confused with current visibility");
        if (!adaptive) check(hidden.getJSONArray("visibleRanges").length() == 0, "fixed window did not archive the first readback page");
        contexts.checkpoint(lease, middle.getLong("basisVersion"), middle);
        JSONObject secondArgs = new JSONObject().put("intent", "p0/1").put("offset", first.getInt("nextOffset")).put("limit", 1024);
        JSONObject second = (JSONObject)resources.invoke(identity, run, "context.read_result", secondArgs);
        ProgressTest.step(lease, 3, "context.read_result", secondArgs, ProgressTest.success(second.toString()));
        JSONObject snapshot = contexts.assemble(identity, run, new JSONArray());
        check((visible(snapshot, "p1").getString("text") + visible(snapshot, "p3").getString("text")).equals(JdbcRunStore.canonical(original)),
                "resumed readback did not restore the earlier archived page");
        JSONObject session = snapshot.getJSONObject("taskState").getJSONObject("workingMemory").getJSONArray("readingSessions").getJSONObject(0);
        check(session.getBoolean("complete") && session.getBoolean("visibleComplete"), "complete readback visibility was not measured");
    }
    static void watermarks() throws Exception {
        JSONObject policy = new JSONObject().put("adaptiveHistory", true).put("compressionTriggerPercent", 60).put("compressionTargetPercent", 80);
        fail("CONTEXT_THRESHOLDS_INVALID", () -> ContextPolicy.validate(policy));
        String release = catalog.pinned(T, S, run("context-integrity", "fixture")).getJSONArray("releases").getJSONObject(0).getString("digest");
        JSONObject profile = task("context-watermarks", release, "context-large", 4096);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", true).put("maxInputBytes", 36000)
                .put("compressionTriggerPercent", 80).put("compressionTargetPercent", 60).put("repairReserveBytes", 2048).put("repairReserveTokens", 1024);
        profiles.publish(T, profile);
        String run = run("context-watermarks", "Summarize all evidence"); JdbcRunStore.Lease lease = running(run, 60000);
        for (int i = 0; i < 6; i++) ProgressTest.step(lease, i, "resource.read", new JSONObject().put("page", i),
                ProgressTest.success(new JSONObject().put("unknownExact", text(4000)).toString()));
        JSONObject before = contexts.assemble(identity, run, new JSONArray()), state = before.getJSONObject("compressionState");
        check(state.getBoolean("pressureTriggered") && state.getBoolean("targetReached"), "early trigger did not reach lower watermark");
        check(before.getString("rendered").getBytes(StandardCharsets.UTF_8).length < 36000 - 2048, "repair reserve was consumed");
        contexts.checkpoint(lease, before.getLong("basisVersion"), before);
        JSONObject after = contexts.assemble(identity, run, new JSONArray());
        check(before.getString("requestDigest").equals(after.getString("requestDigest")), "checkpoint re-expanded compacted context");
        check(!after.getJSONObject("compressionState").getBoolean("pressureTriggered"), "compression retriggered below high watermark");

        JSONObject fixed = task("context-unreachable-target", release, "context-large", 4096);
        fixed.getJSONObject("contextPolicy").put("adaptiveHistory", true).put("maxInputBytes", 40000)
                .put("compressionTriggerPercent", 80).put("compressionTargetPercent", 60);
        profiles.publish(T, fixed);
        String fixedRun = run("context-unreachable-target", "Explain the confirmed evidence for " + text(23000));
        JdbcRunStore.Lease fixedLease = running(fixedRun, 60000);
        resources.activate(identity, fixedRun, release);
        ProgressTest.step(fixedLease, 0, "common.data.query", new JSONObject().put("page", 0), ProgressTest.success(new JSONObject().put("text", text(4096)).toString()));
        JSONObject preserved = contexts.assemble(identity, fixedRun, new JSONArray());
        check(visible(preserved, "p0").getString("text").length() == 4096, "unreachable soft watermark erased useful evidence");
        check(preserved.getJSONObject("compressionState").getBoolean("targetUnreachable") && !preserved.getJSONObject("compressionState").getBoolean("targetReached"),
                "unreachable target was not distinguished from successful compression");
        check(RuntimeSupport.bytes(preserved.getJSONObject("request")).length < 40000, "soft-target fallback exceeded the hard budget");
        for (Object entry : preserved.getJSONObject("request").getJSONArray("messages")) {
            JSONObject message = (JSONObject)entry;
            if (message.getString("provenance").equals("skill-resource")) check(message.getJSONObject("content").getInt("nextOffset") == -1,
                    "restoring the admitted resource view retained a truncated cursor");
        }
        contexts.checkpoint(fixedLease, preserved.getLong("basisVersion"), preserved);
        check(contexts.assemble(identity, fixedRun, new JSONArray()).getString("requestDigest").equals(preserved.getString("requestDigest")),
                "soft-target fallback changed across checkpoint");
    }
    static JSONObject response(JSONObject content) {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", content.toString()))))
                .put("usage", new JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5));
    }
    static void summaryWireBudget() throws Exception {
        profiles.publish(T, ModelProfiles.model("context-summary-large", false, 200000).put("version", "1"));
        JSONObject profile = task("context-summary-headroom", release, "context-summary-large", 4096);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", true).put("taskState", true).put("maxInputBytes", 30000)
                .put("budgetVersion", "transport-bytes-v1").put("finalWireGuard", true).put("compressionTriggerPercent", 80).put("compressionTargetPercent", 60)
                .put("semanticSummary", new JSONObject().put("enabled", true).put("maxCalls", 1).put("outputReserve", 2048).put("maxSummaryBytes", 4096));
        profiles.publish(T, profile);
        int scheduled = 0;
        for (int pages : new int[]{3, 7}) {
            String run = run("context-summary-headroom", "Summarize original records and preserve literal escaped text");
            JdbcRunStore.Lease lease = running(run, 60000); resources.activate(identity, run, release);
            for (int n = 0; n < 3; n++) ProgressTest.step(lease, n, "resource.read", new JSONObject().put("page", n),
                    ProgressTest.success(new JSONObject().put("unknownExact", text(4500)).put("value", n).toString()));
            JSONArray handles = new JSONArray();
            for (int n = 0; n < pages; n++) handles.put(new JSONObject().put("release", release).put("path", "guide.txt").put("offset", n).put("limit", 1));
            JSONObject request = contexts.assemble(identity, run, handles).getJSONObject("request"), pin = catalog.pinned(T, S, run);
            JSONObject body = ModelWire.prepare(pin.getJSONObject("model"), request); new ModelBudgets().admitWire(pin, body);
            JSONObject spec = request.optJSONObject("contextSummaryRequest");
            if (spec == null) continue; // Optional summarization yields its reservation when fixed sources need the space.
            scheduled++;
            JSONObject source = spec.getJSONArray("sources").getJSONObject(0); String slashes = text(256).replace('x', '\\');
            JSONObject content = new JSONObject().put("progress", slashes + slashes).put("decisions", new JSONArray().put(slashes).put(slashes).put(slashes))
                    .put("constraints", new JSONArray()).put("nextSteps", new JSONArray()).put("evidence", new JSONArray().put(new JSONObject()
                            .put("intent", source.getString("intent")).put("resultHash", source.getString("resultHash"))));
            JSONObject summary = ContextSummary.validate(spec, response(content)); check(summary != null, "valid escaped summary was refused");
            int before = RuntimeSupport.bytes(body).length; body.getJSONArray("messages").put(ContextSummary.wireMessage(summary));
            check(RuntimeSupport.bytes(body).length - before <= ContextSummary.wireReserve(4096), "summary reserve omitted wire escaping");
            new ModelBudgets().admitWire(pin, body);
        }
        check(scheduled > 0, "summary budget regression never exercised actual summary insertion");
    }
    static void resourcePageIdentity() throws Exception {
        String source = catalog.pinned(T, S, run("context-integrity", "fixture")).getJSONArray("releases").getJSONObject(0).getString("digest");
        JSONObject profile = task("context-page-identity", source, "context-large", 128);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", true); profiles.publish(T, profile);
        String run = run("context-page-identity", "Read business records and a real resource page"); JdbcRunStore.Lease lease = running(run, 60000);
        JSONObject numeric = new JSONObject().put("release", "v1").put("path", "business-records").put("offset", 12).put("nextOffset", 777).put("text", text(1000));
        JSONObject opaque = RuntimeSupport.copy(numeric).put("offset", "opaque-cursor").put("nextOffset", "opaque-next").put("nested", numeric);
        ProgressTest.step(lease, 0, "common.data.query", new JSONObject(), ProgressTest.success(opaque.toString()));
        ProgressTest.step(lease, 1, "common.data.query", new JSONObject().put("page", 1), ProgressTest.success(numeric.toString()));
        resources.activate(identity, run, source);
        JSONObject args = new JSONObject().put("release", source).put("path", "guide.txt").put("offset", 20).put("limit", 1024);
        JSONObject original = (JSONObject)resources.invoke(identity, run, "resource.read", args);
        ProgressTest.step(lease, 2, "resource.read", args, ProgressTest.success(original.toString()));
        JSONObject snapshot = contexts.assemble(identity, run, new JSONArray()), business = visible(snapshot, "p0");
        check(business.getString("offset").equals("opaque-cursor") && business.getString("nextOffset").equals("opaque-next"), "business string cursor was treated as a resource offset");
        check(business.getJSONObject("nested").getInt("nextOffset") == 777 && visible(snapshot, "p1").getInt("nextOffset") == 777,
                "numeric business cursor was rewritten based on field names");
        JSONObject page = visible(snapshot, "p2");
        check(page.getString("text").length() < original.getString("text").length() && page.getInt("nextOffset") == 20 + page.getString("text").length(),
                "known resource cursor did not follow the visible text");
    }
    static JSONObject modelJob(ModelJobStore jobs, JdbcRunStore.Lease lease, JSONObject request, String worker) throws Exception {
        JSONObject model = catalog.pinned(T, S, lease.runId).getJSONObject("model");
        intent(lease, "round", "round-model", new JSONObject().put("request", request));
        jobs.enqueue(lease, row(lease.runId, "round", "round-model"), model);
        JSONObject descriptor = new JSONObject().put("backendId", "context-backend").put("model", model.getString("id"))
                .put("adapter", model.getString("adapter")).put("protocolVersion", 3);
        jobs.backend("context-backend", new JSONObject().put("backendId", "context-backend").put("type", "unit"), 8);
        return jobs.poll(worker, "instance-" + worker, "session-" + worker, descriptor, 1, true);
    }
    static void inferenceEvidence() throws Exception {
        ModelJobStore jobs = new ModelJobStore(store, config.mysql()); InferenceStore inference = new InferenceStore(store, config.mysql());
        String run = run("context-wire", "Budget rejection is safe to rebuild"); JdbcRunStore.Lease lease = running(run, 60000);
        JSONObject request = contexts.assemble(identity, run, new JSONArray()).getJSONObject("request").put("maxRepairs", 1);
        JSONObject job = modelJob(jobs, lease, request, "retry"), model = catalog.pinned(T, S, run).getJSONObject("model");
        JSONObject wire = ModelWire.prepare(model, request), oversized = RuntimeSupport.copy(wire);
        oversized.getJSONArray("messages").put(new JSONObject().put("role", "user").put("content", text(50000)));
        JSONObject rejected = inference.submit(job.getString("jobId"), "retry", "instance-retry", "instance-retry", 1, JdbcRunStore.hash(oversized), oversized);
        check(rejected.getInt("actual_finished") == 1 && rejected.isNull("node_id"), "budget retry lacks durable pre-dispatch proof");
        fail("INFERENCE_CONFLICT", () -> inference.submit(job.getString("jobId"), "retry", "instance-retry", "instance-retry", 1, JdbcRunStore.hash(wire), wire));
        JSONObject admitted = inference.submit(job.getString("jobId"), "retry", "instance-retry", "instance-retry", 2, JdbcRunStore.hash(wire), wire);
        JSONObject decision = new JSONObject().put("kind", "final").put("text", "ok");
        inference.finish(admitted.getString("inference_id"), response(decision), null, true, "unit");
        JSONObject claim = new JSONObject().put("status", "success").put("decision", decision).put("audit", new JSONArray()).put("cancellationObserved", false);
        JSONObject outcome = inference.reconcile(job.getString("jobId"), claim);
        check(outcome.getString("status").equals("success") && outcome.getJSONObject("usage").getInt("input") == 10 && InferenceStore.repairs(outcome) == 0,
                "successful budget retry consumed format repair or double-counted usage");
        JSONObject unknownJob = modelJob(jobs, running(run("context-wire", "Unknown must not retry"), 60000), request, "unknown");
        JSONObject unknown = inference.submit(unknownJob.getString("jobId"), "unknown", "instance-unknown", "instance-unknown", 1, JdbcRunStore.hash(wire), wire);
        inference.finish(unknown.getString("inference_id"), null, "MODEL_COMPLETION_UNKNOWN", false, "unit");
        fail("INFERENCE_PREVIOUS_UNRESOLVED", () -> inference.submit(unknownJob.getString("jobId"), "unknown", "instance-unknown", "instance-unknown", 2, JdbcRunStore.hash(wire), wire));
        JSONObject bounded = modelJob(jobs, running(run("context-wire", "Two budget retries only"), 60000), request, "bounded");
        for (int attempt = 1; attempt <= 3; attempt++) inference.submit(bounded.getString("jobId"), "bounded", "instance-bounded", "instance-bounded", attempt, JdbcRunStore.hash(oversized), oversized);
        fail("INFERENCE_PROFILE_MISMATCH", () -> inference.submit(bounded.getString("jobId"), "bounded", "instance-bounded", "instance-bounded", 4, JdbcRunStore.hash(wire), wire));

        JSONObject profile = task("context-summary", catalog.pinned(T, S, run).getJSONArray("releases").getJSONObject(0).getString("digest"), "context-wire", 4096);
        profile.getJSONObject("contextPolicy").put("adaptiveHistory", true).put("taskState", true).put("budgetVersion", "transport-bytes-v1")
                .put("outputReserve", 2048).put("maxInputBytes", 48000).put("finalWireGuard", true).put("compressionTriggerPercent", 80).put("compressionTargetPercent", 60)
                .put("semanticSummary", new JSONObject().put("enabled", true).put("maxCalls", 1).put("outputReserve", 512).put("maxSummaryBytes", 4096));
        profiles.publish(T, profile);
        String summaryRun = run("context-summary", "Summarize original records"); JdbcRunStore.Lease summaryLease = running(summaryRun, 60000);
        for (int i = 0; i < 6; i++) ProgressTest.step(summaryLease, i, "resource.read", new JSONObject().put("page", i),
                ProgressTest.success(new JSONObject().put("unknownExact", text(4000)).put("value", i).toString()));
        JSONObject snapshot = contexts.assemble(identity, summaryRun, new JSONArray()), input = snapshot.getJSONObject("request");
        check(input.has("contextSummaryRequest"), "optional summary was not scheduled from archived original evidence");
        contexts.checkpoint(summaryLease, snapshot.getLong("basisVersion"), snapshot);
        check(contexts.assemble(identity, summaryRun, new JSONArray()).getString("requestDigest").equals(snapshot.getString("requestDigest")), "summary request changed across checkpoint");
        JSONObject summaryJob = modelJob(jobs, summaryLease, input.put("maxRepairs", 1), "summary"), spec = input.getJSONObject("contextSummaryRequest");
        JSONObject summaryWire = ContextSummary.wire(spec);
        JSONObject first = inference.submit(summaryJob.getString("jobId"), "summary", "instance-summary", "instance-summary", 1, JdbcRunStore.hash(summaryWire), summaryWire);
        JSONObject ref = spec.getJSONArray("sources").getJSONObject(0);
        JSONObject content = new JSONObject().put("progress", "Original evidence is available").put("decisions", new JSONArray()).put("constraints", new JSONArray())
                .put("nextSteps", new JSONArray().put("Answer using confirmed results")).put("evidence", new JSONArray().put(new JSONObject().put("intent", ref.getString("intent")).put("resultHash", ref.getString("resultHash"))));
        JSONObject summaryResponse = response(content), verified = ContextSummary.validate(spec, summaryResponse);
        check(verified != null, "valid semantic summary rejected");
        JSONObject invalid = RuntimeSupport.copy(content); invalid.getJSONArray("evidence").getJSONObject(0).put("resultHash", text(64));
        check(ContextSummary.validate(spec, response(invalid)) == null, "summary accepted fabricated evidence");
        inference.finish(first.getString("inference_id"), summaryResponse, null, true, "unit");
        fail("INFERENCE_EVIDENCE_REQUIRED", () -> inference.reconcile(summaryJob.getString("jobId"), claim));
        JSONObject main = ModelWire.prepare(model, input); main.getJSONArray("messages").put(ContextSummary.wireMessage(verified));
        JSONObject oversizedSummary = RuntimeSupport.copy(main); oversizedSummary.getJSONArray("messages").put(new JSONObject().put("role", "user").put("content", text(50000)));
        JSONObject summaryRejected = inference.submit(summaryJob.getString("jobId"), "summary", "instance-summary", "instance-summary", 2, JdbcRunStore.hash(oversizedSummary), oversizedSummary);
        check(summaryRejected.getInt("actual_finished") == 1, "summary fallback lacked finished pre-dispatch rejection evidence");
        JSONObject withoutSummary = ModelWire.prepare(model, input);
        JSONObject second = inference.submit(summaryJob.getString("jobId"), "summary", "instance-summary", "instance-summary", 3, JdbcRunStore.hash(withoutSummary), withoutSummary);
        inference.finish(second.getString("inference_id"), response(decision), null, true, "unit");
        JSONObject reconciled = inference.reconcile(summaryJob.getString("jobId"), RuntimeSupport.copy(claim).put("contextSummary", new JSONObject().put("forged", true)));
        check(JdbcRunStore.hash(reconciled.getJSONObject("contextSummary")).equals(JdbcRunStore.hash(verified)), "worker summary overrode actual Java response");
        check(InferenceStore.repairs(reconciled) == 0 && reconciled.getJSONObject("usage").getInt("input") == 20, "summary miscounted repairs or real usage");
        Contract.validate(reconciled, StrictJson.object(Contract.resource("execution/schemas/model-outcome.json")));
        jobs.report("summary", ExecutionTest.report(summaryJob, decision, true).put("outcomeJson", reconciled.toString()));
        confirm(summaryLease, "round", "round-model", decision);
        JSONObject resumed = contexts.assemble(identity, summaryRun, new JSONArray());
        check(!resumed.getJSONObject("request").has("contextSummaryRequest") && !resumed.isNull("semanticSummary"), "restart repeated summary or lost provenance");
        boolean found = false;
        for (Object entry : resumed.getJSONObject("request").getJSONArray("messages")) {
            JSONObject m = (JSONObject)entry;
            if (m.optString("provenance").equals("semantic-context-summary")) { found = true; check(m.getString("role").equals("user"), "semantic summary elevated to policy"); }
        }
        check(found, "validated semantic handoff was not restored");
        inference.closeAccounting();
    }
    public static void main(String[] args) throws Exception {
        ExecutionTest.prepare(Files.createTempDirectory("harness-context-regression-"), "");
        profiles.publish(T, RuntimeSupport.copy(model).put("id", "context-large").put("maxContextTokens", 200000));
        rendering(); integrity(); workingState(); adaptiveHistory(); paginationChain(); interleavedReadback(); watermarks(); summaryWireBudget(); inferenceEvidence(); resourcePageIdentity();
        System.out.println("PASS: wire parity, exact evidence/cursors/readback, working state, dependency selection, watermarks/restart, authoritative budget retries and optional semantic summaries");
    }
}
