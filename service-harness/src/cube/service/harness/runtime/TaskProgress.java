package cube.service.harness.runtime;

import cube.service.harness.store.JdbcRunStore;
import org.json.*;
import java.util.*;

/** Exact working state from original user events and confirmed tools; never infers goal completion. */
final class TaskProgress {
    private TaskProgress() {}
    static JSONObject data(JSONObject fact) {
        JSONObject result = fact.optJSONObject("result");
        Object value = result == null ? null : ContextData.parse(result.optString("dataJson"));
        return value instanceof JSONObject ? (JSONObject)value : null;
    }
    static JSONObject evidence(JSONObject fact) {
        return new JSONObject().put("intent", fact.getString("intent")).put("resultHash", fact.getString("resultHash"))
                .put("tool", fact.getJSONObject("payload").getString("tool"))
                .put("inputRevision", fact.optLong("inputRevision", 0));
    }
    static JSONObject projection(JSONObject original, int bytes) {
        JSONObject out = new JSONObject(); JSONArray omitted = new JSONArray();
        for (String name : Arrays.asList("answer", "response", "value", "totalItems", "totalRecords", "totalMedia", "complete", "partial",
                "coverage", "nextCursor", "nextOffset", "pageRef", "datasetRef", "profileId", "references", "refs", "conflicts", "sourceVersion")) {
            if (!original.has(name)) continue;
            JSONObject candidate = RuntimeSupport.copy(out).put(name, original.get(name));
            if (RuntimeSupport.bytes(candidate).length <= bytes) out = candidate; else omitted.put(name);
        }
        if (omitted.length() > 0) out.put("omittedFields", omitted);
        return out;
    }
    static JSONObject derive(JSONObject basis, JSONArray facts) {
        JSONArray inputs = new JSONArray(), evidence = new JSONArray(), routes = new JSONArray();
        Map<String, JSONObject> sessions = new LinkedHashMap<>(), errors = new LinkedHashMap<>();
        Map<String, List<int[]>> ranges = new HashMap<>();
        JSONArray pagination = new JSONArray();
        Set<String> readingCalls = new LinkedHashSet<>();
        Set<String> activeReading = ContextHistory.readingCalls(facts);
        for (Object entry : basis.getJSONArray("events")) {
            JSONObject event = (JSONObject)entry; String type = event.getString("event_type");
            JSONObject input = null;
            if (type.equals("run.created")) input = new JSONObject(event.getString("data_json")).getJSONObject("input");
            if (type.equals("message.accepted")) input = new JSONObject(event.getString("data_json")).getJSONObject("input");
            if (type.equals("control.resume")) input = new JSONObject(event.getString("data_json")).getJSONObject("payload").getJSONObject("input");
            if (input != null) inputs.put(new JSONObject().put("source", type).put("eventSeq", event.getLong("event_seq"))
                    .put("inputDigest", JdbcRunStore.hash(input)).put("attachmentIds", input.optJSONArray("attachmentIds") == null ? new JSONArray() : input.getJSONArray("attachmentIds")));
        }
        for (Object entry : facts) {
            JSONObject fact = (JSONObject)entry;
            if (!fact.getString("kind").equals("tool")) continue;
            JSONObject call = fact.getJSONObject("payload"), result = fact.optJSONObject("result");
            String name = call.getString("tool");
            if (activeReading.contains(call.getString("proposalId"))) readingCalls.add(fact.getString("intent"));
            if (result == null) continue;
            JSONObject args = new JSONObject(call.getString("argumentsJson"));
            String argumentsHash = JdbcRunStore.hash(args);
            if (result.getString("status").equals("error")) {
                JSONObject error = evidence(fact).put("errorCode", result.optString("errorCode", "TOOL_ERROR_UNSPECIFIED"))
                        .put("argumentsHash", argumentsHash);
                // Keep every failure. A later success must not erase earlier attempts.
                errors.put(fact.getString("intent"), error); continue;
            }
            if (!result.getString("status").equals("success")) continue;
            Set<String> consumed = new HashSet<>(); ContextHistory.collect(args, consumed, false, "");
            for (Object entryPage : pagination) {
                JSONObject page = (JSONObject)entryPage;
                if (consumed.contains(page.get("nextCursor").toString())) page.put("consumed", true);
            }
            for (JSONObject error : errors.values()) {
                if (error.has("resolvedBy") || !error.getString("tool").equals(name)
                        || error.getLong("inputRevision") != fact.optLong("inputRevision", 0)) continue;
                if (error.getString("argumentsHash").equals(argumentsHash)) {
                    error.put("resolvedBy", fact.getString("intent")).put("resolvedResultHash", fact.getString("resultHash"))
                            .put("resolution", "same-arguments-execution-succeeded; task-correctness-not-inferred");
                } else {
                    // Changed arguments can target unrelated work. Link the observation,
                    // but require evidence review rather than inventing semantic recovery.
                    JSONArray candidates = error.optJSONArray("successfulFollowUps");
                    if (candidates == null) { candidates = new JSONArray(); error.put("successfulFollowUps", candidates); }
                    if (candidates.length() < 3) candidates.put(evidence(fact).put("argumentsHash", argumentsHash));
                    error.put("resolution", "changed-arguments-success-observed; verify-relatedness-before-retrying");
                }
            }
            JSONObject data = data(fact);
            if (data != null) {
                JSONObject values = projection(data, 1536);
                if (values.length() > 0) evidence.put(evidence(fact).put("partialProjection", true).put("data", values));
                if (data.has("nextCursor") && !data.isNull("nextCursor") && !data.optString("nextCursor").isEmpty())
                    pagination.put(evidence(fact).put("kind", "pagination").put("nextCursor", data.get("nextCursor")));
            }
            if (name.equals("common.data.query")) {
                JSONObject route = evidence(fact);
                for (String field : Arrays.asList("skill", "connectorId")) if (args.has(field)) route.put(field, args.get(field));
                route.put("argumentsHash", JdbcRunStore.hash(args)); routes.put(route);
            }
            if (!name.equals("context.read_result") || data == null || !data.optString("encoding").equals("tool-result-json-v1")) continue;
            String intent = args.getString("intent"), hash = data.getString("resultHash");
            JSONObject session = sessions.get(intent);
            if (session == null) {
                session = new JSONObject().put("intent", intent).put("resultHash", hash).put("totalCodePoints", data.getInt("totalCodePoints"));
                sessions.put(intent, session); ranges.put(intent, new ArrayList<int[]>());
            }
            if (!session.getString("resultHash").equals(hash)) throw RuntimeSupport.error("CONTEXT_LEDGER_MISMATCH");
            int start = args.getInt("offset"), count = data.getString("text").codePointCount(0, data.getString("text").length());
            ranges.get(intent).add(new int[]{start, start + count});
        }
        JSONArray readers = new JSONArray(), dependencies = new JSONArray();
        for (Object entry : pagination) {
            JSONObject page = (JSONObject)entry;
            if (!page.optBoolean("consumed")) dependencies.put(page);
        }
        for (Map.Entry<String, JSONObject> entry : sessions.entrySet()) {
            List<int[]> pages = ranges.get(entry.getKey()); pages.sort(Comparator.comparingInt(a -> a[0]));
            int end = 0; JSONArray coverage = new JSONArray();
            for (int[] page : pages) {
                coverage.put(new JSONObject().put("offset", page[0]).put("endOffset", page[1]));
                if (page[0] <= end) end = Math.max(end, page[1]);
            }
            JSONObject session = entry.getValue().put("ranges", coverage).put("contiguousCodePoints", end)
                    .put("complete", end == entry.getValue().getInt("totalCodePoints"));
            session.put("nextOffset", session.getBoolean("complete") ? JSONObject.NULL : end); readers.put(session);
            if (!session.getBoolean("complete")) dependencies.put(new JSONObject().put("kind", "read-result")
                    .put("intent", entry.getKey()).put("resultHash", session.getString("resultHash")).put("offset", end));
        }
        JSONArray corrected = new JSONArray(), unresolved = new JSONArray();
        for (JSONObject error : errors.values()) {
            if (error.getLong("inputRevision") < basis.optLong("inputRevision", 0))
                error.put("supersededByInputRevision", basis.getLong("inputRevision"));
            corrected.put(error);
            if (!error.has("resolvedBy") && !error.has("supersededByInputRevision")) unresolved.put(error);
        }
        return new JSONObject().put("version", "task-working-state-v2").put("userInputs", inputs).put("userInputsDigest", JdbcRunStore.hash(inputs))
                .put("confirmedEvidence", boundedEvidence(evidence)).put("confirmedEvidenceDigest", JdbcRunStore.hash(evidence))
                .put("queryRoutes", tail(routes, 8)).put("readingSessions", readers)
                .put("errorResolutions", corrected).put("unresolvedErrors", unresolved).put("unresolvedDependencies", dependencies)
                .put("readingIntents", new JSONArray(readingCalls)).put("completion", "not-inferred");
    }
    /** Ledger coverage records what was fetched; visibility records what this model request can reconstruct. */
    static void visibleReadback(JSONObject memory, JSONArray facts, JSONArray messages) {
        Set<String> selected = new HashSet<>();
        for (Object entry : messages) {
            JSONObject message = (JSONObject)entry;
            if (message.getString("role").equals("tool")) selected.add(message.optString("proposalId"));
        }
        for (Object entry : memory.getJSONArray("readingSessions")) {
            JSONObject session = (JSONObject)entry; List<int[]> ranges = new ArrayList<>();
            for (Object value : facts) {
                JSONObject f = (JSONObject)value;
                if (!f.getString("kind").equals("tool") || !selected.contains(f.getJSONObject("payload").getString("proposalId"))
                        || !f.getJSONObject("payload").getString("tool").equals("context.read_result")) continue;
                JSONObject data = data(f);
                if (data == null || !data.optString("encoding").equals("tool-result-json-v1") || !data.getString("resultHash").equals(session.getString("resultHash"))) continue;
                JSONObject args = new JSONObject(f.getJSONObject("payload").getString("argumentsJson"));
                if (!args.getString("intent").equals(session.getString("intent"))) continue;
                int start = args.getInt("offset"), count = data.getString("text").codePointCount(0, data.getString("text").length());
                ranges.add(new int[]{start, start + count});
            }
            ranges.sort(Comparator.comparingInt(a -> a[0])); int end = 0; JSONArray coverage = new JSONArray();
            for (int[] range : ranges) {
                coverage.put(new JSONObject().put("offset", range[0]).put("endOffset", range[1]));
                if (range[0] <= end) end = Math.max(end, range[1]);
            }
            boolean complete = end == session.getInt("totalCodePoints");
            session.put("visibleRanges", coverage).put("visibleContiguousCodePoints", end).put("visibleComplete", complete)
                    .put("visibleNextOffset", complete ? JSONObject.NULL : end);
        }
    }
    private static JSONArray tail(JSONArray array, int max) {
        JSONArray out = new JSONArray(); for (int i = Math.max(0, array.length() - max); i < array.length(); i++) out.put(array.get(i)); return out;
    }
    private static JSONArray boundedEvidence(JSONArray evidence) {
        List<JSONObject> selected = new ArrayList<>(); int remaining = 8192;
        for (int i = evidence.length() - 1; i >= 0 && selected.size() < 16; i--) {
            JSONObject item = evidence.getJSONObject(i); int bytes = RuntimeSupport.bytes(item).length;
            if (bytes <= remaining) { selected.add(item); remaining -= bytes; }
        }
        Collections.reverse(selected); return new JSONArray(selected);
    }
}
