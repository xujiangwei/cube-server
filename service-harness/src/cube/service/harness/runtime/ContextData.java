/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.runtime;

import cube.service.harness.protocol.StrictJson;
import cube.service.harness.store.JdbcRunStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 压缩文本叶子节点，同时保留合法 JSON、数组覆盖与完整的执行引用。
 */
final class ContextData {
    /**
     * 必须精确保留的字段名：引用、标识、摘要与位置等执行相关字段。
     */
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
            "release", "path", "name", "skill", "unit", "mediaType", "answer", "response", "sha256",
            "source", "location", "fileCode", "fileName"));

    /**
     * 允许压缩的散文文本字段名。
     */
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "text", "content", "description", "snippet", "title", "message", "observation", "notes", "body", "excerpt"));

    /**
     * 解析严格 JSON 文本，格式非法时返回 null。
     *
     * @param text 待解析的 JSON 文本。
     * @return 解析后的对象，非法时返回 null。
     */
    static Object parse(String text) {
        try {
            return StrictJson.parse(text.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * 按预算压缩对象中的文本叶子，非资源页场景。
     *
     * @param value     待压缩对象。
     * @param allowance 可用文本字符预算。
     * @return 规范化 JSON 文本。
     */
    static String compact(Object value, int allowance) {
        return compact(value, allowance, false);
    }

    /**
     * 按预算压缩对象中的文本叶子；资源页场景额外维护 nextOffset 游标。
     *
     * @param value       待压缩对象。
     * @param allowance   可用文本字符预算。
     * @param resourcePage 是否为资源分页结果。
     * @return 规范化 JSON 文本。
     */
    static String compact(Object value, int allowance, boolean resourcePage) {
        Object projected = project(value, "", new int[]{allowance});
        if (resourcePage && value instanceof JSONObject && projected instanceof JSONObject) {
            JSONObject original = (JSONObject) value;
            JSONObject out = (JSONObject) projected;
            Object offset = original.opt("offset");
            Object next = original.opt("nextOffset");
            if (original.opt("release") instanceof String && original.opt("path") instanceof String
                    && original.opt("text") instanceof String && pageOffset(offset, 0) && pageOffset(next, -1)
                    && !original.getString("text").equals(out.getString("text"))) {
                String text = out.getString("text");
                String raw = original.getString("text");
                if (text.isEmpty() && !raw.isEmpty()) {
                    text = raw.substring(0, raw.offsetByCodePoints(0, 1));
                    out.put("text", text);
                }
                long end = ((Number) offset).longValue() + text.codePointCount(0, text.length());
                if (end <= Integer.MAX_VALUE) {
                    out.put("nextOffset", end);
                }
            }
        }
        return JdbcRunStore.canonical(projected);
    }

    /**
     * 判断分页游标是否为合法的非负整数。
     *
     * @param value   游标值。
     * @param minimum 允许的最小值。
     * @return 合法时返回 true。
     */
    private static boolean pageOffset(Object value, int minimum) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= minimum
                && ((Number) value).longValue() <= Integer.MAX_VALUE;
    }

    /**
     * 递归投影并压缩值：对象按键排序、数组保留全部元素，文本按字段类别决定是否压缩。
     *
     * @param value     当前值。
     * @param key       所属字段名。
     * @param remaining 剩余文本预算，单元素数组以便递归共享。
     * @return 投影后的值。
     */
    private static Object project(Object value, String key, int[] remaining) {
        if (value instanceof JSONObject) {
            JSONObject out = new JSONObject();
            JSONObject object = (JSONObject) value;
            for (String child : new TreeSet<>(object.keySet())) {
                out.put(child, project(object.get(child), child, remaining));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray out = new JSONArray();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                out.put(project(array.get(i), key, remaining));
            }
            return out;
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = (String) value;
        if (EXACT.contains(key) || key.matches("(?i).*(refs?|ids?|hash|digest|cursor|version|url|uri|path|token|code|key)$")) {
            return text;
        }
        if (key.endsWith("Json")) {
            Object nested = parse(text);
            if (nested instanceof JSONObject || nested instanceof JSONArray) {
                return compactNested(nested, remaining);
            }
        }
        // 未知字符串可能是可执行值，只有声明的散文字段才允许压缩。
        if (!key.isEmpty() && !TEXT.contains(key)) {
            return text;
        }
        int length = text.codePointCount(0, text.length());
        // 短标量值（计数、状态、日期、枚举）同样精确保留。
        if (length <= 128) {
            return text;
        }
        int kept = Math.min(length, remaining[0]);
        remaining[0] -= kept;
        return text.substring(0, text.offsetByCodePoints(0, kept));
    }

    /**
     * 压缩内嵌 JSON 文本并复用同一预算。
     *
     * @param value     已解析的内嵌对象。
     * @param remaining 剩余文本预算。
     * @return 规范化 JSON 文本。
     */
    private static String compactNested(Object value, int[] remaining) {
        return JdbcRunStore.canonical(project(value, "", remaining));
    }
}
