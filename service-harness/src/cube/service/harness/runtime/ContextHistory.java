/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 以预算为驱动淘汰完整工具往返组，并对精确引用句柄做依赖闭包。
 */
final class ContextHistory {
    /**
     * 原始消息列表。
     */
    private final JSONArray original;

    /**
     * 已确认的事实账本。
     */
    private final JSONArray facts;

    /**
     * 完整工具往返组在消息列表中的下标。
     */
    private final List<Integer> groups = new ArrayList<>();

    /**
     * 每个往返组包含的工具提案 id 集合。
     */
    private final Map<Integer, Set<String>> ids = new LinkedHashMap<>();

    /**
     * 每个往返组依赖的其它组下标集合。
     */
    private final Map<Integer, Set<Integer>> dependencies = new LinkedHashMap<>();

    /**
     * 提案 id 到所属往返组的映射。
     */
    private final Map<String, Integer> owners = new HashMap<>();

    /**
     * 被钉住（不得淘汰）的组下标到钉住原因的映射。
     */
    private final Map<Integer, String> pinned = new LinkedHashMap<>();

    /**
     * 已淘汰的组下标集合。
     */
    private final Set<Integer> removed = new LinkedHashSet<>();

    /**
     * 是否启用自适应依赖淘汰，否则按固定窗口淘汰。
     */
    private final boolean adaptive;

    /**
     * 依据消息与事实账本构造历史视图。
     *
     * @param messages 原始消息列表。
     * @param facts    已确认的事实账本。
     * @param window   非自适应模式保留的往返组数量。
     * @param adaptive 是否启用自适应依赖淘汰。
     */
    ContextHistory(JSONArray messages, JSONArray facts, int window, boolean adaptive) {
        this.original = messages;
        this.facts = facts;
        this.adaptive = adaptive;
        Map<String, JSONObject> results = new LinkedHashMap<>();
        for (Object value : facts) {
            JSONObject f = (JSONObject) value;
            if (f.getString("kind").equals("tool") && f.has("result")) {
                results.put(f.getJSONObject("payload").getString("proposalId"), f);
            }
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            JSONObject content = m.optJSONObject("content");
            if (!m.getString("role").equals("assistant") || content == null || !content.optString("kind").equals("tool_calls")) {
                continue;
            }
            Set<String> group = new LinkedHashSet<>();
            for (Object call : content.getJSONArray("calls")) {
                group.add(((JSONObject) call).getString("proposalId"));
            }
            if (results.keySet().containsAll(group)) {
                groups.add(i);
                ids.put(i, group);
                for (String id : group) {
                    owners.put(id, i);
                }
            }
        }
        Set<String> reading = readingCalls(facts);
        for (int group : groups) {
            if (!Collections.disjoint(ids.get(group), reading)) {
                pinned.put(group, "active-readback");
            }
        }
        if (adaptive && !groups.isEmpty()) {
            pinned.put(groups.get(groups.size() - 1), "latest-complete-group");
            Map<String, Set<Integer>> producers = new HashMap<>();
            for (int group : groups) {
                Set<String> consumed = new HashSet<>();
                for (String id : ids.get(group)) {
                    collect(new JSONObject(results.get(id).getJSONObject("payload").getString("argumentsJson")), consumed, false, "");
                }
                Set<Integer> depends = new LinkedHashSet<>();
                for (String handle : consumed) {
                    if (producers.containsKey(handle)) {
                        depends.addAll(producers.get(handle));
                    }
                }
                dependencies.put(group, depends);
                for (String id : ids.get(group)) {
                    JSONObject f = results.get(id);
                    JSONObject data = TaskProgress.data(f);
                    if (data == null || !f.getJSONObject("result").getString("status").equals("success")) {
                        continue;
                    }
                    Set<String> produced = new HashSet<>();
                    // nextCursor 记录的是执行顺序，而非对前一页正文的依赖。
                    // 未被消费的游标在下方被钉住；已成功消费的页可以归档。
                    for (String field : data.keySet()) {
                        if (!field.equals("nextCursor")) {
                            collect(data.get(field), produced, true, field);
                        }
                    }
                    for (String handle : produced) {
                        producers.computeIfAbsent(handle, k -> new LinkedHashSet<>()).add(group);
                    }
                    Object cursor = data.opt("nextCursor");
                    if (cursor != null && cursor != JSONObject.NULL && !cursor.toString().isEmpty()) {
                        boolean used = false;
                        for (Object later : facts) {
                            JSONObject next = (JSONObject) later;
                            if (!next.getString("kind").equals("tool") || next.getLong("eventSeq") <= f.getLong("eventSeq")) {
                                continue;
                            }
                            if (next.optJSONObject("result") == null || !next.getJSONObject("result").getString("status").equals("success")) {
                                continue;
                            }
                            Set<String> values = new HashSet<>();
                            collect(new JSONObject(next.getJSONObject("payload").getString("argumentsJson")), values, false, "");
                            if (values.contains(cursor.toString())) {
                                used = true;
                                break;
                            }
                        }
                        if (!used) {
                            pinned.put(group, "unconsumed-pagination-cursor");
                        }
                    }
                }
            }
            boolean changed;
            do {
                changed = false;
                for (int group : new ArrayList<>(pinned.keySet())) {
                    for (int source : dependencies.getOrDefault(group, Collections.emptySet())) {
                        if (!pinned.containsKey(source)) {
                            pinned.put(source, "referenced-evidence");
                            changed = true;
                        }
                    }
                }
            } while (changed);
        }
        if (!adaptive) {
            for (int i = 0; i < groups.size() - window; i++) {
                if (!pinned.containsKey(groups.get(i))) {
                    removed.add(groups.get(i));
                }
            }
        }
    }

    /**
     * 跨中间工具恢复同一证据流，包括已归档的早期分页。
     *
     * @param facts 已确认的事实账本。
     * @return 仍需读取的工具提案 id 集合。
     */
    static Set<String> readingCalls(JSONArray facts) {
        Set<String> sessions = new HashSet<>();
        Set<String> calls = new LinkedHashSet<>();
        for (int i = facts.length() - 1; i >= 0; i--) {
            JSONObject f = facts.getJSONObject(i);
            if (!f.getString("kind").equals("tool")) {
                continue;
            }
            if (!f.getJSONObject("payload").getString("tool").equals("context.read_result")) {
                break;
            }
            String session = readSession(f);
            if (session != null) {
                sessions.add(session);
            }
        }
        for (Object entry : facts) {
            JSONObject f = (JSONObject) entry;
            String session = readSession(f);
            if (session != null && sessions.contains(session)) {
                calls.add(f.getJSONObject("payload").getString("proposalId"));
            }
        }
        return calls;
    }

    /**
     * 计算一次成功 read_result 的证据会话键。
     *
     * @param fact 工具事实。
     * @return 会话键，非成功 read_result 返回 null。
     */
    private static String readSession(JSONObject fact) {
        if (!fact.getString("kind").equals("tool") || !fact.getJSONObject("payload").getString("tool").equals("context.read_result")
                || fact.optJSONObject("result") == null || !fact.getJSONObject("result").getString("status").equals("success")) {
            return null;
        }
        JSONObject data = TaskProgress.data(fact);
        if (data == null || !Arrays.asList("tool-result-json-v1", "tool-result-selection-json-v1").contains(data.optString("encoding"))) {
            return null;
        }
        JSONObject args = new JSONObject(fact.getJSONObject("payload").getString("argumentsJson"));
        return JdbcRunStore.canonical(new JSONArray().put(args.getString("intent"))
                .put(data.getString("resultHash"))
                .put(data.getString("encoding"))
                .put(args.has("selector") ? args.get("selector") : JSONObject.NULL));
    }

    /**
     * 递归收集值中的引用句柄；仅类型化结果句柄产生依赖，参数按完整值搜索。
     *
     * @param value 当前值。
     * @param out   收集到的句柄集合。
     * @param typed 是否只收集类型化结果字段。
     * @param field 当前字段名。
     */
    static void collect(Object value, Set<String> out, boolean typed, String field) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet()) {
                collect(object.get(key), out, typed, key);
            }
        } else if (value instanceof JSONArray) {
            for (Object item : (JSONArray) value) {
                collect(item, out, typed, field);
            }
        } else if (value instanceof String) {
            String text = (String) value;
            Object parsed = ContextData.parse(text);
            if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
                collect(parsed, out, typed, field);
            } else if (!text.isEmpty() && (!typed || field.matches("(?i).*(ref|refs|id|ids|cursor|token|hash|digest)$"))) {
                out.add(text);
            }
        }
    }

    /**
     * 自适应模式下淘汰下一组未钉住的往返组。
     *
     * @return 淘汰了新组返回 true。
     */
    boolean retire() {
        if (!adaptive) {
            return false;
        }
        for (int group : groups) {
            if (!removed.contains(group) && !pinned.containsKey(group)) {
                removed.add(group);
                return true;
            }
        }
        return false;
    }

    /**
     * 返回当前已淘汰组的标记副本。
     *
     * @return 淘汰组下标集合。
     */
    Set<Integer> mark() {
        return new LinkedHashSet<>(removed);
    }

    /**
     * 用给定标记重置淘汰集合。
     *
     * @param mark 淘汰组下标集合。
     */
    void rewind(Set<Integer> mark) {
        removed.clear();
        removed.addAll(mark);
    }

    /**
     * 依据归档证据恢复对应往返组到淘汰集合。
     *
     * @param archived 归档证据列表，可为 null。
     */
    void restore(JSONArray archived) {
        if (!adaptive || archived == null) {
            return;
        }
        Set<String> evidence = new HashSet<>();
        for (Object entry : archived) {
            JSONObject f = (JSONObject) entry;
            evidence.add(f.getString("intent") + ":" + f.getString("resultHash"));
        }
        Set<String> idsToRetire = new HashSet<>();
        for (Object entry : facts) {
            JSONObject f = (JSONObject) entry;
            if (f.getString("kind").equals("tool") && f.has("resultHash") && evidence.contains(f.getString("intent") + ":" + f.getString("resultHash"))) {
                idsToRetire.add(f.getJSONObject("payload").getString("proposalId"));
            }
        }
        for (int group : groups) {
            if (!pinned.containsKey(group) && idsToRetire.containsAll(ids.get(group))) {
                removed.add(group);
            }
        }
    }

    /**
     * 生成当前历史视图：保留消息、归档证据、钉住原因与部分观测。
     *
     * @return 历史视图对象。
     */
    JSONObject view() {
        Set<String> retired = new HashSet<>();
        for (int group : removed) {
            retired.addAll(ids.get(group));
        }
        JSONArray selected = new JSONArray();
        JSONArray archive = new JSONArray();
        JSONArray pins = new JSONArray();
        for (int i = 0; i < original.length(); i++) {
            JSONObject m = original.getJSONObject(i);
            if (!removed.contains(i) && !(m.getString("role").equals("tool") && retired.contains(m.optString("proposalId")))) {
                selected.put(m);
            }
        }
        for (Object value : facts) {
            JSONObject f = (JSONObject) value;
            if (!f.getString("kind").equals("tool") || !f.has("result")) {
                continue;
            }
            String id = f.getJSONObject("payload").getString("proposalId");
            if (retired.contains(id)) {
                archive.put(TaskProgress.evidence(f).put("status", f.getJSONObject("result").getString("status")));
            }
            Integer group = owners.get(id);
            if (group != null && pinned.containsKey(group)) {
                pins.put(TaskProgress.evidence(f).put("reason", pinned.get(group)));
            }
        }
        JSONArray observations = new JSONArray();
        int allowance = 4096;
        for (int i = facts.length() - 1; i >= 0 && observations.length() < 8; i--) {
            JSONObject f = facts.getJSONObject(i);
            if (!f.getString("kind").equals("tool") || !retired.contains(f.getJSONObject("payload").getString("proposalId"))) {
                continue;
            }
            JSONObject data = TaskProgress.data(f);
            if (data == null) {
                continue;
            }
            JSONObject projection = TaskProgress.projection(data, 512);
            if (projection.length() == 0) {
                continue;
            }
            JSONObject observation = TaskProgress.evidence(f).put("partialProjection", true).put("data", projection);
            int length = RuntimeSupport.bytes(observation).length;
            if (length <= allowance) {
                observations.put(observation);
                allowance -= length;
            }
        }
        return new JSONObject()
                .put("method", adaptive ? "ledger-budget-dependencies-v1" : "ledger-window-v1")
                .put("messages", selected)
                .put("archived", archive)
                .put("pinned", pins)
                .put("observations", observations)
                .put("observationsDigest", JdbcRunStore.hash(observations))
                .put("archiveDigest", JdbcRunStore.hash(archive))
                .put("retainedToolRounds", groups.size() - removed.size());
    }
}
