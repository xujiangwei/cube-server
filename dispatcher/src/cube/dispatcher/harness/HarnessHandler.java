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
 * REST/SHM conversion. No AIGC Manager, service HTTP client or service implementation dependency.
 */
final class HarnessHandler extends AbstractHandler {

    private final HarnessGateway gateway;

    HarnessHandler(HarnessGateway gateway) {
        this.gateway = gateway;
    }

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
        if (match.groupCount() > 0) data.put("id", match.group(1));
        if (match.groupCount() > 1) data.put("approvalId", match.group(2));
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
            if (file) gateway.files.release();
            if (sse) gateway.streams.release();
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
                    if (state.closed.get()) return;
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
                if (remaining <= 0) throw new IOException("Expired transfer");
                state.timeout(remaining);
                if (action == HarnessAction.UploadInput) upload(state, descriptor);
                else download(state, descriptor);
            } else {
                byte[] bytes = state.sse && status == 200 ? sse(reply.getJSONObject("body"))
                        : reply.getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8);
                write(state, status, state.sse && status == 200 ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8", bytes);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            state.fail(503, "HARNESS_UNAVAILABLE");
        }
    }

    private JSONObject control(HarnessGateway.RequestState state, HarnessAction action) throws IOException {
        return gateway.call(state, action, new JSONObject(state.auth.toString()).put("transferId", state.transferId), null, 5000);
    }

    private JSONObject result(HarnessGateway.RequestState state, long expires) throws Exception {
        while (!state.closed.get() && System.currentTimeMillis() < expires) {
            JSONObject reply = control(state, HarnessAction.TransferResult);
            if (reply.getInt("status") >= 400) return reply;
            JSONObject body = reply.getJSONObject("body");
            if (body.getBoolean("complete")) return body.getJSONObject("response");
            Thread.sleep(25);
        }
        throw new IOException("Transfer timeout");
    }

    private void upload(HarnessGateway.RequestState state, JSONObject descriptor) throws Exception {
        long expected = descriptor.getJSONObject("metadata").getLong("size"), size = 0;
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
                if (size > expected || state.closed.get() || System.currentTimeMillis() >= descriptor.getLong("expiresAt"))
                    throw new IOException("Upload size or deadline");
                output.write(buffer, 0, n);
            }
        } finally {
            output.close();
        }
        // The service validates the original declared size/digest and commits before reporting success.
        JSONObject reply = result(state, descriptor.getLong("expiresAt"));
        write(state, reply.getInt("status"), "application/json; charset=utf-8", reply.getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8));
    }

    private void download(HarnessGateway.RequestState state, JSONObject descriptor) throws Exception {
        HarnessGateway.Receiver receiver = state.registerReceiver();
        JSONObject start = control(state, HarnessAction.StartDownload);
        if (start.getInt("status") >= 400) {
            write(state, start.getInt("status"), "application/json; charset=utf-8", start.getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        PrimitiveInputStream input = receiver.input.get(5, TimeUnit.SECONDS);
        state.track(input);
        JSONObject headers = descriptor.getJSONObject("headers"), metadata = descriptor.getJSONObject("metadata");
        long expected = metadata.getLong("size"), size = 0;
        state.response.setStatus(200);
        for (String header : new String[]{"Content-Type", "Content-Disposition"})
            if (headers.has(header)) state.response.setHeader(header, headers.getString(header));
        state.response.setContentLengthLong(expected);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ServletOutputStream output = state.response.getOutputStream();
        byte[] buffer = new byte[32768];
        int n;
        while ((n = input.read(buffer)) != -1) {
            size += n;
            if (state.closed.get() || size > expected || System.currentTimeMillis() >= descriptor.getLong("expiresAt"))
                throw new IOException("Download size or deadline");
            digest.update(buffer, 0, n);
            output.write(buffer, 0, n);
        }
        if (size != expected || !hex(digest.digest()).equals(metadata.getString("sha256")))
            throw new IOException("Incomplete download");
        JSONObject reply = result(state, descriptor.getLong("expiresAt"));
        if (reply.getInt("status") != 200) throw new IOException("Download failed");
        state.complete();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b & 255));
        return text.toString();
    }

    private static byte[] sse(JSONObject page) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write("retry: 500\n\n".getBytes(StandardCharsets.UTF_8));
        JSONArray events = page.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            byte[] frame = ("id: " + event.getLong("eventSeq") + "\nevent: " + event.getString("type") + "\ndata: " + event + "\n\n").getBytes(StandardCharsets.UTF_8);
            if (data.size() + frame.length > HarnessAction.MAX_RESPONSE_BYTES - 32)
                throw new IOException("Event page limit");
            data.write(frame);
        }
        data.write(": replay-page-complete\n\n".getBytes(StandardCharsets.UTF_8));
        return data.toByteArray();
    }

    private static void write(HarnessGateway.RequestState state, int status, String type, byte[] bytes) throws IOException {
        if (state.closed.get()) return;
        state.response.setStatus(status);
        state.response.setContentType(type);
        state.timeout(state.sse ? 2000 : 10000);
        ServletOutputStream output = state.response.getOutputStream();
        output.setWriteListener(new WriteListener() {
            int offset;

            @Override
            public void onWritePossible() throws IOException {
                while (!state.closed.get() && output.isReady() && offset < bytes.length) {
                    int n = Math.min(4096, bytes.length - offset);
                    output.write(bytes, offset, n);
                    offset += n;
                }
                if (offset == bytes.length) state.complete();
            }

            @Override
            public void onError(Throwable error) {
                state.complete();
            }
        });
    }
}
