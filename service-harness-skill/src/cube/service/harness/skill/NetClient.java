/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import cube.service.harness.store.StoreException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * 有界的一次性 HTTP 客户端。
 *
 * <p>公网访问先解析一次并逐个校验地址，再按 IP 直接连接；不跟随重定向，
 * 响应体有大小上限，全过程受截止时间与取消标记约束。</p>
 */
class NetClient implements AutoCloseable {
    /**
     * 域名解析器，便于测试替换。
     */
    interface Resolver {
        /**
         * 解析域名。
         *
         * @param host 域名。
         * @return 返回解析出的地址列表。
         * @throws Exception 解析过程中的异常。
         */
        InetAddress[] resolve(String host) throws Exception;
    }

    /**
     * HTTP 响应。
     */
    static final class Response {
        /**
         * 状态码。
         */
        final int status;
        /**
         * 响应头，键统一为小写。
         */
        final Map<String, String> headers;
        /**
         * 响应体字节。
         */
        final byte[] body;

        /**
         * 构造函数。
         *
         * @param status  状态码。
         * @param headers 响应头。
         * @param body    响应体字节。
         */
        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        /**
         * 按名称读取响应头。
         *
         * @param name 响应头名称，忽略大小写。
         * @return 返回响应头值，不存在时返回空串。
         */
        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }

        /**
         * 返回去掉参数的媒体类型。
         *
         * @return 返回小写的媒体类型。
         */
        String media() {
            return header("content-type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        }

        /**
         * 按响应头声明的字符集解码响应体。
         *
         * @return 返回解码后的文本。
         */
        String text() {
            Charset charset = StandardCharsets.UTF_8;
            for (String part : header("content-type").split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        charset = Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (IllegalArgumentException e) {
                        throw new StoreException("WEB_CHARSET_UNSUPPORTED");
                    }
                }
            }
            try {
                return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            } catch (CharacterCodingException e) {
                throw new StoreException("WEB_TEXT_INVALID");
            }
        }
    }

    /**
     * 域名解析器。
     */
    private final Resolver resolver;
    /**
     * TLS 上下文。
     */
    private final SSLContext tls;
    /**
     * 网络事件循环线程组。
     */
    private final EventLoopGroup eventLoop = new NioEventLoopGroup(1, new DefaultThreadFactory("skill-http", true));
    /**
     * DNS 解析线程池，队列有界，满了直接拒绝。
     */
    private final ExecutorService dns = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8), new DefaultThreadFactory("skill-dns", true),
            new ThreadPoolExecutor.AbortPolicy());
    /**
     * 尚未关闭的连接，用于统一释放。
     */
    private final Set<Channel> channels = Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());

    /**
     * 构造函数：使用系统解析器与默认 TLS 上下文。
     */
    NetClient() {
        this(InetAddress::getAllByName, defaultTls());
    }

    /**
     * 构造函数。
     *
     * @param resolver 域名解析器。
     * @param tls      TLS 上下文。
     */
    NetClient(Resolver resolver, SSLContext tls) {
        this.resolver = resolver;
        this.tls = tls;
    }

    /**
     * 取得默认 TLS 上下文。
     *
     * @return 返回默认 TLS 上下文。
     */
    private static SSLContext defaultTls() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("TLS_UNAVAILABLE", e);
        }
    }

    /**
     * 校验 URL 是否符合策略：协议、主机、端口、长度与换行都受限。
     *
     * @param value      URL 文本。
     * @param publicOnly 是否只允许公网地址。
     * @param hosts      允许访问的主机名单，为空表示不限制。
     * @return 返回规范化后的 URI 。
     */
    static URI uri(String value, boolean publicOnly, Set<String> hosts) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || publicOnly && host.indexOf(':') >= 0
                    || publicOnly && uri.toASCIIString().length() > 2048
                    || value.matches("(?s).*[\\\\\\r\\n].*")
                    || !("https".equals(uri.getScheme()) || !publicOnly && "http".equals(uri.getScheme()))
                    || publicOnly && uri.getPort() != -1 && uri.getPort() != 443
                    || !hosts.isEmpty() && !hosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            return new URI(uri.toASCIIString());
        } catch (Exception e) {
            throw new StoreException("WEB_URL_FORBIDDEN");
        }
    }

    /**
     * 判断地址是否为可用的公网 IPv4 地址。
     *
     * @param address 待判断的地址。
     * @return 返回 true 表示允许访问。
     */
    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        // 第一版刻意拒绝 IPv6、映射地址、过渡地址与全部特殊用途 IPv4 网段。
        if (b.length != 4) {
            return false;
        }
        int a = b[0] & 255;
        int n = b[1] & 255;
        int c = b[2] & 255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224
                || a == 100 && n >= 64 && n <= 127 || a == 169 && n == 254
                || a == 172 && n >= 16 && n <= 31 || a == 192 && n == 168
                || a == 192 && n == 0 && (c == 0 || c == 2)
                || a == 192 && n == 88 && c == 99 || a == 198 && (n == 18 || n == 19)
                || a == 198 && n == 51 && c == 100 || a == 203 && n == 0 && c == 113
                || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress());
    }

    /**
     * 发起一次 GET 请求。
     *
     * @param uri        请求地址。
     * @param publicOnly 是否只允许公网地址。
     * @param hosts      允许访问的主机名单。
     * @param headers    附加请求头。
     * @param deadline   截止时间。
     * @param maxBytes   响应体字节上限。
     * @param cancelled  取消标记。
     * @return 返回 HTTP 响应。
     */
    Response get(URI uri, boolean publicOnly, Set<String> hosts, Map<String, String> headers,
                 long deadline, int maxBytes, BooleanSupplier cancelled) {
        uri(uri.toString(), publicOnly, hosts);
        Future<InetAddress[]> lookup;
        try {
            lookup = dns.submit(() -> resolver.resolve(uri.getHost()));
        } catch (RejectedExecutionException e) {
            throw new StoreException("WEB_BUSY");
        }
        InetAddress[] addresses;
        try {
            addresses = await(lookup, deadline, cancelled);
        } finally {
            lookup.cancel(true);
        }
        if (addresses.length == 0) {
            throw new StoreException("WEB_DNS_INVALID");
        }
        for (InetAddress address : addresses) {
            if (publicOnly && !publicAddress(address)) {
                throw new StoreException("WEB_DNS_FORBIDDEN");
            }
        }
        return connect(uri, addresses[0], headers, deadline, maxBytes, cancelled);
    }

    /**
     * 按已解析的地址建立连接并完成一次请求。
     *
     * @param uri       请求地址。
     * @param address   已校验的目标地址。
     * @param headers   附加请求头。
     * @param deadline  截止时间。
     * @param maxBytes  响应体字节上限。
     * @param cancelled 取消标记。
     * @return 返回 HTTP 响应。
     */
    private Response connect(URI uri, InetAddress address, Map<String, String> headers,
                             long deadline, int maxBytes, BooleanSupplier cancelled) {
        CompletableFuture<Response> response = new CompletableFuture<>();
        int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        Bootstrap bootstrap = new Bootstrap().group(eventLoop).channel(NioSocketChannel.class)
                .resolver(NoopAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, remaining(deadline))
                .handler(new ChannelInitializer<SocketChannel>() {
                    protected void initChannel(SocketChannel channel) {
                        if ("https".equals(uri.getScheme())) {
                            SSLEngine engine = tls.createSSLEngine(uri.getHost(), port);
                            engine.setUseClientMode(true);
                            SSLParameters p = engine.getSSLParameters();
                            p.setEndpointIdentificationAlgorithm("HTTPS");
                            if (!uri.getHost().matches("[0-9.]+")) {
                                p.setServerNames(Collections.singletonList(new SNIHostName(uri.getHost())));
                            }
                            engine.setSSLParameters(p);
                            SslHandler ssl = new SslHandler(engine);
                            ssl.setHandshakeTimeoutMillis(remaining(deadline));
                            channel.pipeline().addLast(ssl);
                        }
                        channel.pipeline().addLast(new HttpClientCodec(), new HttpContentDecompressor(),
                                new HttpObjectAggregator(maxBytes), new SimpleChannelInboundHandler<FullHttpResponse>() {
                                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse message) {
                                        if (!message.decoderResult().isSuccess()) {
                                            response.completeExceptionally(new StoreException("WEB_RESPONSE_INVALID"));
                                        } else {
                                            byte[] bytes = new byte[message.content().readableBytes()];
                                            message.content().readBytes(bytes);
                                            Map<String, String> meta = new HashMap<>();
                                            for (Map.Entry<String, String> h : message.headers()) {
                                                meta.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue());
                                            }
                                            response.complete(new Response(message.status().code(), meta, bytes));
                                        }
                                        ctx.close();
                                    }

                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                                        response.completeExceptionally(new StoreException(
                                                error instanceof io.netty.handler.codec.TooLongFrameException
                                                        ? "WEB_RESPONSE_LIMIT" : "WEB_CONNECTION_FAILED"));
                                        ctx.close();
                                    }

                                    public void channelInactive(ChannelHandlerContext ctx) {
                                        response.completeExceptionally(new StoreException("WEB_RESPONSE_INCOMPLETE"));
                                    }
                                });
                    }
                });
        ChannelFuture connection = bootstrap.connect(new InetSocketAddress(address, port));
        Channel channel = connection.channel();
        channels.add(channel);
        connection.addListener(future -> {
            if (!future.isSuccess()) {
                response.completeExceptionally(new StoreException("WEB_CONNECTION_FAILED"));
                return;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    path, Unpooled.EMPTY_BUFFER);
            request.headers().set(HttpHeaderNames.HOST, uri.getHost() + (uri.getPort() == -1 ? "" : ":" + port));
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.IDENTITY);
            request.headers().set(HttpHeaderNames.USER_AGENT, "Cube-Harness-Skill/3.0");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                request.headers().set(h.getKey(), h.getValue());
            }
            channel.writeAndFlush(request).addListener(f -> {
                if (!f.isSuccess()) {
                    response.completeExceptionally(new StoreException("WEB_CONNECTION_FAILED"));
                }
            });
        });
        try {
            return await(response, deadline, cancelled);
        } finally {
            // 取消只关闭本次调用自己的套接字，绝不误伤其它调用方的连接。
            channel.close().awaitUninterruptibly(200);
            channels.remove(channel);
        }
    }

    /**
     * 计算截止时间点。
     *
     * @param millis 有效时长，单位毫秒。
     * @return 返回基于单调时钟的截止时间点。
     */
    static long deadline(int millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /**
     * 计算距离截止时间还剩多少毫秒。
     *
     * @param deadline 截止时间点。
     * @return 返回剩余毫秒数。
     */
    private static int remaining(long deadline) {
        long value = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (value <= 0) {
            throw new StoreException("WEB_TIMEOUT");
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    /**
     * 等待任务完成，期间持续检查取消标记与总截止时间。
     *
     * @param future    待等待的任务。
     * @param deadline  截止时间点。
     * @param cancelled 取消标记。
     * @param <T>       结果类型。
     * @return 返回任务结果。
     */
    private static <T> T await(Future<T> future, long deadline, BooleanSupplier cancelled) {
        while (true) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                throw new StoreException("TOOL_CANCELLED");
            }
            try {
                return future.get(Math.min(50, remaining(deadline)), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // 继续检查取消标记与总截止时间。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException("TOOL_CANCELLED");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof StoreException) {
                    throw (StoreException) e.getCause();
                }
                throw new StoreException("WEB_CONNECTION_FAILED");
            }
        }
    }

    /**
     * 关闭全部连接、解析线程池与事件循环。
     */
    public void close() {
        for (Channel channel : channels) {
            channel.close();
        }
        dns.shutdownNow();
        eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(1500);
    }
}
