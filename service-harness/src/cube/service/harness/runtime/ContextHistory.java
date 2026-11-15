package cube.service.harness.runtime;

import cube.service.harness.store.JdbcRunStore;
import org.json.*;
import java.util.*;

/** Retire only complete tool-call/result groups. The original ledger is never edited. */
final class ContextHistory {
    static JSONObject select(JSONArray messages, JSONArray facts, int window) {
        Set<String> complete = new HashSet<>();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject f = facts.getJSONObject(i);
            if (f.getString("kind").equals("tool") && f.has("result")) complete.add(f.getJSONObject("payload").getString("proposalId"));
        }
        List<Integer> eligible = new ArrayList<>();
        Map<Integer, Set<String>> ids = new HashMap<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i), content = m.optJSONObject("content");
            if (!m.getString("role").equals("assistant") || content == null || !content.optString("kind").equals("tool_calls")) continue;
            Set<String> group = new HashSet<>();
            JSONArray calls = content.getJSONArray("calls");
            for (int c = 0; c < calls.length(); c++) group.add(calls.getJSONObject(c).getString("proposalId"));
            if (complete.containsAll(group)) { eligible.add(i); ids.put(i, group); }
        }
        Set<Integer> removed = new HashSet<>(); Set<String> retired = new HashSet<>();
        for (int i = 0; i < eligible.size() - window; i++) { removed.add(eligible.get(i)); retired.addAll(ids.get(eligible.get(i))); }
        JSONArray selected = new JSONArray(), archive = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if (removed.contains(i) || message.getString("role").equals("tool") && retired.contains(message.optString("proposalId"))) continue;
            selected.put(message);
        }
        for (int i = 0; i < facts.length(); i++) {
            JSONObject f = facts.getJSONObject(i);
            if (f.getString("kind").equals("tool") && retired.contains(f.getJSONObject("payload").getString("proposalId")))
                archive.put(new JSONObject().put("intent", f.getString("intent")).put("tool", f.getJSONObject("payload").getString("tool"))
                        .put("resultHash", f.getString("resultHash")).put("status", f.getJSONObject("result").getString("status")));
        }
        JSONArray observations = new JSONArray(); int allowance = 4096;
        for (int i = facts.length() - 1; i >= 0 && observations.length() < 8; i--) {
            JSONObject fact = facts.getJSONObject(i);
            if (!fact.getString("kind").equals("tool") || !retired.contains(fact.getJSONObject("payload").getString("proposalId"))) continue;
            Object parsed = ContextData.parse(fact.getJSONObject("result").optString("dataJson"));
            if (!(parsed instanceof JSONObject)) continue;
            JSONObject original = (JSONObject)parsed, projection = new JSONObject();
            // Extract exact small fields only. No generated conclusions or partial arrays.
            for (String field : Arrays.asList("answer", "response", "value", "totalItems", "totalRecords", "totalMedia", "complete", "partial",
                    "coverage", "nextCursor", "nextOffset", "pageRef", "datasetRef", "profileId", "references", "refs")) {
                if (!original.has(field)) continue;
                JSONObject candidate = new JSONObject(projection.toString()).put(field, original.get(field));
                if (JdbcRunStore.canonical(candidate).length() <= 512) projection = candidate;
            }
            if (projection.length() == 0) continue;
            JSONObject observation = new JSONObject().put("intent", fact.getString("intent")).put("resultHash", fact.getString("resultHash"))
                    .put("partialProjection", true).put("data", projection);
            int length = JdbcRunStore.canonical(observation).length();
            if (length <= allowance) { observations.put(observation); allowance -= length; }
        }
        return new JSONObject().put("method", "ledger-window-v1").put("messages", selected).put("archived", archive)
                .put("observations", observations).put("observationsDigest", JdbcRunStore.hash(observations))
                .put("archiveDigest", JdbcRunStore.hash(archive)).put("retainedToolRounds", window);
    }
}
