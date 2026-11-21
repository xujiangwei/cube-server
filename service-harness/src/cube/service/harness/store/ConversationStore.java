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
import java.util.LinkedHashSet;
import java.util.Set;

import static cube.service.harness.store.JdbcRunStore.*;

/**
 * 有界且不可变的会话转移；绝不转移授权或执行证据。
 */
final class ConversationStore {
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
                JSONObject input = e.getString("event_type").equals("control.resume") ? data.getJSONObject("payload").getJSONObject("input") : data.getJSONObject("input");
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
