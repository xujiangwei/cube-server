/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import cube.service.harness.runtime.ExecutionProfiles;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 持久化用户输入。所有受理与执行门禁都在 run 行上串行化。
 */
public final class MessageStore {
    /**
     * 共享的 RunStore 事务入口。
     */
    private final JdbcRunStore store;

    /**
     * 是否 MySQL 方言。
     */
    private final boolean mysql;

    /**
     * 文件服务，可为 null。
     */
    private FileStore files;

    /**
     * 交互能力是否启用。
     */
    private boolean enabled;

    /**
     * 消息条数上限与待处理消息上限。
     */
    private int maximum = 128;
    private int pendingMaximum = 32;

    /**
     * 构造消息存储。
     *
     * @param store 共享的 RunStore。
     * @param mysql 是否 MySQL 方言。
     */
    public MessageStore(JdbcRunStore store, boolean mysql) {
        this.store = store;
        this.mysql = mysql;
    }

    /**
     * 注入文件服务。
     *
     * @param value 文件服务。
     * @return 本实例，便于链式调用。
     */
    public MessageStore files(FileStore value) {
        files = value;
        return this;
    }

    /**
     * 从部署配置读取交互上限。
     *
     * @param p 部署私有配置。
     * @return 本实例，便于链式调用。
     */
    public MessageStore configure(Properties p) {
        enabled = Boolean.parseBoolean(p.getProperty("interaction.enabled", "false"));
        maximum = Integer.parseInt(p.getProperty("interaction.max.messages", "128"));
        pendingMaximum = Integer.parseInt(p.getProperty("interaction.max.pending", "32"));
        if (maximum < 1 || maximum > 1024 || pendingMaximum < 1 || pendingMaximum > maximum) {
            throw new IllegalArgumentException("INTERACTION_LIMIT_INVALID");
        }
        return this;
    }

    /**
     * 返回交互能力描述。
     *
     * @return 能力描述对象。
     */
    public JSONObject capabilities() {
        return new JSONObject()
                .put("enabled", enabled)
                .put("messages", "run-messages-v1")
                .put("questions", "structured-input-v1")
                .put("maxMessages", maximum)
                .put("maxPending", pendingMaximum);
    }

    /**
     * 校验输入修订号已全部应用。
     *
     * @param r run 行。
     */
    public static void requireCurrent(JSONObject r) {
        if (r.optLong("input_revision", 0) != r.optLong("applied_input_revision", 0)) {
            throw new StoreException("INPUT_REVISION_CONFLICT");
        }
    }

    /**
     * 校验消息与待处理队列容量。
     *
     * @param c 事务连接。
     * @param r run 行。
     * @throws Exception 查询失败时抛出。
     */
    void requireCapacity(Connection c, JSONObject r) throws Exception {
        long count = first(rows(c, "SELECT COUNT(*) AS n FROM harness_message WHERE run_id=?", r.getString("run_id"))).getLong("n");
        if (count >= maximum || r.getLong("input_revision") - r.getLong("applied_input_revision") >= pendingMaximum) {
            throw new StoreException("MESSAGE_QUEUE_FULL");
        }
    }

    /**
     * 落库一条用户消息并推进输入修订号。
     *
     * @param store 共享 RunStore。
     * @param c     事务连接。
     * @param r     run 行。
     * @param kind  消息类型。
     * @param input 消息输入。
     * @return 已受理的消息对象。
     * @throws Exception 写入失败时抛出。
     */
    static JSONObject record(JdbcRunStore store, Connection c, JSONObject r, String kind, JSONObject input) throws Exception {
        long revision = r.getLong("input_revision") + 1;
        String message = UUID.randomUUID().toString();
        long received = store.now(c);
        update(c, "INSERT INTO harness_message VALUES (?,?,?,?,?,?,?,?)", message, r.getString("run_id"),
                revision, kind, revision, canonical(input), received, canonical(new JSONObject().put("kind", kind).put("input", input)));
        update(c, "UPDATE harness_run SET input_revision=? WHERE run_id=?", revision, r.getString("run_id"));
        r.put("input_revision", revision);
        return new JSONObject()
                .put("messageId", message)
                .put("messageSeq", revision)
                .put("inputRevision", revision)
                .put("kind", kind)
                .put("input", input)
                .put("receivedAt", received)
                .put("status", "accepted");
    }

    /**
     * 标记当前输入已被应用。
     *
     * @param store 共享 RunStore。
     * @param c     事务连接。
     * @param r     run 行。
     * @throws Exception 写入失败时抛出。
     */
    static void applied(JdbcRunStore store, Connection c, JSONObject r) throws Exception {
        long revision = r.getLong("input_revision");
        if (revision <= r.getLong("applied_input_revision")) {
            return;
        }
        update(c, "UPDATE harness_run SET applied_input_revision=? WHERE run_id=?", revision, r.getString("run_id"));
        r.put("applied_input_revision", revision);
        store.emit(c, r, "message.applied", new JSONObject().put("inputRevision", revision), "control");
    }

    /**
     * 结束当前等待并累计等待时长。
     *
     * @param store 共享 RunStore。
     * @param c     事务连接。
     * @param r     run 行。
     * @throws Exception 写入失败时抛出。
     */
    static void endWait(JdbcRunStore store, Connection c, JSONObject r) throws Exception {
        long start = r.getLong("wait_started_at");
        if (start == 0) {
            return;
        }
        long elapsed = r.getLong("wait_accumulated_millis") + Math.max(0, store.now(c) - start);
        update(c, "UPDATE harness_run SET wait_started_at=0,wait_accumulated_millis=? WHERE run_id=?", elapsed, r.getString("run_id"));
        r.put("wait_started_at", 0).put("wait_accumulated_millis", elapsed);
    }

    /**
     * 校验结构化问题的 id 与选项唯一性。
     *
     * @param decision 含 questions 的决策对象。
     */
    public static void validateQuestions(JSONObject decision) {
        JSONArray questions = decision.optJSONArray("questions");
        if (questions == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Object entry : questions) {
            JSONObject q = (JSONObject) entry;
            if (!ids.add(q.getString("questionId"))) {
                throw new StoreException("QUESTION_ID_CONFLICT");
            }
            Set<String> options = new HashSet<>();
            for (Object option : q.getJSONArray("options")) {
                if (!options.add(((JSONObject) option).getString("id"))) {
                    throw new StoreException("QUESTION_OPTION_CONFLICT");
                }
            }
            if (options.isEmpty() && !q.getBoolean("allowFreeText")) {
                throw new StoreException("QUESTION_NO_ANSWER");
            }
        }
    }

    /**
     * 把等待的回答组装为输入，并核对交互版本与问题答案一致性。
     *
     * @param wait 等待行。
     * @param body 回答请求体。
     * @return 组装后的输入对象。
     */
    static JSONObject answer(JSONObject wait, JSONObject body) {
        JSONObject details = new JSONObject(wait.getString("payload_json"));
        if (body.getLong("interactionVersion") != details.optLong("interactionVersion", 0)) {
            throw new StoreException("INTERACTION_VERSION_CONFLICT");
        }
        JSONObject input = new JSONObject(body.getJSONObject("input").toString());
        JSONArray questions = details.optJSONArray("questions");
        JSONArray answers = body.optJSONArray("answers");
        if (questions == null) {
            if (answers != null && answers.length() > 0) {
                throw new StoreException("ANSWER_QUESTION_CONFLICT");
            }
            return input;
        }
        Map<String, JSONObject> supplied = new HashMap<>();
        if (answers != null) {
            for (Object entry : answers) {
                JSONObject a = (JSONObject) entry;
                if (supplied.put(a.getString("questionId"), a) != null) {
                    throw new StoreException("ANSWER_QUESTION_CONFLICT");
                }
            }
        }
        // 聊天输入框可直接回答单个自由文本问题。
        if (supplied.isEmpty() && questions.length() == 1 && questions.getJSONObject(0).getBoolean("allowFreeText")) {
            supplied.put(questions.getJSONObject(0).getString("questionId"), new JSONObject().put("text", input.getString("instruction")));
        }
        StringBuilder text = new StringBuilder(input.getString("instruction"));
        for (Object entry : questions) {
            JSONObject q = (JSONObject) entry;
            JSONObject a = supplied.remove(q.getString("questionId"));
            if (a == null) {
                throw new StoreException("ANSWER_REQUIRED");
            }
            String selected = a.optString("optionId", "");
            String free = a.optString("text", "");
            if (selected.isEmpty() && free.trim().isEmpty()) {
                throw new StoreException("ANSWER_REQUIRED");
            }
            if (!free.isEmpty() && !q.getBoolean("allowFreeText")) {
                throw new StoreException("ANSWER_FREE_TEXT_FORBIDDEN");
            }
            String label = "";
            for (Object option : q.getJSONArray("options")) {
                JSONObject o = (JSONObject) option;
                if (o.getString("id").equals(selected)) {
                    label = o.getString("label");
                }
            }
            if (!selected.isEmpty() && label.isEmpty()) {
                throw new StoreException("ANSWER_OPTION_CONFLICT");
            }
            text.append('\n').append(q.getString("prompt")).append(": ").append(label);
            if (!free.isEmpty()) {
                text.append(' ').append(free);
            }
        }
        if (!supplied.isEmpty()) {
            throw new StoreException("ANSWER_QUESTION_CONFLICT");
        }
        if (text.length() > 32768) {
            throw new StoreException("MESSAGE_TOO_LARGE");
        }
        return input.put("instruction", text.toString());
    }

    /**
     * 受理一条用户消息，按幂等键查回放，并联动等待与附件绑定。
     *
     * @param tenant  租户标识。
     * @param subject 主体标识。
     * @param run     run ID。
     * @param key     幂等键。
     * @param body    消息请求体。
     * @return 已受理的消息对象。
     */
    public JSONObject append(String tenant, String subject, String run, String key, JSONObject body) {
        String receipt = hash(new JSONArray().put(tenant).put(subject).put(run).put("message").put(id(key)));
        String digest = hash(body);
        return store.tx(c -> {
            JSONObject r = store.run(c, tenant, subject, run, true);
            JSONObject old = first(rows(c, "SELECT * FROM harness_control_receipt WHERE receipt_id=?", receipt));
            if (old != null) {
                if (!digest.equals(old.getString("payload_hash"))) {
                    throw new StoreException("IDEMPOTENCY_CONFLICT");
                }
                return new JSONObject(old.getString("response_json"));
            }
            if (!enabled) {
                throw new StoreException("INTERACTION_NOT_READY");
            }
            if (Arrays.asList("succeeded", "failed", "cancelled", "timed_out", "cancelling").contains(r.getString("state"))) {
                throw new StoreException("RUN_TERMINAL");
            }
            JSONObject pin = new CatalogStore(store, mysql).pinned(c, tenant, subject, run);
            long lifetime = ExecutionProfiles.execution(pin).getJSONObject("executionLimits").getLong("maxLifetimeMillis");
            if (store.now(c) >= r.getLong("created_at") + lifetime) {
                throw new StoreException("WAIT_EXPIRED");
            }
            requireCapacity(c, r);
            JSONObject input = new JSONObject(body.getJSONObject("input").toString());
            JSONObject wait = first(rows(c, "SELECT * FROM harness_wait WHERE run_id=? AND status='pending'", run));
            if (wait != null && store.now(c) >= wait.getLong("expires_at")) {
                throw new StoreException("WAIT_EXPIRED");
            }
            boolean answering = body.getString("kind").equals("answer");
            if (answering) {
                if (wait == null || !wait.getString("wait_id").equals(body.getString("waitId"))
                        || !wait.getString("kind").equals("input") || !r.getString("state").equals("waiting_input")) {
                    throw new StoreException("WAIT_STATE_CONFLICT");
                }
                if (store.now(c) >= wait.getLong("expires_at")) {
                    throw new StoreException("WAIT_EXPIRED");
                }
                input = answer(wait, body);
            }
            JSONArray attachments = input.getJSONArray("attachmentIds");
            if (files == null && attachments.length() > 0) {
                throw new StoreException("FILES_DISABLED");
            }
            if (files != null) {
                files.bind(c, tenant, subject, run, attachments);
            }
            if (wait != null) {
                update(c, "UPDATE harness_wait SET status=? WHERE wait_id=?", answering ? "resumed" : "superseded", wait.getString("wait_id"));
                endWait(store, c, r);
                if (r.getString("state").startsWith("waiting_")) {
                    update(c, "UPDATE harness_run SET state='running' WHERE run_id=?", run);
                    r.put("state", "running");
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

    /**
     * 分页读取消息列表。
     *
     * @param tenant  租户标识。
     * @param subject 主体标识。
     * @param run     run ID。
     * @param after   游标（不含）。
     * @param limit   最多返回条数，1..100。
     * @return 含 messages、nextCursor 与修订号的分页对象。
     */
    public JSONObject list(String tenant, String subject, String run, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) {
            throw new StoreException("INVALID_PAGE");
        }
        return store.tx(c -> {
            JSONObject r = store.run(c, tenant, subject, run, false);
            JSONArray all = rows(c, "SELECT * FROM harness_message WHERE run_id=? AND message_seq>? ORDER BY message_seq LIMIT ?", run, after, limit);
            JSONArray messages = new JSONArray();
            long cursor = after;
            int size = 0;
            for (Object entry : all) {
                JSONObject m = (JSONObject) entry;
                JSONObject view = new JSONObject()
                        .put("messageId", m.getString("message_id"))
                        .put("messageSeq", m.getLong("message_seq"))
                        .put("inputRevision", m.getLong("input_revision"))
                        .put("kind", m.getString("kind"))
                        .put("input", new JSONObject(m.getString("input_json")))
                        .put("request", new JSONObject(m.getString("request_json")))
                        .put("receivedAt", m.getLong("received_at"))
                        .put("status", m.getLong("input_revision") <= r.getLong("applied_input_revision") ? "applied" : Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(r.getString("state")) ? "unprocessed_terminal" : "accepted");
                int bytes = canonical(view).getBytes(StandardCharsets.UTF_8).length;
                if (messages.length() > 0 && size + bytes > 512 * 1024) {
                    break;
                }
                messages.put(view);
                size += bytes;
                cursor = m.getLong("message_seq");
            }
            return new JSONObject()
                    .put("messages", messages)
                    .put("nextCursor", cursor)
                    .put("inputRevision", r.getLong("input_revision"))
                    .put("appliedInputRevision", r.getLong("applied_input_revision"));
        });
    }
}
