/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.harness.execution;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行监听器的 JDK 8 HTTP 门面：底层为 Jetty，对外呈现 com.sun.net.httpserver.HttpExchange。
 *
 * <p>SSE 使用 Servlet 3.1 非阻塞写入（startAsync + WriteListener），有限回放页一次性写出、
 * 2 秒异步超时；每次回放页的并发连接数由 ExecutionRuntime 的信号量限制（2）。
 * 仅实现执行监听所需的方法，getHttpContext/setStreams 明确不支持，getPrincipal 返回 null。
 */
public final class JettyExchange extends HttpExchange {

    /**
     * 执行监听器回调：每个交换一个实例，仅在该线程内处理请求。
     */
    public interface Handler {
        /**
         * 处理一次 HTTP 交换。
         *
         * @param exchange 已包装请求/响应流的交换对象
         * @throws IOException 读写失败
         */
        void handle(HttpExchange exchange) throws IOException;
    }

    /**
     * 底层 servlet 请求（请求头快照、异步上下文与属性的来源）。
     */
    private final HttpServletRequest request;

    /**
     * 底层 servlet 响应（状态码、内容类型与输出流的去向）。
     */
    private final HttpServletResponse response;

    /**
     * 请求头快照，构造时从 servlet 请求拷贝。
     */
    private final Headers inputHeaders = new Headers();

    /**
     * 响应头暂存对象；sendResponseHeaders 或 stream 时刷入底层响应。
     */
    private final Headers outputHeaders = new Headers();

    /**
     * 请求体输入流（request.getInputStream()）。
     */
    private final InputStream input;

    /**
     * 响应体输出流（response.getOutputStream()），SSE 非阻塞写入使用。
     */
    private final ServletOutputStream output;

    /**
     * 包装 servlet 请求/响应并拷贝请求头。
     *
     * @param request  Jetty 传入的 servlet 请求
     * @param response Jetty 传入的 servlet 响应
     * @throws IOException 获取请求体/响应流失败
     */
    private JettyExchange(HttpServletRequest request, HttpServletResponse response) throws IOException {
        this.request = request;
        this.response = response;
        input = request.getInputStream();
        output = response.getOutputStream();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            inputHeaders.put(name, Collections.list(request.getHeaders(name)));
        }
    }

    /**
     * 在给定地址启动仅含执行上下文的 Jetty 服务器并返回已启动实例。
     *
     * <p>线程池 16/4、60s 空闲保持、有界队列 64；连接器 1 个 acceptor/selector，
     * 空闲超时 5s；停止超时 2s。所有请求委托给 handler，由本类包装交换对象。
     *
     * @param host    监听地址
     * @param port    监听端口
     * @param handler 交换回调
     * @return 已启动的 Server（调用方负责 stop）
     * @throws Exception 端口占用或服务器启动失败
     */
    public static Server start(String host, int port, Handler handler) throws Exception {
        QueuedThreadPool threads = new QueuedThreadPool(16, 4, 60000, new ArrayBlockingQueue<Runnable>(64));
        threads.setName("harness-http");
        threads.setDaemon(true);
        Server server = new Server(threads);
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setHost(host);
        connector.setPort(port);
        connector.setIdleTimeout(5000);
        server.addConnector(connector);
        ContextHandler context = new ContextHandler("/");
        context.setHandler(new AbstractHandler() {
            /**
             * 接管全部请求并包装为 JettyExchange 交给回调。
             *
             * @param target 请求目标路径
             * @param base   Jetty 原始请求（标记为已处理）
             * @param req    servlet 请求
             * @param res    servlet 响应
             * @throws IOException 回调处理失败
             */
            public void handle(String target, Request base, HttpServletRequest req, HttpServletResponse res) throws IOException {
                base.setHandled(true);
                handler.handle(new JettyExchange(req, res));
            }
        });
        server.setHandler(context);
        server.setStopTimeout(2000);
        server.start();
        return server;
    }

    /**
     * 把缓存的响应头刷入 servlet 响应（addHeader 追加，不覆盖既有头）。
     */
    private void headers() {
        for (Map.Entry<String, List<String>> e : outputHeaders.entrySet()) {
            for (String v : e.getValue()) {
                response.addHeader(e.getKey(), v);
            }
        }
    }

    /**
     * 以 SSE 有限回放页写出事件列表：先 retry 提示，再逐事件帧，最后注释结束行。
     *
     * <p>帧格式为 id/event/data 三行加空行；累积超过 8MiB 明确拒绝
     * （EVENT_PAGE_TOO_LARGE），不静默截断。异步上下文 2 秒超时，写入完成、超时、出错
     * 或完成时经 {@code done} 保证 release 只执行一次；客户端按完整收到的 Last-Event-ID 重连。
     *
     * @param page    含 events 数组的页面对象（每项含 eventSeq、type 及 data 字段集）
     * @param release 回放页结束（完成/超时/出错）后释放连接配额的回调
     * @throws IOException              非阻塞写入失败
     * @throws IllegalArgumentException 页面超过 8MiB（EVENT_PAGE_TOO_LARGE）
     */
    public void stream(JSONObject page, Runnable release) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write("retry: 500\n\n".getBytes(StandardCharsets.UTF_8));
        JSONArray list = page.getJSONArray("events");
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            byte[] frame = ("id: " + e.getLong("eventSeq") + "\nevent: " + e.getString("type") + "\ndata: " + e.toString() + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (data.size() + frame.length > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("EVENT_PAGE_TOO_LARGE");
            }
            data.write(frame);
        }
        data.write(": replay-page-complete\n\n".getBytes(StandardCharsets.UTF_8));
        byte[] bytes = data.toByteArray();
        response.setStatus(200);
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
        AsyncContext async = request.startAsync();
        async.setTimeout(2000);
        AtomicBoolean released = new AtomicBoolean();
        Runnable done = () -> {
            if (released.compareAndSet(false, true)) {
                release.run();
            }
        };
        async.addListener(new AsyncListener() {
            /**
             * 异步处理完成：释放连接配额。
             *
             * @param e 异步事件
             */
            public void onComplete(AsyncEvent e) {
                done.run();
            }

            /**
             * 异步超时（2s）：释放配额并尝试完成异步。
             *
             * @param e 异步事件
             */
            public void onTimeout(AsyncEvent e) {
                done.run();
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                    // 异步已完成时重复 complete 属正常竞态，忽略。
                }
            }

            /**
             * 异步出错：释放配额并尝试完成异步。
             *
             * @param e 异步事件
             */
            public void onError(AsyncEvent e) {
                done.run();
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                    // 异步已完成时重复 complete 属正常竞态，忽略。
                }
            }

            /**
             * 重新开始异步：无操作。
             *
             * @param e 异步事件
             */
            public void onStartAsync(AsyncEvent e) {
            }
        });
        output.setWriteListener(new WriteListener() {
            /** 已写出的字节数，随 onWritePossible 单调推进。 */
            int offset;

            /**
             * 非阻塞写入：可写时按 4096 字节分块写出，写完后完成异步。
             *
             * @throws IOException 写入失败
             */
            public void onWritePossible() throws IOException {
                while (output.isReady() && offset < bytes.length) {
                    int n = Math.min(4096, bytes.length - offset);
                    output.write(bytes, offset, n);
                    offset += n;
                }
                if (offset == bytes.length) {
                    async.complete();
                }
            }

            /**
             * 写入出错：释放配额并尝试完成异步。
             *
             * @param e 失败原因
             */
            public void onError(Throwable e) {
                done.run();
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                    // 异步已完成时重复 complete 属正常竞态，忽略。
                }
            }
        });
    }

    /**
     * 返回请求头快照。
     *
     * @return 构造时拷贝的请求头
     */
    @Override
    public Headers getRequestHeaders() {
        return inputHeaders;
    }

    /**
     * 返回响应头暂存对象；sendResponseHeaders 或 stream 时刷入底层响应。
     *
     * @return 响应头暂存对象
     */
    @Override
    public Headers getResponseHeaders() {
        return outputHeaders;
    }

    /**
     * 返回含查询串的请求 URI。
     *
     * @return 请求 URI（无查询串时不含 "?"）
     */
    @Override
    public URI getRequestURI() {
        return URI.create(request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
    }

    /**
     * 返回请求方法。
     *
     * @return HTTP 方法名
     */
    @Override
    public String getRequestMethod() {
        return request.getMethod();
    }

    /**
     * 不支持：本门面不暴露 HttpContext。
     *
     * @return 从不返回
     * @throws UnsupportedOperationException 总是抛出
     */
    @Override
    public HttpContext getHttpContext() {
        throw new UnsupportedOperationException();
    }

    /**
     * 关闭交换：异步已开始时完成异步上下文，否则关闭输出流。
     */
    @Override
    public void close() {
        try {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().complete();
            } else {
                output.close();
            }
        } catch (IOException | IllegalStateException ignored) {
            // 关闭竞态（异步已完成或流已关闭）时忽略。
        }
    }

    /**
     * 返回请求体输入流。
     *
     * @return 请求体流
     */
    @Override
    public InputStream getRequestBody() {
        return input;
    }

    /**
     * 返回响应体输出流。
     *
     * @return 响应体流
     */
    @Override
    public OutputStream getResponseBody() {
        return output;
    }

    /**
     * 提交响应头与状态码；length&gt;0 时设置 Content-Length。
     *
     * @param status HTTP 状态码
     * @param length 响应体长度；≤0 表示未知长度（分块/流式）
     */
    @Override
    public void sendResponseHeaders(int status, long length) {
        headers();
        response.setStatus(status);
        if (length > 0) {
            response.setContentLengthLong(length);
        }
    }

    /**
     * 返回远端地址。
     *
     * @return 客户端地址
     */
    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort());
    }

    /**
     * 返回本端地址。
     *
     * @return 服务器本地地址
     */
    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(request.getLocalAddr(), request.getLocalPort());
    }

    /**
     * 返回协议版本。
     *
     * @return 如 HTTP/1.1
     */
    @Override
    public String getProtocol() {
        return request.getProtocol();
    }

    /**
     * 返回响应状态码。
     *
     * @return 当前响应状态码
     */
    @Override
    public int getResponseCode() {
        return response.getStatus();
    }

    /**
     * 返回请求属性。
     *
     * @param name 属性名
     * @return 属性值；不存在时为 null
     */
    @Override
    public Object getAttribute(String name) {
        return request.getAttribute(name);
    }

    /**
     * 设置请求属性。
     *
     * @param name  属性名
     * @param value 属性值
     */
    @Override
    public void setAttribute(String name, Object value) {
        request.setAttribute(name, value);
    }

    /**
     * 不支持：流在构造时绑定，不能替换。
     *
     * @param in  忽略
     * @param out 忽略
     * @throws UnsupportedOperationException 总是抛出
     */
    @Override
    public void setStreams(InputStream in, OutputStream out) {
        throw new UnsupportedOperationException();
    }

    /**
     * 不支持认证主体：身份由上游 Cube Auth 校验，本门面不解析。
     *
     * @return 总是 null
     */
    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }
}
