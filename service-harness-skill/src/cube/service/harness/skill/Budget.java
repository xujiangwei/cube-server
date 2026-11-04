/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.store.StoreException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 技能结果的体积预算工具。
 *
 * <p>预算同时覆盖序列化后的 JSON 与 SDK 内嵌的 dataJson ，UTF-8 编码与转义
 * 带来的膨胀都算在内。超限时由调用方截断文本或条目，而不是让结果原样溢出。</p>
 */
final class Budget {
    /**
     * 工具类，禁止实例化。
     */
    private Budget() {
    }

    /**
     * 计算结果经 SDK 包装（status/dataJson/artifacts）后的字节数。
     *
     * @param data 技能返回的结果对象。
     * @return 返回包装后的 UTF-8 字节数，额外加 1 字节留给分隔符。
     */
    static int runnerBytes(JSONObject data) {
        JSONObject value = new JSONObject().put("status", "success").put("dataJson", data.toString())
                .put("artifacts", new JSONArray());
        return new JSONObject().put("type", "result").put("value", value).toString()
                .getBytes(StandardCharsets.UTF_8).length + 1;
    }

    /**
     * 按字符数截断字符串，截断点不会落在代理对中间。
     *
     * @param value 待截断的字符串。
     * @param chars 最多保留的字符数。
     * @return 返回截断后的字符串。
     */
    static String prefix(String value, int chars) {
        int end = Math.min(value.length(), Math.max(0, chars));
        // 末尾若是高位代理字符，退回一个字符，避免产生半个代理对。
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 按字节预算裁剪结果中的文本字段，必要时标记 truncated 。
     *
     * @param data     结果对象。
     * @param field    文本字段名。
     * @param maxBytes 允许的最大字节数。
     */
    static void text(JSONObject data, String field, int maxBytes) {
        String original = data.getString(field);
        if (AbstractProvider.bytes(data) <= maxBytes) {
            return;
        }
        data.put("truncated", true);
        int low = 0;
        int high = original.length();
        // 二分查找能容纳的最大字符数。
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            data.put(field, prefix(original, mid));
            if (AbstractProvider.bytes(data) <= maxBytes) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        data.put(field, prefix(original, low));
        if (AbstractProvider.bytes(data) > maxBytes) {
            throw new StoreException("TOOL_RESULT_LIMIT");
        }
    }

    /**
     * 按字节预算裁剪结果中的条目数组，必要时标记 truncated 。
     *
     * @param data     结果对象。
     * @param field    条目数组字段名。
     * @param maxBytes 允许的最大字节数。
     */
    static void items(JSONObject data, String field, int maxBytes) {
        JSONArray items = data.getJSONArray(field);
        while (AbstractProvider.bytes(data) > maxBytes && items.length() > 0) {
            data.put("truncated", true);
            items.remove(items.length() - 1);
        }
        if (AbstractProvider.bytes(data) > maxBytes) {
            throw new StoreException("TOOL_RESULT_LIMIT");
        }
    }
}
