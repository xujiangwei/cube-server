/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.completion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * 流式响应累积器。
 *
 * <p>把逐条到达的分片拼装成完整响应，并识别结束标记。</p>
 */
public final class OpenAIResponseAccumulator {

    private final StringBuilder text = new StringBuilder(), refusal = new StringBuilder();

    private final Map<Integer, JSONObject> calls = new TreeMap<>();

    private String finish;

    private JSONObject usage;

    private String stopSequence;

    private final java.util.List<Object> blocks = new java.util.ArrayList<>();

    private StringBuilder textBlock;

    /**
     * 判断是否已收到结束标记。
     *
     * @return 已结束返回 {@code true} 。
     */
    public boolean terminal() {
        return java.util.Arrays.asList("stop", "length", "tool_calls", "content_filter").contains(finish);
    }

    /** Latest cumulative usage snapshot, including evidence received before a disconnect. */
    public JSONObject usageEvidence() {
        return usage != null && cube.aigc.usage.UsageTokens.read(usage) != null ? new JSONObject(usage.toString()) : null;
    }

    /**
     * 追加一个分片。
     *
     * @param chunk 分片数据。
     * @return 可立即下发的增量；无增量时返回 {@code null} 。
     */
    public JSONObject add(JSONObject chunk) {
        if (chunk.has("error")) throw invalid();
        if (chunk.optJSONObject("usage") != null) {
            if (cube.aigc.usage.UsageTokens.read(chunk.getJSONObject("usage")) == null) throw invalid();
            usage = chunk.getJSONObject("usage");
        }
        JSONArray choices = chunk.getJSONArray("choices");
        if (choices.length() > 1) throw invalid();
        if (choices.length() == 0) return new JSONObject();
        JSONObject choice = choices.getJSONObject(0);
        if (choice.optInt("index", 0) != 0 || finish != null) throw invalid();
        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) delta = new JSONObject();
        if (!delta.isNull("content")) {
            String part = delta.getString("content");
            text.append(part);
            if (!part.isEmpty()) {
                if (textBlock == null) {
                    textBlock = new StringBuilder();
                    blocks.add(textBlock);
                }
                textBlock.append(part);
            }
        }
        if (!delta.isNull("refusal")) refusal.append(delta.getString("refusal"));
        JSONArray parts = delta.optJSONArray("tool_calls");
        if (parts != null) for (int n = 0; n < parts.length(); n++) {
            JSONObject part = parts.getJSONObject(n);
            int index = part.getInt("index");
            if (index < 0 || index >= 16 || !"function".equals(part.optString("type", "function"))) throw invalid();
            JSONObject call = calls.get(index);
            if (call == null) {
                call = new JSONObject().put("id", "").put("type", "function")
                        .put("function", new JSONObject().put("name", "").put("arguments", ""));
                calls.put(index, call);
                blocks.add(index);
            }
            textBlock = null;
            if (!part.isNull("id")) call.put("id", call.getString("id") + part.getString("id"));
            JSONObject function = part.optJSONObject("function");
            if (function != null) for (String key : new String[]{"name", "arguments"})
                if (!function.isNull(key)) call.getJSONObject("function").put(key,
                        call.getJSONObject("function").getString(key) + function.getString(key));
        }
        if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason");
        if (!choice.isNull("stop_sequence")) stopSequence = choice.getString("stop_sequence");
        if (text.length() + refusal.length() + calls.toString().length() > 65536) throw invalid();
        return delta;
    }

    /**
     * 组装最终响应。
     *
     * @return 完整响应体。
     */
    public JSONObject result() {
        if (finish == null) throw invalid();
        JSONArray tools = new JSONArray();
        for (JSONObject call : calls.values()) tools.put(call);
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text.toString());
        if (tools.length() > 0) message.put("tool_calls", tools);
        if (refusal.length() > 0) message.put("refusal", refusal.toString());
        JSONObject result = new JSONObject().put("message", message).put("finish_reason", finish);
        if (usage == null) throw new CompletionError(502, "usage_missing", "Model response omitted usage", null);
        result.put("usage", usage);
        if (stopSequence != null) result.put("stop_sequence", stopSequence);
        JSONArray ordered = new JSONArray();
        for (Object block : blocks) {
            if (block instanceof StringBuilder)
                ordered.put(new JSONObject().put("type", "text").put("text", block.toString()));
            else {
                JSONObject call = calls.get((Integer) block), f = call.getJSONObject("function");
                ordered.put(new JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                        .put("name", f.getString("name")).put("input", new JSONObject(f.getString("arguments"))));
            }
        }
        result.put("content_blocks", ordered);
        CompletionResult.validate(result);
        return result;
    }

    /**
     * 提取响应中的载荷。
     *
     * @param response 响应对象。
     * @return 载荷 JSON 。
     */
    public static JSONObject json(JSONObject response) {
        if (response.has("error")) throw invalid();
        JSONArray choices = response.getJSONArray("choices");
        if (choices.length() != 1 || choices.getJSONObject(0).optInt("index", 0) != 0) throw invalid();
        JSONObject choice = choices.getJSONObject(0);
        JSONObject result = new JSONObject().put("message", choice.getJSONObject("message"))
                .put("finish_reason", choice.getString("finish_reason"));
        if (!choice.isNull("stop_sequence")) result.put("stop_sequence", choice.getString("stop_sequence"));
        if (response.optJSONObject("usage") == null)
            throw new CompletionError(502, "usage_missing", "Model response omitted usage", null);
        result.put("usage", response.getJSONObject("usage"));
        CompletionResult.validate(result);
        return result;
    }

    private static CompletionError invalid() {
        return new CompletionError(502, "upstream_error", "Invalid OpenAI completion response", null);
    }
}
