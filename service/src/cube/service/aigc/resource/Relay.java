/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.resource;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.Utils;
import cell.util.log.Logger;
import cube.aigc.Consts;
import cube.aigc.ModelConfig;
import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionProvider;
import cube.aigc.completion.CompletionRequest;
import cube.aigc.completion.OpenAIResponseAccumulator;
import cube.auth.AuthConsts;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AICapability;
import cube.common.entity.AIGCUnit;
import cube.common.entity.Contact;
import cube.common.entity.GeneratingOption;
import cube.common.state.AIGCStateCode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仅供开发环境使用的 HTTP 单元代理。其身份由本类拥有，绝不从联系人 ID 推断。
 *
 * <p>当本机无法安装大模型时，Relay 让开发机借用另一套 Cube 的推理能力：它发布的每一个
 * {@link AIGCUnit} 都是代理单元，其传输方式是发往远端 Cube 的 HTTP 调用，而不是进程内模型。</p>
 *
 * <p>实现由三条不变量约束：</p>
 * <ul>
 *   <li><b>身份自有。</b>只有当单元的 {@link Context} 指回本类时，它才属于本 Relay。
 *       所有代理共用同一个虚拟联系人 ID，因此该 ID 本身无法证明任何事情。</li>
 *   <li><b>共享一份预算。</b>所有模型别名、REST 端点与旧聊天路径都从同一批
 *       {@link #slots} 许可中取用，所以 {@code relay.capacity} 限制的是整个 Relay，
 *       而不是单个模型。</li>
 *   <li><b>以证据判定完成。</b>只有远端执行到达确定终态——要么是终态应答，要么是确定拒绝
 *       ——许可才会归还。结果不确定的失败会保留许可，因为断流并不能证明远端模型已经停止。</li>
 * </ul>
 */
public final class Relay implements AutoCloseable {

    /**
     * 所有代理单元共用的联系人 ID，仅用于让代理可被寻址；归属由 {@link Context} 决定，与该数值无关。
     */
    private static final long PROXY_CONTACT_ID = 100000L;

    /**
     * 旧聊天生成请求中随行通道标识的长度。
     */
    private static final int CHANNEL_ID_LENGTH = 16;

    /**
     * 后台能力探测的间隔。
     */
    private static final long REFRESH_INTERVAL_SECONDS = 10;

    /**
     * 单次能力探测的总期限。
     */
    private static final long PROBE_TIMEOUT_MILLIS = 3000;

    /**
     * 连接阶段的上限，与读取期限相互独立。
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    /**
     * 远端模型列表的最大长度。
     */
    private static final int MAX_MODELS_BYTES = 4 * 1024 * 1024;

    /**
     * 非流式应答体的最大长度。
     */
    private static final int MAX_COMPLETION_BYTES = 32 * 1024 * 1024;

    /**
     * 整个事件流的最大长度。
     */
    private static final int MAX_STREAM_BYTES = 32 * 1024 * 1024;

    /**
     * 单个流式事件负载的最大长度。
     */
    private static final int MAX_EVENT_BYTES = 4 * 1024 * 1024;

    /**
     * 流式单行的最大长度。
     */
    private static final int MAX_LINE_BYTES = 4 * 1024 * 1024;

    /**
     * 两种协议都能承载的生成参数。
     */
    private static final Set<String> SHARED_PARAMETERS = new HashSet<>(Arrays.asList(
            "max_tokens", "max_completion_tokens", "temperature", "top_p", "top_k", "stop",
            "tool_choice", "parallel_tool_calls"));

    /**
     * 只有 OpenAI 协议能承载的生成参数。
     */
    private static final Set<String> OPENAI_ONLY_PARAMETERS = new HashSet<>(Arrays.asList(
            "seed", "presence_penalty", "frequency_penalty"));

    /**
     * 能证明远端从未开始生成的 HTTP 状态码。
     */
    private static final Set<Integer> DEFINITE_REJECTIONS = new HashSet<>(Arrays.asList(
            400, 401, 403, 404, 413, 429));

    /**
     * 当前生效的 Relay，禁用时为 null。
     */
    private static volatile Relay instance;

    /**
     * 远端 Cube 的基础地址，始终以斜杠结尾。
     */
    private final String url;

    /**
     * 出示给远端 Cube 的令牌。
     */
    private final String token;

    /**
     * 配置指纹；指纹不变则保留正在运行的实例。
     */
    private final String fingerprint;

    /**
     * 远端 Cube 使用 Claude 协议而非 OpenAI 时为 true。
     */
    private final boolean claude;

    /**
     * 所有模型别名与传输方式共享的 HTTP 许可数量。
     */
    private final int capacity;

    /**
     * Claude 请求未指定长度上限时采用的缺省值。
     */
    private final int maxTokens;

    /**
     * 请求期限（毫秒），排队时间与 HTTP 时间一并计入。
     */
    private final long timeoutMillis;

    /**
     * 共享的 HTTP 许可。
     */
    private final Semaphore slots;

    /**
     * 按能力名索引的代理单元。
     */
    private final Map<String, AIGCUnit> units = new LinkedHashMap<>();

    /**
     * 按请求 ID 索引的在途补全调用。
     */
    private final Map<String, Call> calls = new ConcurrentHashMap<>();

    /**
     * 已打开的 HTTP 连接，Relay 关闭时统一断开。
     */
    private final Set<HttpURLConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 后台能力探测的调度器。
     */
    private final ScheduledExecutorService refresh =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "relay-models"));

    /**
     * 硬期限调度器；断开超过期限的连接。
     */
    private final ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "relay-deadlines"));

    /**
     * 执行补全调用的工作线程池。
     */
    private final ThreadPoolExecutor executor;

    /**
     * 接收过代理单元的单元注册表，便于关闭时移除。
     */
    private final Set<Map<String, AIGCUnit>> registries = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Relay 停止后为 true；其上下文随之失效，且不再接受新任务。
     */
    private volatile boolean closed;

    /**
     * 最近一次能力探测的失败原因，仅用于在状态翻转时记录日志。
     */
    private String lastRefreshError;

    /**
     * 本 Relay 实例的独有组标识，用于与真实单元相区分。
     */
    private final String group = "relay:" + UUID.randomUUID();

    /**
     * 代理单元的传输上下文。
     *
     * <p>它持有拥有该单元的 Relay，这才是代理单元的归属凭据。所有代理共用的虚拟联系人 ID
     * 无法起到这个作用。</p>
     */
    public static final class Context extends TalkContext {

        /**
         * 拥有该代理单元的 Relay。
         */
        public final Relay owner;

        /**
         * 代理单元的能力名，例如 Baize。
         */
        public final String unit;

        private Context(Relay owner, String unit) {
            super(null, null);
            this.owner = owner;
            this.unit = unit;
        }

        @Override
        public boolean isValid() {
            return !owner.closed;
        }

        @Override
        public String getSessionHost() {
            return "development-relay";
        }
    }

    /**
     * 创建守护线程，避免 Relay 阻止 JVM 退出。
     *
     * @param r    要运行的任务。
     * @param name 线程名。
     * @return 准备就绪的线程。
     */
    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 返回当前生效的 Relay。
     *
     * @return 生效的 Relay，禁用时为 null。
     */
    public static Relay getInstance() {
        return instance;
    }

    /**
     * 应用一次配置：禁用、保留或替换当前生效的 Relay。
     *
     * <p>指纹不变时保留正在运行的实例及其在途工作；任何其它变化都会新建实例并关闭旧实例，
     * 因此配置重载不会在同一 JVM 中混入两个 Relay。</p>
     *
     * @param properties 服务配置。
     * @return 生效的 Relay，禁用时为 null。
     */
    public static synchronized Relay configure(Properties properties) {
        if (!Boolean.parseBoolean(properties.getProperty("relay", "false"))) {
            if (instance != null) {
                instance.close();
            }
            instance = null;
            return null;
        }
        String fingerprint = configuration(properties);
        if (instance != null && !instance.closed && instance.fingerprint.equals(fingerprint)) {
            return instance;
        }
        Relay next = new Relay(properties);
        Relay previous = instance;
        instance = next;
        if (previous != null) {
            previous.close();
        }
        return next;
    }

    /**
     * 以显式的地址与令牌创建 Relay，绕过配置文件。
     *
     * @param url   远端 Cube 的基础地址。
     * @param token 出示给远端 Cube 的令牌。
     * @return 生效的 Relay。
     */
    public static synchronized Relay createInstance(String url, String token) {
        Properties p = new Properties();
        p.setProperty("relay", "true");
        p.setProperty("relay.url", url);
        p.setProperty("relay.token", token);
        return configure(p);
    }

    /**
     * 构建决定运行实例是否存活的配置指纹。
     *
     * @param p 服务配置。
     * @return 生效 Relay 配置的指纹。
     */
    private static String configuration(Properties p) {
        return new JSONObject().put("url", p.getProperty("relay.url", "http://127.0.0.1:7010"))
                .put("token", p.getProperty("relay.token", "")).put("protocol", p.getProperty("relay.protocol", "openai"))
                .put("capacity", p.getProperty("relay.capacity", "1")).put("timeout", p.getProperty("relay.timeout.seconds", "300"))
                .put("tokens", p.getProperty("relay.max.tokens", "2048")).toString();
    }

    /**
     * 创建 Relay，校验其配置并启动能力探测。
     *
     * @param p 服务配置。
     * @throws IllegalArgumentException 地址、令牌、协议或某个数值边界非法。
     */
    public Relay(Properties p) {
        fingerprint = configuration(p);
        String base = p.getProperty("relay.url", "http://127.0.0.1:7010").replaceAll("/+$", "");
        try {
            URI uri = new URI(base);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new Exception();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid relay.url");
        }
        url = base + "/";
        token = p.getProperty("relay.token", "");
        if (token.isEmpty() || token.contains("\r") || token.contains("\n") || !token.matches("[A-Za-z0-9_.~-]+")) {
            throw new IllegalArgumentException("Invalid relay.token");
        }
        String protocol = p.getProperty("relay.protocol", "openai");
        if (!protocol.equals("openai") && !protocol.equals("claude")) {
            throw new IllegalArgumentException("Invalid relay.protocol");
        }
        claude = protocol.equals("claude");
        capacity = positive(p, "relay.capacity", 1, 32);
        timeoutMillis = positive(p, "relay.timeout.seconds", 300, 600) * 1000L;
        maxTokens = positive(p, "relay.max.tokens", 2048, Integer.MAX_VALUE);
        slots = new Semaphore(capacity);
        executor = new ThreadPoolExecutor(capacity, capacity, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
                r -> daemon(r, "relay-http"), new ThreadPoolExecutor.AbortPolicy());
        Contact contact = new Contact(PROXY_CONTACT_ID, AuthConsts.DEFAULT_DOMAIN);
        for (String name : Arrays.asList(ModelConfig.BAIZE_UNIT, ModelConfig.BAIZE_2_UNIT)) {
            AICapability caps = new AICapability(name, AICapability.NaturalLanguageProcessingTask, "2.0",
                    Collections.singletonList(AICapability.NaturalLanguageProcessing.Conversational), "");
            AIGCUnit unit = new AIGCUnit(contact, caps, new Context(this, name)) {
                @Override
                public String getQueryKey() {
                    // 组后缀避免代理与真实单元共用同一个查询键。
                    return super.getQueryKey() + "_" + group;
                }
            };
            unit.setTimestamp(0); // 代理路由没有真实 Unit 注册后的忙碌期。
            units.put(name, unit);
        }
        refresh.scheduleWithFixedDelay(this::refreshModels, 0, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 从配置中读取一个正整数边界值。
     *
     * @param p        服务配置。
     * @param k        属性名。
     * @param fallback 属性缺省时采用的值。
     * @param max      允许的最大值。
     * @return 配置得到的值。
     * @throws IllegalArgumentException 该值不是数字或超出范围。
     */
    private static int positive(Properties p, String k, int fallback, int max) {
        int v;
        try {
            v = Integer.parseInt(p.getProperty(k, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + k);
        }
        if (v < 1 || v > max) {
            throw new IllegalArgumentException("Invalid " + k);
        }
        return v;
    }

    /**
     * 将代理单元发布到单元注册表，并记住该注册表以便清理。
     *
     * @param map 调度器的单元注册表。
     */
    public synchronized void fillUnits(Map<String, AIGCUnit> map) {
        if (closed) {
            return;
        }
        registries.add(map);
        for (AIGCUnit unit : units.values()) {
            map.put(unit.getQueryKey(), unit);
        }
    }

    /**
     * 按能力名查找代理单元。
     *
     * @param name 能力名，例如 Baize。
     * @return 代理单元；Relay 已关闭或名称未知时为 null。
     */
    public AIGCUnit selectUnit(String name) {
        return closed ? null : units.get(name);
    }

    /**
     * 返回远端 Cube 的基础地址。
     *
     * @return 远端基础地址。
     */
    public String getUrl() {
        return url;
    }

    /**
     * 解析拥有某个单元的 Relay。
     *
     * @param unit 待检查的单元。
     * @return 拥有它的 Relay；若该单元是真实单元而非代理，则为 null。
     */
    public static Relay owner(AIGCUnit unit) {
        return unit.getContext() instanceof Context ? ((Context) unit.getContext()).owner : null;
    }

    /**
     * 描述本 Relay 向容量接口提供的共享预算。
     *
     * <p>该报告覆盖整个 Relay 组，因此对每个代理单元都相同；单元参数只用于标识调用方所询问的路由。</p>
     *
     * @param unit 发起该报告的单元。
     * @return 组标识以及共享许可计数。
     */
    public JSONObject capacity(AIGCUnit unit) {
        return new JSONObject().put("capacityGroup", group).put("groupCapacity", capacity)
                .put("groupAvailable", closed ? 0 : slots.availablePermits());
    }

    /**
     * 探测远端模型列表并刷新对外声明的结构化能力。
     *
     * <p>该后台能力探测不执行生成、不参与调度，也不使用调用方令牌。代理单元对外声明的能力，
     * 是远端声明与本地协议转换可承载范围的交集。探测失败会清空快照，因此连不上远端的 Relay
     * 不会声明任何结构化模型。</p>
     */
    public synchronized void refreshModels() {
        if (closed) {
            return;
        }
        Map<String, JSONObject> next = new HashMap<>();
        String failure = null;
        HttpURLConnection c = null;
        ScheduledFuture<?> deadline = null;
        try {
            c = open("v1/models", false, PROBE_TIMEOUT_MILLIS);
            final HttpURLConnection probe = c;
            // 一旦超过期限就放弃探测，即使远端始终沉默。
            deadline = deadlines.schedule(probe::disconnect, PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            int status = c.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP_" + status);
            }
            JSONArray models;
            try (InputStream in = c.getInputStream()) {
                models = new JSONObject(read(in, MAX_MODELS_BYTES)).getJSONArray("data");
            }
            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.getJSONObject(i), remote = model.optJSONObject("capabilities");
                if (remote == null || !remote.optBoolean("text")) {
                    continue;
                }
                JSONObject caps = new JSONObject().put("text", true).put("images", remote.optBoolean("images"))
                        .put("tools", remote.optBoolean("tools")).put("stream", remote.optBoolean("stream"));
                JSONArray params = new JSONArray(), offered = remote.optJSONArray("parameters");
                if (offered != null) {
                    for (int j = 0; j < offered.length(); j++) {
                        String k = offered.getString(j);
                        // 只声明本地协议转换确实能承载的参数。
                        if (SHARED_PARAMETERS.contains(k) || (!claude && OPENAI_ONLY_PARAMETERS.contains(k))) {
                            params.put(k);
                        }
                    }
                }
                caps.put("parameters", params);
                next.put(model.getString("id"), caps);
            }
        } catch (Exception e) {
            next.clear();
            failure = e instanceof IOException && e.getMessage() != null && e.getMessage().matches("HTTP_[0-9]{3}")
                    ? e.getMessage() : "MODEL_DISCOVERY_FAILED";
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            disconnect(c);
        }
        for (Map.Entry<String, AIGCUnit> entry : units.entrySet()) {
            entry.getValue().getCapability()
                    .setChatCompletion(next.getOrDefault(entry.getKey(), new JSONObject()));
        }
        if (!Objects.equals(failure, lastRefreshError)) {
            if (failure != null) {
                Logger.w(Relay.class, "#refreshModels - Development relay unavailable: " + failure);
            } else {
                Logger.i(Relay.class, "#refreshModels - Development relay model capabilities ready");
            }
        }
        lastRefreshError = failure;
    }

    /**
     * 打开一条通向远端 Cube 的已认证连接。
     *
     * @param path      相对于 Relay 基础地址的路径。
     * @param useClaude 发送 Claude 认证头而非 OpenAI Bearer 令牌。
     * @param timeout   读取期限（毫秒）；连接阶段另有独立上限。
     * @return 已打开的连接。
     * @throws IOException Relay 已停止或连接无法建立。
     */
    private HttpURLConnection open(String path, boolean useClaude, long timeout) throws IOException {
        if (closed) {
            throw new IOException("Relay stopped");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        connections.add(c);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout((int) Math.min(timeout, CONNECT_TIMEOUT_MILLIS));
        c.setReadTimeout((int) timeout);
        c.setRequestProperty("Content-Type", "application/json");
        if (useClaude) {
            c.setRequestProperty("x-api-key", token);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        return c;
    }

    /**
     * 关闭一条连接并将其遗忘。
     *
     * @param c 待关闭的连接，允许为 null。
     */
    private void disconnect(HttpURLConnection c) {
        if (c != null) {
            connections.remove(c);
            c.disconnect();
        }
    }

    /**
     * 读取有上限的应答体。
     *
     * @param in    应答流。
     * @param limit 允许的最大字节数。
     * @return 解码后的文本。
     * @throws IOException 应答体超出上限。
     */
    private static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (bytes.size() + n > limit) {
                throw new IOException("Relay response too large");
            }
            bytes.write(buffer, 0, n);
        }
        return decode(bytes.toByteArray());
    }

    /**
     * 以严格 UTF-8 解码；非法输入视为错误，而非替换字符。
     *
     * @param bytes 原始字节。
     * @return 解码后的文本。
     * @throws CharacterCodingException 字节不是合法 UTF-8。
     */
    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 以固定长度发送 JSON 请求体。
     *
     * @param c    已打开的连接。
     * @param body 请求体。
     * @throws IOException 请求体无法写出。
     */
    private static void post(HttpURLConnection c, JSONObject body) throws IOException {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
    }

    /**
     * 以 HTTP 方式提供旧版文生文动作。
     *
     * <p>只有旧版动作会走这里；其它动作一律回以错误封包，让调用方得到确定的失败，而不是悬挂的请求。</p>
     *
     * @param context 指明目标单元的代理上下文。
     * @param dialect 请求方言。
     * @param timeout 调用方期限（毫秒）。
     * @return 携带生成文本的应答方言。
     */
    public ActionDialect transmit(Context context, ActionDialect dialect, long timeout) {
        Packet packet = new Packet(dialect);
        if (!AIGCAction.TextToText.name.equals(packet.name)) {
            return legacyReply(packet, null);
        }
        HttpURLConnection c = null;
        ScheduledFuture<?> deadline = null;
        boolean acquired = false, attempted = false, finished = false;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeout, timeoutMillis));
        try {
            acquired = slots.tryAcquire(Math.min(timeout, timeoutMillis), TimeUnit.MILLISECONDS);
            if (!acquired) {
                return legacyReply(packet, null);
            }
            long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
            c = open("aigc/chat/" + token + "/", false, remaining);
            final HttpURLConnection active = c;
            deadline = deadlines.schedule(active::disconnect, remaining, TimeUnit.MILLISECONDS);
            JSONObject wire = legacyWire(context, packet.data);
            attempted = true;
            post(c, wire);
            int status = c.getResponseCode();
            if (status != 200) {
                // 确定拒绝可证明远端从未开始生成。
                finished = DEFINITE_REJECTIONS.contains(status);
                return legacyReply(packet, null);
            }
            try (InputStream in = c.getInputStream()) {
                JSONObject result = new JSONObject(read(in, MAX_COMPLETION_BYTES));
                finished = result.opt("content") instanceof String;
                return legacyReply(packet, result);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Logger.w(Relay.class, "#transmit - Development relay text request failed: " + e.getClass().getSimpleName());
            return legacyReply(packet, null);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            disconnect(c);
            // 结果不确定的失败不能证明远端模型已停止，因此保留许可。
            if (acquired && (finished || !attempted)) {
                slots.release();
            }
        }
    }

    /**
     * 构建发往远端 Cube 的旧版生成请求体。
     *
     * @param context 指明目标单元的代理上下文。
     * @param data    请求负载。
     * @return 旧版请求体。
     */
    private static JSONObject legacyWire(Context context, JSONObject data) {
        return new JSONObject().put("channel", Utils.randomString(CHANNEL_ID_LENGTH)).put("content", data.getString("content"))
                .put("unit", context.unit).put("option", data.optJSONObject("option") == null ? new GeneratingOption().toJSON() : data.getJSONObject("option"))
                .put("histories", 0).put("pattern", Consts.PATTERN_CHAT).put("recordable", false)
                .put("records", data.optJSONArray("history") == null ? new JSONArray() : data.getJSONArray("history"));
    }

    /**
     * 由远端结果构建旧版应答封包。
     *
     * @param request 原始请求封包。
     * @param result  远端结果，调用失败时为 null。
     * @return 应答方言。
     */
    private static ActionDialect legacyReply(Packet request, JSONObject result) {
        boolean ok = result != null && result.opt("content") instanceof String;
        return new Packet(request.sn, request.name, new JSONObject().put("code", ok ? AIGCStateCode.Ok.code : AIGCStateCode.UnitError.code)
                .put("data", ok ? new JSONObject().put("response", result.getString("content")).put("thought", result.optString("thought", "")) : new JSONObject())).toDialect();
    }

    /**
     * 接收一条结构化补全请求或一次取消。
     *
     * @param context  指明目标单元的代理上下文。
     * @param dialect  请求方言。
     * @param listener 接收 started、delta、completed 与 error 事件。
     * @return 请求被接受时为 true；不是补全请求、Relay 已关闭或请求 ID 已被占用时为 false。
     */
    public boolean send(Context context, ActionDialect dialect, CompletionProvider.Listener listener) {
        Packet p = new Packet(dialect);
        String id = p.data.optString("requestId");
        if (AIGCAction.CancelChatCompletion.name.equals(p.name)) {
            Call call = calls.get(id);
            if (call != null) {
                call.cancelled = true;
                if (call.connection != null) {
                    call.connection.disconnect();
                }
            }
            return true;
        }
        if (!AIGCAction.ChatCompletion.name.equals(p.name) || closed) {
            return false;
        }
        JSONObject request = p.data.getJSONObject("request");
        Call call = new Call(id, context.unit, request, Math.min(timeoutMillis, p.data.getLong("timeoutMs")), listener);
        if (calls.putIfAbsent(id, call) != null) {
            return false;
        }
        try {
            executor.execute(call);
        } catch (RejectedExecutionException e) {
            calls.remove(id, call);
            call.error(new CompletionError(429, "rate_limit_error", "Relay queue is full", null), true);
        }
        return true;
    }

    /**
     * 一条发往远端 Cube 的结构化补全请求。
     *
     * <p>一次调用在整个交互期间独占一份共享许可，只有远端执行到达确定终态时才会归还。</p>
     */
    private final class Call implements Runnable {

        /**
         * 请求 ID，同时是 {@link Relay#calls} 的键。
         */
        final String id;

        /**
         * 目标代理单元的能力名。
         */
        final String unit;

        /**
         * 规范请求的防御性副本。
         */
        final JSONObject request;

        /**
         * 整次交互的期限。
         */
        final long end;

        /**
         * 补全事件的接收方。
         */
        final CompletionProvider.Listener listener;

        /**
         * 已发出事件的单调序号。
         */
        final AtomicLong sequence = new AtomicLong();

        /**
         * 存活的连接，供取消时切断。
         */
        volatile HttpURLConnection connection;

        /**
         * 调用方取消请求后为 true。
         */
        volatile boolean cancelled;

        /**
         * 远端执行到达确定终态后为 true。只有此时共享许可才归还池中。
         */
        boolean definiteEnd;

        Call(String id, String unit, JSONObject request, long timeout, CompletionProvider.Listener listener) {
            this.id = id;
            this.unit = unit;
            this.request = new JSONObject(request.toString());
            this.end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            this.listener = listener;
        }

        /**
         * 发出一个规范事件。
         *
         * @param kind           事件类型，例如 delta 或 completed。
         * @param payload        事件负载。
         * @param actualFinished 该事件携带的执行证据。
         */
        void emit(String kind, JSONObject payload, boolean actualFinished) {
            listener.onEvent(new JSONObject().put("requestId", id).put("sequence", sequence.incrementAndGet())
                    .put("kind", kind).put("payload", payload).put("actualFinished", actualFinished));
        }

        /**
         * 发出一个终态错误事件。
         *
         * @param error          失败原因。
         * @param actualFinished 该事件携带的执行证据。
         */
        void error(CompletionError error, boolean actualFinished) {
            emit("error", new JSONObject().put("error", error.toJSON()), actualFinished);
        }

        /**
         * 返回距离期限所剩的毫秒数。
         *
         * @return 剩余时间（毫秒）。
         * @throws SocketTimeoutException 期限已过或请求已被取消。
         */
        long remaining() throws SocketTimeoutException {
            long ms = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (ms <= 0 || cancelled) {
                throw new SocketTimeoutException();
            }
            return ms;
        }

        @Override
        public void run() {
            boolean acquired = false, attempted = false;
            ScheduledFuture<?> task = null;
            try {
                CompletionRequest.validate(request);
                AIGCUnit target = units.get(unit);
                CompletionRequest.checkCapabilities(request, target.getCapability().getChatCompletion());
                JSONObject wire = RelayCodec.request(request, claude, maxTokens);
                acquired = slots.tryAcquire(remaining(), TimeUnit.MILLISECONDS);
                if (!acquired) {
                    throw new SocketTimeoutException();
                }
                connection = open(claude ? "v1/messages" : "v1/chat/completions", claude, remaining());
                HttpURLConnection active = connection;
                task = deadlines.schedule(active::disconnect, remaining(), TimeUnit.MILLISECONDS);
                connection.setRequestProperty("Accept", request.getBoolean("stream") ? "text/event-stream" : "application/json");
                attempted = true;
                post(connection, wire);
                int status = connection.getResponseCode();
                if (status != 200) {
                    // 确定拒绝可证明远端从未开始生成。
                    definiteEnd = DEFINITE_REJECTIONS.contains(status);
                    throw new CompletionError(status >= 400 && status < 500 ? status : 502,
                            "relay_http_" + status, "Remote Cube rejected the Relay request", null);
                }
                JSONObject result;
                try (InputStream in = connection.getInputStream()) {
                    if (!request.getBoolean("stream")) {
                        JSONObject response = new JSONObject(read(in, MAX_COMPLETION_BYTES));
                        definiteEnd = terminal(response, claude);
                        result = RelayCodec.result(response, claude);
                    } else {
                        result = stream(in);
                    }
                }
                emit("completed", new JSONObject().put("result", result), true);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                boolean timedOut = e instanceof SocketTimeoutException || cancelled || System.nanoTime() >= end;
                CompletionError error = e instanceof CompletionError ? (CompletionError) e : new CompletionError(
                        timedOut ? 504 : 502, timedOut ? "request_timeout" : "upstream_error", "Relay completion failed", null);
                Logger.w(Relay.class, "#send - Development relay completion failed: status=" + error.status + " code=" + error.code);
                error(error, definiteEnd || !attempted);
            } finally {
                if (task != null) {
                    task.cancel(false);
                }
                disconnect(connection);
                // 结果不确定的 HTTP 失败不能证明远端模型已停止。
                if (acquired && (definiteEnd || !attempted)) {
                    slots.release();
                }
                calls.remove(id, this);
            }
        }

        /**
         * 消费 SSE 事件流并转换为规范结果。
         *
         * <p>事件流只接受文档规定的顺序。断流、未知事件或没有终态事件的流都是错误，
         * 而非部分成功，因为残缺的应答不能证明远端执行已经结束。</p>
         *
         * @param in 应答流。
         * @return 规范结果。
         * @throws IOException 事件流畸形、被截断或超出某个长度上限。
         */
        private JSONObject stream(InputStream in) throws IOException {
            if (connection.getContentType() == null || !connection.getContentType().toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                throw RelayCodec.invalid();
            }
            emit("started", new JSONObject(), false);
            RelayCodec.ClaudeStream anthropic = new RelayCodec.ClaudeStream();
            OpenAIResponseAccumulator openai = new OpenAIResponseAccumulator();
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT));
            StringBuilder data = new StringBuilder();
            String event = "";
            int total = 0;
            while (true) {
                String line = line(reader);
                if (line == null) {
                    break;
                }
                total += line.length();
                if (total > MAX_STREAM_BYTES) {
                    throw RelayCodec.invalid();
                }
                if (line.isEmpty()) {
                    // 空行结束一个事件；连续的空行属于保活。
                    if (data.length() == 0) {
                        event = "";
                        continue;
                    }
                    String raw = data.toString();
                    data.setLength(0);
                    if (!claude && raw.equals("[DONE]")) {
                        definiteEnd = openai.terminal();
                        break;
                    }
                    JSONObject parsed = new JSONObject(raw);
                    JSONObject delta = claude ? anthropic.add(event, parsed) : openai.add(parsed);
                    event = "";
                    if (delta.length() > 0) {
                        emit("delta", new JSONObject().put("delta", delta), false);
                    }
                    if (claude && anthropic.stopped()) {
                        definiteEnd = true;
                        break;
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).replaceFirst("^ ", ""));
                    if (data.length() > MAX_EVENT_BYTES) {
                        throw RelayCodec.invalid();
                    }
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                }
            }
            if (!definiteEnd) {
                throw RelayCodec.invalid();
            }
            return claude ? anthropic.result() : openai.result();
        }

        /**
         * 读取事件流中的一行。
         *
         * @param reader 数据来源。
         * @return 去掉行尾符的那一行；流结束时为 null。
         * @throws IOException 该行超出长度上限或期限已过。
         */
        String line(Reader reader) throws IOException {
            StringBuilder s = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1 && ch != '\n') {
                remaining();
                if (s.length() >= MAX_LINE_BYTES) {
                    throw new IOException("SSE line too large");
                }
                s.append((char) ch);
            }
            if (ch == -1 && s.length() == 0) {
                return null;
            }
            if (s.length() > 0 && s.charAt(s.length() - 1) == '\r') {
                s.setLength(s.length() - 1);
            }
            return s.toString();
        }
    }

    /**
     * 判断一个非流式响应是否到达确定终态。
     *
     * @param response 远端响应。
     * @param claude   响应是否遵循 Claude 协议。
     * @return 响应带有终止型 finish reason 时为 true。
     */
    private static boolean terminal(JSONObject response, boolean claude) {
        if (claude) {
            return "message".equals(response.optString("type")) && "assistant".equals(response.optString("role"))
                    && Arrays.asList("end_turn", "stop_sequence", "max_tokens", "tool_use").contains(response.optString("stop_reason"));
        }
        JSONArray choices = response.optJSONArray("choices");
        return choices != null && choices.length() == 1 && choices.optJSONObject(0) != null
                && Arrays.asList("stop", "length", "tool_calls", "content_filter").contains(choices.getJSONObject(0).optString("finish_reason"));
    }

    /**
     * 停止 Relay，并从它发布过的每个注册表中移除其代理单元。
     *
     * <p>只移除归属于本 Relay 的单元；注册在同一调度器中的真实单元不受影响。</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        refresh.shutdownNow();
        executor.shutdownNow();
        deadlines.shutdownNow();
        for (HttpURLConnection c : connections) {
            c.disconnect();
        }
        for (Map<String, AIGCUnit> registry : registries) {
            for (AIGCUnit unit : units.values()) {
                registry.remove(unit.getQueryKey(), unit);
            }
        }
        registries.clear();
        for (AIGCUnit unit : units.values()) {
            unit.getCapability().setChatCompletion(new JSONObject());
        }
    }
}