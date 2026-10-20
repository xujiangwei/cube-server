/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.resource;

import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionResult;
import cube.aigc.completion.OpenAIResponseAccumulator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Cube 对外公开的两种 HTTP 协议在单元传输边界上的编解码器。
 *
 * <p>Cube 在网络上使用 OpenAI 与 Claude 两套协议，而引擎内部只使用一种规范化表示。
 * 本类是二者之间唯一的转换点：</p>
 * <ul>
 *   <li>{@link #request} 把规范请求转换为 OpenAI 或 Claude 的请求体；对于目标协议
 *       无法表达的内容，它明确拒绝而不是静默丢弃。</li>
 *   <li>{@link #result} 把协议响应转换回规范结果。</li>
 *   <li>{@link ClaudeStream} 把 Claude 事件流组装成规范结果。</li>
 * </ul>
 */
public final class RelayCodec {

    private RelayCodec() {
    }

    /**
     * 将规范请求转换为目标协议的请求体。
     *
     * @param canonical 规范请求。
     * @param claude    是否使用 Claude 协议，false 表示 OpenAI。
     * @param maxTokens Claude 请求未指定长度上限时采用的缺省值。
     * @return 协议请求体。
     * @throws CompletionError 请求无法用目标协议表达。
     */
    public static JSONObject request(JSONObject canonical, boolean claude, int maxTokens) {
        JSONObject options = new JSONObject(canonical.getJSONObject("options").toString());
        JSONObject out = new JSONObject().put("model", canonical.getString("model"))
                .put("stream", canonical.getBoolean("stream"));
        JSONArray messages = canonical.getJSONArray("messages");
        if (claude) {
            return claudeRequest(canonical, out, options, messages, maxTokens);
        }
        return openAiRequest(canonical, out, options, messages);
    }

    /**
     * 构建 OpenAI 请求体。
     *
     * <p>OpenAI 把文本与工具调用放在不同字段中，因此一条混合二者的消息，只有在其文本
     * 全部位于工具调用之前时才可能被完整还原。</p>
     *
     * @param canonical 规范请求。
     * @param out       正在构建的请求体。
     * @param options   规范生成参数。
     * @param messages  规范消息列表。
     * @return 构建完成的请求体。
     * @throws CompletionError 历史消息无法用 OpenAI 表达。
     */
    private static JSONObject openAiRequest(JSONObject canonical, JSONObject out, JSONObject options, JSONArray messages) {
        JSONArray wire = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = new JSONObject(messages.getJSONObject(i).toString());
            JSONArray order = message.optJSONArray("content_order");
            message.remove("content_order");
            if (order == null) {
                wire.put(message);
            } else {
                JSONArray content = new JSONArray(), calls = new JSONArray();
                for (int j = 0; j < order.length(); j++) {
                    JSONObject part = order.getJSONObject(j);
                    if (part.getString("type").equals("content")) {
                        // 工具调用之后的文本无法再放回同一条消息中。
                        if (calls.length() > 0) {
                            throw CompletionError.invalid("OpenAI cannot preserve text following tool_use in one message", "messages");
                        }
                        content.put(message.getJSONArray("content").get(part.getInt("index")));
                    } else {
                        calls.put(message.getJSONArray("tool_calls").get(part.getInt("index")));
                    }
                }
                message.put("content", content).put("tool_calls", calls);
                wire.put(message);
            }
        }
        for (String key : options.keySet()) {
            out.put(key, options.get(key));
        }
        out.put("messages", wire);
        if (canonical.has("tools")) {
            out.put("tools", canonical.getJSONArray("tools"));
        }
        if (out.getBoolean("stream")) {
            out.put("stream_options", new JSONObject().put("include_usage", true));
        }
        return out;
    }

    /**
     * 构建 Claude 请求体。
     *
     * <p>Claude 的系统提示只允许出现在最开头，既没有 developer 角色，也没有具名参与者，
     * 并且把工具结果表达为一个 user 轮次。</p>
     *
     * @param canonical 规范请求。
     * @param out       正在构建的请求体。
     * @param options   规范生成参数。
     * @param messages  规范消息列表。
     * @param maxTokens 请求未指定长度上限时采用的缺省值。
     * @return 构建完成的请求体。
     * @throws CompletionError 历史消息无法用 Claude 表达。
     */
    private static JSONObject claudeRequest(JSONObject canonical, JSONObject out, JSONObject options,
                                            JSONArray messages, int maxTokens) {
        ClaudeMessages conversation = claudeMessages(messages);
        if (conversation.system.length() > 0) {
            out.put("system", conversation.system);
        }
        out.put("messages", conversation.wire).put("max_tokens", options.opt("max_tokens") != null ? options.get("max_tokens")
                : options.opt("max_completion_tokens") != null ? options.get("max_completion_tokens") : maxTokens);
        for (String k : Arrays.asList("temperature", "top_p", "top_k")) {
            if (options.has(k)) {
                out.put(k, options.get(k));
            }
        }
        if (out.optDouble("temperature", 0) > 1) {
            throw CompletionError.invalid("Claude temperature must be at most 1", "temperature");
        }
        if (options.has("stop")) {
            out.put("stop_sequences", options.get("stop") instanceof String
                    ? new JSONArray().put(options.getString("stop")) : options.getJSONArray("stop"));
        }
        JSONArray tools = canonical.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            out.put("tools", claudeTools(tools));
            out.put("tool_choice", claudeToolChoice(options));
        }
        for (String k : Arrays.asList("seed", "presence_penalty", "frequency_penalty")) {
            if (options.has(k) && !options.isNull(k)) {
                throw CompletionError.invalid("Claude does not support " + k, k);
            }
        }
        return out;
    }

    /**
     * 将规范消息转换为 Claude 的系统提示与对话。
     *
     * @param messages 规范消息列表。
     * @return 系统提示与对话。
     * @throws CompletionError 某条消息无法用 Claude 表达。
     */
    private static ClaudeMessages claudeMessages(JSONArray messages) {
        ClaudeMessages conversation = new ClaudeMessages();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.getString("role");
            if (role.equals("system")) {
                // 只要已发出其它轮次，系统提示就不再合法。
                if (conversation.wire.length() > 0) {
                    throw CompletionError.invalid("Claude requires leading system messages", "messages");
                }
                append(conversation.system, contentBlocks(m.get("content")));
                continue;
            }
            if (role.equals("developer") || m.has("name")) {
                throw CompletionError.invalid("Message metadata cannot be represented by Claude", "messages");
            }
            JSONArray content;
            if (role.equals("tool")) {
                // Claude 把工具结果表达为携带 tool_result 块的 user 轮次。
                role = "user";
                content = new JSONArray().put(new JSONObject().put("type", "tool_result")
                        .put("tool_use_id", m.getString("tool_call_id")).put("content", contentBlocks(m.get("content"))));
            } else {
                content = new JSONArray();
                JSONArray text = contentBlocks(m.opt("content")), calls = m.optJSONArray("tool_calls"),
                        order = m.optJSONArray("content_order");
                if (order != null) {
                    for (int j = 0; j < order.length(); j++) {
                        JSONObject part = order.getJSONObject(j);
                        content.put(part.getString("type").equals("content") ? text.get(part.getInt("index"))
                                : toolUse(calls.getJSONObject(part.getInt("index"))));
                    }
                } else {
                    append(content, text);
                    if (calls != null) {
                        for (int j = 0; j < calls.length(); j++) {
                            content.put(toolUse(calls.getJSONObject(j)));
                        }
                    }
                }
            }
            // 连续的工具结果归属于同一条 Claude user 消息。
            if (m.getString("role").equals("tool") && conversation.wire.length() > 0
                    && conversation.wire.getJSONObject(conversation.wire.length() - 1).getString("role").equals("user")) {
                append(conversation.wire.getJSONObject(conversation.wire.length() - 1).getJSONArray("content"), content);
            } else {
                conversation.wire.put(new JSONObject().put("role", role).put("content", content));
            }
        }
        return conversation;
    }

    /**
     * 将规范工具转换为 Claude 工具声明。
     *
     * @param tools 规范工具列表。
     * @return Claude 工具声明。
     */
    private static JSONArray claudeTools(JSONArray tools) {
        JSONArray converted = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject f = tools.getJSONObject(i).getJSONObject("function");
            JSONObject t = new JSONObject().put("name", f.getString("name"))
                    .put("input_schema", f.optJSONObject("parameters") == null ? new JSONObject().put("type", "object") : f.getJSONObject("parameters"));
            if (f.has("description")) {
                t.put("description", f.getString("description"));
            }
            converted.put(t);
        }
        return converted;
    }

    /**
     * 将规范工具选择转换为 Claude 工具选择。
     *
     * @param options 规范生成参数。
     * @return Claude 工具选择。
     */
    private static JSONObject claudeToolChoice(JSONObject options) {
        Object choice = options.opt("tool_choice");
        JSONObject c = choice instanceof JSONObject ? new JSONObject().put("type", "tool")
                .put("name", ((JSONObject) choice).getJSONObject("function").getString("name"))
                : new JSONObject().put("type", "required".equals(choice) ? "any" : choice == null ? "auto" : choice);
        if (options.has("parallel_tool_calls")) {
            c.put("disable_parallel_tool_use", !options.getBoolean("parallel_tool_calls"));
        }
        return c;
    }

    /**
     * 将一个数组的全部元素追加到另一个数组。
     *
     * @param into 目标数组。
     * @param from 源数组。
     */
    private static void append(JSONArray into, JSONArray from) {
        for (int i = 0; i < from.length(); i++) {
            into.put(from.get(i));
        }
    }

    /**
     * 将规范工具调用转换为 Claude 工具使用块。
     *
     * @param call 规范工具调用。
     * @return Claude 工具使用块。
     */
    private static JSONObject toolUse(JSONObject call) {
        JSONObject f = call.getJSONObject("function");
        return new JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                .put("name", f.getString("name")).put("input", new JSONObject(f.getString("arguments")));
    }

    /**
     * 将规范消息内容转换为 Claude 内容块。
     *
     * <p>纯文本字符串转换为一个文本块；图片转换为内联 base64 来源或 URL 来源。图片细节
     * 只要不是 auto 就无法被保留，因此会被明确拒绝。</p>
     *
     * @param content 规范消息内容。
     * @return Claude 内容块。
     * @throws CompletionError 内容无法用 Claude 表达。
     */
    private static JSONArray contentBlocks(Object content) {
        JSONArray out = new JSONArray();
        if (content == null || content == JSONObject.NULL) {
            return out;
        }
        if (content instanceof String) {
            return out.put(new JSONObject().put("type", "text").put("text", content));
        }
        JSONArray array = (JSONArray) content;
        for (int i = 0; i < array.length(); i++) {
            JSONObject b = array.getJSONObject(i);
            if (b.getString("type").equals("text")) {
                out.put(new JSONObject(b.toString()));
            } else {
                JSONObject image = b.getJSONObject("image_url");
                if (!image.optString("detail", "auto").equals("auto")) {
                    throw CompletionError.invalid("Claude cannot preserve image detail", "messages");
                }
                String url = image.getString("url");
                JSONObject source = new JSONObject();
                if (url.startsWith("data:")) {
                    int separator = url.indexOf(";base64,");
                    if (separator < 0) {
                        throw CompletionError.invalid("Invalid image data URI", "messages");
                    }
                    source.put("type", "base64").put("media_type", url.substring(5, separator)).put("data", url.substring(separator + 8));
                } else {
                    source.put("type", "url").put("url", url);
                }
                out.put(new JSONObject().put("type", "image").put("source", source));
            }
        }
        return out;
    }

    /**
     * 将协议响应转换为规范结果。
     *
     * @param response 协议响应。
     * @param claude   是否使用 Claude 协议，false 表示 OpenAI。
     * @return 规范结果。
     * @throws CompletionError 响应不是合法的终态结果。
     */
    public static JSONObject result(JSONObject response, boolean claude) {
        if (!claude) {
            return OpenAIResponseAccumulator.json(response);
        }
        return claudeResult(response);
    }

    /**
     * 将 Claude 响应转换为规范结果。
     *
     * @param response Claude 响应。
     * @return 规范结果。
     * @throws CompletionError 响应不是合法的终态结果。
     */
    private static JSONObject claudeResult(JSONObject response) {
        if (!"message".equals(response.optString("type")) || !"assistant".equals(response.optString("role"))) {
            throw invalid();
        }
        JSONArray content = response.getJSONArray("content"), calls = new JSONArray();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) {
                text.append(block.getString("text"));
            } else if ("tool_use".equals(block.optString("type"))) {
                calls.put(new JSONObject().put("id", block.getString("id"))
                        .put("type", "function").put("function", new JSONObject().put("name", block.getString("name"))
                                .put("arguments", block.getJSONObject("input").toString())));
            } else {
                throw invalid();
            }
        }
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text.toString());
        if (calls.length() > 0) {
            message.put("tool_calls", calls);
        }
        String reason = response.getString("stop_reason");
        String finish;
        if (reason.equals("end_turn") || reason.equals("stop_sequence")) {
            finish = "stop";
        } else if (reason.equals("max_tokens")) {
            finish = "length";
        } else if (reason.equals("tool_use")) {
            finish = "tool_calls";
        } else {
            throw invalid();
        }
        JSONObject usage = response.getJSONObject("usage");
        long input = CompletionResult.tokens(usage, "input_tokens"), output = CompletionResult.tokens(usage, "output_tokens");
        JSONObject result = new JSONObject().put("message", message).put("content_blocks", content).put("finish_reason", finish)
                .put("usage", new JSONObject().put("prompt_tokens", input).put("completion_tokens", output).put("total_tokens", Math.addExact(input, output)));
        if (!response.isNull("stop_sequence")) {
            result.put("stop_sequence", response.getString("stop_sequence"));
        }
        CompletionResult.validate(result);
        return result;
    }

    /**
     * 构造用于上游响应畸形或不完整时的错误。
     *
     * @return 上游错误。
     */
    public static CompletionError invalid() {
        return new CompletionError(502, "upstream_error", "Invalid Relay completion response", null);
    }

    /**
     * Claude 请求的系统提示与对话。
     */
    private static final class ClaudeMessages {

        /**
         * 开头的系统提示，请求未携带时为空。
         */
        final JSONArray system = new JSONArray();

        /**
         * 对话内容，每个轮次一个 JSON 对象。
         */
        final JSONArray wire = new JSONArray();
    }

    /**
     * 严格的 Claude SSE 组装器，保持块顺序与真实 token 用量。
     *
     * <p>事件流只接受文档规定的顺序：先是一个消息开始，随后是任意多组严格配对的内容块，
     * 最后是消息增量和消息结束。任何偏离都直接报错，而不做修复性拼接。</p>
     */
    public static final class ClaudeStream {

        /**
         * 事件流声明的消息信封，消息开始之前为 null。
         */
        private JSONObject message;

        /**
         * 按开启顺序排列的内容块。
         */
        private final JSONArray blocks = new JSONArray();

        /**
         * 规范工具调用列表中的工具下标，以块下标为键。
         */
        private final Map<Integer, Integer> toolIndices = new HashMap<>();

        /**
         * 当前开启的块下标，没有开启的块时为 -1。
         */
        private int open = -1;

        /**
         * 迄今为止出现的工具块数量。
         */
        private int toolCount;

        /**
         * 是否已收到消息增量。
         */
        private boolean delta;

        /**
         * 是否已收到消息结束。
         */
        private boolean stopped;

        /**
         * 当前开启的工具块已收集到的参数。
         */
        private StringBuilder arguments;

        /**
         * 接收事件流中的一个事件。
         *
         * @param eventName 事件流声明的事件名，未声明时为空字符串。
         * @param e         事件内容。
         * @return 该事件产生的增量，未产生时为空。
         * @throws CompletionError 事件违反 Claude 事件流约定。
         */
        public JSONObject add(String eventName, JSONObject e) {
            String type = e.getString("type");
            if (!eventName.isEmpty() && !eventName.equals(type)) {
                throw invalid();
            }
            if (stopped) {
                throw invalid();
            }
            if (type.equals("ping")) {
                return new JSONObject();
            }
            if (type.equals("message_start")) {
                return startMessage(e);
            }
            // 其余事件都依附于一条必须已存在的消息。
            if (message == null) {
                throw invalid();
            }
            if (type.equals("content_block_start")) {
                return startBlock(e);
            }
            if (type.equals("content_block_delta")) {
                return deltaBlock(e);
            }
            if (type.equals("content_block_stop")) {
                return stopBlock(e);
            }
            if (type.equals("message_delta")) {
                return endMessage(e);
            }
            if (type.equals("message_stop")) {
                return endStream(e);
            }
            throw invalid();
        }

        /**
         * 处理消息开始事件。
         *
         * @param e 事件内容。
         * @return 空增量。
         * @throws CompletionError 该消息不是空的 assistant 消息。
         */
        private JSONObject startMessage(JSONObject e) {
            if (message != null) {
                throw invalid();
            }
            message = new JSONObject(e.getJSONObject("message").toString());
            if (!"assistant".equals(message.optString("role")) || message.getJSONArray("content").length() != 0) {
                throw invalid();
            }
            return new JSONObject();
        }

        /**
         * 处理内容块的开始事件。
         *
         * @param e 事件内容。
         * @return 声明该块的增量。
         * @throws CompletionError 该块不符合事件流约定。
         */
        private JSONObject startBlock(JSONObject e) {
            JSONObject out = new JSONObject();
            int index = e.getInt("index");
            if (open != -1 || index != blocks.length() || delta) {
                throw invalid();
            }
            open = index;
            JSONObject block = new JSONObject(e.getJSONObject("content_block").toString());
            blocks.put(block);
            if (block.getString("type").equals("text")) {
                out.put("content", block.getString("text"));
            } else if (block.getString("type").equals("tool_use")) {
                int toolIndex = toolCount++;
                toolIndices.put(index, toolIndex);
                arguments = new StringBuilder();
                if (block.getJSONObject("input").length() > 0) {
                    arguments.append(block.getJSONObject("input").toString());
                }
                out.put("tool_calls", new JSONArray().put(new JSONObject().put("index", toolIndex)
                        .put("id", block.getString("id")).put("type", "function")
                        .put("function", new JSONObject().put("name", block.getString("name")).put("arguments", arguments.toString()))));
            } else {
                throw invalid();
            }
            return out;
        }

        /**
         * 处理已开启内容块的一次增量。
         *
         * @param e 事件内容。
         * @return 该增量产生的增量内容。
         * @throws CompletionError 该增量与已开启的块不匹配。
         */
        private JSONObject deltaBlock(JSONObject e) {
            JSONObject out = new JSONObject();
            if (open == -1 || e.getInt("index") != open) {
                throw invalid();
            }
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
            } else {
                throw invalid();
            }
            return out;
        }

        /**
         * 处理内容块的结束事件。
         *
         * @param e 事件内容。
         * @return 补齐工具参数产生的增量，无需补齐时为空。
         * @throws CompletionError 该块与已开启的块不匹配。
         */
        private JSONObject stopBlock(JSONObject e) {
            JSONObject out = new JSONObject();
            if (open == -1 || e.getInt("index") != open) {
                throw invalid();
            }
            if (toolIndices.containsKey(open)) {
                if (arguments.length() > 0) {
                    blocks.getJSONObject(open).put("input", new JSONObject(arguments.toString()));
                } else {
                    out.put("tool_calls", new JSONArray().put(new JSONObject().put("index", toolIndices.get(open))
                            .put("function", new JSONObject().put("arguments", blocks.getJSONObject(open).getJSONObject("input").toString()))));
                }
            }
            open = -1;
            return out;
        }

        /**
         * 处理消息增量事件，停止原因与用量都由此事件携带。
         *
         * @param e 事件内容。
         * @return 空增量。
         * @throws CompletionError 该事件不符合事件流约定。
         */
        private JSONObject endMessage(JSONObject e) {
            if (open != -1 || delta) {
                throw invalid();
            }
            delta = true;
            JSONObject d = e.getJSONObject("delta"), u = e.getJSONObject("usage");
            CompletionResult.tokens(u, "output_tokens");
            message.put("stop_reason", d.getString("stop_reason")).put("stop_sequence", d.opt("stop_sequence"));
            JSONObject usage = message.getJSONObject("usage");
            for (String key : u.keySet()) {
                usage.put(key, u.get(key));
            }
            return new JSONObject();
        }

        /**
         * 处理消息结束事件。
         *
         * @param e 事件内容。
         * @return 空增量。
         * @throws CompletionError 该事件不符合事件流约定。
         */
        private JSONObject endStream(JSONObject e) {
            if (!delta || open != -1) {
                throw invalid();
            }
            stopped = true;
            return new JSONObject();
        }

        /**
         * 判断事件流是否已到达终态事件。
         *
         * @return 已收到消息结束时为 true。
         */
        public boolean stopped() {
            return stopped;
        }

        /**
         * 由已组装的事件流构建规范结果。
         *
         * @return 规范结果。
         * @throws CompletionError 事件流未到达终态事件。
         */
        public JSONObject result() {
            if (!stopped) {
                throw invalid();
            }
            return RelayCodec.result(message.put("content", blocks), true);
        }
    }
}