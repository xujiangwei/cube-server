/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionProvider;
import cube.aigc.completion.OpenAIResponseAccumulator;
import cube.service.harness.protocol.StrictJson;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * 显式配置的 Java 外部 OpenAI 兼容推理后端。
 *
 * <p>关键设计点：
 * <ul>
 *   <li>仅接受 http/https 基地址，归一化后固定指向 {@code /chat/completions}；
 *       地址中的用户信息、查询串与片段一律拒绝，避免凭据经 URL 外泄；</li>
 *   <li>取消只做观察：截止到达或连接异常时断开连接，但断开从不作为模型物理结束的证据，
 *       无法确认时以 unknown 类错误码回报，绝不自动补发新推理；</li>
 *   <li>容量由固定线程数与有界队列封顶，队列满时以 {@code MODEL_BACKEND_BUSY} 拒绝；</li>
 *   <li>流式响应按 SSE 逐行严格解析，校验 UTF-8、事件大小、结束标记与真实 usage，
 *       缺失 usage 不估算 token（{@code MODEL_USAGE_MISSING}）。</li>
 * </ul>
 */
public final class OpenAIHttpProvider implements CompletionProvider, AutoCloseable {
    /**
     * 推理端点 URL，构造时由 baseUrl 归一化并追加 /chat/completions 固化。
     */
    private final URL endpoint;

    /**
     * 模型名；端点在生命周期内只服务该模型。
     */
    private final String model;

    /**
     * 访问令牌，以 Bearer 方式放入 Authorization 头；为空时省略该头。
     */
    private final String token;

    /**
     * 固定大小推理线程池：capacity 个线程 + 32 容量的有界队列，满则 AbortPolicy 拒绝。
     */
    private final ThreadPoolExecutor executor;

    /**
     * 单线程守护调度器，按超时 deadlines 断开对应 HTTP 连接。
     */
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harness-openai-deadline");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造外部 OpenAI 兼容推理后端。
     *
     * @param baseUrl  外部服务基地址，仅接受 http/https 且不带用户信息、查询串与片段
     * @param model    模型名，非空
     * @param token    访问令牌，非 null 且不得包含 CR/LF（防请求头注入）
     * @param capacity 固定推理线程数，同时决定并发推理上限
     * @throws Exception                地址解析失败时向上传播
     * @throws IllegalArgumentException 地址 scheme/host 非法、带用户信息、查询串或片段，
     *                                  model 为空，或 token 含 CR/LF 时抛出，
     *                                  错误码为 INVALID_EXTERNAL_BACKEND
     */
    public OpenAIHttpProvider(String baseUrl, String model, String token, int capacity) throws Exception {
        URI uri = new URI(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || model == null || model.isEmpty() || token == null || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException("INVALID_EXTERNAL_BACKEND");
        }
        endpoint = new URL(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.model = model;
        this.token = token;
        executor = new ThreadPoolExecutor(capacity, capacity, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "harness-openai");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 返回本后端登记的模型清单。
     *
     * @param ignored 接口占位参数，未使用
     * @return 仅含当前模型 id 的单元素数组
     */
    @Override
    public JSONArray completionModels(String ignored) {
        return new JSONArray().put(new JSONObject().put("id", model));
    }

    /**
     * 报告后端容量：登记上限与当前可用推理槽。
     *
     * @param ignored        接口占位参数，未使用
     * @param requestedModel 请求的模型名，未使用（端点只服务固定模型）
     * @param nativeTools    是否查询原生工具能力，未使用
     * @return registered 为固定线程数；available 在线程池关闭时为 0，
     * 否则为未占用的剩余槽数（不小于 0）
     */
    @Override
    public JSONObject completionCapacity(String ignored, String requestedModel, boolean nativeTools) {
        return new JSONObject().put("registered", executor.getMaximumPoolSize())
                .put("available", executor.isShutdown() ? 0 : Math.max(0, executor.getMaximumPoolSize() - executor.getActiveCount()));
    }

    /**
     * 提交一次推理：按规范化请求组装 OpenAI 线报并投递到推理线程池。
     *
     * <p>线报由 canonical 的 options 叠加 model/messages/stream 得到；携带 tools 时原样转发；
     * 流式请求自动附带 stream_options.include_usage 以取得真实用量。
     * 线程池拒绝（队列已满）时以 {@code MODEL_BACKEND_BUSY} 错误事件回报并标记结束。
     *
     * @param ignored   接口占位参数，未使用
     * @param id        请求标识，回显于全部事件
     * @param canonical 规范化请求，必须含 options、messages、stream，可含 tools
     * @param timeout   整体超时（毫秒），同时约束连接与读取
     * @param listener  事件监听器，在推理线程上回调
     */
    @Override
    public void submitCompletion(String ignored, String id, JSONObject canonical, long timeout, Listener listener) {
        JSONObject wire = new JSONObject(canonical.getJSONObject("options").toString())
                .put("model", model).put("messages", canonical.getJSONArray("messages"))
                .put("stream", canonical.getBoolean("stream"));
        if (canonical.has("tools")) {
            wire.put("tools", canonical.getJSONArray("tools"));
        }
        if (wire.getBoolean("stream")) {
            wire.put("stream_options", new JSONObject().put("include_usage", true));
        }
        try {
            executor.execute(() -> execute(id, wire, timeout, listener));
        } catch (RejectedExecutionException e) {
            listener.onEvent(error(id, 1, "MODEL_BACKEND_BUSY", true).put("modelDispatched", false));
        }
    }

    /**
     * 组装标准事件信封。
     *
     * @param id       请求标识
     * @param seq      事件序号，同一请求内从 1 起单调递增
     * @param kind     事件类型，如 delta、completed、error
     * @param payload  事件载荷
     * @param finished 是否为实际结束事件（actualFinished 语义）
     * @return 事件 JSON 对象
     */
    private static JSONObject event(String id, long seq, String kind, JSONObject payload, boolean finished) {
        return new JSONObject().put("requestId", id).put("sequence", seq).put("kind", kind)
                .put("payload", payload).put("actualFinished", finished);
    }

    /**
     * 组装 error 类型事件，载荷形如 {@code {"error":{"code":...}}}。
     *
     * @param id       请求标识
     * @param seq      事件序号
     * @param code     错误码
     * @param finished 是否为实际结束事件
     * @return error 事件 JSON 对象
     */
    private static JSONObject error(String id, long seq, String code, boolean finished) {
        return event(id, seq, "error", new JSONObject().put("error", new JSONObject().put("code", code)), finished);
    }

    /**
     * 在推理线程上执行单次 HTTP 推理并逐事件回报。
     *
     * <p>流程：设置整体截止时间并调度强制断开 → POST 线报 → 非 200 按状态码错误回报 →
     * 非流式限长读取或流式 SSE 解析 → 抛出 completed 事件。
     *
     * <p>并发与边界约定：
     * <ul>
     *   <li>截止时间是整个请求的墙钟上限，在每次读取边界检查，超时抛 SocketTimeoutException；</li>
     *   <li>截止到达经 disconnect() 强制断开以解除阻塞读取；断开只是观察手段，
     *       不宣称模型物理结束；</li>
     *   <li>SSE 总量上限 1 MiB、单事件 64 KiB、单行 64 KiB，超出抛 IOException；</li>
     *   <li>非 200 时仅 400/401/403/404/413/429 视为确定的推理前拒绝，可安全释放容量，
     *       其余状态码无法证明推理未开始；</li>
     *   <li>usage 缺失映射为 MODEL_USAGE_MISSING（不估算 token），其余异常为
     *       MODEL_COMPLETION_UNKNOWN；请求未实际发出时一律按未完成回报。</li>
     * </ul>
     *
     * @param id       请求标识
     * @param wire     已组装的上游线报，含 model、messages、stream，可含 tools
     * @param timeout  整体超时（毫秒）
     * @param listener 事件监听器
     */
    private void execute(String id, JSONObject wire, long timeout, Listener listener) {
        HttpURLConnection connection = null;
        boolean attempted = false;
        boolean finished = false;
        long seq = 0;
        ScheduledFuture<?> deadlineTask = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        JSONObject knownUsage = null;
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            final HttpURLConnection active = connection;
            deadlineTask = deadlines.schedule(active::disconnect, timeout, TimeUnit.MILLISECONDS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout((int) Math.min(timeout, 10000));
            connection.setReadTimeout((int) Math.min(timeout, 600000));
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", wire.getBoolean("stream") ? "text/event-stream" : "application/json");
            if (!token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            byte[] body = wire.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            attempted = true;
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                // 仅明确的推理前拒绝（参数、鉴权、不存在、过大、限流）才可安全释放物理容量。
                finished = status == 400 || status == 401 || status == 403 || status == 404 || status == 413 || status == 429;
                listener.onEvent(error(id, ++seq, "MODEL_HTTP_" + status, finished).put("modelDispatched", !finished));
                return;
            }
            JSONObject result;
            JSONObject rawResponse = null;
            try (InputStream input = connection.getInputStream()) {
                if (!wire.getBoolean("stream")) {
                    byte[] raw = read(input, 65536, deadline);
                    rawResponse = StrictJson.object(raw);
                    if (cube.aigc.usage.UsageTokens.read(rawResponse) != null)
                        knownUsage = rawResponse.getJSONObject("usage");
                    JSONArray choices = rawResponse.optJSONArray("choices");
                    if (choices != null && choices.length() == 1) {
                        JSONObject choice = choices.optJSONObject(0);
                        JSONObject message = choice == null ? null : choice.optJSONObject("message");
                        finished = message != null && "assistant".equals(message.optString("role"))
                                && java.util.Arrays.asList("stop", "length", "tool_calls", "content_filter").contains(choice.optString("finish_reason"));
                    }
                    result = OpenAIResponseAccumulator.json(rawResponse);
                } else {
                    if (connection.getContentType() == null
                            || !connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                        throw new IOException("Expected SSE");
                    }
                    OpenAIResponseAccumulator accumulator = new OpenAIResponseAccumulator();
                    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
                    Reader reader = new InputStreamReader(input, decoder);
                    StringBuilder data = new StringBuilder();
                    int total = 0;
                    boolean done = false;
                    while (true) {
                        checkDeadline(deadline);
                        String line = line(reader, deadline);
                        if (line == null) {
                            break;
                        }
                        total += line.length();
                        if (total > 1048576) {
                            throw new IOException("SSE too large");
                        }
                        if (line.isEmpty() && data.length() > 0) {
                            // 空行标志一个 SSE 事件结束，分发已缓冲的 data 载荷。
                            String raw = data.toString();
                            data.setLength(0);
                            if (done) {
                                throw new IOException("Trailing SSE data");
                            }
                            if (raw.equals("[DONE]")) {
                                done = true;
                                break;
                            }
                            JSONObject delta = accumulator.add(StrictJson.object(raw.getBytes(StandardCharsets.UTF_8)));
                            knownUsage = accumulator.usageEvidence();
                            if (delta.length() > 0) {
                                listener.onEvent(event(id, ++seq, "delta", new JSONObject().put("delta", delta), false));
                            }
                        } else if (line.startsWith("data:")) {
                            // 多行 data 字段按 SSE 规范以 \n 连接，并剥除一个可选前导空格。
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).replaceFirst("^ ", ""));
                            if (data.length() > 65536) {
                                throw new IOException("SSE event too large");
                            }
                        }
                    }
                    if (!done || data.length() > 0) {
                        throw new IOException("Truncated SSE");
                    }
                    finished = true;
                    result = accumulator.result();
                }
            }
            JSONObject completed = event(id, ++seq, "completed", new JSONObject().put("result", result), true);
            if (rawResponse != null) {
                completed.put("rawResponse", rawResponse);
            }
            listener.onEvent(completed);
        } catch (Exception e) {
            // 缺失 usage 不估算 token；请求未实际发出（attempted=false）时一律按未完成回报。
            String code = e instanceof CompletionError && "usage_missing".equals(((CompletionError) e).code)
                    ? "MODEL_USAGE_MISSING" : "MODEL_COMPLETION_UNKNOWN";
            JSONObject failure = error(id, ++seq, code, finished || !attempted).put("modelDispatched", attempted);
            if (knownUsage != null) failure.getJSONObject("payload").put("usage", knownUsage);
            listener.onEvent(failure);
        } finally {
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 检查整体截止时间是否已用尽。
     *
     * @param deadline 截止时间戳，System.nanoTime 基准
     * @throws SocketTimeoutException 已过截止时间时抛出，消息为 Inference deadline elapsed
     */
    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) {
            throw new SocketTimeoutException("Inference deadline elapsed");
        }
    }

    /**
     * 从输入流读取至多 limit 字节，并在每次读取边界检查截止时间。
     *
     * @param input    输入流
     * @param limit    字节上限
     * @param deadline 截止时间戳，System.nanoTime 基准
     * @return 读取到的字节，以流 EOF 为止（可能不足 limit）
     * @throws IOException 超出 limit 时抛出（Response too large），或读取失败、超时
     */
    private static byte[] read(InputStream input, int limit, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = input.read(b)) != -1) {
            checkDeadline(deadline);
            if (out.size() + n > limit) {
                throw new IOException("Response too large");
            }
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 读取一行 SSE 数据（以 LF 分隔），并剥除行尾 CR。
     *
     * @param reader   输入读取器
     * @param deadline 截止时间戳，System.nanoTime 基准
     * @return 一行内容；流干净结束（EOF 且无残行）时返回 null
     * @throws IOException 单行达到 64 KiB 上限时抛出（SSE line too large），或读取失败、超时
     */
    private static String line(Reader reader, long deadline) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1 && ch != '\n') {
            checkDeadline(deadline);
            if (line.length() >= 65536) {
                throw new IOException("SSE line too large");
            }
            line.append((char) ch);
        }
        if (ch == -1 && line.length() == 0) {
            return null;
        }
        if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    /**
     * 关闭后端：关闭推理线程池与截止调度器，不再接受新提交。
     */
    @Override
    public void close() {
        executor.shutdown();
        deadlines.shutdown();
    }
}
