package cube.service.harness.store;

import org.json.*;
import java.sql.Connection;
import java.util.*;
import static cube.service.harness.store.JdbcRunStore.*;

/** Bounded, immutable conversation transfer. Never transfers grants or execution evidence. */
final class ConversationStore {
    static JSONObject inherit(JdbcRunStore store, Connection c, String tenant, String subject,
                              String parent, FileStore files) throws Exception {
        JSONObject r = store.run(c, tenant, subject, parent, true);
        if (!Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(r.getString("state")))
            throw new StoreException("PARENT_RUN_ACTIVE");
        if (!r.getString("execution_settlement").equals("confirmed")) throw new StoreException("PARENT_RUN_UNSETTLED");
        JSONArray history = new JSONArray(); int depth = 1;
        Set<String> ids = new LinkedHashSet<>();
        JSONArray events = rows(c, "SELECT event_type,data_json FROM harness_event WHERE run_id=? AND event_type IN ('conversation.history','run.created','control.resume','message.accepted') ORDER BY event_seq", parent);
        for (Object entry : events) {
            JSONObject e = (JSONObject)entry, data = new JSONObject(e.getString("data_json"));
            if (e.getString("event_type").equals("conversation.history")) {
                depth = data.getInt("depth") + 1;
                for (Object message : data.getJSONArray("messages")) history.put(message);
                for (Object id : data.getJSONArray("attachmentIds")) ids.add((String)id);
            } else {
                JSONObject input = e.getString("event_type").equals("control.resume") ? data.getJSONObject("payload").getJSONObject("input") : data.getJSONObject("input");
                history.put(new JSONObject().put("role", "user").put("content", input));
                for (Object id : input.getJSONArray("attachmentIds")) ids.add((String)id);
            }
        }
        JSONObject row = first(rows(c, "SELECT result_json FROM harness_result WHERE run_id=?", parent));
        if (row == null) throw new StoreException("PARENT_RUN_UNSETTLED");
        JSONObject result = new JSONObject(row.getString("result_json")).getJSONObject("result");
        String text = result.optString("text", "Previous task ended: " + r.getString("state"));
        history.put(new JSONObject().put("role", "assistant").put("content", text));
        JSONObject transfer = new JSONObject().put("parentRunId", parent).put("depth", depth)
                .put("messages", history).put("attachmentIds", new JSONArray(ids));
        if (depth > 16 || history.length() > 128 || ids.size() > 64 || canonical(transfer).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 49152)
            throw new StoreException("CONVERSATION_LIMIT");
        if (files == null && !ids.isEmpty()) throw new StoreException("FILES_DISABLED");
        if (files != null) files.validateBindings(c, tenant, subject, transfer.getJSONArray("attachmentIds"));
        return transfer;
    }
}
