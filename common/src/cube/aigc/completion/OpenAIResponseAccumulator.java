/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.completion;

import cube.aigc.usage.UsageTokens;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * OpenAI 流式响应累积器。
 *
 * <p>把逐条到达的 SSE 分片（{@code chat.completion.chunk}）拼装成一条完整响应，并识别结束标记。
 * 累积过程中同时维护两类信息：</p>
 *
 * <ul>
 *     <li>会话级字段：{@code message}（正文、拒答、工具调用）、{@code finish_reason}、
 *     {@code stop_sequence} 与 {@code usage}；</li>
 *     <li>保序的内容块 {@code content_blocks}：文本块与工具调用块按到达顺序排列，
 *     供需要保序的协议转换（例如 Claude 消息格式）使用。</li>
 * </ul>
 *
 * <p>本类只做累积与结构校验，不做重试，也不吞异常。分片结构不合法、累积量超限或缺少用量时，
 * 一律抛出 {@link CompletionError}：残缺的应答不能证明远端模型已经停止，只能整体判定失败。</p>
 *
 * <p>非线程安全，一个实例只对应一条响应流。</p>
 *
 * @see CompletionResult#validate(JSONObject)
 */
public final class OpenAIResponseAccumulator {

    /**
     * 允许的最大工具调用序号上界，超出即视为上游数据异常。
     */
    private static final int MAX_TOOL_CALLS = 16;

    /**
     * 单条响应累积的字符上限，超出即视为上游失控。
     *
     * <p>统计口径为正文、拒答与工具调用的 JSON 序列化长度之和。</p>
     */
    private static final int MAX_PAYLOAD_CHARS = 65536;

    /**
     * 正文增量累积器，对应分片中的 {@code delta.content} 。
     */
    private final StringBuilder text = new StringBuilder();

    /**
     * 拒答增量累积器，对应分片中的 {@code delta.refusal} 。
     */
    private final StringBuilder refusal = new StringBuilder();

    /**
     * 工具调用集合，键为分片里的 {@code index} ，按序号升序排列。
     */
    private final Map<Integer, JSONObject> calls = new TreeMap<>();

    /**
     * 内容块序列：{@link StringBuilder} 表示文本块，{@link Integer} 表示指向 {@link #calls} 的工具调用块。
     */
    private final List<Object> blocks = new ArrayList<>();

    /**
     * 当前仍可追加的文本块；工具调用到达后置空，使后续文本另起一块。
     */
    private StringBuilder textBlock;

    /**
     * 结束原因，取值为 {@code stop} 、{@code length} 、{@code tool_calls} 或 {@code content_filter} 。
     */
    private String finish;

    /**
     * 用量快照，尚未收到时为 {@code null} 。
     */
    private JSONObject usage;

    /**
     * 停止序列，模型未返回时为 {@code null} 。
     */
    private String stopSequence;

    /**
     * 创建一个空累积器，用于消费一条新的响应流。
     *
     * <p>实例保存整条流的状态，因此不能跨请求复用。</p>
     */
    public OpenAIResponseAccumulator() {
    }

    /**
     * 判断是否已收到终态结束原因。
     *
     * <p>只有四类终态原因算作已结束；{@code null} 表示流尚未给出结论，调用方不得据此假定成功。</p>
     *
     * @return 已结束返回 {@code true} ，否则返回 {@code false} 。
     */
    public boolean terminal() {
        return Arrays.asList("stop", "length", "tool_calls", "content_filter").contains(finish);
    }

    /**
     * 取已观测到的用量快照。
     *
     * <p>返回的是最近一次出现、且能被 {@link UsageTokens} 解析的用量对象副本，因此断流前收到的用量证据
     * 仍可用于计费与容量核算。返回副本，避免调用方篡改累积器内部状态。</p>
     *
     * @return 用量副本；从未收到合法用量时返回 {@code null} 。
     */
    public JSONObject usageEvidence() {
        if (usage == null) {
            return null;
        }
        if (UsageTokens.read(usage) == null) {
            return null;
        }
        return new JSONObject(usage.toString());
    }

    /**
     * 追加一个分片。
     *
     * <p>分片必须满足 OpenAI 流式协议：只允许一个 {@code choices} 元素、序号为 0、且终态原因只出现一次。
     * 正文与工具调用的增量在此处拼接，内容块顺序也在此时确定。</p>
     *
     * @param chunk 分片数据，即一个 {@code data:} 事件解析出的 JSON 对象。
     * @return 可立即下发的增量；本分片没有增量时返回空对象。
     * @throws CompletionError 分片结构不合法、用量非法或累积量超过 {@link #MAX_PAYLOAD_CHARS} 。
     */
    public JSONObject add(JSONObject chunk) {
        if (chunk.has("error")) {
            throw invalid();
        }
        if (chunk.optJSONObject("usage") != null) {
            if (UsageTokens.read(chunk.getJSONObject("usage")) == null) {
                throw invalid();
            }
            usage = chunk.getJSONObject("usage");
        }
        JSONArray choices = chunk.getJSONArray("choices");
        if (choices.length() > 1) {
            throw invalid();
        }
        if (choices.length() == 0) {
            return new JSONObject();
        }
        JSONObject choice = choices.getJSONObject(0);
        if (choice.optInt("index", 0) != 0 || finish != null) {
            throw invalid();
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) {
            delta = new JSONObject();
        }
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
        if (!delta.isNull("refusal")) {
            refusal.append(delta.getString("refusal"));
        }
        JSONArray parts = delta.optJSONArray("tool_calls");
        if (parts != null) {
            for (int n = 0; n < parts.length(); ++n) {
                JSONObject part = parts.getJSONObject(n);
                int index = part.getInt("index");
                if (index < 0 || index >= MAX_TOOL_CALLS
                        || !"function".equals(part.optString("type", "function"))) {
                    throw invalid();
                }
                JSONObject call = calls.get(index);
                if (call == null) {
                    call = new JSONObject()
                            .put("id", "")
                            .put("type", "function")
                            .put("function", new JSONObject().put("name", "").put("arguments", ""));
                    calls.put(index, call);
                    blocks.add(index);
                }
                // 工具调用开启新块，后续到达的正文必须另起一个文本块。
                textBlock = null;
                if (!part.isNull("id")) {
                    call.put("id", call.getString("id") + part.getString("id"));
                }
                JSONObject function = part.optJSONObject("function");
                if (function != null) {
                    // 名称与参数都是跨分片拼接的，缺一个分片就是残缺调用。
                    for (String key : new String[]{"name", "arguments"}) {
                        if (!function.isNull(key)) {
                            call.getJSONObject("function").put(key,
                                    call.getJSONObject("function").getString(key) + function.getString(key));
                        }
                    }
                }
            }
        }
        if (!choice.isNull("finish_reason")) {
            finish = choice.getString("finish_reason");
        }
        if (!choice.isNull("stop_sequence")) {
            stopSequence = choice.getString("stop_sequence");
        }
        if (text.length() + refusal.length() + calls.toString().length() > MAX_PAYLOAD_CHARS) {
            throw invalid();
        }
        return delta;
    }

    /**
     * 组装最终响应。
     *
     * <p>要求已收到终态原因与合法用量，随后按 {@code blocks} 记录的顺序输出 {@code content_blocks}，
     * 最后交 {@link CompletionResult#validate(JSONObject)} 做整体校验。因此返回的对象可以直接下发给调用方。</p>
     *
     * @return 完整响应体，含 {@code message} 、{@code finish_reason} 、{@code usage} 与 {@code content_blocks} 。
     * @throws CompletionError 流尚未结束、用量缺失或组装结果未通过校验。
     */
    public JSONObject result() {
        if (finish == null) {
            throw invalid();
        }
        JSONArray tools = new JSONArray();
        for (JSONObject call : calls.values()) {
            tools.put(call);
        }
        JSONObject message = new JSONObject()
                .put("role", "assistant")
                .put("content", text.toString());
        if (tools.length() > 0) {
            message.put("tool_calls", tools);
        }
        if (refusal.length() > 0) {
            message.put("refusal", refusal.toString());
        }
        JSONObject result = new JSONObject()
                .put("message", message)
                .put("finish_reason", finish);
        if (usage == null) {
            throw new CompletionError(502, "usage_missing", "Model response omitted usage", null);
        }
        result.put("usage", usage);
        if (stopSequence != null) {
            result.put("stop_sequence", stopSequence);
        }
        JSONArray ordered = new JSONArray();
        for (Object block : blocks) {
            if (block instanceof StringBuilder) {
                ordered.put(new JSONObject().put("type", "text").put("text", block.toString()));
            } else {
                JSONObject call = calls.get((Integer) block);
                JSONObject function = call.getJSONObject("function");
                ordered.put(new JSONObject()
                        .put("type", "tool_use")
                        .put("id", call.getString("id"))
                        .put("name", function.getString("name"))
                        .put("input", new JSONObject(function.getString("arguments"))));
            }
        }
        result.put("content_blocks", ordered);
        CompletionResult.validate(result);
        return result;
    }

    /**
     * 把非流式响应归一成与 {@link #result()} 相同的结构。
     *
     * <p>非流式应答本身已是完整响应，但字段布局与流式累积结果不同：这里只保留 {@code message} 、
     * {@code finish_reason} 、{@code stop_sequence} 与 {@code usage} ，不生成 {@code content_blocks} 。</p>
     *
     * @param response 上游返回的完整响应对象。
     * @return 归一后的响应体。
     * @throws CompletionError 响应携带错误、结构不合法或缺少用量。
     */
    public static JSONObject json(JSONObject response) {
        if (response.has("error")) {
            throw invalid();
        }
        JSONArray choices = response.getJSONArray("choices");
        if (choices.length() != 1 || choices.getJSONObject(0).optInt("index", 0) != 0) {
            throw invalid();
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject result = new JSONObject()
                .put("message", choice.getJSONObject("message"))
                .put("finish_reason", choice.getString("finish_reason"));
        if (!choice.isNull("stop_sequence")) {
            result.put("stop_sequence", choice.getString("stop_sequence"));
        }
        if (response.optJSONObject("usage") == null) {
            throw new CompletionError(502, "usage_missing", "Model response omitted usage", null);
        }
        result.put("usage", response.getJSONObject("usage"));
        CompletionResult.validate(result);
        return result;
    }

    /**
     * 构造统一的上游响应错误。
     *
     * @return 状态码 502、错误码 {@code upstream_error} 的异常实例，由调用方直接抛出。
     */
    private static CompletionError invalid() {
        return new CompletionError(502, "upstream_error", "Invalid OpenAI completion response", null);
    }
}
