package cube.service.harness.runtime;

import cube.service.harness.store.JdbcRunStore;
import org.json.*;
import java.util.*;

/** Budget-driven retirement of complete pairs, with a dependency closure over exact handles. */
final class ContextHistory {
    private final JSONArray original, facts;
    private final List<Integer> groups = new ArrayList<>();
    private final Map<Integer, Set<String>> ids = new LinkedHashMap<>();
    private final Map<Integer, Set<Integer>> dependencies = new LinkedHashMap<>();
    private final Map<String, Integer> owners = new HashMap<>();
    private final Map<Integer, String> pinned = new LinkedHashMap<>();
    private final Set<Integer> removed = new LinkedHashSet<>();
    private final boolean adaptive;

    ContextHistory(JSONArray messages, JSONArray facts, int window, boolean adaptive) {
        this.original = messages; this.facts = facts; this.adaptive = adaptive;
        Map<String, JSONObject> results = new LinkedHashMap<>();
        for (Object value : facts) {
            JSONObject f = (JSONObject)value;
            if (f.getString("kind").equals("tool") && f.has("result")) results.put(f.getJSONObject("payload").getString("proposalId"), f);
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i), content = m.optJSONObject("content");
            if (!m.getString("role").equals("assistant") || content == null || !content.optString("kind").equals("tool_calls")) continue;
            Set<String> group = new LinkedHashSet<>();
            for (Object call : content.getJSONArray("calls")) group.add(((JSONObject)call).getString("proposalId"));
            if (results.keySet().containsAll(group)) {
                groups.add(i); ids.put(i, group);
                for (String id : group) owners.put(id, i);
            }
        }
        Set<String> reading = new HashSet<>();
        for (int i = facts.length() - 1; i >= 0; i--) {
            JSONObject f = facts.getJSONObject(i);
            if (!f.getString("kind").equals("tool")) continue;
            if (!f.getJSONObject("payload").getString("tool").equals("context.read_result")) break;
            reading.add(f.getJSONObject("payload").getString("proposalId"));
        }
        for (int group : groups) if (!Collections.disjoint(ids.get(group), reading)) pinned.put(group, "active-readback");
        if (adaptive && !groups.isEmpty()) {
            pinned.put(groups.get(groups.size() - 1), "latest-complete-group");
            Map<String, Set<Integer>> producers = new HashMap<>();
            for (int group : groups) {
                Set<String> consumed = new HashSet<>();
                for (String id : ids.get(group)) collect(new JSONObject(results.get(id).getJSONObject("payload").getString("argumentsJson")), consumed, false, "");
                Set<Integer> depends = new LinkedHashSet<>();
                for (String handle : consumed) if (producers.containsKey(handle)) depends.addAll(producers.get(handle));
                dependencies.put(group, depends);
                for (String id : ids.get(group)) {
                    JSONObject f = results.get(id), data = TaskProgress.data(f);
                    if (data == null || !f.getJSONObject("result").getString("status").equals("success")) continue;
                    Set<String> produced = new HashSet<>(); collect(data, produced, true, "");
                    for (String handle : produced) producers.computeIfAbsent(handle, k -> new LinkedHashSet<>()).add(group);
                    Object cursor = data.opt("nextCursor");
                    if (cursor != null && cursor != JSONObject.NULL && !cursor.toString().isEmpty()) {
                        boolean used = false;
                        for (Object later : facts) {
                            JSONObject next = (JSONObject)later;
                            if (!next.getString("kind").equals("tool") || next.getLong("eventSeq") <= f.getLong("eventSeq")) continue;
                            if (next.optJSONObject("result") == null || !next.getJSONObject("result").getString("status").equals("success")) continue;
                            Set<String> values = new HashSet<>(); collect(new JSONObject(next.getJSONObject("payload").getString("argumentsJson")), values, false, "");
                            if (values.contains(cursor.toString())) { used = true; break; }
                        }
                        if (!used) pinned.put(group, "unconsumed-pagination-cursor");
                    }
                }
            }
            boolean changed;
            do {
                changed = false;
                for (int group : new ArrayList<>(pinned.keySet())) for (int source : dependencies.getOrDefault(group, Collections.emptySet()))
                    if (!pinned.containsKey(source)) { pinned.put(source, "referenced-evidence"); changed = true; }
            } while (changed);
        }
        if (!adaptive) for (int i = 0; i < groups.size() - window; i++) if (!pinned.containsKey(groups.get(i))) removed.add(groups.get(i));
    }

    /** Only typed result handles produce dependencies; arguments are searched as complete values. */
    static void collect(Object value, Set<String> out, boolean typed, String field) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            for (String key : object.keySet()) collect(object.get(key), out, typed, key);
        } else if (value instanceof JSONArray) for (Object item : (JSONArray)value) collect(item, out, typed, field);
        else if (value instanceof String) {
            String text = (String)value;
            Object parsed = ContextData.parse(text);
            if (parsed instanceof JSONObject || parsed instanceof JSONArray) collect(parsed, out, typed, field);
            else if (!text.isEmpty() && (!typed || field.matches("(?i).*(ref|refs|id|ids|cursor|token|hash|digest)$"))) out.add(text);
        }
    }

    boolean retire() {
        if (!adaptive) return false;
        for (int group : groups) if (!removed.contains(group) && !pinned.containsKey(group)) { removed.add(group); return true; }
        return false;
    }

    void restore(JSONArray archived) {
        if (!adaptive || archived == null) return;
        Set<String> evidence = new HashSet<>();
        for (Object entry : archived) { JSONObject f = (JSONObject)entry; evidence.add(f.getString("intent") + ":" + f.getString("resultHash")); }
        Set<String> idsToRetire = new HashSet<>();
        for (Object entry : facts) {
            JSONObject f = (JSONObject)entry;
            if (f.getString("kind").equals("tool") && f.has("resultHash") && evidence.contains(f.getString("intent") + ":" + f.getString("resultHash")))
                idsToRetire.add(f.getJSONObject("payload").getString("proposalId"));
        }
        for (int group : groups) if (!pinned.containsKey(group) && idsToRetire.containsAll(ids.get(group))) removed.add(group);
    }

    JSONObject view() {
        Set<String> retired = new HashSet<>(); for (int group : removed) retired.addAll(ids.get(group));
        JSONArray selected = new JSONArray(), archive = new JSONArray(), pins = new JSONArray();
        for (int i = 0; i < original.length(); i++) {
            JSONObject m = original.getJSONObject(i);
            if (!removed.contains(i) && !(m.getString("role").equals("tool") && retired.contains(m.optString("proposalId")))) selected.put(m);
        }
        for (Object value : facts) {
            JSONObject f = (JSONObject)value;
            if (!f.getString("kind").equals("tool") || !f.has("result")) continue;
            String id = f.getJSONObject("payload").getString("proposalId");
            if (retired.contains(id)) archive.put(TaskProgress.evidence(f).put("status", f.getJSONObject("result").getString("status")));
            Integer group = owners.get(id);
            if (group != null && pinned.containsKey(group)) pins.put(TaskProgress.evidence(f).put("reason", pinned.get(group)));
        }
        JSONArray observations = new JSONArray(); int allowance = 4096;
        for (int i = facts.length() - 1; i >= 0 && observations.length() < 8; i--) {
            JSONObject f = facts.getJSONObject(i);
            if (!f.getString("kind").equals("tool") || !retired.contains(f.getJSONObject("payload").getString("proposalId"))) continue;
            JSONObject data = TaskProgress.data(f); if (data == null) continue;
            JSONObject projection = TaskProgress.projection(data, 512);
            if (projection.length() == 0) continue;
            JSONObject observation = TaskProgress.evidence(f).put("partialProjection", true).put("data", projection);
            int length = RuntimeSupport.bytes(observation).length;
            if (length <= allowance) { observations.put(observation); allowance -= length; }
        }
        return new JSONObject().put("method", adaptive ? "ledger-budget-dependencies-v1" : "ledger-window-v1")
                .put("messages", selected).put("archived", archive).put("pinned", pins)
                .put("observations", observations).put("observationsDigest", JdbcRunStore.hash(observations))
                .put("archiveDigest", JdbcRunStore.hash(archive)).put("retainedToolRounds", groups.size() - removed.size());
    }
}
