/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.harness;

import cell.api.Speakable;
import cell.core.talk.Primitive;
import cell.core.talk.PrimitiveInputStream;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import cube.dispatcher.PerformerListener;
import cube.util.HttpServer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST endpoint assembly; the sole service transport is Performer's SHM connection.
 */
public final class HarnessGateway extends ContextHandler implements PerformerListener {

    final Performer performer;

    final Semaphore inflight = new Semaphore(16), files = new Semaphore(2), streams = new Semaphore(2);

    final Set<RequestState> requests = Collections.newSetFromMap(new ConcurrentHashMap<RequestState, Boolean>());

    final ConcurrentHashMap<String, Receiver> receivers = new ConcurrentHashMap<>();

    ThreadPoolExecutor pool, filePool, cleanup;

    ScheduledExecutorService deadlines;

    public static HarnessGateway install(HttpServer server, Performer performer) {
        HarnessGateway gateway = new HarnessGateway(performer);
        server.addContextHandler(gateway);
        return gateway;
    }

    public HarnessGateway(Performer performer) {
        super("/harness");
        this.performer = Objects.requireNonNull(performer, "performer");
        setHandler(new HarnessHandler(this));
    }

    static ThreadPoolExecutor pool(String name, int size, int queue) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(queue), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    JSONObject credential(String authorization) {
        if (authorization == null || authorization.length() > 4096 || !authorization.startsWith("Bearer ") || authorization.length() < 31)
            return null;
        // Service authenticates the original credential and determines the caller's role.
        return new JSONObject().put("credential", authorization.substring(7));
    }

    JSONObject call(RequestState state, HarnessAction action, JSONObject data, byte[] body, long timeout) throws IOException {
        if (state.closed.get() || !state.connectionValid()) throw new IOException("Request connection closed");
        cell.core.talk.dialect.ActionDialect request = new Packet(action.name, data).toDialect();
        if (body != null) request.addParam("body", body);
        cell.core.talk.dialect.ActionDialect response = performer.syncTransmit(state.director, HarnessAction.CELLET, request, timeout);
        if (response == null) throw new IOException("Harness unavailable");
        JSONObject reply = new Packet(response).data;
        // A preparation reply racing HTTP cancellation still needs its remote lease released.
        if ((action == HarnessAction.UploadInput || action == HarnessAction.DownloadArtifact) && reply.getInt("status") < 400)
            state.setTransfer(reply.getJSONObject("body").getString("transferId"));
        if (state.closed.get() || !state.connectionValid()) throw new IOException("Harness unavailable");
        if (reply.toString().getBytes(StandardCharsets.UTF_8).length > HarnessAction.MAX_RESPONSE_BYTES)
            throw new IOException("Harness response limit");
        return reply;
    }

    static JSONObject error(int status, String code) {
        return new JSONObject().put("status", status).put("body", new JSONObject().put("errorCode", code)
                .put("retryable", status == 429 || status == 503).put("requestId", UUID.randomUUID().toString()));
    }

    static void error(HttpServletResponse response, int status, String code) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setContentLength(-1);
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(error(status, code).getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onReceived(String cellet, Primitive primitive) {
    }

    @Override
    public void onReceivedStream(Speakable source, String cellet, PrimitiveInputStream stream) {
        Receiver receiver = receivers.get(stream.getName());
        if (receiver == null || receiver.source != source || !receiver.input.complete(stream)) closeQuietly(stream);
    }

    @Override
    public void onDisconnected(Speakable source) {
        for (RequestState state : requests)
            if (state.source == source)
                state.fail(503, "HARNESS_UNAVAILABLE");
    }

    static void closeQuietly(AutoCloseable resource) {
        if (resource != null) try {
            if (resource instanceof cell.core.talk.PrimitiveOutputStream)
                ((cell.core.talk.PrimitiveOutputStream) resource).interrupt();
            resource.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void doStart() throws Exception {
        pool = pool("harness-rest", 4, 16);
        filePool = pool("harness-rest-files", 2, 2);
        cleanup = pool("harness-rest-cleanup", 1, 32);
        deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harness-rest-deadlines");
            t.setDaemon(true);
            return t;
        });
        deadlines.scheduleWithFixedDelay(() -> {
            try {
                cleanup.execute(() -> {
                    try {
                        performer.retryFailedConnection(HarnessAction.CELLET);
                    } catch (Exception ignored) {
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }, 5, 5, TimeUnit.SECONDS);
        performer.setListener(HarnessAction.CELLET, this);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        for (RequestState state : requests) state.fail(503, "HARNESS_UNAVAILABLE");
        performer.removeListener(HarnessAction.CELLET, this);
        if (deadlines != null) deadlines.shutdownNow();
        if (pool != null) pool.shutdownNow();
        if (filePool != null) filePool.shutdownNow();
        if (cleanup != null) {
            cleanup.shutdown();
            try {
                if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) cleanup.shutdownNow();
            } catch (InterruptedException e) {
                cleanup.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        super.doStop();
    }

    static final class Receiver implements AutoCloseable {
        final Speakable source;
        final CompletableFuture<PrimitiveInputStream> input = new CompletableFuture<>();

        Receiver(Speakable source) {
            this.source = source;
        }

        @Override
        public void close() {
            if (!input.completeExceptionally(new IOException("Download closed"))) {
                try {
                    closeQuietly(input.getNow(null));
                } catch (CompletionException ignored) {
                }
            }
        }
    }

    final class RequestState implements AsyncListener {
        final AsyncContext async;
        final HttpServletRequest request;
        final HttpServletResponse response;
        final AtomicBoolean closed = new AtomicBoolean();
        final boolean file, sse;
        volatile Director director;
        volatile Speakable source;
        volatile cell.core.net.Session session;
        volatile Future<?> task;
        volatile AutoCloseable io;
        volatile String transferId;
        volatile JSONObject auth;
        volatile boolean completed;
        volatile Receiver receiver;
        ScheduledFuture<?> deadline;
        final long sseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        void selectConnection(Director selected) {
            director = selected;
            source = selected == null ? null : selected.speaker;
            session = source instanceof cell.core.talk.Speaker ? ((cell.core.talk.Speaker) source).getSession() : null;
        }

        boolean connectionValid() {
            return source != null && source.isReady() && director.speaker == source
                    && (!(source instanceof cell.core.talk.Speaker) || ((cell.core.talk.Speaker) source).getSession() == session);
        }

        synchronized void timeout(long millis) {
            if (deadline != null) deadline.cancel(false);
            if (sse) millis = Math.min(millis, TimeUnit.NANOSECONDS.toMillis(sseDeadline - System.nanoTime()));
            if (!closed.get())
                deadline = deadlines.schedule(() -> onTimeout(null), Math.max(1, millis), TimeUnit.MILLISECONDS);
        }

        synchronized void track(AutoCloseable resource) {
            if (closed.get()) closeQuietly(resource);
            else io = resource;
        }

        synchronized void setTask(Future<?> future) {
            task = future;
            if (closed.get() && !completed) future.cancel(true);
        }

        synchronized void setTransfer(String id) {
            transferId = id;
            if (closed.get()) abortTransfer();
        }

        synchronized Receiver registerReceiver() throws IOException {
            if (closed.get()) throw new IOException("Request closed");
            if (!connectionValid()) throw new IOException("Connection changed");
            Receiver next = new Receiver(source);
            if (receivers.putIfAbsent(transferId, next) != null) throw new IOException("Duplicate transfer");
            receiver = next;
            return next;
        }

        RequestState(HttpServletRequest request, HttpServletResponse response, boolean file, boolean sse) {
            this.request = request;
            this.response = response;
            this.file = file;
            this.sse = sse;
            async = request.startAsync();
            // Jetty does not reschedule an already armed async timeout when setTimeout changes.
            async.setTimeout(0);
            async.addListener(this);
            requests.add(this);
            timeout(10000);
        }

        void complete() {
            completed = true;
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
            }
        }

        void fail(int status, String code) {
            if (closed.get()) return;
            if (response.isCommitted()) {
                done();
                org.eclipse.jetty.server.Request.getBaseRequest(request).getHttpChannel().abort(new IOException(code));
                return;
            }
            try {
                error(response, status, code);
            } catch (Exception ignored) {
            }
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
            }
        }

        private synchronized void abortTransfer() {
            if (transferId != null && director != null && auth != null && cleanup != null) {
                final JSONObject data = new JSONObject(auth.toString()).put("transferId", transferId);
                try {
                    cleanup.execute(() -> {
                        if (!connectionValid()) return;
                        try {
                            performer.syncTransmit(director, HarnessAction.CELLET,
                                    new Packet(HarnessAction.AbortTransfer.name, data).toDialect(), 1000);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }
        }

        private synchronized void done() {
            if (!closed.compareAndSet(false, true)) return;
            requests.remove(this);
            if (deadline != null) deadline.cancel(false);
            if (!completed && task != null) task.cancel(true);
            closeQuietly(io);
            if (receiver != null) {
                receivers.remove(transferId, receiver);
                closeQuietly(receiver);
            }
            abortTransfer();
            inflight.release();
            if (file) files.release();
            if (sse) streams.release();
        }

        @Override
        public void onComplete(AsyncEvent event) {
            done();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            completed = false;
            fail(503, "HARNESS_TIMEOUT");
            done();
        }

        @Override
        public void onError(AsyncEvent event) {
            completed = false;
            done();
            complete();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }
    }
}
