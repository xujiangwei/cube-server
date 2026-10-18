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
 * Strict single-choice OpenAI JSON/SSE decoder shared by Java inference backends.
 */
public final class OpenAIResponseAccumulator {
    private final StringBuilder text = new StringBuilder(), refusal = new StringBuilder();
    private final Map<Integer, JSONObject> calls = new TreeMap<>();
    private String finish;
    private JSONObject usage;

    public JSONObject add(JSONObject chunk) {
        if (chunk.has("error")) throw invalid();
        if (chunk.optJSONObject("usage") != null) {
            if (usage != null) throw invalid();
            usage = chunk.getJSONObject("usage");
        }
        JSONArray choices = chunk.getJSONArray("choices");
        if (choices.length() > 1) throw invalid();
        if (choices.length() == 0) return new JSONObject();
        JSONObject choice = choices.getJSONObject(0);
        if (choice.optInt("index", 0) != 0 || finish != null) throw invalid();
        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) delta = new JSONObject();
        if (!delta.isNull("content")) text.append(delta.getString("content"));
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
            }
            if (!part.isNull("id")) call.put("id", call.getString("id") + part.getString("id"));
            JSONObject function = part.optJSONObject("function");
            if (function != null) for (String key : new String[]{"name", "arguments"})
                if (!function.isNull(key)) call.getJSONObject("function").put(key,
                        call.getJSONObject("function").getString(key) + function.getString(key));
        }
        if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason");
        if (text.length() + refusal.length() + calls.toString().length() > 65536) throw invalid();
        return delta;
    }

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
        CompletionResult.validate(result);
        return result;
    }

    public static JSONObject json(JSONObject response) {
        if (response.has("error")) throw invalid();
        JSONArray choices = response.getJSONArray("choices");
        if (choices.length() != 1 || choices.getJSONObject(0).optInt("index", 0) != 0) throw invalid();
        JSONObject choice = choices.getJSONObject(0);
        JSONObject result = new JSONObject().put("message", choice.getJSONObject("message"))
                .put("finish_reason", choice.getString("finish_reason"));
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
