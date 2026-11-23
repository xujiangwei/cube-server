/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 有界且不可变的会话转移；绝不转移授权或执行证据。
 */
final class ConversationStore {
    private static final int MAX_HISTORY_MESSAGES = 64;
    private static final int MAX_HISTORY_BYTES = 24576;
    private static final int MAX_HISTORY_RUNS = 32;

    static void validateSelection(JSONObject request) {
        boolean newConversation = request.optBoolean("newConversation");
        boolean existingConversation = request.has("conversationId");
        if ((newConversation && existingConversation)
                || (request.has("parentRunId") && (newConversation || existingConversation))
                || (request.has("conversationInstructions") && !newConversation && !existingConversation)) {
            throw new StoreException("CONVERSATION_SELECTION_INVALID");
        }
    }

    /** 解析当前会话及最近消息。调用方在同一创建事务中增加会话序号。 */
    static JSONObject session(Connection c, boolean mysql, String tenant, String subject,
                              JSONObject request) throws Exception {
        boolean newConversation = request.optBoolean("newConversation");
        boolean existingConversation = request.has("conversationId");
        if (!newConversation && !existingConversation) {
            return null;
        }
        String conversation = newConversation ? UUID.randomUUID().toString() : request.getString("conversationId");
        long previous = 0;
        String instructions = "";
        if (existingConversation) {
            JSONObject row = first(rows(c, "SELECT sequence_no,instructions FROM harness_conversation"
                            + " WHERE conversation_id=? AND tenant=? AND subject_id=?" + (mysql ? " FOR UPDATE" : ""),
                    conversation, tenant, subject));
            if (row == null) {
                throw new StoreException("CONVERSATION_NOT_FOUND");
            }
            previous = row.getLong("sequence_no");
            instructions = row.getString("instructions");
            JSONObject last = first(rows(c, "SELECT state FROM harness_run"
                            + " WHERE conversation_id=? AND conversation_seq=? AND tenant=? AND subject_id=?",
                    conversation, previous, tenant, subject));
            if (last == null) {
                throw new StoreException("CONVERSATION_HISTORY_MISSING");
            }
            if (!Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(last.getString("state"))) {
                throw new StoreException("CONVERSATION_ACTIVE_RUN");
            }
        }
        instructions = request.optString("conversationInstructions", instructions);
        JSONObject history = sessionHistory(c, tenant, subject, conversation, previous);
        history.put("conversationId", conversation);
        if (!instructions.isEmpty()) {
            history.put("instructions", instructions);
        }
        return new JSONObject().put("id", conversation).put("sequence", previous + 1)
                .put("instructions", instructions).put("history", history);
    }

    static void advanceSession(Connection c, String tenant, String subject, JSONObject session) throws Exception {
        String id = session.getString("id");
        long sequence = session.getLong("sequence");
        if (sequence == 1) {
            update(c, "INSERT INTO harness_conversation (conversation_id,tenant,subject_id,sequence_no,instructions)"
                    + " VALUES (?,?,?,?,?)", id, tenant, subject, sequence, session.getString("instructions"));
        } else if (update(c, "UPDATE harness_conversation SET sequence_no=?,instructions=?"
                        + " WHERE conversation_id=? AND tenant=? AND subject_id=? AND sequence_no=?",
                sequence, session.getString("instructions"), id, tenant, subject, sequence - 1) != 1) {
            throw new StoreException("CONVERSATION_SEQUENCE_CONFLICT");
        }
    }

    private static JSONObject sessionHistory(Connection c, String tenant, String subject,
                                             String conversation, long previous) throws Exception {
        JSONArray runs = previous == 0 ? new JSONArray() : rows(c,
                "SELECT run_id,state,execution_settlement FROM harness_run"
                        + " WHERE conversation_id=? AND tenant=? AND subject_id=? ORDER BY conversation_seq DESC LIMIT " + MAX_HISTORY_RUNS,
                conversation, tenant, subject);
        List<JSONObject> latest = new ArrayList<>();
        int remaining = MAX_HISTORY_BYTES;
        boolean truncated = previous > runs.length();
        history:
        for (Object entry : runs) {
            JSONObject run = (JSONObject) entry;
            String runId = run.getString("run_id");
            // Reserve space for the user's messages from this run before considering its answer.
            int answerPosition = latest.size();
            JSONArray inputs = rows(c, "SELECT event_type,data_json FROM harness_event WHERE run_id=?"
                            + " AND event_type IN ('run.created','control.resume','message.accepted')"
                            + " ORDER BY event_seq DESC LIMIT " + (MAX_HISTORY_MESSAGES + 1), runId);
            for (Object item : inputs) {
                JSONObject event = (JSONObject) item;
                JSONObject data = new JSONObject(event.getString("data_json"));
                JSONObject input = input(event, data);
                // Previous attachments stay bound to their original run. Explicit inputs are checked at admission.
                JSONObject content = new JSONObject().put("instruction", input.getString("instruction"))
                        .put("attachmentIds", new JSONArray());
                JSONObject message = new JSONObject().put("role", "user").put("content", content);
                if (latest.size() == MAX_HISTORY_MESSAGES) {
                    truncated = true;
                    break history;
                }
                int bytes = canonical(message).getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes > remaining) {
                    truncated = true;
                    continue;
                }
                remaining -= bytes;
                latest.add(message);
            }
            truncated |= inputs.length() > MAX_HISTORY_MESSAGES;
            JSONObject assistant = sessionAnswer(c, run);
            int answerBytes = canonical(assistant).getBytes(StandardCharsets.UTF_8).length + 1;
            if (latest.size() == MAX_HISTORY_MESSAGES || answerBytes > remaining) {
                truncated = true;
            } else {
                remaining -= answerBytes;
                latest.add(answerPosition, assistant);
            }
        }
        Collections.reverse(latest);
        return new JSONObject().put("messages", new JSONArray(latest)).put("attachmentIds", new JSONArray())
                .put("historyTruncated", truncated);
    }

    private static JSONObject sessionAnswer(Connection c, JSONObject run) throws Exception {
        String state = run.getString("state");
        String text = "Previous task ended: " + state;
        if (!run.getString("execution_settlement").equals("confirmed")) {
            text = "Previous task has unconfirmed execution. Its effects are unknown. Do not automatically repeat it.";
        } else if (state.equals("succeeded")) {
            JSONObject result = first(rows(c, "SELECT result_json FROM harness_result WHERE run_id=?", run.getString("run_id")));
            if (result != null) {
                text = new JSONObject(result.getString("result_json")).getJSONObject("result").optString("text", text);
            }
        }
        return new JSONObject().put("role", "assistant").put("content", text);
    }

    private static JSONObject input(JSONObject event, JSONObject data) {
        return event.getString("event_type").equals("control.resume")
                ? data.getJSONObject("payload").getJSONObject("input") : data.getJSONObject("input");
    }

    /**
     * 从已终态、已结算的父 run 继承会话历史。
     *
     * @param store   共享 RunStore。
     * @param c       事务连接。
     * @param tenant  租户标识。
     * @param subject 主体标识。
     * @param parent  父 run 标识。
     * @param files   文件服务，可为 null。
     * @return 会话转移对象，含父 run、深度、消息与附件引用。
     * @throws Exception 父 run 未终态/未结算、超限或附件校验失败时抛出。
     */
    static JSONObject inherit(JdbcRunStore store, Connection c, String tenant, String subject,
                              String parent, FileStore files) throws Exception {
        JSONObject r = store.run(c, tenant, subject, parent, true);
        if (!Arrays.asList("succeeded", "failed", "cancelled", "timed_out").contains(r.getString("state"))) {
            throw new StoreException("PARENT_RUN_ACTIVE");
        }
        if (!r.getString("execution_settlement").equals("confirmed")) {
            throw new StoreException("PARENT_RUN_UNSETTLED");
        }
        JSONArray history = new JSONArray();
        int depth = 1;
        Set<String> ids = new LinkedHashSet<>();
        JSONArray events = rows(c, "SELECT event_type,data_json FROM harness_event WHERE run_id=? AND event_type IN ('conversation.history','run.created','control.resume','message.accepted') ORDER BY event_seq", parent);
        for (Object entry : events) {
            JSONObject e = (JSONObject) entry;
            JSONObject data = new JSONObject(e.getString("data_json"));
            if (e.getString("event_type").equals("conversation.history")) {
                depth = data.getInt("depth") + 1;
                for (Object message : data.getJSONArray("messages")) {
                    history.put(message);
                }
                for (Object id : data.getJSONArray("attachmentIds")) {
                    ids.add((String) id);
                }
            } else {
                JSONObject input = input(e, data);
                history.put(new JSONObject()
                        .put("role", "user")
                        .put("content", input));
                for (Object id : input.getJSONArray("attachmentIds")) {
                    ids.add((String) id);
                }
            }
        }
        JSONObject row = first(rows(c, "SELECT result_json FROM harness_result WHERE run_id=?", parent));
        if (row == null) {
            throw new StoreException("PARENT_RUN_UNSETTLED");
        }
        JSONObject result = new JSONObject(row.getString("result_json")).getJSONObject("result");
        String text = result.optString("text", "Previous task ended: " + r.getString("state"));
        history.put(new JSONObject()
                .put("role", "assistant")
                .put("content", text));
        JSONObject transfer = new JSONObject()
                .put("parentRunId", parent)
                .put("depth", depth)
                .put("messages", history)
                .put("attachmentIds", new JSONArray(ids));
        if (depth > 16 || history.length() > 128 || ids.size() > 64 || canonical(transfer).getBytes(StandardCharsets.UTF_8).length > 49152) {
            throw new StoreException("CONVERSATION_LIMIT");
        }
        if (files == null && !ids.isEmpty()) {
            throw new StoreException("FILES_DISABLED");
        }
        if (files != null) {
            files.validateBindings(c, tenant, subject, transfer.getJSONArray("attachmentIds"));
        }
        return transfer;
    }
}
