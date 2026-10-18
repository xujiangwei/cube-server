/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.harness;

import cube.util.HttpServer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Separate opt-in development gateway. Never dispatches through Completion, Chat or the old token pool.
 */
public final class HarnessGateway extends ContextHandler {
    private final URL backend;
    private final String clientToken, backendToken;
    private ThreadPoolExecutor pool;
    private final Set<AsyncContext> requests = Collections.newSetFromMap(new ConcurrentHashMap<AsyncContext, Boolean>());
    private final Semaphore inflight = new Semaphore(16);

    public static HarnessGateway install(HttpServer server, Properties p) {
        if (!Boolean.parseBoolean(p.getProperty("harness.rest.enabled", "false"))) return null;
        HarnessGateway gateway = new HarnessGateway(p);
        server.addContextHandler(gateway);
        return gateway;
    }

    public HarnessGateway(Properties p) {
        super("/harness");
        try {
            if (!"development".equals(p.getProperty("harness.rest.mode")))
                throw new IllegalArgumentException("Harness development identity required");
            backend = new URL(p.getProperty("harness.rest.backend"));
            if (!backend.getProtocol().equals("http") || !InetAddress.getByName(backend.getHost()).isLoopbackAddress()
                    || backend.getUserInfo() != null || backend.getQuery() != null || backend.getRef() != null || !Arrays.asList("", "/").contains(backend.getPath()))
                throw new IllegalArgumentException("Dedicated loopback Harness backend required");
            clientToken = p.getProperty("harness.rest.client.credential", "");
            backendToken = p.getProperty("harness.rest.backend.credential", "");
            if (clientToken.length() < 24 || backendToken.length() < 24 || clientToken.equals(backendToken))
                throw new IllegalArgumentException("Separate Harness credentials required");
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid Harness backend");
        }
        setHandler(new AbstractHandler() {
            public void handle(String target, Request base, HttpServletRequest req, HttpServletResponse res) throws IOException {
                base.setHandled(true);
                handleRequest(req, res);
            }
        });
    }

    private static void error(HttpServletResponse res, int status, String code) throws IOException {
        if (res.isCommitted()) return;
        res.setStatus(status);
        res.setContentType("application/json; charset=utf-8");
        res.getOutputStream().write(("{\"errorCode\":\"" + code + "\",\"retryable\":" + (status == 503) + ",\"requestId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private void handleRequest(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setHeader("Cache-Control", "no-store");
        String auth = Objects.toString(req.getHeader("Authorization"), "");
        if (!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + clientToken).getBytes(StandardCharsets.UTF_8))) {
            error(res, 401, "UNAUTHORIZED");
            return;
        }
        String path = req.getRequestURI();
        String method = req.getMethod();
        boolean create = path.equals("/harness/v1/runs") && method.equals("POST");
        boolean read = method.equals("GET") && (path.matches("/harness/v1/(runs/[A-Za-z0-9_.:-]+(/events)?|task-profiles(/[A-Za-z0-9_.:-]+)?|capabilities)"));
        if (!create && !read) {
            error(res, 404, "NOT_FOUND");
            return;
        }
        String query = req.getQueryString();
        if (query != null && !query.matches("(after|limit)=[0-9]{1,15}(&(after|limit)=[0-9]{1,15})?")) {
            error(res, 400, "INVALID_EVENT_CURSOR");
            return;
        }
        if (!inflight.tryAcquire()) {
            error(res, 503, "HARNESS_BUSY");
            return;
        }
        final String suffix = path + (query == null ? "" : "?" + query);
        AsyncContext async = req.startAsync();
        async.setTimeout(10000);
        requests.add(async);
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        async.addListener(new AsyncListener() {
            private void done(AsyncEvent e) {
                requests.remove(async);
                if (released.compareAndSet(false, true)) inflight.release();
            }

            public void onComplete(AsyncEvent e) {
                done(e);
            }

            public void onTimeout(AsyncEvent e) {
                done(e);
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                }
            }

            public void onError(AsyncEvent e) {
                done(e);
            }

            public void onStartAsync(AsyncEvent e) {
            }
        });
        try {
            pool.execute(() -> proxy(req, res, async, suffix, method));
        } catch (RejectedExecutionException e) {
            error(res, 503, "HARNESS_BUSY");
            async.complete();
        }
    }

    private void proxy(HttpServletRequest req, HttpServletResponse res, AsyncContext async, String suffix, String method) {
        HttpURLConnection connection = null;
        try {
            byte[] payload = new byte[0];
            if (method.equals("POST")) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = req.getInputStream().read(buffer)) != -1) {
                    if (body.size() + n > 262144) {
                        error(res, 413, "PAYLOAD_TOO_LARGE");
                        async.complete();
                        return;
                    }
                    body.write(buffer, 0, n);
                }
                payload = body.toByteArray();
            }
            connection = (HttpURLConnection) new URL(backend, suffix).openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + backendToken);
            for (String h : Arrays.asList("Idempotency-Key", "Content-Type", "Accept", "Last-Event-ID")) {
                String v = req.getHeader(h);
                if (v != null) connection.setRequestProperty(h, v);
            }
            if (method.equals("POST")) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            // Upstream SSE is a finite replay page. Buffer a bounded page, then use nonblocking servlet writes.
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (in != null) try (InputStream source = in) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = source.read(buffer)) != -1) {
                    if (data.size() + n > 8 * 1024 * 1024) throw new IOException("Harness response limit");
                    data.write(buffer, 0, n);
                }
            }
            res.setStatus(status);
            res.setContentType(Objects.toString(connection.getContentType(), "application/json; charset=utf-8"));
            byte[] bytes = data.toByteArray();
            ServletOutputStream output = res.getOutputStream();
            output.setWriteListener(new WriteListener() {
                int offset;

                public void onWritePossible() throws IOException {
                    while (output.isReady() && offset < bytes.length) {
                        int n = Math.min(4096, bytes.length - offset);
                        output.write(bytes, offset, n);
                        offset += n;
                    }
                    if (offset == bytes.length) async.complete();
                }

                public void onError(Throwable e) {
                    try {
                        async.complete();
                    } catch (IllegalStateException ignored) {
                    }
                }
            });
        } catch (Exception e) {
            try {
                error(res, 503, "HARNESS_UNAVAILABLE");
                async.complete();
            } catch (Exception ignored) {
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    protected void doStart() throws Exception {
        pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
            Thread t = new Thread(r, "harness-gateway");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        for (AsyncContext a : requests)
            try {
                a.complete();
            } catch (IllegalStateException ignored) {
            }
        pool.shutdownNow();
        super.doStop();
    }
}
