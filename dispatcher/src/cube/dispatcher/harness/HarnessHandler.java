/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.harness;

import cell.core.talk.PrimitiveInputStream;
import cell.core.talk.PrimitiveOutputStream;
import cube.common.action.HarnessAction;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * Harness REST 接入层的 Jetty 请求处理器。
 *
 * <p>负责把 HTTP 请求转换为对 Harness Cellet 的调用：请求匹配、凭据校验、并发配额
 * 控制、请求体读取，以及普通 JSON 应答、文件上传/下载和 SSE 事件流的响应写出。</p>
 *
 * <p>本处理器不依赖 AIGC Manager、服务 HTTP 客户端或任何服务侧实现，与服务单元的
 * 全部通信均经由 {@link HarnessGateway} 完成。</p>
 */
final class HarnessHandler extends AbstractHandler {

    private final HarnessGateway gateway;

    /**
     * 构造函数。
     *
     * @param gateway 指定 Harness 网关，请求的匹配、鉴权与转发均通过该网关完成。
     */
    HarnessHandler(HarnessGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 处理 Harness REST 请求。
     *
     * <p>依次执行：请求匹配（URI 匹配但方法不匹配时返回 405 ，无匹配时返回 404 ）、
     * 凭据校验（失败返回 401 ）、路径参数与请求头参数提取（单值超限返回 400 ）、
     * 并发配额申请（全局在途、文件、SSE 流），最后将请求提交至线程池异步执行。</p>
     *
     * @param target   请求的目标路径。
     * @param base     Jetty 请求对象，用于标记该请求已被处理。
     * @param request  HTTP 请求对象。
     * @param response HTTP 响应对象。
     * @throws IOException 当读写请求数据发生错误时抛出。
     */
    @Override
    public void handle(String target, Request base, HttpServletRequest request, HttpServletResponse response) throws IOException {
        base.setHandled(true);
        response.setHeader("Cache-Control", "no-store");

        HarnessAction action = null;
        Matcher match = null;
        boolean known = false;
        for (HarnessAction candidate : HarnessAction.values()) {
            Matcher matcher = candidate.match(request.getRequestURI());
            if (matcher != null && matcher.matches()) {
                known = true;
                if (candidate.method.equals(request.getMethod())) {
                    action = candidate;
                    match = matcher;
                    break;
                }
            }
        }
        if (action == null) {
            HarnessGateway.error(response, known ? 405 : 404, known ? "METHOD_NOT_ALLOWED" : "NOT_FOUND");
            return;
        }

        JSONObject data = gateway.credential(request.getHeader("Authorization"));
        if (data == null) {
            HarnessGateway.error(response, 401, "UNAUTHORIZED");
            return;
        }

        // 路径参数：动作正则的捕获组按位置映射为 id / approvalId
        if (match.groupCount() > 0) {
            data.put("id", match.group(1));
        }
        if (match.groupCount() > 1) {
            data.put("approvalId", match.group(2));
        }

        // 透传的请求头：HTTP 头名 -> 数据键名
        for (String[] header : new String[][]{{"Idempotency-Key", "key"}, {"Content-Type", "contentType"}, {"Last-Event-ID", "lastEventId"}}) {
            String value = request.getHeader(header[0]);
            if (value != null) {
                if (value.length() > 4096) {
                    HarnessGateway.error(response, 400, "INVALID_REQUEST");
                    return;
                }
                data.put(header[1], value);
            }
        }

        String query = request.getQueryString();
        if (query != null) {
            if (query.length() > 4096) {
                HarnessGateway.error(response, 400, "INVALID_REQUEST");
                return;
            }
            data.put("query", query);
        }

        boolean file = action == HarnessAction.UploadInput || action == HarnessAction.DownloadArtifact;
        boolean sse = action == HarnessAction.Events && String.valueOf(request.getHeader("Accept")).contains("text/event-stream");
        if (!gateway.inflight.tryAcquire()) {
            HarnessGateway.error(response, 503, "HARNESS_BUSY");
            return;
        }
        if (file && !gateway.files.tryAcquire()) {
            gateway.inflight.release();
            HarnessGateway.error(response, 429, "FILE_BUSY");
            return;
        }
        if (sse && !gateway.streams.tryAcquire()) {
            gateway.inflight.release();
            HarnessGateway.error(response, 429, "HARNESS_BUSY");
            return;
        }

        HarnessGateway.RequestState state;
        try {
            state = gateway.new RequestState(request, response, file, sse);
        } catch (RuntimeException e) {
            gateway.inflight.release();
            if (file) {
                gateway.files.release();
            }
            if (sse) {
                gateway.streams.release();
            }
            throw e;
        }
        state.auth = new JSONObject().put("credential", data.getString("credential"));

        final HarnessAction selected = action;
        try {
            state.setTask((file ? gateway.filePool : gateway.pool).submit(() -> execute(state, selected, data)));
        } catch (RejectedExecutionException e) {
            state.fail(503, "HARNESS_BUSY");
        }
    }

    /**
     * 在线程池的 worker 线程上执行请求。
     *
     * <p>选择承载 Harness Cellet 的导演机（不可用时返回 503 ），按需读取请求体
     * （超过大小上限返回 413 ），调用服务单元后按动作类型写出响应：文件动作执行
     * 上传/下载，SSE 事件动作输出事件流帧，其余输出 JSON 数据。</p>
     *
     * @param state  请求状态。
     * @param action 请求对应的动作。
     * @param data   请求数据。
     */
    private void execute(HarnessGateway.RequestState state, HarnessAction action, JSONObject data) {
        try {
            state.selectConnection(gateway.performer.selectDirectorForCellet(HarnessAction.CELLET));
            if (state.director == null) {
                state.fail(503, "HARNESS_UNAVAILABLE");
                return;
            }

            byte[] body = null;
            if (action.schema != null) {
                state.track(state.request.getInputStream());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = ((InputStream) state.io).read(buffer)) != -1) {
                    if (out.size() + n > HarnessAction.MAX_BODY_BYTES) {
                        state.fail(413, "PAYLOAD_TOO_LARGE");
                        return;
                    }
                    if (state.closed.get()) {
                        return;
                    }
                    out.write(buffer, 0, n);
                }
                body = out.toByteArray();
            }

            JSONObject reply = gateway.call(state, action, data, body, 5000);
            int status = reply.getInt("status");
            if (status < 400 && state.file) {
                JSONObject descriptor = reply.getJSONObject("body");
                state.setTransfer(descriptor.getString("transferId"));
                long remaining = descriptor.getLong("expiresAt") - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IOException("Expired transfer");
                }
                state.timeout(remaining);
                if (action == HarnessAction.UploadInput) {
                    upload(state, descriptor);
                }
                else {
                    download(state, descriptor);
                }
            }
            else {
                byte[] bytes = state.sse && status == 200 ? sse(reply.getJSONObject("body"))
                        : reply.getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8);
                write(state, status, state.sse && status == 200 ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8", bytes);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            state.fail(503, "HARNESS_UNAVAILABLE");
        }
    }

    /**
     * 向服务单元发送控制指令。
     *
     * @param state  请求状态。
     * @param action 控制动作。
     * @return 返回服务单元的应答数据。
     * @throws IOException 当调用服务单元失败时抛出。
     */
    private JSONObject control(HarnessGateway.RequestState state, HarnessAction action) throws IOException {
        return gateway.call(state, action, new JSONObject(state.auth.toString()).put("transferId", state.transferId), null, 5000);
    }

    /**
     * 轮询文件传输结果，直到传输完成或超过截止时间。
     *
     * @param state   请求状态。
     * @param expires 截止时间戳，单位毫秒。
     * @return 传输完成时返回响应数据；服务单元返回错误时返回错误应答。
     * @throws Exception 当轮询超时、连接关闭或被中断时抛出。
     */
    private JSONObject result(HarnessGateway.RequestState state, long expires) throws Exception {
        while (!state.closed.get() && System.currentTimeMillis() < expires) {
            JSONObject reply = control(state, HarnessAction.TransferResult);
            if (reply.getInt("status") >= 400) {
                return reply;
            }
            JSONObject body = reply.getJSONObject("body");
            if (body.getBoolean("complete")) {
                return body.getJSONObject("response");
            }
            Thread.sleep(25);
        }
        throw new IOException("Transfer timeout");
    }

    /**
     * 把请求体流式上传至服务单元。
     *
     * <p>上传过程中校验数据量与截止时间；上传结束后由服务单元校验原始声明的大小与
     * 摘要并提交，最后将服务单元的应答写回客户端。</p>
     *
     * @param state      请求状态。
     * @param descriptor 服务单元返回的文件描述符。
     * @throws Exception 当上传或等待传输结果失败时抛出。
     */
    private void upload(HarnessGateway.RequestState state, JSONObject descriptor) throws Exception {
        long expected = descriptor.getJSONObject("metadata").getLong("size");
        long size = 0;
        InputStream input = state.request.getInputStream();
        PrimitiveOutputStream output = gateway.performer.openStream(state.director, HarnessAction.CELLET, state.transferId);
        state.track(() -> {
            HarnessGateway.closeQuietly(input);
            HarnessGateway.closeQuietly(output);
        });
        try {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = input.read(buffer)) != -1) {
                size += n;
                if (size > expected || state.closed.get() || System.currentTimeMillis() >= descriptor.getLong("expiresAt")) {
                    throw new IOException("Upload size or deadline");
                }
                output.write(buffer, 0, n);
            }
        } finally {
            output.close();
        }
        // 服务单元会校验原始声明的大小与摘要，提交成功后才会报告成功
        JSONObject reply = result(state, descriptor.getLong("expiresAt"));
        write(state, reply.getInt("status"), "application/json; charset=utf-8", reply.getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从服务单元流式下载文件并写回客户端。
     *
     * <p>下载过程中同步计算 SHA-256 摘要，结束后校验数据量与摘要是否与描述符一致，
     * 并等待服务单元确认提交。</p>
     *
     * @param state      请求状态。
     * @param descriptor 服务单元返回的文件描述符。
     * @throws Exception 当下载、校验或等待传输结果失败时抛出。
     */
    private void download(HarnessGateway.RequestState state, JSONObject descriptor) throws Exception {
        HarnessGateway.Receiver receiver = state.registerReceiver();
        JSONObject start = control(state, HarnessAction.StartDownload);
        if (start.getInt("status") >= 400) {
            write(state, start.getInt("status"), "application/json; charset=utf-8", start.getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        PrimitiveInputStream input = receiver.input.get(5, TimeUnit.SECONDS);
        state.track(input);
        JSONObject headers = descriptor.getJSONObject("headers");
        JSONObject metadata = descriptor.getJSONObject("metadata");
        long expected = metadata.getLong("size");
        long size = 0;
        state.response.setStatus(200);
        for (String header : new String[]{"Content-Type", "Content-Disposition"}) {
            if (headers.has(header)) {
                state.response.setHeader(header, headers.getString(header));
            }
        }
        state.response.setContentLengthLong(expected);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ServletOutputStream output = state.response.getOutputStream();
        byte[] buffer = new byte[32768];
        int n;
        while ((n = input.read(buffer)) != -1) {
            size += n;
            if (state.closed.get() || size > expected || System.currentTimeMillis() >= descriptor.getLong("expiresAt")) {
                throw new IOException("Download size or deadline");
            }
            digest.update(buffer, 0, n);
            output.write(buffer, 0, n);
        }
        if (size != expected || !hex(digest.digest()).equals(metadata.getString("sha256"))) {
            throw new IOException("Incomplete download");
        }
        JSONObject reply = result(state, descriptor.getLong("expiresAt"));
        if (reply.getInt("status") != 200) {
            throw new IOException("Download failed");
        }
        state.complete();
    }

    /**
     * 把字节数组转换为小写的十六进制字符串。
     *
     * @param bytes 指定的字节数组。
     * @return 返回十六进制字符串。
     */
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(String.format("%02x", b & 255));
        }
        return text.toString();
    }

    /**
     * 把事件页数据组装为 SSE 响应帧。
     *
     * <p>帧内容依次为：重连间隔、各事件的 id/event/data 帧、结束注释；事件页数据
     * 超过响应大小上限时抛出异常。</p>
     *
     * @param page 包含事件列表的 JSON 对象。
     * @return 返回 SSE 响应帧的字节数据。
     * @throws IOException 当事件页数据超过响应大小上限时抛出。
     */
    private static byte[] sse(JSONObject page) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write("retry: 500\n\n".getBytes(StandardCharsets.UTF_8));
        JSONArray events = page.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            byte[] frame = ("id: " + event.getLong("eventSeq") + "\nevent: " + event.getString("type") + "\ndata: " + event + "\n\n").getBytes(StandardCharsets.UTF_8);
            if (data.size() + frame.length > HarnessAction.MAX_RESPONSE_BYTES - 32) {
                throw new IOException("Event page limit");
            }
            data.write(frame);
        }
        data.write(": replay-page-complete\n\n".getBytes(StandardCharsets.UTF_8));
        return data.toByteArray();
    }

    /**
     * 把响应数据异步写回客户端。
     *
     * <p>通过 {@link WriteListener} 分块写出，数据写完或写出出错时结束请求状态。</p>
     *
     * @param state  请求状态。
     * @param status HTTP 状态码。
     * @param type   响应的 Content-Type 。
     * @param bytes  响应数据。
     * @throws IOException 当获取响应输出流失败时抛出。
     */
    private static void write(HarnessGateway.RequestState state, int status, String type, byte[] bytes) throws IOException {
        if (state.closed.get()) {
            return;
        }
        state.response.setStatus(status);
        state.response.setContentType(type);
        state.timeout(state.sse ? 2000 : 10000);
        ServletOutputStream output = state.response.getOutputStream();
        output.setWriteListener(new WriteListener() {

            /**
             * 已写出的字节偏移量。
             */
            int offset;

            /**
             * 可写回调：分块写出发送数据，全部写完后结束请求状态。
             *
             * @throws IOException 当写出发送数据失败时抛出。
             */
            @Override
            public void onWritePossible() throws IOException {
                while (!state.closed.get() && output.isReady() && offset < bytes.length) {
                    int n = Math.min(4096, bytes.length - offset);
                    output.write(bytes, offset, n);
                    offset += n;
                }
                if (offset == bytes.length) {
                    state.complete();
                }
            }

            /**
             * 写出错误回调：结束请求状态。
             *
             * @param error 错误信息。
             */
            @Override
            public void onError(Throwable error) {
                state.complete();
            }
        });
    }
}
