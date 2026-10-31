/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.dialect.ActionDialect;
import cell.util.Utils;
import cell.util.log.Logger;
import cube.aigc.*;
import cube.aigc.app.ConfigInfo;
import cube.aigc.complex.widget.Event;
import cube.aigc.psychology.Attribute;
import cube.aigc.psychology.PaintingReport;
import cube.aigc.psychology.Report;
import cube.aigc.psychology.ScaleReport;
import cube.aigc.psychology.algorithm.Attention;
import cube.aigc.psychology.app.UserProfile;
import cube.aigc.psychology.composition.AnswerSheet;
import cube.aigc.psychology.composition.Scale;
import cube.aigc.psychology.composition.ScaleResult;
import cube.aigc.psychology.consultation.ConsultationTheme;
import cube.auth.AuthToken;
import cube.common.JSONable;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.*;
import cube.common.state.AIGCStateCode;
import cube.dispatcher.Performer;
import cube.dispatcher.PerformerListener;
import cube.dispatcher.aigc.completion.CompletionConfig;
import cube.dispatcher.aigc.completion.CompletionGateway;
import cube.dispatcher.aigc.completion.CompletionHandler;
import cube.dispatcher.aigc.handler.*;
import cube.dispatcher.aigc.handler.app.App;
import cube.dispatcher.aigc.spi.DispatcherExtension;
import cube.dispatcher.aigc.spi.DispatcherExtensions;
import cube.dispatcher.stream.StreamType;
import cube.dispatcher.util.Tickable;
import cube.util.FileLabels;
import cube.util.FileUtils;
import cube.util.HttpServer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口管理器。
 */
public class Manager implements Tickable, PerformerListener {

    private final static Manager instance = new Manager();

    private volatile Performer performer;

    private volatile boolean running;

    private final CompletionGateway completions = new CompletionGateway();

    private long generation;

    private static final long TOKEN_CACHE_TTL = 24L * 60 * 60 * 1000;

    private static final long FUTURE_CACHE_TTL = 60L * 60 * 1000;

    private final Set<HttpServer> initializedHttpServers =
            Collections.newSetFromMap(new WeakHashMap<HttpServer, Boolean>());

    /**
     * 按协议封包序号关联任务，避免 reset 后的旧应答完成新任务。
     */
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    // 仅在生命周期锁内访问；完成、替换及过期时同步移除两个索引。
    private final Map<JSONable, PendingRequest> pendingFutures = new IdentityHashMap<>();

    /**
     * 已装载的业务模块网关扩展，按装载顺序。
     */
    private List<DispatcherExtension> dispatcherExtensions;

    private long lastTickTime;

    /**
     * Key：Token code
     */
    private final Map<String, ContactToken> validTokenMap = new ConcurrentHashMap<>();

    /**
     * Key：操作序号。
     */
    private final Map<Long, TextToFileFuture> textToFileFutureMap = new ConcurrentHashMap<>();

    /**
     * Key：查询码
     */
    private final Map<String, SpeechRecognitionFuture> speechRecognitionFutureMap = new ConcurrentHashMap<>();

    /**
     * Key：文件码
     */
    private final Map<String, SpeechEmotionRecognitionFuture> speechEmotionRecognitionFutureMap = new ConcurrentHashMap<>();

    /**
     * Key：查询码
     */
    private final Map<String, SpeechDiarizationFuture> speechDiarizationFutureMap = new ConcurrentHashMap<>();

    /**
     * Key：文件码
     */
    private final Map<String, FacialExpressionRecognitionFuture> facialExpressionRecognitionFutureMap = new ConcurrentHashMap<>();

    /**
     * 获取管理器单例。
     *
     * @return 全局唯一实例。
     */
    public static Manager getInstance() {
        return Manager.instance;
    }

    /**
     * 启动管理器并绑定执行机。
     *
     * <p>仅当 HTTP 服务器尚未在本次进程中注册过处理器时才注册，避免重复注册端点；
     * 随后启动 App 单例、注册周期回调与响应监听器，并启动完成网关。
     * 绑定同一个执行机时幂等返回；启动中途失败会把已注册的回调回滚后再抛出。</p>
     *
     * @param performer 执行机。
     * @throws IllegalArgumentException 执行机或其 HTTP 服务器为空。
     * @throws IllegalStateException    已绑定到另一个执行机。
     */
    public synchronized void start(Performer performer) {
        if (null == performer || null == performer.getHttpServer()) {
            throw new IllegalArgumentException("A configured Performer is required");
        }
        if (this.running) {
            if (this.performer != performer) {
                throw new IllegalStateException("Manager is already bound to another Performer");
            }
            return;
        }
        this.performer = performer;
        ++this.generation;
        try {
            HttpServer httpServer = performer.getHttpServer();
            if (!this.initializedHttpServers.contains(httpServer)) {
                this.setupHandler();
                this.initializedHttpServers.add(httpServer);
            }
            App.getInstance().start();
            performer.addTickable(this);
            performer.setListener(AIGCCellet.NAME, this);
            this.lastTickTime = System.currentTimeMillis();
            this.running = true;
            this.completions.start(performer);
        } catch (RuntimeException | Error e) {
            performer.removeListener(AIGCCellet.NAME, this);
            performer.removeTickable(this);
            App.getInstance().stop();
            throw e;
        }
    }

    /**
     * 停止管理器。
     *
     * <p>未运行时直接返回。停止完成网关、摘除回调与监听器、停止 App，
     * 并在 finally 中把所有未完成的请求置为 {@code Cancelled} 后清空各类等待表——
     * 因此停止操作不会留下悬挂的调用方。</p>
     */
    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        this.completions.stop();
        ++this.generation;
        try {
            this.performer.removeListener(AIGCCellet.NAME, this);
            this.performer.removeTickable(this);
            App.getInstance().stop();
        } finally {
            for (PendingRequest pending : this.pendingRequests.values()) {
                pending.fail(AIGCStateCode.Cancelled);
            }
            this.pendingRequests.clear();
            this.pendingFutures.clear();
            this.validTokenMap.clear();
            this.textToFileFutureMap.clear();
            this.speechRecognitionFutureMap.clear();
            this.speechEmotionRecognitionFutureMap.clear();
            this.speechDiarizationFutureMap.clear();
            this.facialExpressionRecognitionFutureMap.clear();
        }
    }

    private synchronized Performer activePerformer() {
        return this.running ? this.performer : null;
    }

    /**
     * 获取最近一次绑定的执行机。
     *
     * <p>停止后仍保留引用，因此非空不代表管理器正在运行。</p>
     *
     * @return 执行机；从未启动过时为 {@code null} 。
     */
    public Performer getPerformer() {
        return this.performer;
    }

    /**
     * 不持有生命周期锁等待网络；已经开始的同步请求可以正常结束。
     */
    private ActionDialect syncTransmit(String cellet, ActionDialect request) {
        Performer current;
        synchronized (this) {
            if (!this.running) {
                return null;
            }
            current = this.performer;
        }
        return current.syncTransmit(cellet, request);
    }

    private ActionDialect syncTransmit(String cellet, ActionDialect request, long timeout) {
        Performer current;
        synchronized (this) {
            if (!this.running) {
                return null;
            }
            current = this.performer;
        }
        return current.syncTransmit(cellet, request, timeout);
    }

    private <K, F extends JSONable> F submitFuture(Packet packet, String token,
                                                   Map<K, F> cache, K key, F future, boolean reset) {
        final PendingRequest pending;
        final Performer current;
        synchronized (this) {
            if (!this.running || isBlank(token)) {
                return null;
            }
            F previous = cache.get(key);
            if (null != previous) {
                boolean expired = System.currentTimeMillis() - futureTimestamp(previous) >= FUTURE_CACHE_TTL;
                if (!reset && !expired) {
                    return previous;
                }
                this.detachFuture(previous, expired ? AIGCStateCode.Expired : AIGCStateCode.Cancelled);
            }
            current = this.performer;
            pending = new PendingRequest(packet.sn, packet.name, key, cache, future);
            cache.put(key, future);
            this.pendingRequests.put(packet.sn, pending);
            this.pendingFutures.put(future, pending);
        }
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        boolean sent;
        try {
            sent = current.tryTransmit(AIGCCellet.NAME, request);
        } catch (CapabilityUnavailableException e) {
            synchronized (this) {
                pending.fail(AIGCStateCode.UnitNoReady);
                this.removePending(pending);
                cache.remove(key, future);
            }
            throw e;
        } catch (RuntimeException e) {
            Logger.w(Manager.class, "#submitFuture - " + packet.name, e);
            sent = false;
        }
        synchronized (this) {
            if (!sent && this.pendingRequests.get(packet.sn) == pending) {
                pending.fail(AIGCStateCode.Failure);
                this.removePending(pending);
            }
        }
        return future;
    }

    private void removePending(PendingRequest pending) {
        this.pendingRequests.remove(pending.sn, pending);
        this.pendingFutures.remove(pending.future);
    }

    private void detachFuture(JSONable future, AIGCStateCode state) {
        PendingRequest pending = this.pendingFutures.get(future);
        if (null != pending) {
            pending.fail(state);
            this.removePending(pending);
        }
    }

    private static long futureTimestamp(JSONable future) {
        if (future instanceof TextToFileFuture) {
            return ((TextToFileFuture) future).timestamp;
        }
        if (future instanceof SpeechRecognitionFuture) {
            return ((SpeechRecognitionFuture) future).timestamp;
        }
        if (future instanceof SpeechEmotionRecognitionFuture) {
            return ((SpeechEmotionRecognitionFuture) future).timestamp;
        }
        if (future instanceof SpeechDiarizationFuture) {
            return ((SpeechDiarizationFuture) future).timestamp;
        }
        return ((FacialExpressionRecognitionFuture) future).timestamp;
    }

    private static void failFuture(JSONable future, AIGCStateCode state) {
        synchronized (future) {
            if (future instanceof TextToFileFuture) {
                TextToFileFuture f = (TextToFileFuture) future;
                if (f.stateCode == AIGCStateCode.Processing.code) {
                    f.stateCode = state.code;
                }
            } else if (future instanceof SpeechRecognitionFuture) {
                SpeechRecognitionFuture f = (SpeechRecognitionFuture) future;
                if (f.stateCode == AIGCStateCode.Processing) {
                    f.stateCode = state;
                }
            } else if (future instanceof SpeechEmotionRecognitionFuture) {
                SpeechEmotionRecognitionFuture f = (SpeechEmotionRecognitionFuture) future;
                if (f.stateCode == AIGCStateCode.Processing) {
                    f.stateCode = state;
                }
            } else if (future instanceof SpeechDiarizationFuture) {
                SpeechDiarizationFuture f = (SpeechDiarizationFuture) future;
                if (f.stateCode == AIGCStateCode.Processing) {
                    f.stateCode = state;
                }
            } else if (future instanceof FacialExpressionRecognitionFuture) {
                FacialExpressionRecognitionFuture f = (FacialExpressionRecognitionFuture) future;
                if (f.stateCode == AIGCStateCode.Processing) {
                    f.stateCode = state;
                }
            }
        }
    }

    private static final class PendingRequest {

        final long sn;
        final String action;
        final Object key;
        final Map<?, ?> cache;
        final JSONable future;

        PendingRequest(long sn, String action, Object key, Map<?, ?> cache, JSONable future) {
            this.sn = sn;
            this.action = action;
            this.key = key;
            this.cache = cache;
            this.future = future;
        }

        boolean isCurrent() {
            return this.cache.get(this.key) == this.future;
        }

        void fail(AIGCStateCode state) {
            failFuture(this.future, state);
        }
    }

    private void registerHandler(HttpServer server, ContextHandler handler) {
        for (ContextHandler existing : server.getContextHandlers()) {
            if (Objects.equals(existing.getContextPath(), handler.getContextPath())) {
                return;
            }
        }
        server.addContextHandler(handler);
    }

    private void setupHandler() {
        HttpServer httpServer = this.performer.getHttpServer();

        CompletionConfig completionConfig = new CompletionConfig(this.performer.getProperties());
        if (completionConfig.enabled)
            this.registerHandler(httpServer, new CompletionHandler(this.completions, completionConfig));

        this.registerHandler(httpServer, new Static());
        this.registerHandler(httpServer, new InterfaceDocument());

        this.registerHandler(httpServer, new Segmentation());
        this.registerHandler(httpServer, new Channel());
        this.registerHandler(httpServer, new StopProcessing());
        this.registerHandler(httpServer, new Chat());
        this.registerHandler(httpServer, new MultimodalBase());
        this.registerHandler(httpServer, new MultimodalStream());
        this.registerHandler(httpServer, new SpecializedDetectionHandler(false));
        this.registerHandler(httpServer, new SpecializedDetectionHandler(true));
        this.registerHandler(httpServer, new Summarization());
        this.registerHandler(httpServer, new SemanticSearch());
        this.registerHandler(httpServer, new SpeechEmotionRecognition());
        this.registerHandler(httpServer, new AutomaticSpeechRecognition());
        this.registerHandler(httpServer, new FacialExpressionRecognition());
        this.registerHandler(httpServer, new SpeechDiarization());
        this.registerHandler(httpServer, new SpeechDiarizationOperation());
        this.registerHandler(httpServer, new KnowledgeQA());
        this.registerHandler(httpServer, new KnowledgeProfiles());
        this.registerHandler(httpServer, new KnowledgeInfos());
        this.registerHandler(httpServer, new NewKnowledgeBase());
        this.registerHandler(httpServer, new DeleteKnowledgeBase());
        this.registerHandler(httpServer, new UpdateKnowledgeBase());
        this.registerHandler(httpServer, new KnowledgeDocs());
        this.registerHandler(httpServer, new ImportKnowledgeDoc());
        this.registerHandler(httpServer, new RemoveKnowledgeDoc());
        this.registerHandler(httpServer, new ResetKnowledgeStore());
        this.registerHandler(httpServer, new KnowledgeSegments());
        this.registerHandler(httpServer, new KnowledgeBackup());
        this.registerHandler(httpServer, new KnowledgeArticles());
        this.registerHandler(httpServer, new AppendKnowledgeArticle());
        this.registerHandler(httpServer, new RemoveKnowledgeArticle());
        this.registerHandler(httpServer, new ActivateKnowledgeArticle());
        this.registerHandler(httpServer, new DeactivateKnowledgeArticle());
        this.registerHandler(httpServer, new QueryAllArticleCategories());
        this.registerHandler(httpServer, new GenerateKnowledge());
        this.registerHandler(httpServer, new SearchResults());
        this.registerHandler(httpServer, new ContextInference());
        this.registerHandler(httpServer, new ChartData());
        this.registerHandler(httpServer, new Prompts());
        this.registerHandler(httpServer, new SubmitEvent());
        this.registerHandler(httpServer, new QueryAppEvents());
        this.registerHandler(httpServer, new QueryUsages());
        this.registerHandler(httpServer, new ChatHistory());
        this.registerHandler(httpServer, new TextToFile());
        this.registerHandler(httpServer, new GetQueueCount());
        this.registerHandler(httpServer, new ApplyStream());

        // ⚠️ 语音基础能力的端点（语音识别 /aigc/speech/recognition、
        // 说话人分离 /aigc/speech/diarization 及其 opt、情绪识别
        // /aigc/speech/emotion、语音流申请 /aigc/stream/apply/）
        // 属宿主功能实现，仍在本类内联注册，不经插件注入。
        // 另：/aigc/chart/data 由宿主图表任务处理，亦保留在此。
        //
        // ⚠️ 与之相对，「分析语音内容」（/aigc/speech/analysis）与
        // 「停止语音流」（/aigc/stream/stop/）路由的是心理学插件的动作，
        // 注册权已移交插件；说话人视图 /aigc/chart/ 与 /aigc/cot/ 同理。
        // ⚠️ 绘画图表数据（/aigc/chart/data）与说话人视图
        //（/aigc/chart/、/aigc/cot/）中，前者由宿主图表任务处理，
        // 保留在此；后两者路由插件动作，已移交插件。

        // 业务模块兜底通道 POST /aigc/module/{moduleName}/{actionName}。
        //
        // ⚠️ 受配置开关控制（config/dispatcher.properties 的 module.rest.enabled），
        // 默认关闭：关闭时该路径不注册，专属端点不受影响。
        // 专属端点（上面 16 条）始终保留，兜底通道不替代它们。
        if (ModuleAction.isEnabled()) {
            this.registerHandler(httpServer, new ModuleAction());
            Logger.i(Manager.class, "#setupHandler - Module fallback channel enabled at " + ModuleAction.PATH);
        } else {
            Logger.i(Manager.class, "#setupHandler - Module fallback channel is DISABLED");
        }

        // 业务模块的网关扩展：模块声明自己拥有的 REST 前缀，并提供自己的
        // ContextHandler 实例；本侧只按类名反射装载、做路径去重后注册。
        // 详见 cube.dispatcher.aigc.spi.DispatcherExtension 的 javadoc。
        this.dispatcherExtensions = DispatcherExtensions.load();
        Set<String> modulePrefixes = DispatcherExtensions.collectPrefixes(this.dispatcherExtensions);
        if (!modulePrefixes.isEmpty()) {
            Logger.i(Manager.class, "#setupHandler - Module REST prefixes: " + modulePrefixes);
        }

        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Activate());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.User());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.UserModify());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Profile());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Membership());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.AppVersion());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.ASCIIArt());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.WordCloud());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Emotion());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.UserSignOut());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Session());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Verify());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Config());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Change());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Chat());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Evaluate());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.KeepAlive());
        this.registerHandler(httpServer, new cube.dispatcher.aigc.handler.app.Inject());

        // 业务模块自有的 REST 端点：排在全部宿主端点之后，
        // 使宿主端点在路径重复时天然优先（重复项会被记 ERROR 并跳过）。
        DispatcherExtensions.registerEndpointHandlers(this.dispatcherExtensions, httpServer);
    }

    /**
     * 向 AIGC 服务发送同步请求。
     *
     * <p>统一超时 90 秒。响应为空、超时或状态码非 {@code Ok} 都返回 {@code null} ，
     * 调用方拿到 {@code null} 即表示本次业务失败，不需再区分具体原因。</p>
     *
     * @param token  访问令牌。
     * @param action 动作名。
     * @param data   请求载荷。
     * @return 响应中的数据载荷；失败返回 {@code null} 。
     */
    public JSONObject syncRequest(String token, AIGCAction action, JSONObject data) {
        Packet packet = new Packet(action.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 90 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#syncRequest - Response is null, action: " + action.name);
            return null;
        }

        Packet responsePacket = new Packet(response);
        int state = Packet.extractCode(responsePacket);
        if (AIGCStateCode.Ok.code != state) {
            Logger.w(Manager.class, "#syncRequest - " + action.name + " response state is " + state);
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 校验令牌是否有效。
     *
     * @param token  访问令牌。
     * @param device 设备信息，用于绑定校验。
     * @return 有效返回 {@code true} 。
     */
    public boolean checkToken(String token, Device device) {
        return (null != this.checkAndGetToken(token, device));
    }

    /**
     * Module-owned actions need no central enum; preserve the full response code.
     */
    public Packet syncModuleRequest(String token, String module, String action, String requiredUnit,
                                    JSONObject data, long timeoutMs) {
        if (module == null || !module.matches("[a-z][a-z0-9-]{0,63}")
                || action == null || !action.matches("[A-Za-z][A-Za-z0-9]{0,95}")
                || timeoutMs <= 0 || timeoutMs > 3600000)
            throw new IllegalArgumentException("Invalid module request");
        ActionDialect request = new Packet(action, data).toDialect();
        request.addParam("token", token);
        request.addParam("moduleName", module);
        request.addParam("moduleUnit", requiredUnit == null ? "" : requiredUnit);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, timeoutMs);
        if (response == null)
            throw new cube.aigc.spi.PluginCallException(AIGCStateCode.UnitError, 504, "Module request timed out");
        return new Packet(response);
    }

    /**
     * 校验令牌并返回归一化后的令牌码。
     *
     * <p>令牌可能已过期或需要按设备重新换取，返回值可直接用于后续请求。</p>
     *
     * @param token  访问令牌。
     * @param device 设备信息。
     * @return 归一化后的令牌码；校验失败返回 {@code null} 。
     */
    public String checkAndGetToken(String token, Device device) {
        ContactToken contactToken = this.resolveContactToken(token, device);
        return null == contactToken ? null : contactToken.authToken.getCode();
    }

    private ContactToken resolveContactToken(String token, Device device) {
        if (isBlank(token) || null == device || isBlank(device.getName())) {
            return null;
        }
        final long requestGeneration;
        final Performer current;
        synchronized (this) {
            if (!this.running) {
                return null;
            }
            requestGeneration = this.generation;
            current = this.performer;
            ContactToken contactToken = this.validTokenMap.get(token);
            if (null != contactToken) {
                if (isTokenExpired(contactToken, System.currentTimeMillis())) {
                    this.validTokenMap.remove(token, contactToken);
                } else if (device.isUnknown() ||
                        device.getName().equalsIgnoreCase(contactToken.device.getName())) {
                    return contactToken;
                }
            }
        }
        try {
            JSONObject data = new JSONObject();
            data.put(token.length() == 6 ? "invitation" : "token", token);
            data.put("device", device.toJSON());
            Packet packet = new Packet(AIGCAction.CheckToken.name, data);
            ActionDialect response = current.syncTransmit(AIGCCellet.NAME, packet.toDialect());
            if (null == response) {
                return null;
            }
            Packet responsePacket = new Packet(response);
            if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
                return null;
            }
            JSONObject payload = Packet.extractDataPayload(responsePacket);
            JSONObject tokenJson = payload.getJSONObject("token");
            // AuthToken 构造函数吞掉 JSON 异常，在构造前校验必需字段。
            if (isBlank(tokenJson.getString("code"))) {
                return null;
            }
            tokenJson.getString("domain");
            tokenJson.getString("appKey");
            tokenJson.getLong("cid");
            tokenJson.getLong("issue");
            tokenJson.getLong("expiry");
            AuthToken authToken = new AuthToken(tokenJson);
            JSONObject contactJson = payload.getJSONObject("contact");
            contactJson.getLong("id");
            ContactToken contactToken = new ContactToken(authToken, new Contact(contactJson), device);
            synchronized (this) {
                if (!this.running || this.generation != requestGeneration ||
                        isTokenExpired(contactToken, System.currentTimeMillis())) {
                    return null;
                }
                this.validTokenMap.put(authToken.getCode(), contactToken);
                return contactToken;
            }
        } catch (RuntimeException e) {
            Logger.w(Manager.class, "#resolveContactToken - Invalid authentication response", e);
            return null;
        }
    }

    /**
     * 获取令牌对应的联系人令牌对象。
     *
     * @param token  访问令牌。
     * @param device 设备信息。
     * @return 联系人令牌；解析失败返回 {@code null} 。
     */
    public ContactToken getContactToken(String token, Device device) {
        return this.resolveContactToken(token, device);
    }

    /**
     * 清除指定令牌的本地缓存。
     *
     * <p>令牌失效或主动登出后调用，避免继续使用过期信息。</p>
     *
     * @param token 访问令牌。
     */
    public void removeTokenCache(String token) {
        if (null != token) {
            this.validTokenMap.remove(token);
        }
    }

    private static boolean isBlank(String value) {
        return null == value || value.trim().isEmpty();
    }

    private static boolean isTokenExpired(ContactToken token, long now) {
        return now - token.timestamp >= TOKEN_CACHE_TTL || token.authToken.getExpiry() <= now;
    }

    /**
     * 获取或创建用户。
     *
     * <p>按载荷中的身份信息查找用户，不存在时创建；同一身份重复调用不会产生重复用户。</p>
     *
     * @param data 用户身份载荷。
     * @return 用户 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getOrCreateUser(JSONObject data) {
        Packet packet = new Packet(AIGCAction.AppGetOrCreateUser.name, data);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, packet.toDialect());
        if (null == response) {
            Logger.w(Manager.class, "#getOrCreateUser - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getOrCreateUser - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 修改用户资料。
     *
     * @param token        访问令牌。
     * @param modification 需要修改的字段，只提交有变化的项。
     * @return 修改后的用户 JSON ；失败返回 {@code null} 。
     */
    public JSONObject modifyUser(String token, JSONObject modification) {
        Packet packet = new Packet(AIGCAction.AppModifyUser.name, modification);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#modifyUser - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        int state = Packet.extractCode(responsePacket);
        if (AIGCStateCode.Ok.code != state) {
            Logger.w(Manager.class, "#modifyUser - Response state is " + state);
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 用户签到。
     *
     * <p>地址由调用方传入并写入载荷，用于记录签到位置。</p>
     *
     * @param token   访问令牌。
     * @param data    签到载荷。
     * @param address 签到地址。
     * @return 签到结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject checkInUser(String token, JSONObject data, String address) {
        data.put("address", address);
        Packet packet = new Packet(AIGCAction.AppCheckInUser.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#checkInUser - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        int state = Packet.extractCode(responsePacket);
        if (AIGCStateCode.IllegalOperation.code == state) {
            JSONObject json = new JSONObject();
            json.put("valid", false);
            return json;
        } else if (AIGCStateCode.Ok.code == state) {
            JSONObject json = new JSONObject();
            json.put("valid", true);
            json.put("user", Packet.extractDataPayload(responsePacket));
            return json;
        }

        Logger.w(Manager.class, "#checkInUser - Response state is " + state);
        return null;
    }

    /**
     * 注销用户。
     *
     * <p>注销后本地令牌缓存一并清除，客户端需重新登录。</p>
     *
     * @param token 访问令牌。
     * @return 注销结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject signOutUser(String token) {
        Packet packet = new Packet(AIGCAction.AppSignOutUser.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#signOutUser - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#signOutUser - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        this.removeTokenCache(token);
        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取用户画像。
     *
     * @param token 访问令牌。
     * @return 用户画像；失败返回 {@code null} 。
     */
    public UserProfile getUserProfile(String token) {
        Packet packet = new Packet(AIGCAction.AppGetUserProfile.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getUserProfile - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getUserProfile - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new UserProfile(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 激活会员。
     *
     * @param token      访问令牌。
     * @param channel    购买渠道。
     * @param invitation 邀请码，可为 {@code null} 。
     * @return 会员信息；激活失败返回 {@code null} 。
     */
    public Membership activateMembership(String token, String channel, String invitation) {
        JSONObject data = new JSONObject();
        data.put("channel", channel);
        data.put("invitation", invitation);
        Packet packet = new Packet(AIGCAction.AppActivateMembership.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#activateMembership - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#activateMembership - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new Membership(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 获取 App 版本信息。
     *
     * @param token 访问令牌。
     * @return 版本信息 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getAppVersion(String token) {
        Packet packet = new Packet(AIGCAction.AppVersion.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getAppVersion - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getAppVersion - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取字符画。
     *
     * <p>用于客户端启动页等场景，内容由服务端下发。</p>
     *
     * @param token 访问令牌。
     * @return 字符画 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getASCIIArt(String token) {
        Packet packet = new Packet(AIGCAction.AppASCIIArt.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getASCIIArt - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getASCIIArt - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取词云数据。
     *
     * @param token 访问令牌。
     * @return 词云 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getWordCloud(String token) {
        Packet packet = new Packet(AIGCAction.GetWordCloud.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getWordCloud - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getWordCloud - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 按手机号校验或注入联系人令牌。
     *
     * <p>手机号已注册则返回已有令牌，未注册则按用户名创建联系人并签发新令牌。
     * 返回的令牌绑定固定设备标识 {@code Unknown} ，不适用于需要设备校验的场景。</p>
     *
     * @param phoneNumber 手机号码。
     * @param userName    用户名，仅在新签发时使用，可为 {@code null} 。
     * @return 联系人令牌；失败返回 {@code null} 。
     */
    public ContactToken checkOrInjectContactToken(String phoneNumber, String userName) {
        JSONObject data = new JSONObject();
        data.put("phone", phoneNumber);
        if (null != userName) {
            data.put("name", userName);
        }

        Packet packet = new Packet(AIGCAction.AppInjectOrGetToken.name, data);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, packet.toDialect());
        if (null == response) {
            Logger.w(Manager.class, "#checkOrInjectContactToken - Response is null : " + phoneNumber);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#checkOrInjectContactToken - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject responseJson = Packet.extractDataPayload(responsePacket);
        AuthToken authToken = new AuthToken(responseJson.getJSONObject("token"));
        Contact contact = new Contact(responseJson.getJSONObject("contact"));
        return new ContactToken(authToken, contact, new Device("Unknown", "Unknown"));
    }

    /**
     * 获取模型与系统配置。
     *
     * @param token 访问令牌。
     * @return 配置信息；失败返回 {@code null} 。
     */
    public ConfigInfo getConfigInfo(String token) {
        JSONObject data = new JSONObject();
        Packet packet = new Packet(AIGCAction.GetConfig.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getConfigData - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getConfigData - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new ConfigInfo(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 按模型标识查找模型配置。
     *
     * <p>遍历全部模型并忽略大小写比较，模型数量通常很少，无需索引。</p>
     *
     * @param configInfo 配置信息。
     * @param model      模型标识。
     * @return 匹配的配置；未找到返回 {@code null} 。
     */
    public ModelConfig getModelConfigByModel(ConfigInfo configInfo, String model) {
        for (ModelConfig config : configInfo.models) {
            if (config.getModel().equalsIgnoreCase(model)) {
                return config;
            }
        }

        return null;
    }

    /**
     * 按模型名称查找模型配置。
     *
     * <p>与按标识查找的区别：{@code model} 匹配模型标识，这里匹配模型名称，两者都忽略大小写。</p>
     *
     * @param configInfo 配置信息。
     * @param name       模型名称。
     * @return 匹配的配置；未找到返回 {@code null} 。
     */
    public ModelConfig getModelConfigByName(ConfigInfo configInfo, String name) {
        for (ModelConfig config : configInfo.models) {
            if (config.getName().equalsIgnoreCase(name)) {
                return config;
            }
        }

        return null;
    }

    /**
     * 获取知识库概要。
     *
     * @param token 访问令牌。
     * @return 知识库概要；失败返回 {@code null} 。
     */
    public KnowledgeProfile getKnowledgeProfile(String token) {
        Packet packet = new Packet(AIGCAction.GetKnowledgeProfile.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeProfile - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeProfile - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeProfile(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 更新知识库概要。
     *
     * <p>三个可选项各自支持“不修改”：{@code state} 与 {@code maxSize} 传 {@code -1} 、
     * {@code scope} 传 {@code null} 时对应字段不会写入请求。</p>
     *
     * @param token     访问令牌。
     * @param contactId 知识库归属联系人 ID 。
     * @param state     知识库状态，{@code -1} 表示不修改。
     * @param maxSize   容量上限，{@code -1} 表示不修改。
     * @param scope     可见范围，{@code null} 表示不修改。
     * @return 更新后的概要；失败返回 {@code null} 。
     */
    public KnowledgeProfile updateKnowledgeProfile(String token, long contactId,
                                                   int state, long maxSize, KnowledgeScope scope) {
        JSONObject data = new JSONObject();
        data.put("contactId", contactId);
        if (-1 != state) {
            data.put("state", state);
        }
        if (-1 != maxSize) {
            data.put("maxSize", maxSize);
        }
        if (null != scope) {
            data.put("scope", scope.name);
        }

        Packet packet = new Packet(AIGCAction.UpdateKnowledgeProfile.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#updateKnowledgeProfile - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#updateKnowledgeProfile - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeProfile(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 获取知识库框架。
     *
     * <p>框架描述知识库的分层分类结构，用于前端导航渲染。</p>
     *
     * @param token 访问令牌。
     * @return 框架 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getKnowledgeFramework(String token) {
        Packet packet = new Packet(AIGCAction.GetKnowledgeFramework.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeFramework - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeFramework - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 新建知识库。
     *
     * @param token       访问令牌。
     * @param baseName    知识库内部名称，需在域内唯一。
     * @param displayName 展示名称。
     * @param category    分类。
     * @param scope       可见范围。
     * @return 新建的知识库信息；失败返回 {@code null} 。
     */
    public KnowledgeBaseInfo newKnowledgeBase(String token, String baseName, String displayName,
                                              String category, KnowledgeScope scope) {
        JSONObject payload = new JSONObject();
        payload.put("name", baseName);
        payload.put("displayName", displayName);
        if (null != category && category.length() > 0) {
            payload.put("category", category);
        } else {
            payload.put("category", displayName);
        }
        if (null != scope) {
            payload.put("scope", scope.name);
        }
        Packet packet = new Packet(AIGCAction.NewKnowledgeBase.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#newKnowledgeBase - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#newKnowledgeBase - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeBaseInfo(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 删除知识库。
     *
     * <p>名称为空时直接返回 {@code null} ，不发请求。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @return 被删除的知识库信息；失败返回 {@code null} 。
     */
    public KnowledgeBaseInfo deleteKnowledgeBase(String token, String baseName) {
        if (null == baseName) {
            return null;
        }

        Logger.d(this.getClass(), "#deleteKnowledgeBase - " + baseName + " - " + token);

        JSONObject payload = new JSONObject();
        payload.put("name", baseName);
        Packet packet = new Packet(AIGCAction.DeleteKnowledgeBase.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#deleteKnowledgeBase - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#deleteKnowledgeBase - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeBaseInfo(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 更新知识库属性。
     *
     * <p>整个信息对象会序列化为载荷提交，未被修改的字段同样会发送。</p>
     *
     * @param token 访问令牌。
     * @param info  知识库信息。
     * @return 更新后的信息；失败返回 {@code null} 。
     */
    public KnowledgeBaseInfo updateKnowledgeBase(String token, KnowledgeBaseInfo info) {
        JSONObject payload = new JSONObject();
        payload.put("info", info.toJSON());
        Packet packet = new Packet(AIGCAction.UpdateKnowledgeBase.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#updateKnowledgeBase - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#updateKnowledgeBase - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeBaseInfo(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 列出知识库中的文档。
     *
     * <p>超时 60 秒：文档较多时服务端需要遍历存储，比普通接口更慢。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @return 文档列表 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getKnowledgeDocs(String token, String baseName) {
        Logger.d(this.getClass(), "#getKnowledgeDocs - " + baseName + " - " + token);

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        Packet packet = new Packet(AIGCAction.ListKnowledgeDocs.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeDocs - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeDocs - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        try {
            JSONObject data = Packet.extractDataPayload(responsePacket);
            if (data.has("page")) {
                // 有分页
                int page = data.getInt("page");
                int total = data.getInt("total");
                JSONArray list = data.getJSONArray("list");
                while (list.length() < total) {
                    page += 1;

                    JSONObject packetPayload = new JSONObject();
                    packetPayload.put("base", baseName);
                    packetPayload.put("page", page);
                    packet = new Packet(AIGCAction.ListKnowledgeDocs.name, packetPayload);
                    request = packet.toDialect();
                    request.addParam("token", token);
                    response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
                    if (null == response) {
                        Logger.w(Manager.class, "#getKnowledgeDocs - Response is null : " + token);
                        break;
                    }

                    responsePacket = new Packet(response);
                    if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
                        Logger.d(Manager.class, "#getKnowledgeDocs - Response state is NOT ok : "
                                + Packet.extractCode(responsePacket));
                        break;
                    }

                    JSONObject nextData = Packet.extractDataPayload(responsePacket);
                    JSONArray nextList = nextData.getJSONArray("list");
                    if (nextList.length() == 0) {
                        Logger.d(Manager.class, "#getKnowledgeDocs - List is empty: "
                                + Packet.extractCode(responsePacket));
                        break;
                    }

                    for (int i = 0; i < nextList.length(); ++i) {
                        list.put(nextList.getJSONObject(i));
                    }
                }

                JSONObject result = new JSONObject();
                result.put("total", list.length());
                result.put("list", list);
                return result;
            } else {
                return data;
            }
        } catch (Exception e) {
            Logger.e(Manager.class, "#getKnowledgeDocs - Error", e);
            return null;
        }
    }

    /**
     * 导入单个文档到知识库。
     *
     * <p>按指定的切分策略抽取文本并入库，返回入库后的文档描述。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param fileCode 源文件码。
     * @param splitter 文本切分策略。
     * @return 入库后的文档；失败返回 {@code null} 。
     */
    public KnowledgeDocument importKnowledgeDoc(String token, String baseName, String fileCode, TextSplitter splitter) {
        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("fileCode", fileCode);
        payload.put("splitter", splitter.name);
        Packet packet = new Packet(AIGCAction.ImportKnowledgeDoc.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#importKnowledgeDoc - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#importKnowledgeDoc - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeDocument(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 批量导入文档到知识库。
     *
     * <p>返回进度对象而非文档列表，调用方需轮询进度查询接口获取后续进展。</p>
     *
     * @param token         访问令牌。
     * @param baseName      知识库内部名称。
     * @param fileCodeArray 源文件码数组。
     * @param splitter      文本切分策略。
     * @return 导入进度；失败返回 {@code null} 。
     */
    public KnowledgeProgress importKnowledgeDocs(String token, String baseName, JSONArray fileCodeArray, TextSplitter splitter) {
        Logger.d(this.getClass(), "#importKnowledgeDocs - " + baseName + " - " + token);

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("fileCodeList", fileCodeArray);
        payload.put("splitter", splitter.name);
        Packet packet = new Packet(AIGCAction.ImportKnowledgeDoc.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#importKnowledgeDocs - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#importKnowledgeDocs - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeProgress(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 从知识库移除单个文档。
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param fileCode 源文件码。
     * @return 被移除的文档；失败返回 {@code null} 。
     */
    public KnowledgeDocument removeKnowledgeDoc(String token, String baseName, String fileCode) {
        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("fileCode", fileCode);
        Packet packet = new Packet(AIGCAction.RemoveKnowledgeDoc.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#removeKnowledgeDoc - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#removeKnowledgeDoc - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeDocument(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 批量移除知识库文档。
     *
     * <p>返回进度对象，调用方需轮询后续进展。</p>
     *
     * @param token         访问令牌。
     * @param baseName      知识库内部名称。
     * @param fileCodeArray 源文件码数组。
     * @return 移除进度；失败返回 {@code null} 。
     */
    public KnowledgeProgress removeKnowledgeDocs(String token, String baseName, JSONArray fileCodeArray) {
        Logger.d(this.getClass(), "#removeKnowledgeDocs - " + baseName + " - " + token);

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("fileCodeList", fileCodeArray);
        Packet packet = new Packet(AIGCAction.RemoveKnowledgeDoc.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#removeKnowledgeDocs - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#removeKnowledgeDocs - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeProgress(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 读取文档的文本段。
     *
     * <p>按段序号区间取回，用于前端分页展示与引用溯源。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param docId    文档 ID 。
     * @param start    起始段序号。
     * @param end      结束段序号。
     * @return 段内容 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getKnowledgeSegments(String token, String baseName, long docId, int start, int end) {
        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("docId", docId);
        payload.put("start", start);
        payload.put("end", end);
        Packet packet = new Packet(AIGCAction.GetKnowledgeSegments.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeDocSegments - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeDocSegments - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 查询导入或移除进度。
     *
     * <p>名称为空时直接返回 {@code null} 。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param sn       进度序列号，来自批量操作的返回值。
     * @return 进度对象；失败返回 {@code null} 。
     */
    public KnowledgeProgress getKnowledgeProgress(String token, String baseName, long sn) {
        if (null == baseName) {
            return null;
        }

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("sn", sn);
        Packet packet = new Packet(AIGCAction.GetKnowledgeProgress.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeProgress - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeProgress - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeProgress(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 查询重构进度。
     *
     * @param token    访问令牌。
     * @param sn       进度序列号。
     * @param baseName 知识库内部名称。
     * @return 重构进度；失败返回 {@code null} 。
     */
    public ResetKnowledgeProgress getResetKnowledgeProgress(String token, long sn, String baseName) {
        if (null == baseName) {
            return null;
        }

        JSONObject payload = new JSONObject();
        payload.put("sn", sn);
        payload.put("base", baseName);
        Packet packet = new Packet(AIGCAction.GetResetKnowledgeProgress.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getResetKnowledgeProgress - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getResetKnowledgeProgress - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new ResetKnowledgeProgress(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 重构知识库存储。
     *
     * <p>重建索引与向量库，耗时较长，返回进度对象供轮询。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param backup   为 {@code true} 时先备份现有存储再重建。
     * @return 重构进度；失败返回 {@code null} 。
     */
    public ResetKnowledgeProgress resetKnowledgeStore(String token, String baseName, boolean backup) {
        if (null == baseName) {
            return null;
        }

        Logger.d(this.getClass(), "#resetKnowledgeStore - " + baseName + " - " + token);

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("backup", backup);
        Packet packet = new Packet(AIGCAction.ResetKnowledgeStore.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#resetKnowledgeStore - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#resetKnowledgeStore - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new ResetKnowledgeProgress(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 查询知识库备份。
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @return 备份列表 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getBackupKnowledgeStores(String token, String baseName) {
        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        Packet packet = new Packet(AIGCAction.GetBackupKnowledgeStores.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getBackupKnowledgeStores - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getBackupKnowledgeStores - Response state is NOT ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取知识库文章。
     *
     * @param token     访问令牌。
     * @param articleId 文章 ID 。
     * @return 文章 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getKnowledgeArticle(String token, long articleId) {
        JSONObject param = new JSONObject();
        param.put("articleId", articleId);
        Packet packet = new Packet(AIGCAction.ListKnowledgeArticles.name, param);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeArticle - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeArticle - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 按条件查询知识库文章。
     *
     * <p>超时 60 秒，与文档列表接口一致。</p>
     *
     * @param token     访问令牌。
     * @param base      知识库内部名称。
     * @param startTime 起始时间戳，单位毫秒。
     * @param endTime   结束时间戳，单位毫秒。
     * @param activated 激活状态筛选，取值由服务端定义。
     * @return 文章分页 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getKnowledgeArticles(String token, String base, long startTime, long endTime, int activated) {
        JSONObject param = new JSONObject();
        param.put("base", base);
        param.put("start", startTime);
        param.put("end", endTime);
        param.put("activated", activated);
        Packet packet = new Packet(AIGCAction.ListKnowledgeArticles.name, param);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeArticles - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#getKnowledgeArticles - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        try {
            JSONObject data = Packet.extractDataPayload(responsePacket);
            if (data.has("page")) {
                // 有分页
                int total = data.getInt("total");
                JSONArray list = data.getJSONArray("list");
                int page = data.getInt("page");
                while (list.length() < total) {
                    page += 1;

                    JSONObject packetPayload = new JSONObject();
                    packetPayload.put("page", page);
                    packetPayload.put("start", startTime);
                    packetPayload.put("end", endTime);
                    packet = new Packet(AIGCAction.ListKnowledgeArticles.name, packetPayload);
                    request = packet.toDialect();
                    request.addParam("token", token);
                    response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
                    if (null == response) {
                        Logger.w(Manager.class, "#getKnowledgeDocs - Response is null : " + token);
                        return null;
                    }

                    responsePacket = new Packet(response);
                    if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
                        Logger.d(Manager.class, "#getKnowledgeDocs - Response state is NOT ok : "
                                + Packet.extractCode(responsePacket));
                        return null;
                    }

                    JSONObject nextData = Packet.extractDataPayload(responsePacket);
                    JSONArray nextList = nextData.getJSONArray("list");
                    for (int i = 0; i < nextList.length(); ++i) {
                        list.put(nextList.getJSONObject(i));
                    }
                }

                JSONObject result = new JSONObject();
                result.put("total", total);
                result.put("list", list);
                return result;
            } else {
                return data;
            }
        } catch (Exception e) {
            Logger.e(Manager.class, "#getKnowledgeDocs - Error", e);
            return null;
        }
    }

    /**
     * 更新知识库文章。
     *
     * @param token   访问令牌。
     * @param article 文章对象，以其中的 ID 定位。
     * @return 更新后的文章；失败返回 {@code null} 。
     */
    public KnowledgeArticle updateKnowledgeArticle(String token, KnowledgeArticle article) {
        Packet packet = new Packet(AIGCAction.UpdateKnowledgeArticle.name, article.toJSON());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#updateKnowledgeArticle - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#updateKnowledgeArticle - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeArticle(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 新增知识库文章。
     *
     * @param token   访问令牌。
     * @param article 文章对象。
     * @return 入库后的文章；失败返回 {@code null} 。
     */
    public KnowledgeArticle appendKnowledgeArticle(String token, KnowledgeArticle article) {
        Packet packet = new Packet(AIGCAction.AppendKnowledgeArticle.name, article.toJSON());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#appendKnowledgeArticle - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#appendKnowledgeArticle - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeArticle(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 删除知识库文章。
     *
     * @param token  访问令牌。
     * @param idList 文章 ID 数组。
     * @return 删除结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject removeKnowledgeArticle(String token, JSONArray idList) {
        JSONObject payload = new JSONObject();
        payload.put("ids", idList);
        Packet packet = new Packet(AIGCAction.RemoveKnowledgeArticle.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#removeKnowledgeArticle - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#removeKnowledgeArticle - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 批量激活知识库文章。
     *
     * <p>激活后文章才参与检索。该操作可能触发大量索引写入，超时放宽到 2 分钟。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param idList   文章 ID 数组。
     * @return 处理后的文章列表；失败返回 {@code null} 。
     */
    public List<KnowledgeArticle> activateKnowledgeArticle(String token, String baseName, JSONArray idList) {
        List<KnowledgeArticle> result = new ArrayList<>();

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("ids", idList);
        Packet packet = new Packet(AIGCAction.ActivateKnowledgeArticle.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 2 * 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#activateKnowledgeArticle - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#activateKnowledgeArticle - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONArray responseList = Packet.extractDataPayload(responsePacket).getJSONArray("articles");
        for (int i = 0; i < responseList.length(); ++i) {
            KnowledgeArticle article = new KnowledgeArticle(responseList.getJSONObject(i));
            result.add(article);
        }

        return result;
    }

    /**
     * 批量停用知识库文章。
     *
     * <p>停用后文章立即退出检索，但数据仍保留，可再次激活。超时放宽到 2 分钟。</p>
     *
     * @param token    访问令牌。
     * @param baseName 知识库内部名称。
     * @param idList   文章 ID 数组。
     * @return 处理后的文章列表；失败返回 {@code null} 。
     */
    public List<KnowledgeArticle> deactivateKnowledgeArticle(String token, String baseName, JSONArray idList) {
        List<KnowledgeArticle> result = new ArrayList<>();

        JSONObject payload = new JSONObject();
        payload.put("base", baseName);
        payload.put("ids", idList);
        Packet packet = new Packet(AIGCAction.DeactivateKnowledgeArticle.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#deactivateKnowledgeArticle - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#deactivateKnowledgeArticle - Response state is NOT ok : " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONArray responseList = Packet.extractDataPayload(responsePacket).getJSONArray("articles");
        for (int i = 0; i < responseList.length(); ++i) {
            KnowledgeArticle article = new KnowledgeArticle(responseList.getJSONObject(i));
            result.add(article);
        }

        return result;
    }

    /**
     * 查询全部文章分类。
     *
     * @param token 访问令牌。
     * @return 分类 JSON ；失败返回 {@code null} 。
     */
    public JSONObject queryAllArticleCategories(String token) {
        Packet packet = new Packet(AIGCAction.QueryAllArticleCategories.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#queryAllArticleCategories - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#queryAllArticleCategories - Response state is NOT Ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 基于知识库生成回答。
     *
     * @param token    访问令牌。
     * @param query    用户问题。
     * @param baseName 知识库内部名称。
     * @param topK     召回片段数量上限。
     * @return 生成结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject generateKnowledge(String token, String query, String baseName, int topK) {
        JSONObject param = new JSONObject();
        param.put("query", query);
        param.put("base", baseName);
        param.put("topK", topK <= 0 ? 5 : topK);
        Packet packet = new Packet(AIGCAction.GenerateKnowledge.name, param);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#generateKnowledge - Response is null : " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.d(Manager.class, "#generateKnowledge - Response state is NOT Ok : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 为指定回答提交评价。
     *
     * <p>反馈值必须为非负数，负数视为非法参数直接返回 {@code false} 。</p>
     *
     * @param token    访问令牌。
     * @param sn       回答的序列号。
     * @param feedback 评价分值。
     * @return 提交成功返回 {@code true} 。
     */
    public boolean evaluate(String token, long sn, int feedback) {
        if (feedback < 0) {
            return false;
        }

        JSONObject data = new JSONObject();
        data.put("sn", sn);
        data.put("feedback", feedback);
        Packet packet = new Packet(AIGCAction.Evaluate.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#evaluate - Response is null");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#evaluate - Response state code is NOT Ok - " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 上报应用事件。
     *
     * @param token    访问令牌。
     * @param appEvent 应用事件。
     * @return 上报成功返回 {@code true} 。
     */
    public boolean addAppEvent(String token, AppEvent appEvent) {
        Packet packet = new Packet(AIGCAction.AddAppEvent.name, appEvent.toJSON());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#addAppEvent - Response is null");
            return false;
        }

        Packet responsePacket = new Packet(response);
        return Packet.extractCode(responsePacket) == AIGCStateCode.Ok.code;
    }

    /**
     * 分页查询应用事件。
     *
     * @param token     访问令牌。
     * @param contactId 联系人 ID 。
     * @param event     事件名称，为空时不过滤。
     * @param start     起始时间戳，单位毫秒。
     * @param end       结束时间戳，单位毫秒。
     * @param pageIndex 页码，从 0 开始。
     * @param pageSize  每页条数。
     * @return 事件分页 JSON ；失败返回 {@code null} 。
     */
    public JSONObject queryAppEvents(String token, long contactId, String event, long start, long end,
                                     int pageIndex, int pageSize) {
        JSONObject requestData = new JSONObject();
        requestData.put("contactId", contactId);
        requestData.put("event", event);
        requestData.put("start", start);
        requestData.put("end", end);
        requestData.put("page", pageIndex);
        requestData.put("size", pageSize);

        Packet packet = new Packet(AIGCAction.QueryAppEvent.name, requestData);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#queryAppEvents - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#queryAppEvents - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 查询用量统计。
     *
     * @param token     访问令牌。
     * @param contactId 联系人 ID 。
     * @return 用量 JSON ；失败返回 {@code null} 。
     */
    public JSONObject queryUsages(String token, long contactId) {
        JSONObject requestData = new JSONObject();
        requestData.put("contactId", contactId);

        Packet packet = new Packet(AIGCAction.QueryUsages.name, requestData);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#queryUsages - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#queryUsages - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 查询对话历史。
     *
     * @param token       访问令牌。
     * @param channelCode 通道码，为空时按联系人维度查询。
     * @param contactId   联系人 ID 。
     * @param feedback    反馈筛选，取值由服务端定义。
     * @param start       起始时间戳，单位毫秒。
     * @param end         结束时间戳，单位毫秒。
     * @return 历史分页 JSON ；失败返回 {@code null} 。
     */
    public JSONObject queryChatHistory(String token, String channelCode, long contactId, int feedback, long start, long end) {
        JSONObject requestData = new JSONObject();
        if (null != channelCode) {
            requestData.put("channel", channelCode);
        }
        requestData.put("contactId", contactId);
        requestData.put("feedback", feedback);
        requestData.put("start", start);
        requestData.put("end", end);

        Packet packet = new Packet(AIGCAction.QueryChatHistory.name, requestData);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 30 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#queryChatHistory - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#queryChatHistory - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 申请对话通道，使用默认单元。
     *
     * @param token       访问令牌。
     * @param participant 参与者标识。
     * @return 通道对象；失败返回 {@code null} 。
     */
    public AIGCChannel requestChannel(String token, String participant) {
        return this.requestChannel(token, participant, ModelConfig.BAIZE_UNIT);
    }

    /**
     * 申请对话通道。
     *
     * <p>通道承载一次连续对话的上下文与历史，相同参与者重复申请会复用已有通道。</p>
     *
     * @param token       访问令牌。
     * @param participant 参与者标识。
     * @param unit        指定处理该通道的单元。
     * @return 通道对象；失败返回 {@code null} 。
     */
    public AIGCChannel requestChannel(String token, String participant, String unit) {
        JSONObject data = new JSONObject();
        data.put("token", token);
        data.put("participant", participant);
        data.put("unit", unit);
        Packet packet = new Packet(AIGCAction.RequestChannel.name, data);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, packet.toDialect());
        if (null == response) {
            Logger.w(Manager.class, "#requestChannel - Response is null : " + participant);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#requestChannel - Response state code is NOT Ok : " + participant +
                    " - " + Packet.extractCode(responsePacket));
            return null;
        }

        AIGCChannel channel = new AIGCChannel(Packet.extractDataPayload(responsePacket));
        return channel;
    }

    /**
     * 停止通道上正在进行的生成。
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @return 停止后的通道状态；失败返回 {@code null} 。
     */
    public AIGCChannel stopProcessing(String token, String channelCode) {
        JSONObject data = new JSONObject();
        data.put("code", channelCode);

        Packet packet = new Packet(AIGCAction.StopChannel.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#stopProcessing - Response is null : " + channelCode);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#stopProcessing - Response state code is NOT Ok: " + channelCode +
                    " - " + Packet.extractCode(responsePacket));
            return null;
        }

        return new AIGCChannel(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 查询通道信息。
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @return 通道 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getChannel(String token, String channelCode) {
        JSONObject data = new JSONObject();
        data.put("code", channelCode);

        Packet packet = new Packet(AIGCAction.GetChannelInfo.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getChannel - Response is null : " + channelCode);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#getChannel - Response state code is NOT Ok: " + channelCode +
                    " - " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 通道保活。
     *
     * <p>延长通道的空闲存活时间，避免长对话中途因超时被回收。该接口不校验令牌。</p>
     *
     * @param channelCode 通道码。
     * @return 保活成功返回 {@code true} 。
     */
    public boolean keepAliveChannel(String channelCode) {
        JSONObject data = new JSONObject();
        data.put("code", channelCode);
        Packet packet = new Packet(AIGCAction.KeepAliveChannel.name, data);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, packet.toDialect());
        if (null == response) {
            Logger.w(Manager.class, "#keepAliveChannel - Response is null, code : " + channelCode);
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#keepAliveChannel - Response state code is NOT Ok : " + channelCode +
                    " - " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 查询处理队列数量。
     *
     * @param token 访问令牌。
     * @return 队列计数 JSON ；失败返回 {@code null} 。
     */
    public JSONObject queryQueueCount(String token) {
        Packet packet = new Packet(AIGCAction.GetQueueCount.name, new JSONObject());
        ActionDialect dialect = packet.toDialect();
        dialect.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, dialect);
        if (null == response) {
            Logger.w(Manager.class, "#queryQueueCount - Response is null : " + token);
            return null;
        }
        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#queryQueueCount - Response state code is NOT Ok : " + Packet.extractCode(responsePacket));
            return null;
        }
        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 发起互动对话。
     *
     * <p>这是对话的主入口：同步返回 {@link ChatFuture} 句柄，实际的流式回答通过通道推送。
     * {@code pattern} 决定走普通对话还是知识库问答；{@code recordable} 为 {@code true} 时本轮问答才会写入历史。</p>
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @param pattern     对话模式，取值见 {@code Consts.PATTERN_*} 。
     * @param content     用户输入内容。
     * @param unit        指定处理该请求的单元。
     * @param option      生成选项，控制温度、长度等采样参数。
     * @param histories   需要携带的历史对话轮数。
     * @param records     额外附加的记录，允许为 {@code null} 。
     * @param recordable  是否把本轮问答写入历史。
     * @param networking  是否允许联网检索。
     * @param categories  知识库分类过滤，允许为 {@code null} 。
     * @return 对话任务句柄；失败返回 {@code null} 。
     */
    public ChatFuture chat(String token, String channelCode, String pattern, String content, String unit,
                           GeneratingOption option, int histories, JSONArray records,
                           boolean recordable, boolean networking, JSONArray categories) {
        JSONObject data = new JSONObject();
        data.put("token", token);
        data.put("channel", channelCode);
        data.put("pattern", pattern);
        data.put("content", content);
        data.put("option", option.toJSON());
        data.put("histories", histories);
        if (null != unit) {
            data.put("unit", unit);
        }
        if (null != records) {
            data.put("records", records);
        }
        if (null != categories) {
            data.put("categories", categories);
        }
        data.put("recordable", recordable);
        data.put("networking", networking);

        Packet responsePacket = null;
        Packet packet = new Packet(AIGCAction.Chat.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 5 * 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#chat - Response is null - " + channelCode);
            return null;
        }

        responsePacket = new Packet(response);

        if (Packet.extractCode(responsePacket) == AIGCStateCode.Processing.code) {
            AIGCChannel channel = new AIGCChannel(Packet.extractDataPayload(responsePacket));
            ChatFuture future = new ChatFuture(channel);
            future.end = false;
            return future;
        } else if (Packet.extractCode(responsePacket) == AIGCStateCode.Ok.code) {
            ChatFuture future = null;
            if (Consts.PATTERN_CHAT.equals(pattern)) {
                JSONObject responseData = Packet.extractDataPayload(responsePacket);
                if (responseData.has("processing") && responseData.has("channel")) {
                    // 来自 conversation 的结果
                    future = new ChatFuture(new AIGCChannel(responseData.getJSONObject("channel")));
                    future.end = false;
                } else {
                    GeneratingRecord record = new GeneratingRecord(responseData);
                    if (null == record.query) {
                        record.query = content;
                    }
                    future = new ChatFuture(record);
                    future.end = true;
                }
            } else if (Consts.PATTERN_KNOWLEDGE.equals(pattern)) {
                KnowledgeQAResult result = new KnowledgeQAResult(Packet.extractDataPayload(responsePacket));
                future = new ChatFuture(result);
                future.end = true;
            }

            return future;
        } else {
            Logger.w(Manager.class, "#chat - Response state code is NOT Ok - " + channelCode +
                    " - " + Packet.extractCode(responsePacket));
            return null;
        }
    }

    /**
     * 调用专项检测；保留单元状态码和 HTTP 错误语义。
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @param input       专项检测协议输入。
     * @return 专项结果；失败抛出 {@link SpecializedDetectionException}。
     */
    public JSONObject executeSpecializedDetection(String token, String channelCode, SpecializedDetectionInput input) {
        JSONObject data = new JSONObject().put("channel", channelCode).put("input", input.toJSON());
        Packet packet = new Packet(AIGCAction.SpecializedDetection.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        String key = "specialized.detection." + (input.isControl() ? "control" : "data") + ".timeout.ms";
        Properties config = performer.getProperties();
        String fallback = Integer.toString(input.isControl() ? cube.aigc.SpecializedDetectionDefaults.GATEWAY_CONTROL_TIMEOUT_MS
                : cube.aigc.SpecializedDetectionDefaults.GATEWAY_DATA_TIMEOUT_MS);
        long timeout = Long.parseLong(config == null ? fallback : config.getProperty(key, fallback));
        if (timeout < 1 || timeout > 3610000) throw new IllegalArgumentException("Invalid detection gateway timeout");
        ActionDialect response = syncTransmit(AIGCCellet.NAME, request, timeout);
        if (response == null) throw SpecializedDetectionException.timeout();
        Packet reply = new Packet(response);
        if (!packet.sn.equals(reply.sn) || !packet.name.equals(reply.name))
            throw new SpecializedDetectionException(31, 502, "INVALID_SERVICE_RESPONSE", "Invalid detection service response");
        int code = Packet.extractCode(reply);
        JSONObject result = Packet.extractDataPayload(reply);
        if (code != 0) throw SpecializedDetectionException.fromJSON(code, result);
        return result;
    }

    /**
     * 发起多模态对话，失败返回 {@code null}。
     */
    public MultimodalOutput executeMultimodal(String token, String channelCode, MultimodalInput input) {
        JSONObject data = new JSONObject();
        data.put("token", token);
        data.put("channel", channelCode);
        data.put("input", input.toJSON());

        Packet packet = new Packet(AIGCAction.Multimodal.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#executeMultimodal - Response is null - " + channelCode);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#executeMultimodal - Response state code is NOT Ok - " + channelCode +
                    " - " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject responseData = Packet.extractDataPayload(responsePacket);
        return new MultimodalOutput(responseData);
    }

    /**
     * 查询多模态任务的输出。
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @param sn          任务序列号。
     * @return 多模态输出；失败返回 {@code null} 。
     */
    public MultimodalOutput queryMultimodal(String token, String channelCode, long sn) {
        JSONObject data = new JSONObject();
        data.put("token", token);
        data.put("code", channelCode);
        data.put("sn", sn);

        Packet packet = new Packet(AIGCAction.QueryMultimodal.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#queryMultimodal - Response is null - " + channelCode);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#queryMultimodal - Response state code is NOT Ok - " + channelCode +
                    " - " + Packet.extractCode(responsePacket));
            return null;
        }

        return new MultimodalOutput(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 查询检索结果。
     *
     * @param token 访问令牌。
     * @return 检索结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject querySearchResults(String token) {
        Packet packet = new Packet(AIGCAction.GetSearchResults.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#querySearchResults - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#querySearchResults - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取上下文推理结果。
     *
     * @param token     访问令牌。
     * @param contextId 上下文 ID 。
     * @return 推理结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getContextInference(String token, long contextId) {
        JSONObject data = new JSONObject();
        data.put("id", contextId);
        Packet packet = new Packet(AIGCAction.GetContextInference.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getContextInference - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#getContextInference - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 执行知识库问答。
     *
     * <p>{@code sync} 为 {@code true} 时服务端同步等待结果再返回，否则立即返回进度对象供轮询。</p>
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @param query       用户问题。
     * @param baseName    知识库内部名称。
     * @param sync        是否同步等待。
     * @return 问答进度；失败返回 {@code null} 。
     */
    public KnowledgeQAProgress performKnowledgeQA(String token, String channelCode, String query,
                                                  String baseName, boolean sync) {
        JSONObject data = new JSONObject();
        data.put("channel", channelCode);
        data.put("query", query);
        data.put("topK", 5);
        data.put("sync", sync);
        if (null != baseName) {
            data.put("baseName", baseName);
        }
        Packet packet = new Packet(AIGCAction.PerformKnowledgeQA.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 4 * 60 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#performKnowledgeQA - Response is null: " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#performKnowledgeQA - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject responseData = Packet.extractDataPayload(responsePacket);
        return new KnowledgeQAProgress(responseData);
    }

    /**
     * 查询知识库问答进度。
     *
     * @param token    访问令牌。
     * @param channel  通道码。
     * @param baseName 知识库内部名称。
     * @return 问答进度；失败返回 {@code null} 。
     */
    public KnowledgeQAProgress getKnowledgeQAProgress(String token, String channel, String baseName) {
        JSONObject data = new JSONObject();
        data.put("channel", channel);
        if (null != baseName) {
            data.put("base", baseName);
        }
        Packet packet = new Packet(AIGCAction.GetKnowledgeQAProgress.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(Manager.class, "#getKnowledgeQAProgress - Response is null: " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#getKnowledgeQAProgress - Response state code is NOT Ok - "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new KnowledgeQAProgress(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 语义检索。
     *
     * @param token 访问令牌。
     * @param query 检索语句。
     * @return 检索结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject semanticSearch(String token, String query) {
        JSONObject data = new JSONObject();
        data.put("query", query);
        Packet packet = new Packet(AIGCAction.SemanticSearch.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 90 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#semanticSearch - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#semanticSearch - Response state code : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 生成文本摘要。
     *
     * <p>同步接口，直接返回摘要文本而非进度对象。</p>
     *
     * @param text 待摘要文本。
     * @return 摘要；失败返回 {@code null} 。
     */
    public String generateSummarization(String text) {
        JSONObject data = new JSONObject();
        data.put("text", text);
        Packet packet = new Packet(AIGCAction.Summarization.name, data);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, packet.toDialect(), 90 * 1000);
        if (null == response) {
            Logger.w(Manager.class, "#generateSummarization - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#generateSummarization - Response state code : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket).getString("summarization");
    }

    /*public NLTask performNaturalLanguageTask(NLTask task) {
        // 检查任务
        if (!task.check()) {
            // 任务参数不正确
            Logger.w(Manager.class, "#performNaturalLanguageTask - task data error: " + task.type);
            return null;
        }

        Packet packet = new Packet(AIGCAction.NaturalLanguageTask.name, task.toJSON());
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, packet.toDialect(), 120 * 100);
        if (null == response) {
            Logger.w(Manager.class, "#performNaturalLanguageTask - Response is null");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(Manager.class, "#performNaturalLanguageTask - Response state code : "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return new NLTask(Packet.extractDataPayload(responsePacket));
    }*/

    /**
     * 提交文本转文件任务。
     *
     * <p>参数不全时直接返回 {@code null} 且不发请求；返回的任务句柄需由调用方轮询。</p>
     *
     * @param token        访问令牌。
     * @param text         待转换的文本。
     * @param fileCodeList 需要关联的源文件码列表。
     * @return 任务句柄；参数非法或失败返回 {@code null} 。
     */
    public TextToFileFuture textToFile(String token, String text, JSONArray fileCodeList) {
        if (isBlank(token) || null == text || null == fileCodeList) {
            return null;
        }
        long sn = Utils.generateSerialNumber();
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        data.put("text", text);
        data.put("files", fileCodeList);
        Packet packet = new Packet(sn, AIGCAction.TextToFile.name, data);
        return this.submitFuture(packet, token, this.textToFileFutureMap, sn,
                new TextToFileFuture(sn, token, text, fileCodeList), false);
    }

    /**
     * 获取文本转文件任务句柄。
     *
     * <p>只查本地缓存，不发起网络请求。</p>
     *
     * @param sn 任务序列号。
     * @return 任务句柄；不存在返回 {@code null} 。
     */
    public TextToFileFuture getTextToFileFuture(long sn) {
        return this.textToFileFutureMap.get(sn);
    }

    /**
     * 提交语音识别任务。
     *
     * <p>文件码与文件地址二选一，都为空白时直接返回 {@code null} 且不发请求。</p>
     *
     * @param token    访问令牌。
     * @param fileCode 源文件码。
     * @param fileUrl  源文件地址。
     * @param sync     是否同步等待识别结果。
     * @param reset    为 {@code true} 时丢弃已有结果重新识别。
     * @return 任务句柄；失败返回 {@code null} 。
     */
    public SpeechRecognitionFuture automaticSpeechRecognition(String token, String fileCode, String fileUrl,
                                                              boolean sync, boolean reset) {
        if (isBlank(token) || isBlank(null != fileCode ? fileCode : fileUrl)) {
            return null;
        }
        JSONObject data = new JSONObject();
        data.put(null != fileCode ? "fileCode" : "fileUrl", null != fileCode ? fileCode : fileUrl);
        Packet packet = new Packet(AIGCAction.AutomaticSpeechRecognition.name, data);
        if (sync) {
            ActionDialect request = packet.toDialect();
            request.addParam("token", token);
            ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
            if (null == response) {
                return null;
            }
            try {
                Packet responsePacket = new Packet(response);
                if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
                    return null;
                }
                SpeechRecognitionInfo result = new SpeechRecognitionInfo(Packet.extractDataPayload(responsePacket));
                if (null == result.file) {
                    return null;
                }
                return new SpeechRecognitionFuture(token, fileCode, fileUrl, result);
            } catch (RuntimeException e) {
                Logger.w(Manager.class, "#automaticSpeechRecognition - Invalid response", e);
                return null;
            }
        }
        String queryCode = FileUtils.fastHash(null != fileCode ? fileCode : fileUrl);
        return this.submitFuture(packet, token, this.speechRecognitionFutureMap, queryCode,
                new SpeechRecognitionFuture(token, fileCode, fileUrl, queryCode), reset);
    }

    /**
     * 获取语音识别任务句柄。
     *
     * <p>只查本地缓存，不发起网络请求。</p>
     *
     * @param queryCode 查询码。
     * @return 任务句柄；不存在返回 {@code null} 。
     */
    public SpeechRecognitionFuture getSpeechRecognitionFuture(String queryCode) {
        return null == queryCode ? null : this.speechRecognitionFutureMap.get(queryCode);
    }

    /**
     * 提交语音情绪识别任务。
     *
     * @param token    访问令牌。
     * @param fileCode 源文件码。
     * @param reset    为 {@code true} 时丢弃已有结果重新识别。
     * @return 任务句柄；失败返回 {@code null} 。
     */
    public SpeechEmotionRecognitionFuture speechEmotionRecognition(String token, String fileCode, boolean reset) {
        if (isBlank(token) || isBlank(fileCode)) {
            return null;
        }
        JSONObject payload = new JSONObject();
        payload.put("fileCode", fileCode);
        return this.submitFuture(new Packet(AIGCAction.SpeechEmotionRecognition.name, payload), token,
                this.speechEmotionRecognitionFutureMap, fileCode,
                new SpeechEmotionRecognitionFuture(token, fileCode), reset);
    }

    /**
     * 获取语音情绪识别任务句柄。
     *
     * <p>只查本地缓存，不发起网络请求。</p>
     *
     * @param fileCode 源文件码。
     * @return 任务句柄；不存在返回 {@code null} 。
     */
    public SpeechEmotionRecognitionFuture getSpeechEmotionRecognitionFuture(String fileCode) {
        return null == fileCode ? null : this.speechEmotionRecognitionFutureMap.get(fileCode);
    }

    /**
     * 提交面部表情识别任务。
     *
     * @param token     访问令牌。
     * @param fileCode  源文件码。
     * @param visualize 是否要求返回可视化结果。
     * @param reset     为 {@code true} 时丢弃已有结果重新识别。
     * @return 任务句柄；失败返回 {@code null} 。
     */
    public FacialExpressionRecognitionFuture facialExpressionRecognition(String token, String fileCode,
                                                                         boolean visualize, boolean reset) {
        if (isBlank(token) || isBlank(fileCode)) {
            return null;
        }
        JSONObject payload = new JSONObject();
        payload.put("fileCode", fileCode);
        payload.put("visualize", visualize);
        return this.submitFuture(new Packet(AIGCAction.FacialExpressionRecognition.name, payload), token,
                this.facialExpressionRecognitionFutureMap, fileCode,
                new FacialExpressionRecognitionFuture(token, fileCode), reset);
    }

    /**
     * 获取面部表情识别任务句柄。
     *
     * <p>只查本地缓存，不发起网络请求。</p>
     *
     * @param fileCode 源文件码。
     * @return 任务句柄；不存在返回 {@code null} 。
     */
    public FacialExpressionRecognitionFuture getFacialExpressionRecognitionFuture(String fileCode) {
        return null == fileCode ? null : this.facialExpressionRecognitionFutureMap.get(fileCode);
    }

    /**
     * 提交语音分离任务。
     *
     * <p>文件码与文件地址二选一，都为空白时直接返回 {@code null} 且不发请求。
     * 查询码由文件码或地址的哈希生成，同一文件重复提交会命中同一任务。</p>
     *
     * @param token    访问令牌。
     * @param fileCode 源文件码。
     * @param fileUrl  源文件地址。
     * @param reset    为 {@code true} 时丢弃已有任务重新提交。
     * @return 任务句柄；失败返回 {@code null} 。
     */
    public SpeechDiarizationFuture speechDiarization(String token, String fileCode, String fileUrl, boolean reset) {
        if (isBlank(token) || isBlank(null != fileCode ? fileCode : fileUrl)) {
            return null;
        }
        String queryCode = FileUtils.fastHash(null != fileCode ? fileCode : fileUrl);
        JSONObject payload = new JSONObject();
        payload.put(null != fileCode ? "fileCode" : "fileUrl", null != fileCode ? fileCode : fileUrl);
        return this.submitFuture(new Packet(AIGCAction.SpeechDiarization.name, payload), token,
                this.speechDiarizationFutureMap, queryCode,
                new SpeechDiarizationFuture(token, fileCode, fileUrl, queryCode), reset);
    }

    /**
     * 获取语音分离任务句柄。
     *
     * <p>先查本地缓存；未命中时向服务端查询并重建句柄，超时 60 秒。
     * 查询码长度为 64 时按文件地址的哈希处理，否则按文件码直接查找。</p>
     *
     * @param token     访问令牌。
     * @param queryCode 查询码或文件码。
     * @return 任务句柄；查不到返回 {@code null} 。
     */
    public SpeechDiarizationFuture getSpeechDiarization(String token, String queryCode) {
        if (isBlank(token) || isBlank(queryCode)) {
            return null;
        }
        SpeechDiarizationFuture future = null;
        if (queryCode.length() == 64) {
            future = this.speechDiarizationFutureMap.get(FileUtils.fastHash(queryCode));
        } else {
            future = this.speechDiarizationFutureMap.get(queryCode);
        }
        if (null != future) {
            return future;
        }

        JSONObject payload = new JSONObject();
        payload.put("fileCode", queryCode);
        Packet packet = new Packet(AIGCAction.GetSpeechDiarization.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getSpeechDiarization - No response: " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getSpeechDiarization - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject responseData = Packet.extractDataPayload(responsePacket);
        if (responseData.has("file")) {
            FileLabels.reviseFileLabel(responseData.getJSONObject("file"), token,
                    getPerformer().getExternalHttpEndpoint(),
                    getPerformer().getExternalHttpsEndpoint());
        }
        VoiceDiarization diarization = new VoiceDiarization(responseData);
        future = new SpeechDiarizationFuture(diarization.getTimestamp(),
                token, diarization.fileCode, AIGCStateCode.Ok, diarization);
        return future;
    }

    /**
     * 列出语音分离记录。
     *
     * @param token 访问令牌。
     * @return 记录列表 JSON ；失败返回 {@code null} 。
     */
    public JSONObject listSpeechDiarizations(String token) {
        JSONObject payload = new JSONObject();
        Packet packet = new Packet(AIGCAction.ListSpeechDiarizations.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 2 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#listSpeechDiarizations - No response: " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#listSpeechDiarizations - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject responseData = Packet.extractDataPayload(responsePacket);
        JSONArray list = responseData.getJSONArray("list");
        for (int i = 0; i < list.length(); ++i) {
            JSONObject json = list.getJSONObject(i);
            if (json.has("file")) {
                FileLabels.reviseFileLabel(json.getJSONObject("file"), token,
                        getPerformer().getExternalHttpEndpoint(),
                        getPerformer().getExternalHttpsEndpoint());
            }
        }
        return responseData;
    }

    /**
     * 删除语音分离记录。
     *
     * @param token    访问令牌。
     * @param fileCode 源文件码。
     * @return 操作结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject deleteSpeechDiarization(String token, String fileCode) {
        JSONObject payload = new JSONObject();
        payload.put("fileCode", fileCode);
        Packet packet = new Packet(AIGCAction.DeleteSpeechDiarization.name, payload);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 2 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#deleteSpeechDiarization - No response: " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#deleteSpeechDiarization - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject responseData = Packet.extractDataPayload(responsePacket);
        if (responseData.has("file")) {
            FileLabels.reviseFileLabel(responseData.getJSONObject("file"), token,
                    getPerformer().getExternalHttpEndpoint(),
                    getPerformer().getExternalHttpsEndpoint());
        }
        VoiceDiarization diarization = new VoiceDiarization(responseData);
        SpeechDiarizationFuture future = new SpeechDiarizationFuture(diarization.getTimestamp(),
                token, diarization.fileCode, AIGCStateCode.Ok, diarization);
        return future.toJSON();
    }

    /**
     * 获取用户情绪数据。
     *
     * <p>聚合该用户历史对话与语音中的情绪指标，用于趋势展示。</p>
     *
     * @param token 访问令牌。
     * @return 情绪数据 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getUserEmotionData(String token) {
        JSONObject result = new JSONObject();
        JSONArray data = new JSONArray();

        Packet packet = new Packet(AIGCAction.GetEmotionRecords.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);
        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getUserEmotionData - No response: " + token);
            return null;
        }
        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getUserEmotionData - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }
        JSONObject emotionRecords = Packet.extractDataPayload(responsePacket);
        JSONArray array = emotionRecords.getJSONArray("list");
        List<EmotionRecord> emotionRecordList = new ArrayList<>();
        for (int i = 0; i < array.length(); ++i) {
            emotionRecordList.add(new EmotionRecord(array.getJSONObject(i)));
        }

        for (Emotion emotion : Emotion.values()) {
            if (emotion == Emotion.Unknown || emotion == Emotion.None) {
                continue;
            }
            String name = emotion.name();
            int value = 0;
            for (EmotionRecord er : emotionRecordList) {
                if (er.emotion == emotion) {
                    value += 1;
                }
            }
            JSONObject emotionJson = new JSONObject();
            emotionJson.put("name", name);
            emotionJson.put("value", value);
            data.put(emotionJson);
        }

        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());
        result.put("num", emotionRecordList.size());
        return result;
    }

    /**
     * 分词。
     *
     * @param token 访问令牌。
     * @param text  待分词文本。
     * @return 分词结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject segmentation(String token, String text) {
        JSONObject data = new JSONObject();
        data.put("text", text);
        Packet packet = new Packet(AIGCAction.Segmentation.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#segmentation - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#segmentation - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 处理图表数据。
     *
     * <p>载荷原样透传，由服务端按图表类型解析。</p>
     *
     * @param token 访问令牌。
     * @param data  图表数据。
     * @return 处理结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject handleChartData(String token, JSONObject data) {
        Packet packet = new Packet(AIGCAction.ChartData.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#handleChartData - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#handleChartData - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取提示词列表。
     *
     * @param token 访问令牌。
     * @return 提示词 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPrompts(String token) {
        Packet packet = new Packet(AIGCAction.GetPrompts.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#getPrompts - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPrompts - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 批量新增提示词。
     *
     * <p>同一批提示词按联系人列表分发，每个联系人各得一份副本。</p>
     *
     * @param token            访问令牌。
     * @param promptRecordList 待新增的提示词。
     * @param contactIdList    目标联系人 ID 列表。
     * @return 全部成功返回 {@code true} 。
     */
    public boolean addPrompts(String token, List<PromptRecord> promptRecordList, List<Long> contactIdList) {
        JSONObject data = new JSONObject();
        data.put("action", "add");

        JSONArray promptArray = new JSONArray();
        for (PromptRecord promptRecord : promptRecordList) {
            promptArray.put(promptRecord.toJSON());
        }
        data.put("prompts", promptArray);

        if (null != contactIdList) {
            JSONArray contactIdArray = new JSONArray();
            for (long contactId : contactIdList) {
                contactIdArray.put(contactId);
            }
            data.put("contactIds", contactIdArray);
        }

        Packet packet = new Packet(AIGCAction.SetPrompts.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#addPrompts - No response");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#addPrompts - Response state is " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 批量删除提示词。
     *
     * @param token        访问令牌。
     * @param promptIdList 提示词 ID 列表。
     * @return 全部成功返回 {@code true} 。
     */
    public boolean removePrompts(String token, List<Long> promptIdList) {
        JSONObject data = new JSONObject();
        data.put("action", "remove");

        JSONArray idArray = new JSONArray();
        for (long id : promptIdList) {
            idArray.put(id);
        }
        data.put("idList", idArray);

        Packet packet = new Packet(AIGCAction.SetPrompts.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#removePrompts - No response");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#removePrompts - Response state is " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 更新提示词。
     *
     * @param token        访问令牌。
     * @param promptRecord 提示词记录，以其中的 ID 定位。
     * @return 更新成功返回 {@code true} 。
     */
    public boolean updatePrompt(String token, PromptRecord promptRecord) {
        JSONObject data = new JSONObject();
        data.put("action", "update");
        data.put("id", promptRecord.id);
        data.put("title", promptRecord.title);
        data.put("content", promptRecord.content);
        data.put("act", promptRecord.act);

        Packet packet = new Packet(AIGCAction.SetPrompts.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#updatePrompt - No response");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#updatePrompt - Response state is " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 提交事件。
     *
     * @param token 访问令牌。
     * @param event 事件对象。
     * @return 提交结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject submitEvent(String token, Event event) {
        Packet packet = new Packet(AIGCAction.SubmitEvent.name, event.toJSON());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#submitEvent - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#submitEvent - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /*
     * @deprecated
     * @param token
     * @param moduleName
     * @param param
     * @return
     */
    /*public JSONObject inferByModule(String token, String moduleName, JSONObject param) {
        JSONObject data = new JSONObject();
        data.put("module", moduleName);
        data.put("param", param);
        Packet packet = new Packet(AIGCAction.InferByModule.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request);
        if (null == response) {
            Logger.w(this.getClass(), "#inferByModule - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#inferByModule - Response state is "
                    + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }*/

    /**
     * 生成心理测验报告（绘画分析）。
     *
     * <p>以绘画文件为输入，按主题与指标数量生成分析报告。
     * {@code remote} 原样下发给服务端，用于标识调用来源或回调地址。</p>
     *
     * @param remote     调用方标识。
     * @param token      访问令牌。
     * @param attribute  绘画属性。
     * @param fileCode   绘画文件码。
     * @param theme      分析主题。
     * @param indicators 期望生成的指标数量。
     * @param adjust     是否允许自动调整指标。
     * @param remark     备注，允许为 {@code null} 。
     * @return 绘画分析报告；失败返回 {@code null} 。
     */
    public PaintingReport generatePsychologyReport(String remote, String token, Attribute attribute,
                                                   String fileCode, String theme, int indicators,
                                                   boolean adjust, String remark) {
        JSONObject data = new JSONObject();
        data.put("remote", remote);
        data.put("attribute", attribute.toJSON());
        data.put("fileCode", fileCode);
        data.put("theme", theme);
        data.put("indicators", indicators);
        data.put("adjust", adjust);
        if (null != remark) {
            data.put("remark", remark);
        }

        Packet packet = new Packet(AIGCAction.GeneratePsychologyReport.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#generatePsychologyReport - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#generatePsychologyReport - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        PaintingReport report = new PaintingReport(Packet.extractDataPayload(responsePacket));
        return report;
    }

    /**
     * 生成心理测验报告（量表分析）。
     *
     * <p>以已提交的量表答题记录为输入，与绘画分析共用同一入口，返回类型不同。</p>
     *
     * @param remote  调用方标识。
     * @param token   访问令牌。
     * @param scaleSn 量表记录序列号。
     * @return 量表报告；失败返回 {@code null} 。
     */
    public ScaleReport generatePsychologyReport(String remote, String token, long scaleSn) {
        JSONObject data = new JSONObject();
        data.put("remote", remote);
        data.put("scaleSn", scaleSn);

        Packet packet = new Packet(AIGCAction.GeneratePsychologyReport.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#generatePsychologyReport - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#generatePsychologyReport - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        ScaleReport report = new ScaleReport(Packet.extractDataPayload(responsePacket));
        return report;
    }

    /**
     * 分页查询心理报告。
     *
     * @param token      访问令牌。
     * @param type       报告类型。
     * @param pageIndex  页码，从 0 开始。
     * @param pageSize   每页条数。
     * @param descending 是否按时间倒序。
     * @return 报告分页 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPsychologyReports(String token, String type, int pageIndex, int pageSize, boolean descending) {
        if (pageSize <= 0) {
            Logger.w(this.getClass(), "#getPsychologyReports - page size is zero: " + token);
            return null;
        }

        JSONObject data = new JSONObject();
        data.put("type", type);
        data.put("page", pageIndex);
        data.put("size", pageSize);
        data.put("desc", descending);
        data.put("state", 0);
        Packet packet = new Packet(AIGCAction.GetPsychologyReport.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyReports - No response: " + token);
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyReports - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 校验绘画是否可以生成报告。
     *
     * <p>用于在提交分析前确认文件可用、绘画已解析完成。</p>
     *
     * @param token    访问令牌。
     * @param fileCode 绘画文件码。
     * @return 校验结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject checkPsychologyPainting(String token, String fileCode) {
        JSONObject data = new JSONObject();
        data.put("fileCode", fileCode);
        Packet packet = new Packet(AIGCAction.CheckPsychologyPainting.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#checkPsychologyPainting - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#checkPsychologyPainting - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取心理分析报告。
     *
     * @param token    访问令牌。
     * @param sn       报告序列号。
     * @param markdown 为 {@code true} 时要求返回 Markdown 文本。
     * @return 报告对象；失败返回 {@code null} 。
     */
    public Report getPsychologyReport(String token, long sn, boolean markdown) {
        // 第一步，获取基础数据
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        data.put("sections", false);
        data.put("markdown", false);
        Packet packet = new Packet(AIGCAction.GetPsychologyReport.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyReport - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyReport - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        // 解析报告
        JSONObject reportJson = Packet.extractDataPayload(responsePacket);
        if (reportJson.has("factors") && !reportJson.has("fileLabel")) {
            // 量表报告
            return new ScaleReport(reportJson);
        }

        PaintingReport report = new PaintingReport(reportJson);

        // 第二步，获取报告数据各段落
        data = new JSONObject();
        data.put("sn", sn);
        data.put("sections", true);
        data.put("markdown", false);
        packet = new Packet(AIGCAction.GetPsychologyReport.name, data);
        request = packet.toDialect();
        request.addParam("token", token);

        response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyReport - No response");
            return null;
        }

        responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyReport - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        report.extendReportSections(Packet.extractDataPayload(responsePacket));

        // 第三步，是否获取 Markdown 数据
        if (markdown) {
            data = new JSONObject();
            data.put("sn", sn);
            data.put("sections", false);
            data.put("markdown", true);
            packet = new Packet(AIGCAction.GetPsychologyReport.name, data);
            request = packet.toDialect();
            request.addParam("token", token);

            response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
            if (null == response) {
                Logger.w(this.getClass(), "#getPsychologyReport - No response");
                return null;
            }

            responsePacket = new Packet(response);
            if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
                Logger.w(this.getClass(), "#getPsychologyReport - Response state is " + Packet.extractCode(responsePacket));
                return null;
            }

            report.extendMarkdown(Packet.extractDataPayload(responsePacket));
        }

        return report;
    }

    /**
     * 获取报告的指定片段。
     *
     * <p>六个开关分别控制返回内容、章节、思考过程、摘要、评分与关联链接，
     * 按需取用可显著减小响应体积。</p>
     *
     * @param token   访问令牌。
     * @param sn      报告序列号。
     * @param content 是否包含正文。
     * @param section 是否包含章节。
     * @param thought 是否包含思考过程。
     * @param summary 是否包含摘要。
     * @param rating  是否包含评分。
     * @param link    是否包含关联链接。
     * @return 报告片段 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPsychologyReportPart(String token, long sn, boolean content, boolean section, boolean thought,
                                              boolean summary, boolean rating, boolean link) {
        Performer current = this.activePerformer();
        if (null == current) {
            return null;
        }

        JSONObject data = new JSONObject();
        data.put("sn", sn);
        data.put("content", content);
        data.put("section", section);
        data.put("thought", thought);
        data.put("summary", summary);
        data.put("rating", rating);
        data.put("link", link);
        data.put("endpoint", current.getExternalHttpsEndpoint().toJSON());
        Packet packet = new Packet(AIGCAction.GetPsychologyReportPart.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyReportPart - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyReportPart - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 停止正在生成的报告。
     *
     * @param token 访问令牌。
     * @param sn    报告序列号。
     * @return 停止结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject stopGeneratingPsychologyReport(String token, long sn) {
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        Packet packet = new Packet(AIGCAction.StopGeneratingPsychologyReport.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#stopGeneratingPsychologyReport - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#stopGeneratingPsychologyReport - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 重设报告的关注重点。
     *
     * @param token     访问令牌。
     * @param sn        报告序列号。
     * @param attention 关注点配置。
     * @return 更新结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject resetReportAttention(String token, long sn, Attention attention) {
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        if (null != attention) {
            data.put("attention", attention.level);
        }
        Packet packet = new Packet(AIGCAction.ResetReportAttention.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#resetReportAttention - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#resetReportAttention - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 修改报告备注。
     *
     * <p>载荷原样透传，由服务端解析需要修改的字段。</p>
     *
     * @param token 访问令牌。
     * @param data  备注载荷。
     * @return 修改结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject modifyReportRemark(String token, JSONObject data) {
        Packet packet = new Packet(AIGCAction.ModifyReportRemark.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#modifyReportRemark - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#modifyReportRemark - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }
        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 列出可用量表。
     *
     * @param token 访问令牌。
     * @return 量表列表 JSON ；失败返回 {@code null} 。
     */
    public JSONObject listPsychologyScales(String token) {
        Packet packet = new Packet(AIGCAction.ListPsychologyScales.name, new JSONObject());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#listPsychologyScales - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#listPsychologyScales - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取量表。
     *
     * @param token 访问令牌。
     * @param sn    量表序列号。
     * @return 量表对象；失败返回 {@code null} 。
     */
    public Scale getPsychologyScale(String token, long sn) {
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        Packet packet = new Packet(AIGCAction.GetPsychologyScale.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyScale - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyScale - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return new Scale(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 按对象特征生成量表。
     *
     * <p>同一量表模板会按性别与年龄调整条目与常模。</p>
     *
     * @param token     访问令牌。
     * @param scaleName 量表名称。
     * @param gender    性别。
     * @param age       年龄。
     * @return 生成的量表；失败返回 {@code null} 。
     */
    public Scale generatePsychologyScale(String token, String scaleName, String gender, int age) {
        JSONObject data = new JSONObject();
        data.put("name", scaleName);
        data.put("gender", gender);
        data.put("age", age);
        Packet packet = new Packet(AIGCAction.GeneratePsychologyScale.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#generatePsychologyScale - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#generatePsychologyScale - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return new Scale(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 提交量表答题卡。
     *
     * @param token       访问令牌。
     * @param answerSheet 答题卡。
     * @return 计分结果；失败返回 {@code null} 。
     */
    public ScaleResult submitPsychologyAnswerSheet(String token, AnswerSheet answerSheet) {
        Packet packet = new Packet(AIGCAction.SubmitPsychologyAnswerSheet.name, answerSheet.toJSON());
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#submitPsychologyAnswerSheet - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#submitPsychologyAnswerSheet - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return new ScaleResult(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 执行心理对话（多分支模式）。
     *
     * <p>把执行机的外部 HTTP/HTTPS 端点一并下发给服务端，供其回调；超时 3 分钟。
     * 分支由 {@code relations} 数组驱动，需要单个分支时改用带 {@code relation} 的重载。</p>
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @param relations   分支关系数组。
     * @param query       用户提问。
     * @return 对话结果 JSON ；失败返回 {@code null} 。
     */
    public JSONObject executePsychologyConversation(String token, String channelCode,
                                                    JSONArray relations, String query) {
        Performer current = this.activePerformer();
        if (null == current) {
            return null;
        }

        JSONObject endpoint = new JSONObject();
        endpoint.put("http", current.getExternalHttpEndpoint().toJSON());
        endpoint.put("https", current.getExternalHttpsEndpoint().toJSON());

        JSONObject data = new JSONObject();
        data.put("channelCode", channelCode);
        data.put("endpoint", endpoint);
        data.put("relations", relations);
        data.put("query", query);
        Packet packet = new Packet(AIGCAction.PsychologyConversation.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#executePsychologyConversation - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#executePsychologyConversation - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 执行心理对话（单分支模式）。
     *
     * <p>与多分支重载的区别：这里显式传入单个 {@code relation} 与可选上下文。
     * {@code relation} 不能为 {@code null} ，否则直接返回 {@code null} 且不发请求；
     * {@code context} 可为 {@code null} ，表示不携带上一轮上下文。超时同为 3 分钟。</p>
     *
     * @param token       访问令牌。
     * @param channelCode 通道码。
     * @param context     上下文，允许为 {@code null} 。
     * @param relation    分支关系，不能为 {@code null} 。
     * @param query       用户提问。
     * @return 对话结果 JSON ；参数非法或失败返回 {@code null} 。
     */
    public JSONObject executePsychologyConversation(String token, String channelCode,
                                                    JSONObject context, JSONObject relation, String query) {
        Performer current = this.activePerformer();
        if (null == current) {
            return null;
        }

        if (null == relation) {
            Logger.w(this.getClass(), "#executePsychologyConversation - The relation is null");
            return null;
        }

        JSONObject endpoint = new JSONObject();
        endpoint.put("http", current.getExternalHttpEndpoint().toJSON());
        endpoint.put("https", current.getExternalHttpsEndpoint().toJSON());

        JSONObject data = new JSONObject();
        data.put("channelCode", channelCode);
        data.put("endpoint", endpoint);
        if (null != context) {
            data.put("context", context);
        }
        data.put("relation", relation);
        data.put("query", query);
        Packet packet = new Packet(AIGCAction.PsychologyConversation.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#executePsychologyConversation - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#executePsychologyConversation - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 按文件码获取绘画数据。
     *
     * @param token    访问令牌。
     * @param fileCode 绘画文件码。
     * @return 绘画数据 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPsychologyPainting(String token, String fileCode) {
        JSONObject data = new JSONObject();
        data.put("fileCode", fileCode);
        Packet packet = new Packet(AIGCAction.GetPsychologyPainting.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 120 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyPainting - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyPainting - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 按报告获取绘画数据。
     *
     * @param token    访问令牌。
     * @param reportSn 报告序列号。
     * @param bbox     是否返回检测框。
     * @param vparam   是否返回可视化参数。
     * @param prob     检测概率阈值。
     * @return 绘画数据 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPsychologyPainting(String token, long reportSn, boolean bbox, boolean vparam, double prob) {
        JSONObject data = new JSONObject();
        data.put("sn", reportSn);
        data.put("bbox", bbox);
        data.put("vparam", vparam);
        data.put("prob", prob);
        Packet packet = new Packet(AIGCAction.GetPsychologyPainting.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyPainting - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyPainting - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取绘画的统计图表数据。
     *
     * @param token    访问令牌。
     * @param reportSn 报告序列号。
     * @return 图表数据 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPsychologyPaintingChart(String token, long reportSn) {
        JSONObject data = new JSONObject();
        data.put("sn", reportSn);
        data.put("chart", true);
        Packet packet = new Packet(AIGCAction.GetPsychologyPainting.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPsychologyPaintingChart - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPsychologyPaintingChart - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 获取绘画标签。
     *
     * @param token    访问令牌。
     * @param reportSn 报告序列号。
     * @return 标签 JSON ；失败返回 {@code null} 。
     */
    public JSONObject getPaintingLabels(String token, long reportSn) {
        JSONObject data = new JSONObject();
        data.put("sn", reportSn);
        Packet packet = new Packet(AIGCAction.GetPaintingLabel.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 90 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getPaintingLabels - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getPaintingLabels - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return Packet.extractDataPayload(responsePacket);
    }

    /**
     * 提交人工修订的绘画标签。
     *
     * @param token  访问令牌。
     * @param sn     报告序列号。
     * @param labels 标签数组。
     * @return 提交成功返回 {@code true} 。
     */
    public boolean submitPaintingLabels(String token, long sn, JSONArray labels) {
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        data.put("labels", labels);

        Packet packet = new Packet(AIGCAction.SetPaintingLabel.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 90 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#submitPaintingLabels - No response");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#submitPaintingLabels - Response state is " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 设置报告状态。
     *
     * @param token 访问令牌。
     * @param sn    报告序列号。
     * @param state 目标状态值，取值由服务端定义。
     * @return 设置成功返回 {@code true} 。
     */
    public boolean setPaintingReportState(String token, long sn, int state) {
        JSONObject data = new JSONObject();
        data.put("sn", sn);
        data.put("state", state);
        Packet packet = new Packet(AIGCAction.SetPaintingReportState.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#setPaintingReportState - No response");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#setPaintingReportState - Response state is " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 申请接入实时流。
     *
     * <p>把调用方设备与指定流绑定到当前执行机，后续分片会路由到该设备。</p>
     *
     * @param token      访问令牌。
     * @param device     调用方设备。
     * @param streamType 流类型。
     * @param streamName 流名称。
     * @return 接入成功返回 {@code true} 。
     */
    public boolean applyStream(String token, Device device, String streamType, String streamName) {
        Performer current;
        long requestGeneration;
        synchronized (this) {
            if (!this.running || isBlank(streamType) || isBlank(streamName)) {
                return false;
            }
            current = this.performer;
            requestGeneration = this.generation;
        }
        if (null == StreamType.parse(streamType)) {
            return false;
        }
        ContactToken contactToken = this.getContactToken(token, device);
        if (null == contactToken) {
            return false;
        }
        synchronized (this) {
            if (!this.running || this.generation != requestGeneration) {
                return false;
            }
            Cellet cellet = current.getCellet(AIGCCellet.NAME);
            if (!(cellet instanceof AIGCCellet) || null == ((AIGCCellet) cellet).getStreamProcessor()) {
                return false;
            }
            ((AIGCCellet) cellet).getStreamProcessor().register(streamName, contactToken.authToken);
            return true;
        }
    }

    /**
     * 停止实时流。
     *
     * @param token      访问令牌。
     * @param streamName 流名称。
     * @return 停止成功返回 {@code true} 。
     */
    public boolean stopStream(String token, String streamName) {
        JSONObject data = new JSONObject();
        data.put("streamName", streamName);
        Packet packet = new Packet(AIGCAction.StopVoiceStream.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#stopStream - No response");
            return false;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#stopStream - Response state is " + Packet.extractCode(responsePacket));
            return false;
        }

        return true;
    }

    /**
     * 获取实时流产生的文件。
     *
     * @param token      访问令牌。
     * @param streamName 流名称。
     * @return 文件标签；尚未产生文件或失败时返回 {@code null} 。
     */
    public FileLabel getStreamFile(String token, String streamName) {
        JSONObject data = new JSONObject();
        data.put("streamName", streamName);
        Packet packet = new Packet(AIGCAction.GetVoiceStreamFile.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#getStreamFile - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#getStreamFile - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        JSONObject json = Packet.extractDataPayload(responsePacket);
        return new FileLabel(json);
    }

    /**
     * 查询咨询策略。
     *
     * <p>按流、主题与属性取当前应执行的咨询动作，{@code index} 用于在同一主题下顺序推进。</p>
     *
     * @param token      访问令牌。
     * @param streamName 流名称。
     * @param theme      咨询主题。
     * @param attribute  绘画属性。
     * @param index      策略序号。
     * @return 咨询策略；失败返回 {@code null} 。
     */
    public CounselingStrategy queryCounselingStrategy(String token, String streamName, ConsultationTheme theme,
                                                      Attribute attribute, int index) {
        JSONObject data = new JSONObject();
        data.put("streamName", streamName);
        data.put("theme", theme.code);
        data.put("attribute", attribute.toJSON());
        data.put("index", index);
        Packet packet = new Packet(AIGCAction.QueryCounselingStrategy.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#queryCounselingStrategy - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#queryCounselingStrategy - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return new CounselingStrategy(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 查询咨询提示性说明。
     *
     * <p>与策略查询的区别：这里多指定一个咨询动作，返回该动作对应的引导话术。</p>
     *
     * @param token            访问令牌。
     * @param streamName       流名称。
     * @param theme            咨询主题。
     * @param attribute        绘画属性。
     * @param consultingAction 咨询动作。
     * @param index            策略序号。
     * @return 引导话术；失败返回 {@code null} 。
     */
    public CounselingStrategy queryCounselingCaption(String token, String streamName, ConsultationTheme theme,
                                                     Attribute attribute,
                                                     CounselingStrategy.ConsultingAction consultingAction,
                                                     int index) {
        JSONObject data = new JSONObject();
        data.put("streamName", streamName);
        data.put("theme", theme.code);
        data.put("attribute", attribute.toJSON());
        data.put("consultingAction", consultingAction.code);
        data.put("index", index);
        Packet packet = new Packet(AIGCAction.QueryCounselingCaption.name, data);
        ActionDialect request = packet.toDialect();
        request.addParam("token", token);

        ActionDialect response = this.syncTransmit(AIGCCellet.NAME, request, 3 * 60 * 1000);
        if (null == response) {
            Logger.w(this.getClass(), "#queryCounselingCaption - No response");
            return null;
        }

        Packet responsePacket = new Packet(response);
        if (Packet.extractCode(responsePacket) != AIGCStateCode.Ok.code) {
            Logger.w(this.getClass(), "#queryCounselingCaption - Response state is " + Packet.extractCode(responsePacket));
            return null;
        }

        return new CounselingStrategy(Packet.extractDataPayload(responsePacket));
    }

    /**
     * 周期回调。
     *
     * <p>由执行机按固定间隔驱动，用于维护令牌缓存与清理过期句柄。未运行时直接返回。</p>
     *
     * @param now 当前时间戳，单位毫秒。
     */
    @Override
    public synchronized void onTick(long now) {
        if (!this.running) {
            return;
        }
        if (now - this.lastTickTime >= 60 * 1000) {
            this.lastTickTime = now;
            for (Map.Entry<String, ContactToken> entry : this.validTokenMap.entrySet()) {
                if (isTokenExpired(entry.getValue(), now)) {
                    this.validTokenMap.remove(entry.getKey(), entry.getValue());
                }
            }
            this.clearExpiredFutures(this.textToFileFutureMap, now);
            this.clearExpiredFutures(this.speechRecognitionFutureMap, now);
            this.clearExpiredFutures(this.speechEmotionRecognitionFutureMap, now);
            this.clearExpiredFutures(this.speechDiarizationFutureMap, now);
            this.clearExpiredFutures(this.facialExpressionRecognitionFutureMap, now);
        }
        App.getInstance().onTick(now);
    }

    private <K, F extends JSONable> void clearExpiredFutures(Map<K, F> cache, long now) {
        for (Map.Entry<K, F> entry : cache.entrySet()) {
            F future = entry.getValue();
            if (now - futureTimestamp(future) >= FUTURE_CACHE_TTL && cache.remove(entry.getKey(), future)) {
                this.detachFuture(future, AIGCStateCode.Expired);
            }
        }
    }

    /**
     * 收到单元响应时的回调。
     *
     * <p>只处理本单元（{@code AIGCCellet}）的响应，其余单元与空消息直接忽略，
     * 随后按序列号唤醒对应的等待请求。</p>
     *
     * @param cellet    产生响应的单元名称。
     * @param primitive 响应消息。
     */
    @Override
    public synchronized void onReceived(String cellet, Primitive primitive) {
        if (!this.running || !AIGCCellet.NAME.equals(cellet) || null == primitive) {
            return;
        }
        PendingRequest pending = null;
        try {
            ActionDialect response = new ActionDialect(primitive);
            if (AIGCAction.ChatCompletionEvent.name.equals(response.getName())) {
                this.completions.onEvent(response);
                return;
            }
            long sn = response.getParamAsLong("sn");
            pending = this.pendingRequests.get(sn);
            if (null == pending || !pending.action.equals(response.getName()) || !pending.isCurrent()) {
                return;
            }
            if (System.currentTimeMillis() - futureTimestamp(pending.future) >= FUTURE_CACHE_TTL) {
                pending.cache.remove(pending.key, pending.future);
                pending.fail(AIGCStateCode.Expired);
                this.removePending(pending);
                return;
            }
            Packet packet = new Packet(response);
            int code = Packet.extractCode(packet);
            if (code == AIGCStateCode.Processing.code || code == AIGCStateCode.Inferencing.code) {
                return;
            }
            if (code == AIGCStateCode.Ok.code) {
                this.completeFuture(pending.future, Packet.extractDataPayload(packet));
            } else {
                if (pending.future instanceof TextToFileFuture) {
                    synchronized (pending.future) {
                        ((TextToFileFuture) pending.future).stateCode = code;
                    }
                } else {
                    pending.fail(AIGCStateCode.parse(code));
                }
            }
            this.removePending(pending);
        } catch (RuntimeException e) {
            if (null != pending && pending.isCurrent()) {
                pending.fail(AIGCStateCode.DataStructureError);
                this.removePending(pending);
                Logger.w(Manager.class, "#onReceived - Invalid response, action: " + pending.action +
                        ", sn: " + pending.sn, e);
            }
        }
    }

    /**
     * 在同一对象锁中发布结果和完成状态，与 toJSON 保持一致。
     */
    private void completeFuture(JSONable future, JSONObject data) {
        synchronized (future) {
            if (future instanceof TextToFileFuture) {
                TextToFileFuture f = (TextToFileFuture) future;
                JSONObject result = data.getJSONObject("result");
                for (String name : Arrays.asList("fileLabels", "answerFileLabels", "queryFileLabels")) {
                    if (result.has(name)) {
                        JSONArray files = result.getJSONArray(name);
                        for (int i = 0; i < files.length(); ++i) {
                            FileLabels.reviseFileLabel(files.getJSONObject(i), f.token,
                                    this.performer.getExternalHttpEndpoint(), this.performer.getExternalHttpsEndpoint());
                        }
                    }
                }
                f.result = result;
                f.stateCode = AIGCStateCode.Ok.code;
            } else if (future instanceof SpeechRecognitionFuture) {
                SpeechRecognitionInfo result = new SpeechRecognitionInfo(data);
                if (null == result.file) {
                    throw new IllegalArgumentException("Missing speech recognition file");
                }
                SpeechRecognitionFuture f = (SpeechRecognitionFuture) future;
                f.result = result;
                f.stateCode = AIGCStateCode.Ok;
            } else if (future instanceof SpeechEmotionRecognitionFuture) {
                SpeechEmotion result = new SpeechEmotion(data);
                if (null == result.file) {
                    throw new IllegalArgumentException("Missing speech emotion file");
                }
                SpeechEmotionRecognitionFuture f = (SpeechEmotionRecognitionFuture) future;
                f.result = result;
                f.stateCode = AIGCStateCode.Ok;
            } else if (future instanceof SpeechDiarizationFuture) {
                VoiceDiarization result = new VoiceDiarization(data);
                if (null == result.file) {
                    throw new IllegalArgumentException("Missing diarization file");
                }
                SpeechDiarizationFuture f = (SpeechDiarizationFuture) future;
                f.diarization = result;
                f.stateCode = AIGCStateCode.Ok;
            } else if (future instanceof FacialExpressionRecognitionFuture) {
                FacialExpressionResult result = new FacialExpressionResult(data);
                FacialExpressionRecognitionFuture f = (FacialExpressionRecognitionFuture) future;
                f.result = result;
                f.stateCode = AIGCStateCode.Ok;
            }
        }
    }

    public class ChatFuture implements JSONable {

        public boolean end = true;

        public AIGCChannel channel;

        public GeneratingRecord record;

        public KnowledgeQAResult knowledgeResult;

        protected ChatFuture(AIGCChannel channel) {
            this.channel = channel;
        }

        protected ChatFuture(GeneratingRecord record) {
            this.record = record;
        }

        protected ChatFuture(KnowledgeQAResult knowledgeResult) {
            this.knowledgeResult = knowledgeResult;
        }

        @Override
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class TextToFileFuture implements JSONable {

        protected final long sn;

        protected final long timestamp;

        protected final String token;

        protected String text;

        protected JSONArray files;

        protected JSONObject result;

        protected int stateCode = AIGCStateCode.Processing.code;

        public TextToFileFuture(long sn, String token, String text, JSONArray files) {
            this.sn = sn;
            this.token = token;
            this.timestamp = System.currentTimeMillis();
            this.text = text;
            this.files = files;
        }

        @Override
        public synchronized JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("sn", this.sn);
            json.put("timestamp", this.timestamp);
            json.put("state", this.stateCode);
            if (null != this.result) {
                json.put("result", this.result);
            }
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class ObjectDetectionFuture implements JSONable {

        protected final long sn;

        protected final long timestamp;

        protected String channelCode;

        protected JSONArray fileCodeList;

        protected List<SpeechEmotion> resultList;

        protected int stateCode = AIGCStateCode.Processing.code;

        public ObjectDetectionFuture(long sn, String channelCode, JSONArray fileCodeList) {
            this.sn = sn;
            this.timestamp = System.currentTimeMillis();
            this.channelCode = channelCode;
            this.fileCodeList = fileCodeList;
        }

        @Override
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("sn", this.sn);
            json.put("channelCode", this.channelCode);
            json.put("timestamp", this.timestamp);
            json.put("stateCode", this.stateCode);

            if (null != this.resultList) {
                JSONArray array = new JSONArray();
                for (SpeechEmotion result : this.resultList) {
                    array.put(result.toJSON());
                }
                json.put("resultList", array);
            }
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class SpeechRecognitionFuture implements JSONable {

        protected final long timestamp;

        protected String token;

        protected String fileCode;

        protected String fileUrl;

        public String queryCode;

        protected SpeechRecognitionInfo result;

        protected AIGCStateCode stateCode = AIGCStateCode.Processing;

        protected SpeechRecognitionFuture(String token, String fileCode, String fileUrl, String queryCode) {
            this.timestamp = System.currentTimeMillis();
            this.token = token;
            this.fileCode = fileCode;
            this.fileUrl = fileUrl;
            this.queryCode = queryCode;
        }

        protected SpeechRecognitionFuture(String token, String fileCode, String fileUrl, SpeechRecognitionInfo result) {
            this.timestamp = System.currentTimeMillis();
            this.token = token;
            this.fileCode = fileCode;
            this.fileUrl = fileUrl;
            this.result = result;
            this.stateCode = AIGCStateCode.Ok;
        }

        @Override
        public synchronized JSONObject toJSON() {
            JSONObject json = new JSONObject();
            if (null != this.queryCode) {
                json.put("queryCode", this.queryCode);
            }
            json.put("timestamp", this.timestamp);
            json.put("stateCode", this.stateCode.code);
            if (null != this.result) {
                json.put("result", this.result.toJSON());
            }
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class SpeechEmotionRecognitionFuture implements JSONable {

        protected final long timestamp;

        protected String token;

        protected String fileCode;

        protected SpeechEmotion result;

        protected AIGCStateCode stateCode = AIGCStateCode.Processing;

        public SpeechEmotionRecognitionFuture(String token, String fileCode) {
            this.timestamp = System.currentTimeMillis();
            this.token = token;
            this.fileCode = fileCode;
        }

        @Override
        public synchronized JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("fileCode", this.fileCode);
            json.put("timestamp", this.timestamp);
            json.put("stateCode", this.stateCode.code);
            if (null != this.result) {
                json.put("result", this.result.toJSON());
            }
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class SpeechDiarizationFuture implements JSONable {

        protected final long timestamp;

        protected String token;

        protected String fileCode;

        protected String fileUrl;

        public String queryCode;

        protected VoiceDiarization diarization;

        protected AIGCStateCode stateCode = AIGCStateCode.Processing;

        public SpeechDiarizationFuture(String token, String fileCode, String fileUrl, String queryCode) {
            this.timestamp = System.currentTimeMillis();
            this.token = token;
            this.fileCode = fileCode;
            this.fileUrl = fileUrl;
            this.queryCode = queryCode;
        }

        public SpeechDiarizationFuture(long timestamp, String token, String fileCode, AIGCStateCode stateCode,
                                       VoiceDiarization diarization) {
            this.timestamp = timestamp;
            this.token = token;
            this.fileCode = fileCode;
            this.queryCode = FileUtils.fastHash(fileCode);
            this.stateCode = stateCode;
            this.diarization = diarization;
        }

        @Override
        public synchronized JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("queryCode", this.queryCode);
            json.put("timestamp", this.timestamp);
            json.put("stateCode", this.stateCode.code);
            if (null != this.diarization) {
                json.put("result", this.diarization.toJSON());
            }
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class FacialExpressionRecognitionFuture implements JSONable {

        protected final long timestamp;

        protected String token;

        protected String fileCode;

        protected FacialExpressionResult result;

        protected AIGCStateCode stateCode = AIGCStateCode.Processing;

        public FacialExpressionRecognitionFuture(String token, String fileCode) {
            this.timestamp = System.currentTimeMillis();
            this.token = token;
            this.fileCode = fileCode;
        }

        @Override
        public synchronized JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("fileCode", this.fileCode);
            json.put("timestamp", this.timestamp);
            json.put("stateCode", this.stateCode.code);
            if (null != this.result) {
                json.put("result", this.result.toJSON());
            }
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }


    public class ContactToken {

        public final AuthToken authToken;

        public final Contact contact;

        public final Device device;

        public final long timestamp = System.currentTimeMillis();

        protected ContactToken(AuthToken authToken, Contact contact, Device device) {
            this.authToken = authToken;
            this.contact = contact;
            this.device = device;
        }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("token", this.authToken.toJSON());
            json.put("contact", this.contact.toJSON());
            json.put("device", this.device.toJSON());
            return json;
        }
    }
}
