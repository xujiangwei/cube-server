package cube.dispatcher.harness;

import cell.api.*;
import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.action.HarnessAction;
import cube.dispatcher.*;
import cube.util.HttpServer;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.Server;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Harness REST 传输层的进程内集成测试。
 *
 * <p>以真实 Jetty 装载 {@link HarnessGateway}，并由确定性可控的后端
 * {@link Backend} 模拟 Performer 的失败、阻塞与流事件，覆盖：</p>
 *
 * <ul>
 *   <li>导演机选择：仅 Harness scope 且连接就绪的最高权重节点，全局高权重的无关服务不抢选，未就绪时不选；</li>
 *   <li>端点无条件安装：无任何配置时仍挂载，上下文路径为 /harness ；</li>
 *   <li>凭据透传：Bearer 原样交给服务单元，dispatcher 不自报角色；</li>
 *   <li>有界准入：在途配额占满期间返回 503 ，放行后配额回收；</li>
 *   <li>并发应答：16 个并发请求的应答与请求一一对应，未交叉错配；</li>
 *   <li>流归属：来源连接不符或未登记的下载流被直接关闭；</li>
 *   <li>SSE 配额：每网关两个槽位，第三个返回 429 ，超时（503）后槽位如期释放。</li>
 * </ul>
 */
public final class HarnessGatewayTest {

    /**
     * 断言辅助方法：条件不成立时以指定消息抛出 {@link AssertionError}。
     *
     * @param value   断言条件。
     * @param message 断言失败时输出的消息。
     */
    static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    /** 测试专用 Bearer 凭据；后端断言其被原样透传、未被网关改写。 */
    static final String TOKEN = "harness-api-fixture-123456789";

    /**
     * 测试用导演机 scope 。
     *
     * <p>仅包含指定 cellet 并设置权重，用于验证按 cellet 的导演机选择。</p>
     */
    static class TestScope extends Scope {

        /**
         * 构造函数。
         *
         * @param cellet 加入 scope 的 cellet 名称。
         * @param value  该 scope 的权重。
         */
        TestScope(String cellet, int value) {
            cellets.add(cellet);
            weight = value;
        }
    }

    /**
     * 测试用 {@link Speakable} 桩。
     *
     * <p>各 speak 方法均直接返回成功；{@link #ready} 可关闭以模拟连接未就绪。</p>
     */
    static class Speaker implements Speakable {

        /** 连接是否就绪；测试通过翻转该值验证就绪过滤。 */
        boolean ready = true;

        /**
         * 返回远端地址。
         *
         * @return 返回固定的 127.0.0.1:1 地址。
         */
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 1);
        }

        /**
         * 返回连接是否就绪。
         *
         * @return 就绪返回 <code>true</code> ，否则返回 <code>false</code> 。
         */
        public boolean isReady() {
            return ready;
        }

        /**
         * 模拟发送数据，恒成功。
         *
         * @param c cellet 名称。
         * @param p 原始数据。
         * @return 恒返回 <code>true</code> 。
         */
        public boolean speak(String c, Primitive p) {
            return true;
        }

        /**
         * 模拟发送数据，恒成功。
         *
         * @param c cellet 名称。
         * @param p 原始数据。
         * @param f 附带标志位。
         * @return 恒返回 <code>true</code> 。
         */
        public boolean speak(String c, Primitive p, boolean f) {
            return true;
        }

        /**
         * 模拟发送并等待确认，恒成功。
         *
         * @param c cellet 名称。
         * @param p 原始数据。
         * @return 恒返回 <code>true</code> 。
         */
        public boolean speakWithAck(String c, Primitive p) {
            return true;
        }

        /**
         * 模拟发送且不等待确认，恒成功。
         *
         * @param c cellet 名称。
         * @param p 原始数据。
         * @return 恒返回 <code>true</code> 。
         */
        public boolean speakWithoutAck(String c, Primitive p) {
            return true;
        }

        /**
         * 模拟打开输出流；桩实现恒返回 null 。
         *
         * @param c cellet 名称。
         * @param n 流名称。
         * @return 恒返回 <code>null</code> 。
         */
        public PrimitiveOutputStream speakStream(String c, String n) {
            return null;
        }
    }

    /**
     * 测试用输入流桩。
     *
     * <p>记录 {@link #close()} 是否被调用，用于验证流被及时关闭、无泄漏。</p>
     */
    static class Input extends PrimitiveInputStream {

        /** 流是否已被关闭。 */
        boolean closed;

        /**
         * 构造函数。
         *
         * @param name 流名称，对应传输 ID 。
         */
        Input(String name) {
            super(name, null);
        }

        /**
         * 关闭流：仅记录关闭标记，不执行实际 IO 。
         */
        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * 确定性可控的后端 Performer 。
     *
     * <p>注册两个导演机：scope 仅含 Harness 的 {@link #harness}（权重 2）与
     * scope 为 AIGC 的 {@link #unrelated}（权重 10），验证按 cellet 的选择
     * 不受全局权重影响。</p>
     *
     * <p>{@link #available} 模拟服务单元不可用（无应答）；{@link #gate} 作为
     * 闩锁阻塞后端应答，用于制造在途请求堆积。</p>
     */
    static class Backend extends Performer {

        /** 阻塞后端应答的闩锁；计数为 0 时直接放行。 */
        volatile CountDownLatch gate = new CountDownLatch(0);

        /** 服务单元是否可用；为 false 时 syncTransmit 返回 null 。 */
        volatile boolean available = true;

        /** Harness scope 的导演机（权重 2），承载全部 Harness 请求。 */
        final Director harness;

        /** AIGC scope 的导演机（权重 10），与 Harness 选择无关。 */
        final Director unrelated;

        /**
         * 构造函数：创建执行机并注册两个不同 scope 的导演机。
         *
         * @param nucleus 所属 Nucleus 实例。
         */
        Backend(Nucleus nucleus) {
            super(nucleus);
            harness = addDirector("127.0.0.1", 1, "127.0.0.1", 0, new TestScope("Harness", 2));
            unrelated = addDirector("127.0.0.1", 2, "127.0.0.1", 0, new TestScope("AIGC", 10));
            harness.speaker = new Speaker();
            unrelated.speaker = new Speaker();
        }

        /**
         * 同步发送的桩实现：校验目标与凭据后回显资源 ID 。
         *
         * <p>断言目标必须是 Harness 导演机且 cellet 为 "Harness" ；服务不可用
         * 或被中断时返回 null （模拟无应答）；否则先等待 {@link #gate} 放行。</p>
         *
         * <p>断言 Bearer 凭据与 {@link #TOKEN} 一致（未被网关改写），且报文未
         * 携带 dispatcher 自报的 role 字段；应答回显请求 id ，有 body 参数时
         * 以 UTF-8 原文放入 raw 字段，status 固定为 200 。</p>
         *
         * @param node    目标导演机。
         * @param cellet  目标 cellet 名称。
         * @param request 请求报文。
         * @param timeout 超时时长，单位毫秒（桩实现忽略该值）。
         * @return 返回 status 200 的应答报文；不可用或被中断时返回 null 。
         */
        @Override
        public ActionDialect syncTransmit(Director node, String cellet, ActionDialect request, long timeout) {
            check(node == harness && cellet.equals("Harness"), "Wrong node/cellet");
            if (!available) {
                return null;
            }
            try {
                gate.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            JSONObject data = new Packet(request).data;
            check(data.getString("credential").equals(TOKEN), "Bearer credential was changed");
            check(!data.has("role"), "Dispatcher assigned a caller role");
            JSONObject body = new JSONObject().put("id", data.optString("id", ""));
            if (request.containsParam("body")) {
                body.put("raw", new String(request.getParam("body"), StandardCharsets.UTF_8));
            }
            return new Packet(new Packet(request).sn, request.getName(), new JSONObject().put("status", 200)
                    .put("body", body).put("headers", new JSONObject())).toDialect();
        }
    }

    /**
     * 发送非 SSE 的 HTTP GET 请求并校验状态码。
     *
     * @param url      请求地址。
     * @param expected 期望的 HTTP 状态码。
     * @return 返回响应体的 UTF-8 文本。
     * @throws Exception 当连接、读写或断言失败时抛出。
     */
    static String get(String url, int expected) throws Exception {
        return get(url, expected, false);
    }

    /**
     * 发送 HTTP GET 请求并校验状态码，返回响应体文本。
     *
     * <p>固定携带 Bearer 凭据；<code>sse</code> 为 true 时附带
     * <code>Accept: text/event-stream</code> 。状态码不小于 400 时读取错误流，
     * 否则读取普通输入流。</p>
     *
     * @param url      请求地址。
     * @param expected 期望的 HTTP 状态码，不一致时断言失败。
     * @param sse      是否按 SSE 事件流发起请求。
     * @return 返回响应体的 UTF-8 文本。
     * @throws Exception 当连接、读写或断言失败时抛出。
     */
    static String get(String url, int expected, boolean sse) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setRequestProperty("Authorization", "Bearer " + TOKEN);
        c.setReadTimeout(12000);
        if (sse) {
            c.setRequestProperty("Accept", "text/event-stream");
        }
        int status = c.getResponseCode();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            byte[] bytes = new byte[4096];
            int n;
            while (in != null && (n = in.read(bytes)) != -1) {
                out.write(bytes, 0, n);
            }
        }
        finally {
            c.disconnect();
        }
        check(status == expected, "HTTP status " + status + " expected " + expected);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 测试入口：依次验证导演机选择、端点安装、有界准入、并发应答、流归属与
     * SSE 配额，全部通过后输出 PASS 汇总行。
     *
     * @param args 未使用的命令行参数。
     * @throws Exception 当 HTTP 服务启动、请求发送或断言失败时抛出。
     */
    public static void main(String[] args) throws Exception {
        Nucleus nucleus = new Nucleus();
        Backend performer = new Backend(nucleus);
        // Harness scope 节点（低权重）应被选中，全局高权重的 AIGC 节点不抢选
        check(performer.selectDirectorForCellet("Harness") == performer.harness, "Global weight leaked into Harness selection");
        // Harness 节点未就绪时不回落选择其他服务
        ((Speaker) performer.harness.speaker).ready = false;
        check(performer.selectDirectorForCellet("Harness") == null, "Unready/other service fallback");
        ((Speaker) performer.harness.speaker).ready = true;
        // 无任何配置时端点仍被无条件安装，上下文路径为 /harness
        HttpServer host = new HttpServer();
        HarnessGateway gateway = HarnessGateway.install(host, performer);
        check(host.getContextHandlers().contains(gateway) && gateway.getContextPath().equals("/harness"),
                "Harness endpoint not installed without configuration");
        Server server = new Server(new InetSocketAddress("127.0.0.1", 0));
        server.setHandler(gateway);
        ExecutorService clients = Executors.newFixedThreadPool(16);
        try {
            server.start();
            String url = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            // 基础路由：REST 请求经 SHM 送达后端并回显资源 ID
            check(new JSONObject(get(url + "/harness/v1/runs/first", 200)).getString("id").equals("first"), "Resource id lost");
            // 后端不可用（无应答）时返回 503
            performer.available = false;
            get(url + "/harness/v1/capabilities", 503);
            performer.available = true;
            // 阻塞后端应答，制造 16 个在途请求占满有界准入
            performer.gate = new CountDownLatch(1);
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final String id = "run-" + i;
                pending.add(clients.submit(() -> get(url + "/harness/v1/runs/" + id, 200)));
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gateway.inflight.availablePermits() != 0 && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            check(gateway.inflight.availablePermits() == 0, "Did not fill bounded admission");
            // 配额占满期间新请求返回 503
            get(url + "/harness/v1/capabilities", 503);
            performer.gate.countDown();
            // 放行后并发应答与请求一一对应，未交叉错配
            for (int i = 0; i < pending.size(); i++) {
                check(new JSONObject(pending.get(i).get()).getString("id").equals("run-" + i), "Concurrent replies crossed");
            }
            // 下载流归属：来源连接不符的流被直接关闭，接收器未完成
            HarnessGateway.Receiver receiver = new HarnessGateway.Receiver(performer.harness.speaker);
            gateway.receivers.put("owned", receiver);
            Input wrong = new Input("owned");
            gateway.onReceivedStream(performer.unrelated.speaker, "Harness", wrong);
            check(wrong.closed && !receiver.input.isDone(), "Wrong connection acquired stream");
            // 来源匹配的流被交付给接收器
            Input correct = new Input("owned");
            gateway.onReceivedStream(performer.harness.speaker, "Harness", correct);
            check(receiver.input.get() == correct, "Matching stream was not delivered");
            // 接收器关闭时关闭已交付的流
            receiver.close();
            gateway.receivers.remove("owned");
            check(correct.closed, "Receiver close leaked stream");
            // 未登记 transferId 的流同样被直接关闭
            Input unknown = new Input("missing");
            gateway.onReceivedStream(performer.harness.speaker, "Harness", unknown);
            check(unknown.closed, "Unknown stream leaked");
            // SSE：两个事件流请求占满两个槽位（后端阻塞，按 2 秒硬截止返回 503），
            // 第三个请求因无槽位返回 429
            performer.gate = new CountDownLatch(1);
            Future<String> slowA = clients.submit(() -> get(url + "/harness/v1/runs/slow/events", 503, true));
            Future<String> slowB = clients.submit(() -> get(url + "/harness/v1/runs/slow/events", 503, true));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (gateway.streams.availablePermits() != 0 && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            check(gateway.streams.availablePermits() == 0, "SSE slots were not acquired");
            get(url + "/harness/v1/runs/slow/events", 429, true);
            slowA.get(3, TimeUnit.SECONDS);
            slowB.get(3, TimeUnit.SECONDS);
            // 后端放行后，两个超时（503）的 SSE 槽位被如期释放
            performer.gate.countDown();
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (gateway.streams.availablePermits() != 2 && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            check(gateway.streams.availablePermits() == 2, "Timed-out SSE slots leaked");
            System.out.println(
                    "PASS: Harness-only ready routing, direct syncTransmit, bounded queue recovery, concurrent ids, stream ownership, two SSE slots/deadline release, unconditional installation and unchanged Bearer");
        }
        finally {
            performer.gate.countDown();
            clients.shutdownNow();
            server.stop();
            nucleus.destroy();
        }
    }
}
