/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 兼容层响应校验工具。
 *
 * <p>检查推理结果的结构完整性并安全读取用量字段，异常统一以 {@link CompletionError} 抛出。</p>
 */
public final class CompletionResult {
    private CompletionResult() {
    }

    /**
     * 校验响应结构。
     *
     * <p>检查必需字段与嵌套层级，不合法时抛出 {@link CompletionError} 。</p>
     *
     * @param result 响应体。
     */
    public static void validate(JSONObject result) {
        try {
            JSONObject message = result.getJSONObject("message");
            if (!"assistant".equals(message.getString("role"))) throw new IllegalArgumentException();
            if (!message.isNull("content") && !(message.get("content") instanceof String))
                throw new IllegalArgumentException();
            String finish = result.getString("finish_reason");
            if (!finish.equals("stop") && !finish.equals("length") && !finish.equals("tool_calls") && !finish.equals("content_filter"))
                throw new IllegalArgumentException();
            JSONArray calls = message.optJSONArray("tool_calls");
            if (message.has("tool_calls") && calls == null) throw new IllegalArgumentException();
            if (finish.equals("tool_calls") && (calls == null || calls.length() == 0))
                throw new IllegalArgumentException();
            Set<String> ids = new HashSet<>();
            if (calls != null) for (int i = 0; i < calls.length(); ++i) {
                JSONObject call = calls.getJSONObject(i), function = call.getJSONObject("function");
                if (!"function".equals(call.getString("type")) || !ids.add(CompletionRequest.text(call, "id")))
                    throw new IllegalArgumentException();
                CompletionRequest.text(function, "name");
                new JSONObject(function.getString("arguments"));
            }
            if (result.has("content_blocks")) {
                JSONArray blocks = result.getJSONArray("content_blocks");
                StringBuilder text = new StringBuilder();
                Set<String> blockIds = new HashSet<>();
                for (int i = 0; i < blocks.length(); ++i) {
                    JSONObject block = blocks.getJSONObject(i);
                    if ("text".equals(block.getString("type"))) text.append(block.getString("text"));
                    else if ("tool_use".equals(block.getString("type"))) {
                        String id = block.getString("id");
                        if (!ids.contains(id) || !blockIds.add(id)) throw new IllegalArgumentException();
                        boolean found = false;
                        if (calls != null) for (int j = 0; j < calls.length(); ++j) {
                            JSONObject call = calls.getJSONObject(j), function = call.getJSONObject("function");
                            if (id.equals(call.getString("id"))) {
                                found = block.getString("name").equals(function.getString("name"))
                                        && block.getJSONObject("input").similar(new JSONObject(function.getString("arguments")));
                            }
                        }
                        if (!found) throw new IllegalArgumentException();
                    } else throw new IllegalArgumentException();
                }
                if (!blockIds.equals(ids) || !text.toString().equals(message.isNull("content") ? "" : message.getString("content")))
                    throw new IllegalArgumentException();
            }
            if (result.has("stop_sequence") && !result.isNull("stop_sequence"))
                CompletionRequest.text(result, "stop_sequence");
            JSONObject usage = result.getJSONObject("usage");
            long input = tokens(usage, "prompt_tokens"), output = tokens(usage, "completion_tokens");
            if (tokens(usage, "total_tokens") != Math.addExact(input, output)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new CompletionError(502, "upstream_error", "Invalid generation result or token usage", null);
        }
    }

    /**
     * 读取用量中的 token 数。
     *
     * @param usage 用量对象。
     * @param name  字段名，如 {@code prompt_tokens} 。
     * @return token 数；字段缺失返回 0 。
     */
    public static long tokens(JSONObject usage, String name) {
        Object value = usage.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new IllegalArgumentException("Invalid token usage");
        long count = ((Number) value).longValue();
        if (count < 0) throw new IllegalArgumentException("Invalid token usage");
        return count;
    }
}
