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
 * Harness REST 接入点的装配与生命周期管理。
 *
 * <p>作为 Jetty 上下文处理器挂载在 HTTP 服务器上，把 REST 请求转交
 * {@link HarnessHandler} 处理；与服务单元的唯一通道是 Performer 的 SHM 连接。</p>
 *
 * <p>除请求分发外，网关还承担凭据校验、并发配额控制、文件传输登记、
 * 连接监听与请求状态跟踪。</p>
 */
public final class HarnessGateway extends ContextHandler implements PerformerListener {

    /** 执行机实例，用于选择服务节点、同步发送数据与开关流。 */
    final Performer performer;

    /** 全局在途请求信号量，限制同时处理的请求数量。 */
    final Semaphore inflight = new Semaphore(16);

    /** 文件上传/下载请求信号量，限制并发的文件传输数量。 */
    final Semaphore files = new Semaphore(2);

    /** SSE 事件流请求信号量，限制并发的事件流订阅数量。 */
    final Semaphore streams = new Semaphore(2);

    /** 全部在途请求状态的集合。 */
    final Set<RequestState> requests = Collections.newSetFromMap(new ConcurrentHashMap<RequestState, Boolean>());

    /** 传输 ID 到数据接收器的映射，供下载场景接收服务单元下发的流。 */
    final ConcurrentHashMap<String, Receiver> receivers = new ConcurrentHashMap<>();

    /** 普通请求的执行线程池。 */
    ThreadPoolExecutor pool;

    /** 文件上传/下载请求的执行线程池。 */
    ThreadPoolExecutor filePool;

    /** 清理任务（如重试握手失败的连接）的执行线程池。 */
    ThreadPoolExecutor cleanup;

    /** 请求截止时间的调度器。 */
    ScheduledExecutorService deadlines;

    /**
     * 把 Harness 网关安装到指定的 HTTP 服务器上。
     *
     * @param server    指定的 HTTP 服务器。
     * @param performer 指定的执行机实例。
     * @return 返回安装后的网关实例。
     */
    public static HarnessGateway install(HttpServer server, Performer performer) {
        HarnessGateway gateway = new HarnessGateway(performer);
        server.addContextHandler(gateway);
        return gateway;
    }

    /**
     * 构造函数。
     *
     * @param performer 指定的执行机实例，不允许为 null 。
     */
    public HarnessGateway(Performer performer) {
        super("/harness");
        this.performer = Objects.requireNonNull(performer, "performer");
        setHandler(new HarnessHandler(this));
    }

    /**
     * 创建固定大小的守护线程池，等待队列满时拒绝新任务。
     *
     * @param name  线程名称前缀。
     * @param size  线程数量。
     * @param queue 等待队列容量。
     * @return 返回线程池实例。
     */
    static ThreadPoolExecutor pool(String name, int size, int queue) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(queue), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 校验 Authorization 头并提取凭据。
     *
     * <p>凭据的合法性由服务单元最终判定，网关只做格式与长度检查。</p>
     *
     * @param authorization Authorization 头的值。
     * @return 返回包含凭据的数据；请求头缺失、格式或长度不符时返回 null 。
     */
    JSONObject credential(String authorization) {
        if (authorization == null || authorization.length() > 4096 || !authorization.startsWith("Bearer ") || authorization.length() < 31) {
            return null;
        }
        // 服务单元校验原始凭据并确定调用者角色
        return new JSONObject().put("credential", authorization.substring(7));
    }

    /**
     * 向服务单元发送请求并同步等待应答。
     *
     * <p>发送前后都会检查连接有效性；对于文件上传/下载动作，会在准备应答中登记
     * 传输 ID ，以便请求被取消时仍能释放远端租约。</p>
     *
     * @param state   请求状态。
     * @param action  请求对应的动作。
     * @param data    请求数据。
     * @param body    请求体字节数据，无请求体时传 null 。
     * @param timeout 同步等待应答的超时时长，单位毫秒。
     * @return 返回服务单元的应答数据。
     * @throws IOException 当连接关闭、服务单元无应答或应答超过大小上限时抛出。
     */
    JSONObject call(RequestState state, HarnessAction action, JSONObject data, byte[] body, long timeout) throws IOException {
        if (state.closed.get() || !state.connectionValid()) {
            throw new IOException("Request connection closed");
        }
        cell.core.talk.dialect.ActionDialect request = new Packet(action.name, data).toDialect();
        if (body != null) {
            request.addParam("body", body);
        }
        cell.core.talk.dialect.ActionDialect response = performer.syncTransmit(state.director, HarnessAction.CELLET, request, timeout);
        if (response == null) {
            throw new IOException("Harness unavailable");
        }
        JSONObject reply = new Packet(response).data;
        // 与 HTTP 取消竞争时，准备应答仍需要登记传输 ID 以释放远端租约
        if ((action == HarnessAction.UploadInput || action == HarnessAction.DownloadArtifact) && reply.getInt("status") < 400) {
            state.setTransfer(reply.getJSONObject("body").getString("transferId"));
        }
        if (state.closed.get() || !state.connectionValid()) {
            throw new IOException("Harness unavailable");
        }
        if (reply.toString().getBytes(StandardCharsets.UTF_8).length > HarnessAction.MAX_RESPONSE_BYTES) {
            throw new IOException("Harness response limit");
        }
        return reply;
    }

    /**
     * 构造错误应答数据。
     *
     * @param status HTTP 状态码。
     * @param code   错误码。
     * @return 返回包含状态码与错误信息的 JSON 对象。
     */
    static JSONObject error(int status, String code) {
        return new JSONObject().put("status", status).put("body", new JSONObject().put("errorCode", code)
                .put("retryable", status == 429 || status == 503).put("requestId", UUID.randomUUID().toString()));
    }

    /**
     * 把错误应答写入 HTTP 响应。
     *
     * @param response HTTP 响应对象。
     * @param status   HTTP 状态码。
     * @param code     错误码。
     * @throws IOException 当写出发送数据失败时抛出。
     */
    static void error(HttpServletResponse response, int status, String code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setContentLength(-1);
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(error(status, code).getJSONObject("body").toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 监听到服务单元数据的回调。
     *
     * <p>网关不处理该类数据，留空实现。</p>
     *
     * @param cellet    Cellet 名称。
     * @param primitive 原始数据。
     */
    @Override
    public void onReceived(String cellet, Primitive primitive) {
    }

    /**
     * 监听到服务单元流数据的回调。
     *
     * <p>按流名称找到下载接收器并交付数据；接收器不存在、来源不符或已完成时
     * 关闭该流。</p>
     *
     * @param source 数据来源。
     * @param cellet Cellet 名称。
     * @param stream 输入流。
     */
    @Override
    public void onReceivedStream(Speakable source, String cellet, PrimitiveInputStream stream) {
        Receiver receiver = receivers.get(stream.getName());
        if (receiver == null || receiver.source != source || !receiver.input.complete(stream)) {
            closeQuietly(stream);
        }
    }

    /**
     * 服务节点连接断开的回调。
     *
     * <p>把该连接上的全部在途请求置为失败。</p>
     *
     * @param source 断开的数据来源。
     */
    @Override
    public void onDisconnected(Speakable source) {
        for (RequestState state : requests) {
            if (state.source == source) {
                state.fail(503, "HARNESS_UNAVAILABLE");
            }
        }
    }

    /**
     * 安静地关闭资源，忽略关闭过程中的异常。
     *
     * <p>对于输出流，关闭前先中断，避免阻塞在未消费的数据上。</p>
     *
     * @param resource 待关闭的资源，允许为 null 。
     */
    static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                if (resource instanceof cell.core.talk.PrimitiveOutputStream) {
                    ((cell.core.talk.PrimitiveOutputStream) resource).interrupt();
                }
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 网关启动：创建线程池与调度器，注册连接监听，并定时重试握手失败的连接。
     *
     * @throws Exception 当启动底层上下文失败时抛出。
     */
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

    /**
     * 网关停止：把全部在途请求置为失败，注销监听并关闭线程池与调度器。
     *
     * @throws Exception 当停止底层上下文失败时抛出。
     */
    @Override
    protected void doStop() throws Exception {
        for (RequestState state : requests) {
            state.fail(503, "HARNESS_UNAVAILABLE");
        }
        performer.removeListener(HarnessAction.CELLET, this);
        if (deadlines != null) {
            deadlines.shutdownNow();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        if (filePool != null) {
            filePool.shutdownNow();
        }
        if (cleanup != null) {
            cleanup.shutdown();
            try {
                if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) {
                    cleanup.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanup.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        super.doStop();
    }

    /**
     * 下载数据接收器。
     *
     * <p>把服务单元下发的流交付给等待中的下载线程；关闭时以异常完成 future ，
     * 避免下载线程无限等待。</p>
     */
    static final class Receiver implements AutoCloseable {

        /** 请求数据的来源连接。 */
        final Speakable source;

        /** 待交付的输入流，由收到流数据时完成。 */
        final CompletableFuture<PrimitiveInputStream> input = new CompletableFuture<>();

        /**
         * 构造函数。
         *
         * @param source 请求数据的来源连接。
         */
        Receiver(Speakable source) {
            this.source = source;
        }

        /**
         * 关闭接收器：尚未收到流时以异常完成等待，已收到流时关闭该流。
         */
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

    /**
     * 单个请求的运行时状态。
     *
     * <p>实现 {@link AsyncListener} 跟踪异步上下文的生命周期：完成、超时、出错与
     * 再次启动异步；统一负责截止时间调度、资源登记、任务取消、传输中止与配额释放。</p>
     */
    final class RequestState implements AsyncListener {

        /** 异步上下文。 */
        final AsyncContext async;

        /** HTTP 请求对象。 */
        final HttpServletRequest request;

        /** HTTP 响应对象。 */
        final HttpServletResponse response;

        /** 请求是否已关闭（完成、失败或取消）。 */
        final AtomicBoolean closed = new AtomicBoolean();

        /** 是否为文件上传/下载请求。 */
        final boolean file;

        /** 是否为 SSE 事件流请求。 */
        final boolean sse;

        /** 请求所选中的导演机。 */
        volatile Director director;

        /** 与导演机对应的数据来源。 */
        volatile Speakable source;

        /** 数据来源的会话快照，用于检测连接是否已被重建。 */
        volatile cell.core.net.Session session;

        /** 请求的执行任务。 */
        volatile Future<?> task;

        /** 需要随请求关闭而关闭的资源。 */
        volatile AutoCloseable io;

        /** 文件传输 ID 。 */
        volatile String transferId;

        /** 凭据数据，用于传输中止等内部控制动作。 */
        volatile JSONObject auth;

        /** 请求是否已正常完成。 */
        volatile boolean completed;

        /** 下载数据的接收器。 */
        volatile Receiver receiver;

        /** 截止时间的调度句柄。 */
        ScheduledFuture<?> deadline;

        /** SSE 请求的硬性截止时间，单位纳秒。 */
        final long sseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        /**
         * 绑定请求所选中的连接，并记录会话快照。
         *
         * @param selected 选中的导演机，允许为 null 。
         */
        void selectConnection(Director selected) {
            director = selected;
            source = selected == null ? null : selected.speaker;
            session = source instanceof cell.core.talk.Speaker ? ((cell.core.talk.Speaker) source).getSession() : null;
        }

        /**
         * 检查连接是否仍然有效。
         *
         * <p>来源就绪、导演机仍在使用该来源，且会话未发生变更时视为有效。</p>
         *
         * @return 连接有效返回 <code>true</code> ，否则返回 <code>false</code> 。
         */
        boolean connectionValid() {
            return source != null && source.isReady() && director.speaker == source
                    && (!(source instanceof cell.core.talk.Speaker) || ((cell.core.talk.Speaker) source).getSession() == session);
        }

        /**
         * 重新调度请求的截止时间。
         *
         * <p>SSE 请求的截止时间不会超过其硬性截止时间。</p>
         *
         * @param millis 截止时长，单位毫秒。
         */
        synchronized void timeout(long millis) {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (sse) {
                millis = Math.min(millis, TimeUnit.NANOSECONDS.toMillis(sseDeadline - System.nanoTime()));
            }
            if (!closed.get()) {
                deadline = deadlines.schedule(() -> onTimeout(null), Math.max(1, millis), TimeUnit.MILLISECONDS);
            }
        }

        /**
         * 登记需要随请求关闭而关闭的资源；请求已关闭时立即关闭该资源。
         *
         * @param resource 待登记的资源。
         */
        synchronized void track(AutoCloseable resource) {
            if (closed.get()) {
                closeQuietly(resource);
            }
            else {
                io = resource;
            }
        }

        /**
         * 登记请求的执行任务；请求已关闭且未完成时取消该任务。
         *
         * @param future 执行任务的 future 。
         */
        synchronized void setTask(Future<?> future) {
            task = future;
            if (closed.get() && !completed) {
                future.cancel(true);
            }
        }

        /**
         * 登记文件传输 ID ；请求已关闭时立即中止该传输。
         *
         * @param id 文件传输 ID 。
         */
        synchronized void setTransfer(String id) {
            transferId = id;
            if (closed.get()) {
                abortTransfer();
            }
        }

        /**
         * 为当前传输登记下载接收器。
         *
         * @return 返回登记成功的接收器。
         * @throws IOException 当请求已关闭、连接已变更或传输 ID 重复登记时抛出。
         */
        synchronized Receiver registerReceiver() throws IOException {
            if (closed.get()) {
                throw new IOException("Request closed");
            }
            if (!connectionValid()) {
                throw new IOException("Connection changed");
            }
            Receiver next = new Receiver(source);
            if (receivers.putIfAbsent(transferId, next) != null) {
                throw new IOException("Duplicate transfer");
            }
            receiver = next;
            return next;
        }

        /**
         * 构造函数。
         *
         * @param request  HTTP 请求对象。
         * @param response HTTP 响应对象。
         * @param file     是否为文件上传/下载请求。
         * @param sse      是否为 SSE 事件流请求。
         */
        RequestState(HttpServletRequest request, HttpServletResponse response, boolean file, boolean sse) {
            this.request = request;
            this.response = response;
            this.file = file;
            this.sse = sse;
            async = request.startAsync();
            // Jetty 在 setTimeout 改变时不会重新调度已开启的异步超时
            async.setTimeout(0);
            async.addListener(this);
            requests.add(this);
            timeout(10000);
        }

        /**
         * 标记请求正常完成并结束异步上下文。
         */
        void complete() {
            completed = true;
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
            }
        }

        /**
         * 以指定的错误码结束请求。
         *
         * <p>响应尚未提交时直接写入错误应答；已提交时中止底层连接。</p>
         *
         * @param status HTTP 状态码。
         * @param code   错误码。
         */
        void fail(int status, String code) {
            if (closed.get()) {
                return;
            }
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

        /**
         * 向服务单元发送传输中止指令，释放远端租约。
         *
         * <p>在清理线程上异步执行，不阻塞请求的关闭流程。</p>
         */
        private synchronized void abortTransfer() {
            if (transferId != null && director != null && auth != null && cleanup != null) {
                final JSONObject data = new JSONObject(auth.toString()).put("transferId", transferId);
                try {
                    cleanup.execute(() -> {
                        if (!connectionValid()) {
                            return;
                        }
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

        /**
         * 结束请求：注销状态、取消任务与调度、关闭资源、中止传输并释放配额。
         *
         * <p>重复调用时仅首次生效。</p>
         */
        private synchronized void done() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            requests.remove(this);
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (!completed && task != null) {
                task.cancel(true);
            }
            closeQuietly(io);
            if (receiver != null) {
                receivers.remove(transferId, receiver);
                closeQuietly(receiver);
            }
            abortTransfer();
            inflight.release();
            if (file) {
                files.release();
            }
            if (sse) {
                streams.release();
            }
        }

        /**
         * 异步上下文正常完成的回调：结束请求状态。
         *
         * @param event 异步事件。
         */
        @Override
        public void onComplete(AsyncEvent event) {
            done();
        }

        /**
         * 异步上下文超时的回调：以 503 结束请求。
         *
         * @param event 异步事件。
         */
        @Override
        public void onTimeout(AsyncEvent event) {
            completed = false;
            fail(503, "HARNESS_TIMEOUT");
            done();
        }

        /**
         * 异步上下文出错的回调：结束请求状态并尝试完成异步上下文。
         *
         * @param event 异步事件。
         */
        @Override
        public void onError(AsyncEvent event) {
            completed = false;
            done();
            complete();
        }

        /**
         * 异步上下文再次启动异步的回调：无需处理。
         *
         * @param event 异步事件。
         */
        @Override
        public void onStartAsync(AsyncEvent event) {
        }
    }
}
