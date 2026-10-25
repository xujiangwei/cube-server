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
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;

/** Bounded one-shot HTTP. Public fetches resolve once, validate every address, then connect by IP. */
class NetClient implements AutoCloseable {
    interface Resolver { InetAddress[] resolve(String host) throws Exception; }
    static final class Response {
        final int status;
        final Map<String, String> headers;
        final byte[] body;
        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status; this.headers = headers; this.body = body;
        }
        String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
        String media() { return header("content-type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT); }
        String text() {
            Charset charset = StandardCharsets.UTF_8;
            for (String part : header("content-type").split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try { charset = Charset.forName(p.substring(8).replace("\"", "").trim()); }
                    catch (IllegalArgumentException e) { throw new StoreException("WEB_CHARSET_UNSUPPORTED"); }
                }
            }
            try {
                return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            } catch (CharacterCodingException e) { throw new StoreException("WEB_TEXT_INVALID"); }
        }
    }

    private final Resolver resolver;
    private final SSLContext tls;
    private final EventLoopGroup eventLoop = new NioEventLoopGroup(1, new DefaultThreadFactory("skill-http", true));
    private final ExecutorService dns = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8), new DefaultThreadFactory("skill-dns", true),
            new ThreadPoolExecutor.AbortPolicy());
    private final Set<Channel> channels = Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());

    NetClient() { this(InetAddress::getAllByName, defaultTls()); }
    NetClient(Resolver resolver, SSLContext tls) { this.resolver = resolver; this.tls = tls; }
    private static SSLContext defaultTls() {
        try { return SSLContext.getDefault(); }
        catch (Exception e) { throw new IllegalStateException("TLS_UNAVAILABLE", e); }
    }

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
                    || !hosts.isEmpty() && !hosts.contains(host.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException();
            return new URI(uri.toASCIIString());
        } catch (Exception e) { throw new StoreException("WEB_URL_FORBIDDEN"); }
    }

    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        // v1 deliberately refuses IPv6, mapped/transition addresses and all special IPv4 networks.
        if (b.length != 4) return false;
        int a = b[0] & 255, n = b[1] & 255, c = b[2] & 255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224
                || a == 100 && n >= 64 && n <= 127 || a == 169 && n == 254
                || a == 172 && n >= 16 && n <= 31 || a == 192 && n == 168
                || a == 192 && n == 0 && (c == 0 || c == 2)
                || a == 192 && n == 88 && c == 99 || a == 198 && (n == 18 || n == 19)
                || a == 198 && n == 51 && c == 100 || a == 203 && n == 0 && c == 113
                || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress());
    }

    Response get(URI uri, boolean publicOnly, Set<String> hosts, Map<String, String> headers,
                 long deadline, int maxBytes, BooleanSupplier cancelled) {
        uri(uri.toString(), publicOnly, hosts);
        Future<InetAddress[]> lookup;
        try { lookup = dns.submit(() -> resolver.resolve(uri.getHost())); }
        catch (RejectedExecutionException e) { throw new StoreException("WEB_BUSY"); }
        InetAddress[] addresses;
        try { addresses = await(lookup, deadline, cancelled); }
        finally { lookup.cancel(true); }
        if (addresses.length == 0) throw new StoreException("WEB_DNS_INVALID");
        for (InetAddress address : addresses)
            if (publicOnly && !publicAddress(address)) throw new StoreException("WEB_DNS_FORBIDDEN");
        return connect(uri, addresses[0], headers, deadline, maxBytes, cancelled);
    }

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
                            if (!uri.getHost().matches("[0-9.]+"))
                                p.setServerNames(Collections.singletonList(new SNIHostName(uri.getHost())));
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
                                    for (Map.Entry<String, String> h : message.headers())
                                        meta.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue());
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
                response.completeExceptionally(new StoreException("WEB_CONNECTION_FAILED")); return;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    path, Unpooled.EMPTY_BUFFER);
            request.headers().set(HttpHeaderNames.HOST, uri.getHost() + (uri.getPort() == -1 ? "" : ":" + port));
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.IDENTITY);
            request.headers().set(HttpHeaderNames.USER_AGENT, "Cube-Harness-Skill/3.0");
            for (Map.Entry<String, String> h : headers.entrySet()) request.headers().set(h.getKey(), h.getValue());
            channel.writeAndFlush(request).addListener(f -> {
                if (!f.isSuccess()) response.completeExceptionally(new StoreException("WEB_CONNECTION_FAILED"));
            });
        });
        try { return await(response, deadline, cancelled); }
        finally {
            // Cancellation closes this invocation's socket, never another caller's channel.
            channel.close().awaitUninterruptibly(200);
            channels.remove(channel);
        }
    }

    static long deadline(int millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private static int remaining(long deadline) {
        long value = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (value <= 0) throw new StoreException("WEB_TIMEOUT");
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
    private static <T> T await(Future<T> future, long deadline, BooleanSupplier cancelled) {
        while (true) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw new StoreException("TOOL_CANCELLED");
            try { return future.get(Math.min(50, remaining(deadline)), TimeUnit.MILLISECONDS); }
            catch (TimeoutException ignored) { /* Check cancellation and the total deadline again. */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new StoreException("TOOL_CANCELLED"); }
            catch (ExecutionException e) {
                if (e.getCause() instanceof StoreException) throw (StoreException) e.getCause();
                throw new StoreException("WEB_CONNECTION_FAILED");
            }
        }
    }
    public void close() {
        for (Channel channel : channels) channel.close();
        dns.shutdownNow();
        eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(1500);
    }
}
