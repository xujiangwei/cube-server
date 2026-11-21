/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 从原始解码工具数据中按 RFC 6901 指针做有界完整选取。
 */
final class ContextReadback {
    /**
     * 按指针选取工具结果片段并生成带摘要的完整选择结果。
     *
     * @param result 工具结果对象，含 dataJson。
     * @param pointer RFC 6901 指针，空串表示根节点。
     * @param offset  偏移量，仅允许 0。
     * @param limit   选中内容的最大码点数。
     * @param intent  原工具意图标识。
     * @param hash    原工具结果摘要。
     * @return 含选中值、摘要与完整标记的选择结果。
     */
    static JSONObject select(JSONObject result, String pointer, int offset, int limit, String intent, String hash) {
        if (offset != 0 || pointer.length() > 1024 || !pointer.isEmpty() && !pointer.startsWith("/")) {
            throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
        }
        Object value = ContextData.parse(result.optString("dataJson"));
        if (value == null) {
            throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
        }
        String[] parts = pointer.isEmpty() ? new String[0] : pointer.substring(1).split("/", -1);
        if (parts.length > 32) {
            throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
        }
        for (String part : parts) {
            if (Pattern.compile("~(?![01])").matcher(part).find()) {
                throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_INVALID");
            }
            String key = part.replace("~1", "/").replace("~0", "~");
            if (value instanceof JSONObject && ((JSONObject) value).has(key)) {
                value = ((JSONObject) value).get(key);
            } else if (value instanceof JSONArray && key.matches("0|[1-9][0-9]{0,8}")) {
                int index = Integer.parseInt(key);
                if (index >= ((JSONArray) value).length()) {
                    throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_NOT_FOUND");
                }
                value = ((JSONArray) value).get(index);
            } else {
                throw RuntimeSupport.error("CONTEXT_RESULT_SELECTOR_NOT_FOUND");
            }
        }
        String raw = JdbcRunStore.canonical(value);
        if (raw.codePointCount(0, raw.length()) > limit) {
            throw RuntimeSupport.error("CONTEXT_RESULT_SELECTION_TOO_LARGE");
        }
        return new JSONObject()
                .put("encoding", "tool-result-selection-json-v1")
                .put("intent", intent)
                .put("resultHash", hash)
                .put("selector", pointer)
                .put("selectedHash", JdbcRunStore.hash(value))
                .put("complete", true)
                .put("value", value);
    }
}
