package cube.service.harness.execution;

import cube.aigc.completion.*;
import cube.service.harness.protocol.StrictJson;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.*;

/** Explicit Java-only external backend. Cancellation observes; it never claims a closed socket stopped a model. */
public final class OpenAIHttpProvider implements CompletionProvider, AutoCloseable {
    private final URL endpoint;
    private final String model, token;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "harness-openai-deadline"); t.setDaemon(true); return t;
    });

    public OpenAIHttpProvider(String baseUrl, String model, String token, int capacity) throws Exception {
        URI uri = new URI(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || model == null || model.isEmpty() || token == null || token.contains("\r") || token.contains("\n"))
            throw new IllegalArgumentException("INVALID_EXTERNAL_BACKEND");
        endpoint = new URL(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.model = model; this.token = token;
        executor = new ThreadPoolExecutor(capacity, capacity, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "harness-openai"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public JSONArray completionModels(String ignored) {
        return new JSONArray().put(new JSONObject().put("id", model));
    }

    @Override public JSONObject completionCapacity(String ignored, String requestedModel, boolean nativeTools) {
        return new JSONObject().put("registered", executor.getMaximumPoolSize())
                .put("available", executor.isShutdown() ? 0 : Math.max(0, executor.getMaximumPoolSize() - executor.getActiveCount()));
    }

    @Override public void submitCompletion(String ignored, String id, JSONObject canonical, long timeout, Listener listener) {
        JSONObject wire = new JSONObject(canonical.getJSONObject("options").toString())
                .put("model", model).put("messages", canonical.getJSONArray("messages"))
                .put("stream", canonical.getBoolean("stream"));
        if (canonical.has("tools")) wire.put("tools", canonical.getJSONArray("tools"));
        if (wire.getBoolean("stream")) wire.put("stream_options", new JSONObject().put("include_usage", true));
        try { executor.execute(() -> execute(id, wire, timeout, listener)); }
        catch (RejectedExecutionException e) { listener.onEvent(error(id, 1, "MODEL_BACKEND_BUSY", true)); }
    }

    private static JSONObject event(String id, long seq, String kind, JSONObject payload, boolean finished) {
        return new JSONObject().put("requestId", id).put("sequence", seq).put("kind", kind)
                .put("payload", payload).put("actualFinished", finished);
    }

    private static JSONObject error(String id, long seq, String code, boolean finished) {
        return event(id, seq, "error", new JSONObject().put("error", new JSONObject().put("code", code)), finished);
    }

    private void execute(String id, JSONObject wire, long timeout, Listener listener) {
        HttpURLConnection connection = null; boolean attempted = false, finished = false; long seq = 0;
        ScheduledFuture<?> deadlineTask = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            final HttpURLConnection active = connection;
            deadlineTask = deadlines.schedule(active::disconnect, timeout, TimeUnit.MILLISECONDS);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout((int)Math.min(timeout, 10000)); connection.setReadTimeout((int)Math.min(timeout, 600000));
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", wire.getBoolean("stream") ? "text/event-stream" : "application/json");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            byte[] body = wire.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            attempted = true;
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status != 200) {
                // Only definite pre-inference rejections are safe to release.
                finished = status == 400 || status == 401 || status == 403 || status == 404 || status == 413 || status == 429;
                listener.onEvent(error(id, ++seq, "MODEL_HTTP_" + status, finished)); return;
            }
            JSONObject result, rawResponse = null;
            try (InputStream input = connection.getInputStream()) {
                if (!wire.getBoolean("stream")) {
                    byte[] raw = read(input, 65536, deadline);
                    rawResponse = StrictJson.object(raw);
                    JSONArray choices = rawResponse.optJSONArray("choices");
                    if (choices != null && choices.length() == 1) {
                        JSONObject choice = choices.optJSONObject(0);
                        JSONObject message = choice == null ? null : choice.optJSONObject("message");
                        finished = message != null && "assistant".equals(message.optString("role"))
                                && java.util.Arrays.asList("stop", "length", "tool_calls", "content_filter").contains(choice.optString("finish_reason"));
                    }
                    result = OpenAIResponseAccumulator.json(rawResponse);
                } else {
                    if (connection.getContentType() == null || !connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream"))
                        throw new IOException("Expected SSE");
                    OpenAIResponseAccumulator accumulator = new OpenAIResponseAccumulator();
                    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
                    Reader reader = new InputStreamReader(input, decoder); StringBuilder data = new StringBuilder();
                    int total = 0; boolean done = false;
                    while (true) {
                        checkDeadline(deadline);
                        String line = line(reader, deadline); if (line == null) break;
                        total += line.length(); if (total > 1048576) throw new IOException("SSE too large");
                        if (line.isEmpty() && data.length() > 0) {
                            String raw = data.toString(); data.setLength(0);
                            if (done) throw new IOException("Trailing SSE data");
                            if (raw.equals("[DONE]")) { done = true; break; }
                            JSONObject delta = accumulator.add(StrictJson.object(raw.getBytes(StandardCharsets.UTF_8)));
                            if (delta.length() > 0) listener.onEvent(event(id, ++seq, "delta", new JSONObject().put("delta", delta), false));
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) data.append('\n');
                            data.append(line.substring(5).replaceFirst("^ ", ""));
                            if (data.length() > 65536) throw new IOException("SSE event too large");
                        }
                    }
                    if (!done || data.length() > 0) throw new IOException("Truncated SSE");
                    finished = true; result = accumulator.result();
                }
            }
            JSONObject completed = event(id, ++seq, "completed", new JSONObject().put("result", result), true);
            if (rawResponse != null) completed.put("rawResponse", rawResponse);
            listener.onEvent(completed);
        } catch (Exception e) {
            String code = e instanceof CompletionError && "usage_missing".equals(((CompletionError)e).code)
                    ? "MODEL_USAGE_MISSING" : "MODEL_COMPLETION_UNKNOWN";
            listener.onEvent(error(id, ++seq, code, finished || !attempted));
        } finally { if (deadlineTask != null) deadlineTask.cancel(false); if (connection != null) connection.disconnect(); }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) throw new SocketTimeoutException("Inference deadline elapsed");
    }

    private static byte[] read(InputStream input, int limit, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = input.read(b)) != -1) { checkDeadline(deadline); if (out.size() + n > limit) throw new IOException("Response too large"); out.write(b, 0, n); }
        return out.toByteArray();
    }

    private static String line(Reader reader, long deadline) throws IOException {
        StringBuilder line = new StringBuilder(); int ch;
        while ((ch = reader.read()) != -1 && ch != '\n') {
            checkDeadline(deadline);
            if (line.length() >= 65536) throw new IOException("SSE line too large");
            line.append((char)ch);
        }
        if (ch == -1 && line.length() == 0) return null;
        if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
        return line.toString();
    }

    @Override public void close() { executor.shutdown(); deadlines.shutdown(); }
}
