package cube.service.harness.store;

import cube.service.harness.runtime.ExecutionProfiles;
import org.json.*;
import java.sql.Connection;
import java.util.*;
import static cube.service.harness.store.JdbcRunStore.*;

/** Durable user input. All acceptance and execution gates serialize on the run row. */
public final class MessageStore {
    private final JdbcRunStore store;
    private final boolean mysql;
    private FileStore files;
    private boolean enabled;
    private int maximum = 128, pendingMaximum = 32;

    public MessageStore(JdbcRunStore store, boolean mysql) { this.store = store; this.mysql = mysql; }
    public MessageStore files(FileStore value) { files = value; return this; }
    public MessageStore configure(Properties p) {
        enabled = Boolean.parseBoolean(p.getProperty("interaction.enabled", "false"));
        maximum = Integer.parseInt(p.getProperty("interaction.max.messages", "128"));
        pendingMaximum = Integer.parseInt(p.getProperty("interaction.max.pending", "32"));
        if (maximum < 1 || maximum > 1024 || pendingMaximum < 1 || pendingMaximum > maximum)
            throw new IllegalArgumentException("INTERACTION_LIMIT_INVALID");
        return this;
    }
    public JSONObject capabilities() {
        return new JSONObject().put("enabled", enabled).put("messages", "run-messages-v1")
                .put("questions", "structured-input-v1").put("maxMessages", maximum).put("maxPending", pendingMaximum);
    }

    public static void requireCurrent(JSONObject r) {
        if (r.optLong("input_revision", 0) != r.optLong("applied_input_revision", 0))
            throw new StoreException("INPUT_REVISION_CONFLICT");
    }

    void requireCapacity(Connection c, JSONObject r) throws Exception {
        long count = first(rows(c, "SELECT COUNT(*) AS n FROM harness_message WHERE run_id=?", r.getString("run_id"))).getLong("n");
        if (count >= maximum || r.getLong("input_revision") - r.getLong("applied_input_revision") >= pendingMaximum)
            throw new StoreException("MESSAGE_QUEUE_FULL");
    }

    static JSONObject record(JdbcRunStore store, Connection c, JSONObject r, String kind, JSONObject input) throws Exception {
        long revision = r.getLong("input_revision") + 1;
        String message = UUID.randomUUID().toString();
        long received = store.now(c);
        update(c, "INSERT INTO harness_message VALUES (?,?,?,?,?,?,?,?)", message, r.getString("run_id"),
                revision, kind, revision, canonical(input), received, canonical(new JSONObject().put("kind", kind).put("input", input)));
        update(c, "UPDATE harness_run SET input_revision=? WHERE run_id=?", revision, r.getString("run_id"));
        r.put("input_revision", revision);
        return new JSONObject().put("messageId", message).put("messageSeq", revision).put("inputRevision", revision)
                .put("kind", kind).put("input", input).put("receivedAt", received).put("status", "accepted");
    }

    static void applied(JdbcRunStore store, Connection c, JSONObject r) throws Exception {
        long revision = r.getLong("input_revision");
        if (revision <= r.getLong("applied_input_revision")) return;
        update(c, "UPDATE harness_run SET applied_input_revision=? WHERE run_id=?", revision, r.getString("run_id"));
        r.put("applied_input_revision", revision);
        store.emit(c, r, "message.applied", new JSONObject().put("inputRevision", revision), "control");
    }

    static void endWait(JdbcRunStore store, Connection c, JSONObject r) throws Exception {
        long start = r.getLong("wait_started_at");
        if (start == 0) return;
        long elapsed = r.getLong("wait_accumulated_millis") + Math.max(0, store.now(c) - start);
        update(c, "UPDATE harness_run SET wait_started_at=0,wait_accumulated_millis=? WHERE run_id=?", elapsed, r.getString("run_id"));
        r.put("wait_started_at", 0).put("wait_accumulated_millis", elapsed);
    }

    public static void validateQuestions(JSONObject decision) {
        JSONArray questions = decision.optJSONArray("questions");
        if (questions == null) return;
        Set<String> ids = new HashSet<>();
        for (Object entry : questions) {
            JSONObject q = (JSONObject) entry;
            if (!ids.add(q.getString("questionId"))) throw new StoreException("QUESTION_ID_CONFLICT");
            Set<String> options = new HashSet<>();
            for (Object option : q.getJSONArray("options"))
                if (!options.add(((JSONObject) option).getString("id"))) throw new StoreException("QUESTION_OPTION_CONFLICT");
            if (options.isEmpty() && !q.getBoolean("allowFreeText")) throw new StoreException("QUESTION_NO_ANSWER");
        }
    }

    static JSONObject answer(JSONObject wait, JSONObject body) {
        JSONObject details = new JSONObject(wait.getString("payload_json"));
        if (body.getLong("interactionVersion") != details.optLong("interactionVersion", 0))
            throw new StoreException("INTERACTION_VERSION_CONFLICT");
        JSONObject input = new JSONObject(body.getJSONObject("input").toString());
        JSONArray questions = details.optJSONArray("questions"), answers = body.optJSONArray("answers");
        if (questions == null) {
            if (answers != null && answers.length() > 0) throw new StoreException("ANSWER_QUESTION_CONFLICT");
            return input;
        }
        Map<String, JSONObject> supplied = new HashMap<>();
        if (answers != null) for (Object entry : answers) {
            JSONObject a = (JSONObject) entry;
            if (supplied.put(a.getString("questionId"), a) != null) throw new StoreException("ANSWER_QUESTION_CONFLICT");
        }
        // The chat composer may answer one free-form question directly.
        if (supplied.isEmpty() && questions.length() == 1 && questions.getJSONObject(0).getBoolean("allowFreeText"))
            supplied.put(questions.getJSONObject(0).getString("questionId"), new JSONObject().put("text", input.getString("instruction")));
        StringBuilder text = new StringBuilder(input.getString("instruction"));
        for (Object entry : questions) {
            JSONObject q = (JSONObject) entry, a = supplied.remove(q.getString("questionId"));
            if (a == null) throw new StoreException("ANSWER_REQUIRED");
            String selected = a.optString("optionId", ""), free = a.optString("text", "");
            if (selected.isEmpty() && free.trim().isEmpty()) throw new StoreException("ANSWER_REQUIRED");
            if (!free.isEmpty() && !q.getBoolean("allowFreeText")) throw new StoreException("ANSWER_FREE_TEXT_FORBIDDEN");
            String label = "";
            for (Object option : q.getJSONArray("options")) {
                JSONObject o = (JSONObject) option;
                if (o.getString("id").equals(selected)) label = o.getString("label");
            }
            if (!selected.isEmpty() && label.isEmpty()) throw new StoreException("ANSWER_OPTION_CONFLICT");
            text.append('\n').append(q.getString("prompt")).append(": ").append(label);
            if (!free.isEmpty()) text.append(' ').append(free);
        }
        if (!supplied.isEmpty()) throw new StoreException("ANSWER_QUESTION_CONFLICT");
        if (text.length() > 32768) throw new StoreException("MESSAGE_TOO_LARGE");
        return input.put("instruction", text.toString());
    }

    public JSONObject append(String tenant, String subject, String run, String key, JSONObject body) {
        String receipt = hash(new JSONArray().put(tenant).put(subject).put(run).put("message").put(id(key)));
        String digest = hash(body);
        return store.tx(c -> {
            JSONObject r = store.run(c, tenant, subject, run, true);
            JSONObject old = first(rows(c, "SELECT * FROM harness_control_receipt WHERE receipt_id=?", receipt));
            if (old != null) {
                if (!digest.equals(old.getString("payload_hash"))) throw new StoreException("IDEMPOTENCY_CONFLICT");
                return new JSONObject(old.getString("response_json"));
            }
            if (!enabled) throw new StoreException("INTERACTION_NOT_READY");
            if (Arrays.asList("succeeded", "failed", "cancelled", "timed_out", "cancelling").contains(r.getString("state")))
                throw new StoreException("RUN_TERMINAL");
            JSONObject pin = new CatalogStore(store, mysql).pinned(c, tenant, subject, run);
            long lifetime = ExecutionProfiles.execution(pin).getJSONObject("executionLimits").getLong("maxLifetimeMillis");
            if (store.now(c) >= r.getLong("created_at") + lifetime) throw new StoreException("WAIT_EXPIRED");
            requireCapacity(c, r);
            JSONObject input = new JSONObject(body.getJSONObject("input").toString());
            JSONObject wait = first(rows(c, "SELECT * FROM harness_wait WHERE run_id=? AND status='pending'", run));
            if (wait != null && store.now(c) >= wait.getLong("expires_at")) throw new StoreException("WAIT_EXPIRED");
            boolean answering = body.getString("kind").equals("answer");
            if (answering) {
                if (wait == null || !wait.getString("wait_id").equals(body.getString("waitId"))
                        || !wait.getString("kind").equals("input") || !r.getString("state").equals("waiting_input"))
                    throw new StoreException("WAIT_STATE_CONFLICT");
                if (store.now(c) >= wait.getLong("expires_at")) throw new StoreException("WAIT_EXPIRED");
                input = answer(wait, body);
            }
            JSONArray attachments = input.getJSONArray("attachmentIds");
            if (files == null && attachments.length() > 0) throw new StoreException("FILES_DISABLED");
            if (files != null) files.bind(c, tenant, subject, run, attachments);
            if (wait != null) {
                update(c, "UPDATE harness_wait SET status=? WHERE wait_id=?", answering ? "resumed" : "superseded", wait.getString("wait_id"));
                endWait(store, c, r);
                if (r.getString("state").startsWith("waiting_")) {
                    update(c, "UPDATE harness_run SET state='running' WHERE run_id=?", run); r.put("state", "running");
                }
                store.emit(c, r, answering ? "interaction.answered" : "interaction.superseded",
                        new JSONObject().put("waitId", wait.getString("wait_id")), "control");
            }
            JSONObject message = record(store, c, r, body.getString("kind"), input);
            update(c, "UPDATE harness_message SET request_json=? WHERE message_id=?", canonical(body), message.getString("messageId"));
            store.emit(c, r, "message.accepted", message, "control");
            update(c, "INSERT INTO harness_control_receipt VALUES (?,?,?,?)", receipt, digest, canonical(message), store.now(c));
            return message;
        });
    }

    public JSONObject list(String tenant, String subject, String run, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new StoreException("INVALID_PAGE");
        return store.tx(c -> {
            JSONObject r = store.run(c, tenant, subject, run, false);
            JSONArray all = rows(c, "SELECT * FROM harness_message WHERE run_id=? AND message_seq>? ORDER BY message_seq LIMIT ?", run, after, limit);
            JSONArray messages = new JSONArray(); long cursor = after; int size = 0;
            for (Object entry : all) {
                JSONObject m = (JSONObject) entry;
                JSONObject view = new JSONObject().put("messageId", m.getString("message_id")).put("messageSeq", m.getLong("message_seq"))
                        .put("inputRevision", m.getLong("input_revision")).put("kind", m.getString("kind"))
                        .put("input", new JSONObject(m.getString("input_json"))).put("request", new JSONObject(m.getString("request_json"))).put("receivedAt", m.getLong("received_at"))
                        .put("status", m.getLong("input_revision") <= r.getLong("applied_input_revision") ? "applied" : Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(r.getString("state")) ? "unprocessed_terminal" : "accepted");
                int bytes = canonical(view).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (messages.length() > 0 && size + bytes > 512 * 1024) break;
                messages.put(view); size += bytes; cursor = m.getLong("message_seq");
            }
            return new JSONObject().put("messages", messages).put("nextCursor", cursor)
                    .put("inputRevision", r.getLong("input_revision")).put("appliedInputRevision", r.getLong("applied_input_revision"));
        });
    }
}
