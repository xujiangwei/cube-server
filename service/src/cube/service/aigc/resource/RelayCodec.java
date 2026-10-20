/*
 * This source file is part of Cube.
 * Copyright (c) 2023-2026 Ambrose Xu.
 */
package cube.service.aigc.resource;

import cube.aigc.completion.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/** Cube's two public HTTP protocols at the Unit transport boundary. */
public final class RelayCodec {
    private RelayCodec() {}

    public static JSONObject request(JSONObject canonical, boolean claude, int maxTokens) {
        JSONObject options = new JSONObject(canonical.getJSONObject("options").toString());
        JSONObject out = new JSONObject().put("model", canonical.getString("model"))
                .put("stream", canonical.getBoolean("stream"));
        JSONArray messages = canonical.getJSONArray("messages");
        if (!claude) {
            JSONArray wire = new JSONArray();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = new JSONObject(messages.getJSONObject(i).toString());
                JSONArray order = message.optJSONArray("content_order");
                message.remove("content_order");
                if (order == null) wire.put(message);
                else {
                    JSONArray content = new JSONArray(), calls = new JSONArray();
                    for (int j = 0; j < order.length(); j++) {
                        JSONObject part = order.getJSONObject(j);
                        if (part.getString("type").equals("content")) {
                            if (calls.length() > 0) throw CompletionError.invalid("OpenAI cannot preserve text following tool_use in one message", "messages");
                            content.put(message.getJSONArray("content").get(part.getInt("index")));
                        } else calls.put(message.getJSONArray("tool_calls").get(part.getInt("index")));
                    }
                    message.put("content", content).put("tool_calls", calls);
                    wire.put(message);
                }
            }
            for (String key : options.keySet()) out.put(key, options.get(key));
            out.put("messages", wire);
            if (canonical.has("tools")) out.put("tools", canonical.getJSONArray("tools"));
            if (out.getBoolean("stream")) out.put("stream_options", new JSONObject().put("include_usage", true));
            return out;
        }
        JSONArray system = new JSONArray(), wire = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.getString("role");
            if (role.equals("system")) {
                if (wire.length() > 0) throw CompletionError.invalid("Claude requires leading system messages", "messages");
                append(system, blocks(m.get("content")));
                continue;
            }
            if (role.equals("developer") || m.has("name"))
                throw CompletionError.invalid("Message metadata cannot be represented by Claude", "messages");
            JSONArray content;
            if (role.equals("tool")) {
                role = "user";
                content = new JSONArray().put(new JSONObject().put("type", "tool_result")
                        .put("tool_use_id", m.getString("tool_call_id")).put("content", blocks(m.get("content"))));
            } else {
                content = new JSONArray();
                JSONArray text = blocks(m.opt("content")), calls = m.optJSONArray("tool_calls"), order = m.optJSONArray("content_order");
                if (order != null) for (int j = 0; j < order.length(); j++) {
                    JSONObject part = order.getJSONObject(j);
                    content.put(part.getString("type").equals("content") ? text.get(part.getInt("index"))
                            : tool(calls.getJSONObject(part.getInt("index"))));
                } else {
                    append(content, text);
                    if (calls != null) for (int j = 0; j < calls.length(); j++) content.put(tool(calls.getJSONObject(j)));
                }
            }
            // Consecutive tool results belong to the same Claude user message.
            if (m.getString("role").equals("tool") && wire.length() > 0
                    && wire.getJSONObject(wire.length() - 1).getString("role").equals("user"))
                append(wire.getJSONObject(wire.length() - 1).getJSONArray("content"), content);
            else wire.put(new JSONObject().put("role", role).put("content", content));
        }
        if (system.length() > 0) out.put("system", system);
        out.put("messages", wire).put("max_tokens", options.opt("max_tokens") != null ? options.get("max_tokens")
                : options.opt("max_completion_tokens") != null ? options.get("max_completion_tokens") : maxTokens);
        for (String k : Arrays.asList("temperature", "top_p", "top_k")) if (options.has(k)) out.put(k, options.get(k));
        if (out.optDouble("temperature", 0) > 1) throw CompletionError.invalid("Claude temperature must be at most 1", "temperature");
        if (options.has("stop")) out.put("stop_sequences", options.get("stop") instanceof String
                ? new JSONArray().put(options.getString("stop")) : options.getJSONArray("stop"));
        JSONArray tools = canonical.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            JSONArray converted = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject f = tools.getJSONObject(i).getJSONObject("function");
                JSONObject t = new JSONObject().put("name", f.getString("name"))
                        .put("input_schema", f.optJSONObject("parameters") == null ? new JSONObject().put("type", "object") : f.getJSONObject("parameters"));
                if (f.has("description")) t.put("description", f.getString("description"));
                converted.put(t);
            }
            out.put("tools", converted);
            Object choice = options.opt("tool_choice");
            JSONObject c = choice instanceof JSONObject ? new JSONObject().put("type", "tool")
                    .put("name", ((JSONObject) choice).getJSONObject("function").getString("name"))
                    : new JSONObject().put("type", "required".equals(choice) ? "any" : choice == null ? "auto" : choice);
            if (options.has("parallel_tool_calls")) c.put("disable_parallel_tool_use", !options.getBoolean("parallel_tool_calls"));
            out.put("tool_choice", c);
        }
        for (String k : Arrays.asList("seed", "presence_penalty", "frequency_penalty"))
            if (options.has(k) && !options.isNull(k)) throw CompletionError.invalid("Claude does not support " + k, k);
        return out;
    }

    private static void append(JSONArray into, JSONArray from) { for (int i = 0; i < from.length(); i++) into.put(from.get(i)); }

    private static JSONObject tool(JSONObject call) {
        JSONObject f = call.getJSONObject("function");
        return new JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                .put("name", f.getString("name")).put("input", new JSONObject(f.getString("arguments")));
    }

    private static JSONArray blocks(Object content) {
        JSONArray out = new JSONArray();
        if (content == null || content == JSONObject.NULL) return out;
        if (content instanceof String) return out.put(new JSONObject().put("type", "text").put("text", content));
        JSONArray array = (JSONArray) content;
        for (int i = 0; i < array.length(); i++) {
            JSONObject b = array.getJSONObject(i);
            if (b.getString("type").equals("text")) out.put(new JSONObject(b.toString()));
            else {
                JSONObject image = b.getJSONObject("image_url");
                if (!image.optString("detail", "auto").equals("auto"))
                    throw CompletionError.invalid("Claude cannot preserve image detail", "messages");
                String url = image.getString("url");
                JSONObject source = new JSONObject();
                if (url.startsWith("data:")) {
                    int separator = url.indexOf(";base64,");
                    if (separator < 0) throw CompletionError.invalid("Invalid image data URI", "messages");
                    source.put("type", "base64").put("media_type", url.substring(5, separator)).put("data", url.substring(separator + 8));
                } else source.put("type", "url").put("url", url);
                out.put(new JSONObject().put("type", "image").put("source", source));
            }
        }
        return out;
    }

    public static JSONObject result(JSONObject response, boolean claude) {
        if (!claude) return OpenAIResponseAccumulator.json(response);
        if (!"message".equals(response.optString("type")) || !"assistant".equals(response.optString("role"))) throw invalid();
        JSONArray content = response.getJSONArray("content"), calls = new JSONArray();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) text.append(block.getString("text"));
            else if ("tool_use".equals(block.optString("type"))) calls.put(new JSONObject().put("id", block.getString("id"))
                    .put("type", "function").put("function", new JSONObject().put("name", block.getString("name"))
                            .put("arguments", block.getJSONObject("input").toString())));
            else throw invalid();
        }
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text.toString());
        if (calls.length() > 0) message.put("tool_calls", calls);
        String reason = response.getString("stop_reason");
        String finish;
        if (reason.equals("end_turn") || reason.equals("stop_sequence")) finish = "stop";
        else if (reason.equals("max_tokens")) finish = "length";
        else if (reason.equals("tool_use")) finish = "tool_calls";
        else throw invalid();
        JSONObject usage = response.getJSONObject("usage");
        long input = CompletionResult.tokens(usage, "input_tokens"), output = CompletionResult.tokens(usage, "output_tokens");
        JSONObject result = new JSONObject().put("message", message).put("content_blocks", content).put("finish_reason", finish)
                .put("usage", new JSONObject().put("prompt_tokens", input).put("completion_tokens", output).put("total_tokens", Math.addExact(input, output)));
        if (!response.isNull("stop_sequence")) result.put("stop_sequence", response.getString("stop_sequence"));
        CompletionResult.validate(result);
        return result;
    }

    public static CompletionError invalid() { return new CompletionError(502, "upstream_error", "Invalid Relay completion response", null); }

    /** Strict Claude SSE assembly, preserving block order and genuine token counts. */
    public static final class ClaudeStream {
        private JSONObject message;
        private final JSONArray blocks = new JSONArray();
        private final Map<Integer, Integer> toolIndices = new HashMap<>();
        private int open = -1, toolCount;
        private boolean delta, stopped;
        private StringBuilder arguments;

        public JSONObject add(String eventName, JSONObject e) {
            String type = e.getString("type");
            if (!eventName.isEmpty() && !eventName.equals(type)) throw invalid();
            if (stopped) throw invalid();
            JSONObject out = new JSONObject();
            if (type.equals("ping")) return out;
            if (type.equals("message_start")) {
                if (message != null) throw invalid();
                message = new JSONObject(e.getJSONObject("message").toString());
                if (!"assistant".equals(message.optString("role")) || message.getJSONArray("content").length() != 0) throw invalid();
            } else {
                if (message == null) throw invalid();
                if (type.equals("content_block_start")) {
                    int index = e.getInt("index");
                    if (open != -1 || index != blocks.length() || delta) throw invalid();
                    open = index;
                    JSONObject block = new JSONObject(e.getJSONObject("content_block").toString());
                    blocks.put(block);
                    if (block.getString("type").equals("text")) out.put("content", block.getString("text"));
                    else if (block.getString("type").equals("tool_use")) {
                        int toolIndex = toolCount++;
                        toolIndices.put(index, toolIndex);
                        arguments = new StringBuilder();
                        if (block.getJSONObject("input").length() > 0) arguments.append(block.getJSONObject("input").toString());
                        out.put("tool_calls", new JSONArray().put(new JSONObject().put("index", toolIndex)
                                .put("id", block.getString("id")).put("type", "function")
                                .put("function", new JSONObject().put("name", block.getString("name")).put("arguments", arguments.toString()))));
                    } else throw invalid();
                } else if (type.equals("content_block_delta")) {
                    if (open == -1 || e.getInt("index") != open) throw invalid();
                    JSONObject d = e.getJSONObject("delta"), block = blocks.getJSONObject(open);
                    if (d.getString("type").equals("text_delta") && block.getString("type").equals("text")) {
                        String text = d.getString("text");
                        block.put("text", block.getString("text") + text);
                        out.put("content", text);
                    } else if (d.getString("type").equals("input_json_delta") && block.getString("type").equals("tool_use")) {
                        String json = d.getString("partial_json");
                        arguments.append(json);
                        out.put("tool_calls", new JSONArray().put(new JSONObject().put("index", toolIndices.get(open))
                                .put("function", new JSONObject().put("arguments", json))));
                    } else throw invalid();
                } else if (type.equals("content_block_stop")) {
                    if (open == -1 || e.getInt("index") != open) throw invalid();
                    if (toolIndices.containsKey(open)) {
                        if (arguments.length() > 0) blocks.getJSONObject(open).put("input", new JSONObject(arguments.toString()));
                        else out.put("tool_calls", new JSONArray().put(new JSONObject().put("index", toolIndices.get(open))
                                .put("function", new JSONObject().put("arguments", blocks.getJSONObject(open).getJSONObject("input").toString()))));
                    }
                    open = -1;
                } else if (type.equals("message_delta")) {
                    if (open != -1 || delta) throw invalid();
                    delta = true;
                    JSONObject d = e.getJSONObject("delta"), u = e.getJSONObject("usage");
                    CompletionResult.tokens(u, "output_tokens");
                    message.put("stop_reason", d.getString("stop_reason")).put("stop_sequence", d.opt("stop_sequence"));
                    JSONObject usage = message.getJSONObject("usage");
                    for (String key : u.keySet()) usage.put(key, u.get(key));
                } else if (type.equals("message_stop")) {
                    if (!delta || open != -1) throw invalid();
                    stopped = true;
                } else throw invalid();
            }
            return out;
        }

        public boolean stopped() { return stopped; }
        public JSONObject result() {
            if (!stopped) throw invalid();
            return RelayCodec.result(message.put("content", blocks), true);
        }
    }
}
