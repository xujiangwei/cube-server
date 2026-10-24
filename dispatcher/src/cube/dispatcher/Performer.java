/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

import cell.api.*;
import cell.core.cellet.Cellet;
import cell.core.net.Endpoint;
import cell.core.talk.*;
import cell.core.talk.dialect.ActionDialect;
import cell.util.CachedQueueExecutor;
import cell.util.Utils;
import cell.util.log.Logger;
import cube.auth.AuthToken;
import cube.common.StateCode;
import cube.common.UniqueKey;
import cube.common.action.ClientAction;
import cube.common.entity.Contact;
import cube.common.entity.Device;
import cube.common.state.AuthStateCode;
import cube.dispatcher.aigc.AIGCCellet;
import cube.dispatcher.aigc.CapabilityRouter;
import cube.dispatcher.stream.StreamServer;
import cube.dispatcher.util.Tickable;
import cube.util.FileUtils;
import cube.util.HttpClientFactory;
import cube.util.HttpConfig;
import cube.util.HttpServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Performer 接入层连接器。
 */
public class Performer implements TalkListener, Tickable {

    private final String performerKey = "_performer";

    private final String directorKey = "_director";

    protected TalkService talkService;

    protected CelletService celletService;

    private Properties properties;

    /**
     * 线程池执行器。
     */
    private ExecutorService executor;

    /**
     * 节点名称。
     */
    private String nodeName;

    /**
     * HTTP 服务。
     */
    private HttpServer httpServer;
    private cube.dispatcher.harness.HarnessGateway harnessGateway;

    /**
     * 服务主机列表。
     */
    protected List<Director> directorList;

    /**
     * Cellet 对应的 Director 列表。
     */
    private HashMap<String, List<Director>> celletDirectorMap;

    private final Map<String, Long> routingCursors = new HashMap<>();
    private final Map<String, Director> controlDirectors = new HashMap<>();
    private final Map<String, Map<TalkContext, Director>> celletTalkDirectors = new HashMap<>();
    private final CapabilityRouter capabilityRouter = new CapabilityRouter(this);

    /**
     * 会话上下文对应的服务主机。
     */
    protected HashMap<TalkContext, Director> talkDirectorMap;

    /**
     * 执行机监听器。
     */
    private ConcurrentHashMap<String, PerformerListener> listenerMap;
    private final ConcurrentHashMap<Speakable, Boolean> failedHandshakes = new ConcurrentHashMap<>();

    /**
     * 在线的联系人。
     */
    private ConcurrentHashMap<String, Contact> onlineContacts;

    /**
     * 令牌对应的设备。
     */
    private ConcurrentHashMap<String, Device> tokenDeviceMap;

    /**
     * 有效的令牌。
     */
    private ConcurrentHashMap<String, AuthToken> validAuthTokenMap;

    /**
     * 数据传输记录。
     */
    protected ConcurrentHashMap<Long, Transmission> transmissionMap;

    /**
     * 阻塞块记录。
     */
    private ConcurrentHashMap<Long, Block> blockMap;

    /**
     * 阻塞超时时长。
     */
    private long blockTimeout = 30000;

    /**
     * 定时回调清单。
     */
    private final CopyOnWriteArrayList<Tickable> tickableList;

    /**
     * 流服务器。
     */
    private StreamServer streamServer;

    /**
     * 构造函数。
     *
     * @param nucleus 当前 Cell 的内核实例。
     */
    public Performer(Nucleus nucleus) {
        this.talkService = nucleus.getTalkService();
        this.celletService = nucleus.getCelletService();
        this.directorList = new ArrayList<>();
        this.celletDirectorMap = new HashMap<>();
        this.talkDirectorMap = new HashMap<>();
        this.listenerMap = new ConcurrentHashMap<>();
        this.onlineContacts = new ConcurrentHashMap<>();
        this.tokenDeviceMap = new ConcurrentHashMap<>();
        this.validAuthTokenMap = new ConcurrentHashMap<>();
        this.transmissionMap = new ConcurrentHashMap<>();
        this.blockMap = new ConcurrentHashMap<>();
        this.tickableList = new CopyOnWriteArrayList<>();
        this.streamServer = new StreamServer();
    }

    /**
     * 获取指定名称的 Cellet 实例。
     *
     * @param name 指定 Cellet 的名称。
     * @return 返回 Cellet 实例，如果指定名称的 Cellet 不存在则返回 {@code null} 。
     */
    public Cellet getCellet(String name) {
        return this.celletService.getCellet(name);
    }

    /**
     * 设置节点名。
     *
     * @param nodeName 指定节点名。
     */
    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    /**
     * 获取节点名。
     *
     * @return 返回节点名。
     */
    public String getNodeName() {
        return this.nodeName;
    }

    /**
     * 返回 HTTP 服务器实例。
     *
     * @return 返回 HTTP 服务器实例。
     */
    public HttpServer getHttpServer() {
        return this.httpServer;
    }

    /**
     * 配置 HTTP 服务器。
     *
     * @param config 指定配置信息。
     */
    public void configHttpServer(HttpConfig config) {
        if (null == this.httpServer) {
            this.httpServer = new HttpServer();
        }

        if (config.httpsPort > 0) {
            try {
                this.httpServer.setKeystorePath(config.keystore);
            } catch (FileNotFoundException e) {
                Logger.w(this.getClass(), "install", e);
            }
            this.httpServer.setKeystorePassword(config.storePassword, config.managerPassword);
        }

        this.httpServer.setPort(config.httpPort, config.httpsPort);
        this.httpServer.setThreads(config.maxThreads, config.minThreads);

        Logger.i(this.getClass(), "#configHttpServer - The http server threads: "
                + config.maxThreads + "/" + config.minThreads);
    }

    /**
     * 返回流服务器实例。
     *
     * @return 返回流服务器实例。
     */
    public StreamServer getStreamServer() {
        return this.streamServer;
    }

    /**
     * 获取文件输入并发限制数。
     *
     * @return 返回允许的并发文件输入任务数量，默认为 10 。
     */
    public int getConcurrentFileInputLimit() {
        try {
            return Integer.parseInt(this.properties.getProperty("concurrency.file.in", "10"));
        } catch (Exception e) {
            return 10;
        }
    }

    /**
     * 获取文件输出并发限制数。
     *
     * @return 返回允许的并发文件输出任务数量，默认为 10 。
     */
    public int getConcurrentFileOutputLimit() {
        try {
            return Integer.parseInt(this.properties.getProperty("concurrency.file.out", "10"));
        } catch (Exception e) {
            return 10;
        }
    }

    /**
     * 获取文件操作并发限制数。
     *
     * @return 返回允许的并发文件操作任务数量，默认为 10 。
     */
    public int getConcurrentFileOperationLimit() {
        try {
            return Integer.parseInt(this.properties.getProperty("concurrency.file.operation", "10"));
        } catch (Exception e) {
            return 10;
        }
    }

    /**
     * 获取 CV 服务并发限制数。
     *
     * @return 返回允许的并发 CV 任务数量，默认为 10 。
     */
    public int getCVConcurrencyLimit() {
        try {
            return Integer.parseInt(this.properties.getProperty("concurrency.cv", "10"));
        } catch (Exception e) {
            return 10;
        }
    }

    /**
     * 设置配置属性。
     *
     * @param properties 指定属性。
     */
    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    /**
     * 获取配置属性。
     *
     * @return 返回属性。
     */
    public Properties getProperties() {
        return this.properties;
    }

    /**
     * 获取外部 HTTP 节点。
     *
     * @return 外部 HTTP 节点。
     */
    public Endpoint getExternalHttpEndpoint() {
        return new Endpoint(this.properties.getProperty("external.http.host", "127.0.0.1"),
                Integer.parseInt(this.properties.getProperty("external.http.port", "7010")));
    }

    /**
     * 获取外部 HTTPS 节点。
     *
     * @return 外部 HTTPS 节点。
     */
    public Endpoint getExternalHttpsEndpoint() {
        return new Endpoint(this.properties.getProperty("external.https.host", "127.0.0.1"),
                Integer.parseInt(this.properties.getProperty("external.https.port", "7017")));
    }

    /**
     * 添加 Director 节点。
     *
     * @param address   导演机的地址。
     * @param port      导演机的端口。
     * @param fsAddress 文件系统地址。
     * @param fsPort    文件系统端口。
     * @param scope     该导演机的配置范围。
     * @return 返回导演机节点。
     */
    public Director addDirector(String address, int port, String fsAddress, int fsPort, Scope scope) {
        // Client is the internal control Cellet deployed alongside Auth in existing configurations.
        if (scope.cellets.contains("Auth") && !scope.cellets.contains("Client")) scope.cellets.add("Client");
        Endpoint endpoint = new Endpoint(address, port);
        Endpoint fileEndpoint = new Endpoint(fsAddress, fsPort);
        Director director = new Director(endpoint, fileEndpoint, scope);

        if (this.directorList.contains(director)) {
            return null;
        }

        this.directorList.add(director);

        for (String celletName : scope.cellets) {
            List<Director> list = this.celletDirectorMap.computeIfAbsent(celletName, k -> new ArrayList<>());
            list.add(director);
        }

        return director;
    }

    /**
     * 仅选择显式承载指定 Cellet 的就绪节点，不会回退到其他服务节点。
     *
     * @param celletName 指定 Cellet 的名称。
     * @return 返回被选中的导演机，如果没有就绪的候选节点则返回 {@code null} 。
     */
    public synchronized Director selectDirectorForCellet(String celletName) {
        List<Director> candidates = getDirectorsForCellet(celletName);
        List<Director> ready = new ArrayList<>();
        for (Director director : candidates) if (isReady(director, celletName)) ready.add(director);
        if (ready.isEmpty()) return null;
        long cursor = this.routingCursors.getOrDefault(celletName, 0L);
        this.routingCursors.put(celletName, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return ready.get((int) Math.floorMod(cursor, (long) ready.size()));
    }

    public List<Director> getDirectorsForCellet(String celletName) {
        List<Director> list = this.celletDirectorMap.get(celletName);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    public CapabilityRouter getCapabilityRouter() {
        return this.capabilityRouter;
    }

    private boolean isReady(Director director, String celletName) {
        return director != null && director.supports(celletName) && director.speaker != null && director.speaker.isReady();
    }

    public Director selectDirectorForRequest(String token, String celletName, ActionDialect action) {
        if (AIGCCellet.NAME.equals(celletName)) {
            Director director = this.capabilityRouter.select(action);
            if (director != null) return director;
        }
        return this.selectDirector(token, celletName);
    }

    private Director selectDirectorForRequest(TalkContext context, String celletName, ActionDialect action) {
        if (AIGCCellet.NAME.equals(celletName)) {
            Director director = this.capabilityRouter.select(action);
            if (director != null) return director;
        }
        return this.selectDirector(context, celletName);
    }

    private String requestToken(ActionDialect action) {
        if (action.containsParam("token")) return action.getParamAsString("token");
        JSONObject data = action.containsParam("data") ? action.getParamAsJson("data") : null;
        return data == null ? null : data.optString("token", null);
    }

    /**
     * 在与同步控制消息相同的连接上打开数据流。
     *
     * @param director   指定导演机。
     * @param celletName 指定 Cellet 的名称。
     * @param streamName 指定流名称。
     * @return 返回打开的输出流。
     * @throws IOException 当连接不可用或无法打开流时抛出。
     */
    public PrimitiveOutputStream openStream(Director director, String celletName, String streamName) throws IOException {
        if (director == null || !director.supports(celletName)
                || director.speaker == null || !director.speaker.isReady())
            throw new IOException("Service unavailable");
        PrimitiveOutputStream stream = director.speaker.speakStream(celletName, streamName);
        if (stream == null) throw new IOException("Stream unavailable");
        return stream;
    }

    /**
     * 重试握手失败的连接；已建立连接的重连由 Cell 自行处理。
     *
     * @param celletName 指定 Cellet 的名称。
     */
    public synchronized void retryFailedConnection(String celletName) {
        List<Director> directors = this.celletDirectorMap.get(celletName);
        if (directors == null) return;
        for (Director director : directors) {
            if (director.speaker != null && !director.speaker.isReady() && this.failedHandshakes.remove(director.speaker) != null) {
                this.talkService.hangup(director.endpoint.getHost(), director.endpoint.getPort(), true);
                director.speaker = this.talkService.call(director.endpoint.getHost(), director.endpoint.getPort());
            }
        }
    }

    /**
     * 选择节点。
     *
     * @param context 会话上下文。
     * @param celletName  Cellet 名称。
     * @return 返回被选中的导演机。
     */
    private synchronized Director selectDirector(TalkContext context, String celletName) {
        if (context == null) return selectDirector((String) null, celletName);
        Map<TalkContext, Director> bindings = celletTalkDirectors.computeIfAbsent(celletName, k -> new HashMap<>());
        bindings.entrySet().removeIf(e -> !e.getKey().isValid());
        Director director = bindings.get(context);
        if (!isReady(director, celletName)) {
            director = selectDirectorForCellet(celletName);
            if (director != null) {
                bindings.put(context, director);
                talkDirectorMap.put(context, director);
            }
        }
        return director;
    }

    /**
     * Control/session routing only. Capability requests use selectDirectorForRequest.
     */
    public synchronized Director selectDirector(String tokenCode, String celletName) {
        if (tokenCode == null || tokenCode.isEmpty()) return selectDirectorForCellet(celletName);
        Device device = tokenCode == null ? null : tokenDeviceMap.get(tokenCode);
        if (device != null) return selectDirector(device.getTalkContext(), celletName);
        String key = celletName + ":" + (tokenCode == null ? "" : tokenCode);
        Director director = controlDirectors.get(key);
        if (!isReady(director, celletName)) {
            director = selectDirectorForCellet(celletName);
            if (director != null) controlDirectors.put(key, director);
        }
        return director;
    }

    /**
     * 联系人设备是否已存在。
     *
     * @param device 指定待检查的设备。
     * @return 返回已存在的设备，如果不存在则返回 {@code null} 。
     */
    public Device existsDevice(Device device) {
        return this.tokenDeviceMap.get(device.getToken());
    }

    /**
     * 更新联系人。
     *
     * @param contact 指定联系人。
     * @param device  指定当前设备。
     */
    public void updateContact(Contact contact, Device device) {
        // 记录 Token 对应的设备
        this.tokenDeviceMap.put(device.getToken(), device);

        Contact current = this.onlineContacts.get(contact.getUniqueKey());
        if (null == current) {
            this.onlineContacts.put(contact.getUniqueKey(), contact);
        } else {
            current.setName(contact.getName());

            JSONObject ctx = contact.getContext();
            if (null != ctx) {
                current.setContext(ctx);
            }

            for (Device dev : contact.getDeviceList()) {
                current.addDevice(dev);
            }
        }
    }

    /**
     * 从在线列表移除联系人。
     *
     * @param contact 指定联系人。
     * @param device  指定需要移除的设备。
     */
    public void removeContact(Contact contact, Device device) {
        Contact current = this.onlineContacts.get(contact.getUniqueKey());
        if (null == current) {
            return;
        }

        current.removeDevice(device);

        if (current.numDevices() == 0) {
            // 已经没有设备连接，从在线列表删除
            this.onlineContacts.remove(contact.getUniqueKey());

            Logger.i(this.getClass(), "Remove online contact: " + contact.getId());
        }
    }

    /**
     * 获取在线联系人映射。
     *
     * @return 返回联系人唯一键到联系人实例的映射表。
     */
    protected Map<String, Contact> getOnlineContacts() {
        return this.onlineContacts;
    }

    /**
     * 为指定 Cellet 设置执行机监听器。
     *
     * @param celletName 指定 Cellet 的名称。
     * @param listener   指定监听器实例。
     */
    public void setListener(String celletName, PerformerListener listener) {
        this.listenerMap.put(celletName, listener);
    }

    /**
     * 注销指定监听器，不移除已经被替换的监听器。
     */
    public boolean removeListener(String celletName, PerformerListener listener) {
        return null != celletName && null != listener && this.listenerMap.remove(celletName, listener);
    }

    /**
     * 移除指定会话上下文。
     *
     * <p>同时清理该上下文对应的导演机路由记录、数据传输记录以及设备记录。</p>
     *
     * @param context 指定会话上下文。
     */
    public synchronized void removeTalkContext(TalkContext context) {
        this.talkDirectorMap.remove(context);
        for (Map<TalkContext, Director> bindings : this.celletTalkDirectors.values()) bindings.remove(context);

        // 删除记录
        Iterator<Transmission> iter = this.transmissionMap.values().iterator();
        while (iter.hasNext()) {
            Transmission transmission = iter.next();
            if (transmission.source == context) {
                iter.remove();
            }
        }

        Iterator<Device> diter = this.tokenDeviceMap.values().iterator();
        while (diter.hasNext()) {
            Device device = diter.next();
            if (device.getTalkContext() == context) {
                diter.remove();
            }
        }
    }

    /**
     * 获取多线程执行器。
     *
     * @return 返回线程池实例。
     */
    public ExecutorService getExecutor() {
        return this.executor;
    }

    /**
     * 在线程池里执行任务。
     *
     * @param runnable 任务实例。
     */
    public void execute(Runnable runnable) {
        this.executor.execute(runnable);
    }

    /**
     * 启动执行机、服务连接及后台能力查询。
     */
    public void start(List<String> cellets) {
        int max = 8;
        try {
            max = Integer.parseInt(this.properties.getProperty("threadpool.max", "16"));
        } catch (Exception e) {
            // Nothing
        }
        if (this.properties.getProperty("threadpool.type", "cached").equalsIgnoreCase("cached")) {
            this.executor = CachedQueueExecutor.newCachedQueueThreadPool(max);
        } else {
            this.executor = Executors.newFixedThreadPool(max);
        }
        Logger.i(this.getClass(), "#start - The thread pool type: "
                + this.properties.getProperty("threadpool.type", "cached") + " - max: " + max);

        // 添加全局监听
        this.talkService.addListener(this);

        this.capabilityRouter.start();

        // 启动所有 Director
        for (Director director : this.directorList) {
            Endpoint ep = director.endpoint;
            director.speaker = this.talkService.call(ep.getHost(), ep.getPort());
        }

        this.capabilityRouter.onTick(System.currentTimeMillis());

        // 启动 HTTP 服务器
        if (this.harnessGateway == null &&
                Boolean.parseBoolean(this.properties.getProperty("harness.rest.enabled", "false"))) {
            this.harnessGateway = cube.dispatcher.harness.HarnessGateway.install(this.httpServer, this);
        }
        this.httpServer.start();

        // 启动流服务器
        this.streamServer.start(Integer.parseInt(
                this.properties.getProperty("stream.port", "7171")));
    }

    /**
     * 停止执行机。
     *
     * <p>唤醒全部阻塞等待，停止流服务器和 HTTP 服务器，断开与所有导演机的连接，
     * 并关闭线程池。</p>
     */
    public void stop() {
        this.capabilityRouter.stop();
        synchronized (this) {
            this.controlDirectors.clear();
            this.routingCursors.clear();
            this.celletTalkDirectors.clear();
            this.talkDirectorMap.clear();
        }
        for (Block block : this.blockMap.values()) {
            synchronized (block) {
                block.notify();
            }
        }
        this.blockMap.clear();

        // 停止流服务器
        if (null != this.streamServer) {
            this.streamServer.stop();
        }

        // 停止 HTTP 服务器
        this.httpServer.stop();

        for (Director director : this.directorList) {
            Endpoint ep = director.endpoint;
            this.talkService.hangup(ep.getHost(), ep.getPort(), false);
        }

        HttpClientFactory.getInstance().close();

        this.executor.shutdown();
    }

    /**
     * 重启执行机。
     *
     * <p>唤醒全部阻塞等待，断开与所有导演机的连接后重新建立连接。</p>
     */
    public void restart() {
        this.capabilityRouter.stop();
        this.capabilityRouter.start();
        for (Block block : this.blockMap.values()) {
            synchronized (block) {
                block.notify();
            }
        }
        this.blockMap.clear();

        for (Director director : this.directorList) {
            Endpoint ep = director.endpoint;
            this.talkService.hangup(ep.getHost(), ep.getPort(), true);
        }

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 重连
        for (Director director : this.directorList) {
            Endpoint ep = director.endpoint;
            Speakable speakable = this.talkService.call(ep.getHost(), ep.getPort());
            director.speaker = speakable;
        }
    }


    /**
     * 指定的令牌是否在当前节点建立连接。
     *
     * @param tokenCode 指定令牌码。
     * @return 如果令牌已建立连接返回 <code>true</code> ，否则返回 <code>false</code> 。
     */
    public boolean existsTokenCode(String tokenCode) {
        return this.tokenDeviceMap.containsKey(tokenCode);
    }

    /**
     * 获取指定令牌对应的会话上下文。
     *
     * @param tokenCode 指定令牌码。
     * @return 返回会话上下文，如果令牌没有对应设备则返回 {@code null} 。
     */
    public TalkContext getTalkContext(String tokenCode) {
        Device device = this.tokenDeviceMap.get(tokenCode);
        if (null == device) {
            return null;
        }

        return device.getTalkContext();
    }

    /**
     * 查询联系人。
     *
     * @param talkContext 指定会话上下文。
     * @return 返回该会话上下文对应的联系人，如果未找到则返回 {@code null} 。
     */
    public Contact queryContact(TalkContext talkContext) {
        Iterator<Contact> iter = this.onlineContacts.values().iterator();
        while (iter.hasNext()) {
            Contact contact = iter.next();
            Device device = contact.getDevice(talkContext);
            if (null != device) {
                Contact result = new Contact(contact.getId(), contact.getDomain(), contact.getName());
                result.addDevice(device);
                return result;
            }
        }
        return null;
    }

    /**
     * 校验令牌码是否有效。
     *
     * <p>优先从本地缓存读取，缓存未命中时向服务单元请求校验。</p>
     *
     * @param tokenCode 指定令牌码。
     * @return 返回有效的令牌，校验失败时返回 {@code null} 。
     */
    public AuthToken verifyToken(String tokenCode) {
        AuthToken authToken = this.validAuthTokenMap.get(tokenCode);
        if (null != authToken) {
            authToken.timestamp = System.currentTimeMillis();
            return authToken;
        }

        ActionDialect actionDialect = new ActionDialect(ClientAction.GetAuthToken.name);
        actionDialect.addParam("tokenCode", tokenCode);

        ActionDialect response = this.syncTransmit("Client", actionDialect);
        if (response.getParamAsInt("code") != AuthStateCode.Ok.code) {
            return null;
        }

        authToken = new AuthToken(response.getParamAsJson("token"));
        this.validAuthTokenMap.put(tokenCode, authToken);
        return authToken;
    }

    /**
     * 向服务单元发送数据，不等待应答。
     *
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     */
    public void transmit(String celletName, ActionDialect actionDialect) {
        this.tryTransmit(celletName, actionDialect);
    }

    /**
     * 向服务单元发送数据，返回是否成功交给连接发送。
     *
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @return 如果数据成功交给连接发送返回 <code>true</code> ，否则返回 <code>false</code> 。
     */
    public boolean tryTransmit(String celletName, ActionDialect actionDialect) {
        if (null == celletName || null == actionDialect) {
            return false;
        }
        try {
            Director director = this.selectDirectorForRequest(requestToken(actionDialect), celletName, actionDialect);
            if (null == director || null == director.speaker) {
                Logger.w(this.getClass(), "Can not connect '" + celletName + "'");
                return false;
            }
            return this.tryTransmit(director, celletName, actionDialect);
        } catch (cube.dispatcher.aigc.CapabilityUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            Logger.w(this.getClass(), "#tryTransmit - " + celletName, e);
            return false;
        }
    }

    /**
     * 将请求的所有事件和控制操作发送到其原始节点。
     *
     * @param director      指定导演机。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @return 如果数据成功交给连接发送返回 <code>true</code> ，否则返回 <code>false</code> 。
     */
    public boolean tryTransmit(Director director, String celletName, ActionDialect actionDialect) {
        if (!isReady(director, celletName) || actionDialect == null) return false;
        final Speakable source = director.speaker;
        long sn = actionDialect.containsParam("sn") ? actionDialect.getParamAsLong("sn") : Utils.generateSerialNumber();
        actionDialect.addParam(this.performerKey, createPerformer(sn));
        boolean sent = false, tracked = false;
        try {
            if (AIGCCellet.NAME.equals(celletName)) this.capabilityRouter.sent(director, source, actionDialect);
            tracked = AIGCCellet.NAME.equals(celletName);
            sent = source.speak(celletName, actionDialect);
            return sent;
        } catch (cube.dispatcher.aigc.CapabilityUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            Logger.w(this.getClass(), "#tryTransmit - " + celletName, e);
            return false;
        } finally {
            if (!sent && tracked) this.capabilityRouter.failed(actionDialect);
        }
    }

    /**
     * 向服务单元发送数据，不等待应答。
     *
     * @param talkContext   指定会话上下文。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     */
    public void transmit(TalkContext talkContext, String celletName, ActionDialect actionDialect) {
        Director director = this.selectDirectorForRequest(talkContext, celletName, actionDialect);
        if (null == director) {
            Logger.e(this.getClass(), "Can not connect '" + celletName + "'");
            return;
        }

        this.tryTransmit(director, celletName, actionDialect);
    }

    /**
     * 向服务单元发送数据，不等待应答。
     *
     * <p>发送时会记录该数据与客户端会话上下文的映射关系，用于服务节点回送数据时转发客户端。</p>
     *
     * @param talkContext   指定会话上下文。
     * @param cellet        指定 Cellet 实例。
     * @param actionDialect 指定发送的数据。
     */
    public void transmit(TalkContext talkContext, Cellet cellet, ActionDialect actionDialect) {
        long sn = actionDialect.containsParam("sn") ?
                actionDialect.getParamAsLong("sn") : Utils.generateSerialNumber();

        Director director = this.selectDirectorForRequest(talkContext, cellet.getName(), actionDialect);
        if (null == director) {
            Logger.e(this.getClass(), "Can not connect '" + cellet.getName() + "'");
            return;
        }

        // 绑定关系
        Transmission trans = new Transmission(sn, cellet, talkContext);
        this.transmissionMap.put(trans.sn, trans);
        boolean sent = false;
        try {
            sent = this.tryTransmit(director, cellet.getName(), actionDialect);
        } finally {
            if (!sent) this.transmissionMap.remove(trans.sn, trans);
        }
    }

    /**
     * 向服务单元发送数据，该发送不做任何记录。
     *
     * @param tokenCode     指定令牌码。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     */
    public void transmit(String tokenCode, String celletName, ActionDialect actionDialect) {
        Director director = this.selectDirectorForRequest(tokenCode, celletName, actionDialect);
        this.tryTransmit(director, celletName, actionDialect);
    }

    /**
     * 向服务单元发送流。
     *
     * @param tokenCode   指定令牌码。
     * @param celletName  指定 Cellet 的名称。
     * @param streamName  指定流名称。
     * @param inputStream 指定待发送的输入流。
     */
    public void transmit(String tokenCode, String celletName, String streamName, InputStream inputStream) {
        Director director = this.selectDirector(tokenCode, celletName);

        long total = 0;
        // 发送数据
        PrimitiveOutputStream stream = director.speaker.speakStream(celletName, streamName);
        try {
            byte[] buf = new byte[64 * 1024];
            int length = 0;
            while ((length = inputStream.read(buf)) > 0) {
                stream.write(buf, 0, length);
                total += length;
            }
        } catch (IOException e) {
            Logger.e(this.getClass(), "Writing primitive output stream", e);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                // Nothing
            }

            try {
                inputStream.close();
            } catch (IOException e) {
                // Nothing
            }
        }

        if (Logger.isDebugLevel()) {
            Logger.d(this.getClass(), "#transmit stream '" + streamName + "' (" + total + " bytes) to " + tokenCode);
        }
    }

    /**
     * 向服务单元发送流。
     *
     * @param celletName  指定 Cellet 的名称。
     * @param streamName  指定流名称。
     * @param inputStream 指定待发送的输入流。
     * @return 返回流的 MD5 码和 SHA1 码，数组索引 0 为 MD5 码，索引 1 为 SHA1 码。
     */
    public String[] transmit(String celletName, String streamName, InputStream inputStream) {
        // 选择 Director
        Director director = this.selectDirector((String) null, celletName);

        MessageDigest md5 = null;
        MessageDigest sha1 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
            sha1 = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        long total = 0;
        // 发送数据
        PrimitiveOutputStream stream = director.speaker.speakStream(celletName, streamName);
        try {
            byte[] buf = new byte[64 * 1024];
            int length = 0;
            while ((length = inputStream.read(buf)) > 0) {
                // 发送数据
                stream.write(buf, 0, length);

                // 计算散列
                md5.update(buf, 0, length);
                sha1.update(buf, 0, length);

                total += length;
            }
        } catch (IOException e) {
            Logger.e(this.getClass(), "Writing primitive output stream", e);
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                // Nothing
            }

            try {
                inputStream.close();
            } catch (IOException e) {
                // Nothing
            }
        }

        if (Logger.isDebugLevel()) {
            Logger.d(this.getClass(), "#transmit stream '" + streamName + "' (" + total + " bytes)");
        }

        byte[] hashMD5 = md5.digest();
        byte[] hashSHA1 = sha1.digest();
        String md5Code = FileUtils.bytesToHexString(hashMD5);
        String sha1Code = FileUtils.bytesToHexString(hashSHA1);
        return new String[]{md5Code, sha1Code};
    }

    /**
     * 向服务单元发送数据，并阻塞当前线程直到应答或超时。
     *
     * @param talkContext   指定会话上下文。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @return 返回服务单元的应答数据，超时或无可用节点时返回 {@code null} 。
     */
    public ActionDialect syncTransmit(TalkContext talkContext, String celletName, ActionDialect actionDialect) {
        Director director = this.selectDirectorForRequest(talkContext, celletName, actionDialect);
        if (null == director) {
            Logger.e(this.getClass(), "Can not connect '" + celletName + "'");
            return null;
        }

        return this.syncTransmit(director, celletName, actionDialect, this.blockTimeout);
    }

    /**
     * 向服务单元发送数据，并阻塞当前线程直到应答或超时。
     *
     * @param talkContext   指定会话上下文。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @param blockTimeout  指定阻塞超时时长，单位毫秒。
     * @return 返回服务单元的应答数据，超时或无可用节点时返回 {@code null} 。
     */
    public ActionDialect syncTransmit(TalkContext talkContext, String celletName, ActionDialect actionDialect,
                                      long blockTimeout) {
        Director director = this.selectDirectorForRequest(talkContext, celletName, actionDialect);
        if (null == director) {
            Logger.e(this.getClass(), "Can not connect '" + celletName + "'");
            return null;
        }

        return this.syncTransmit(director, celletName, actionDialect, blockTimeout);
    }

    /**
     * 向服务单元发送数据，并阻塞当前线程直到应答或超时。
     *
     * @param tokenCode     指定令牌码。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @return 返回服务单元的应答数据，超时或发送失败时返回 {@code null} 。
     */
    public ActionDialect syncTransmit(String tokenCode, String celletName, ActionDialect actionDialect) {
        Director director = this.selectDirectorForRequest(tokenCode, celletName, actionDialect);
        return this.syncTransmit(director, celletName, actionDialect, this.blockTimeout);
    }

    /**
     * 向服务单元发送数据，并阻塞当前线程直到应答或超时。
     *
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @return 返回服务单元的应答数据，超时或发送失败时返回 {@code null} 。
     */
    public ActionDialect syncTransmit(String celletName, ActionDialect actionDialect) {
        return this.syncTransmit(this.selectDirectorForRequest(requestToken(actionDialect), celletName, actionDialect), celletName, actionDialect, this.blockTimeout);
    }

    /**
     * 向服务单元发送数据，并阻塞当前线程直到应答或超时。
     *
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @param timeout       指定阻塞超时时长，单位毫秒。
     * @return 返回服务单元的应答数据，超时或发送失败时返回 {@code null} 。
     */
    public ActionDialect syncTransmit(String celletName, ActionDialect actionDialect, long timeout) {
        return this.syncTransmit(this.selectDirectorForRequest(requestToken(actionDialect), celletName, actionDialect), celletName, actionDialect, timeout);
    }

    /**
     * 向服务单元发送数据，并阻塞当前线程直到应答或超时。
     *
     * @param director      指定导演机。
     * @param celletName    指定 Cellet 的名称。
     * @param actionDialect 指定发送的数据。
     * @param timeout       指定阻塞超时时长，单位毫秒。
     * @return 返回服务单元的应答数据，超时、连接不可用或发送失败时返回 {@code null} 。
     */
    public ActionDialect syncTransmit(Director director, String celletName, ActionDialect actionDialect, long timeout) {
        if (!isReady(director, celletName) || null == actionDialect) {
            return null;
        }
        final Speakable source = director.speaker;
        long sn = actionDialect.containsParam("sn") ?
                actionDialect.getParamAsLong("sn") : Utils.generateSerialNumber();

        // 添加 Performer 信息
        actionDialect.addParam(this.performerKey, createPerformer(sn));

        final Block block = new Block(sn);
        block.source = source;
        this.blockMap.put(block.sn, block);
        boolean tracked = false, received = false;
        try {
            if (AIGCCellet.NAME.equals(celletName)) this.capabilityRouter.sent(director, source, actionDialect);
            tracked = AIGCCellet.NAME.equals(celletName);
            if (Thread.currentThread().isInterrupted() || !source.speak(celletName, actionDialect)) {
                return null;
            }
            long beginning = System.nanoTime();
            while (null == block.dialect &&
                    System.nanoTime() - beginning < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout)) {
                if (!this.blockMap.containsKey(sn)) {
                    // stop/restart 已释放等待记录。
                    break;
                }
                Thread.sleep(50);
            }
            ActionDialect response = block.dialect;
            if (null != response) {
                received = true;
                if (AIGCCellet.NAME.equals(celletName)) this.capabilityRouter.response(source, response);
                response.removeParam(this.performerKey);
                return response;
            }
            Logger.w(this.getClass(), "Service timeout '" + celletName + "'");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (cube.dispatcher.aigc.CapabilityUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            Logger.w(this.getClass(), "#syncTransmit - " + celletName, e);
            return null;
        } finally {
            this.blockMap.remove(sn, block);
            this.capabilityRouter.finished(sn);
            if (tracked && !received) this.capabilityRouter.failed(actionDialect);
        }
    }

    /**
     * 创建 Performer 标识数据。
     *
     * @param sn 指定序列号。
     * @return 返回包含序列号和时间戳的 JSON 对象。
     */
    private JSONObject createPerformer(long sn) {
        JSONObject json = new JSONObject();
        try {
            json.put("sn", sn);
            json.put("ts", System.currentTimeMillis());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    /**
     * 处理服务单元回送的应答数据。
     *
     * @param response 指定应答数据。
     * @return 返回添加了状态码的应答数据。
     */
    private ActionDialect processResponse(ActionDialect response) {
        response.addParam("state", StateCode.makeState(StateCode.OK, "OK"));
        return response;
    }

    /**
     * 添加定时回调对象。
     *
     * <p>重复添加同一对象不会产生重复记录。</p>
     *
     * @param tickable 指定定时回调实例。
     */
    public void addTickable(Tickable tickable) {
        if (null != tickable) {
            this.tickableList.addIfAbsent(tickable);
        }
    }

    /**
     * 移除定时回调对象。
     *
     * @param tickable 指定定时回调实例。
     */
    public void removeTickable(Tickable tickable) {
        this.tickableList.remove(tickable);
    }

    /**
     * Tick 回调。
     *
     * <p>向已注册的定时回调对象转发 Tick 事件，并清理超过 1 小时未使用的有效令牌。</p>
     *
     * @param now 指定当前时间戳。
     */
    @Override
    public void onTick(long now) {
        this.retryFailedConnection(AIGCCellet.NAME);
        this.capabilityRouter.onTick(now);
        for (Tickable tickable : this.tickableList) {
            tickable.onTick(now);
        }

        Iterator<Map.Entry<String, AuthToken>> atiter = this.validAuthTokenMap.entrySet().iterator();
        while (atiter.hasNext()) {
            Map.Entry<String, AuthToken> entry = atiter.next();
            if (now - entry.getValue().timestamp > 60 * 60 * 1000) {
                atiter.remove();
            }
        }
    }

    /**
     * 监听到服务单元数据的回调。
     *
     * <p>根据数据类型分别处理：同步阻塞应答、向客户端透传数据、广播或指定设备推送，
     * 以及转交给 Cellet 监听器。</p>
     *
     * @param speakable  指定连接实例。
     * @param celletName 指定 Cellet 的名称。
     * @param primitive  指定原始数据。
     */
    @Override
    public void onListened(Speakable speakable, String celletName, Primitive primitive) {
        try {
            ActionDialect actionDialect = new ActionDialect(primitive);
            if (AIGCCellet.NAME.equals(celletName)) this.capabilityRouter.response(speakable, actionDialect);
            if (actionDialect.containsParam(this.performerKey)) {
                JSONObject performer = actionDialect.getParamAsJson(this.performerKey);
                Long sn = performer.getLong("sn");
                final Block block = this.blockMap.get(sn);
                if (null != block) {
                    if (block.source != speakable) return;
                    synchronized (block) {
                        if (null == block.dialect) {
                            block.dialect = actionDialect;
                        }
                    }
                } else {
                    Transmission transmission = this.transmissionMap.get(sn);
                    if (null != transmission) {
                        // 移除 P-KEY
                        actionDialect.removeParam(this.performerKey);
                        // 向客户端发送数据
                        transmission.cellet.speak(transmission.source,
                                this.processResponse(actionDialect));
                    } else {
                        PerformerListener listener = this.listenerMap.get(celletName);
                        if (null != listener) {
                            listener.onReceived(celletName, primitive);
                        }
                    }
                }
            } else if (actionDialect.containsParam(this.directorKey)) {
                JSONObject director = actionDialect.getParamAsJson(this.directorKey);
                // 移除 D-KEY
                actionDialect.removeParam(this.directorKey);

                Long id = director.getLong("id");
                String domain = director.getString("domain");
                Device device = director.has("device") ? new Device(director.getJSONObject("device")) : null;

                // 联系人 KEY
                String key = UniqueKey.make(id, domain);

                Contact contact = this.onlineContacts.get(key);
                Cellet cellet = this.celletService.getCellet(celletName);

//                Logger.d(this.getClass(), "#" + celletName + " - " + contact.getId() + " - " + actionDialect.getName()
//                        + " - " + actionDialect.getParamAsJson("data").toString());

                if (null != contact && null != cellet) {
                    if (null == device) {
                        // 没有指定设备，进行广播
                        for (Device dev : contact.getDeviceList()) {
                            if (dev.isOnline()) {
                                cellet.speak(dev.getTalkContext(), this.processResponse(actionDialect));
                            }
                        }
                    } else {
                        // 指定设备
                        Device dev = contact.getDevice(device);
                        if (null != dev && dev.isOnline()) {
                            cellet.speak(dev.getTalkContext(), this.processResponse(actionDialect));
                        }
                    }
                } else {
                    Logger.w(this.getClass(), "Can NOT find online contact or cellet : " + key + " | " + celletName);
                }
            } else {
                PerformerListener listener = this.listenerMap.get(celletName);
                if (null != listener) {
                    listener.onReceived(celletName, primitive);
                }
            }
        } catch (Exception e) {
            Logger.e(this.getClass(), "#onListened", e);
        }
    }

    /**
     * 监听到服务单元流数据的回调。
     *
     * <p>将流转交给对应 Cellet 的监听器，如果没有注册监听器则关闭该流。</p>
     *
     * @param speakable            指定连接实例。
     * @param cellet               指定 Cellet 的名称。
     * @param primitiveInputStream 指定输入流。
     */
    @Override
    public void onListened(Speakable speakable, String cellet, PrimitiveInputStream primitiveInputStream) {
        PerformerListener listener = this.listenerMap.get(cellet);
        if (listener != null) listener.onReceivedStream(speakable, cellet, primitiveInputStream);
        else try {
            primitiveInputStream.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 数据发送完成的回调。
     *
     * <p>当前无处理逻辑。</p>
     *
     * @param speakable 指定连接实例。
     * @param cellet    指定 Cellet 的名称。
     * @param primitive 指定原始数据。
     */
    @Override
    public void onSpoke(Speakable speakable, String cellet, Primitive primitive) {

    }

    /**
     * 收到应答确认的回调。
     *
     * <p>当前无处理逻辑。</p>
     *
     * @param speakable 指定连接实例。
     * @param cellet    指定 Cellet 的名称。
     * @param primitive 指定原始数据。
     */
    @Override
    public void onAck(Speakable speakable, String cellet, Primitive primitive) {

    }

    /**
     * 数据发送超时的回调。
     *
     * <p>当前无处理逻辑。</p>
     *
     * @param speakable 指定连接实例。
     * @param cellet    指定 Cellet 的名称。
     * @param primitive 指定原始数据。
     */
    @Override
    public void onSpeakTimeout(Speakable speakable, String cellet, Primitive primitive) {

    }

    /**
     * 连接建立成功的回调。
     *
     * <p>清除该连接的握手失败记录。</p>
     *
     * @param speakable 指定连接实例。
     */
    @Override
    public void onContacted(Speakable speakable) {
        this.failedHandshakes.remove(speakable);
        for (Director director : this.directorList) {
            java.net.InetSocketAddress remote = speakable.getRemoteAddress();
            if (director.endpoint.getPort() == remote.getPort()
                    && (director.speaker == speakable || director.endpoint.getHost().equals(remote.getHostString())
                    || (remote.getAddress() != null && director.endpoint.getHost().equals(remote.getAddress().getHostAddress())))) {
                director.speaker = speakable;
                if (director.supports(AIGCCellet.NAME)) this.capabilityRouter.connected(director, speakable);
                break;
            }
        }
        Logger.i(this.getClass(), "Contacted " + speakable.getRemoteAddress().getHostString() + ":" +
                speakable.getRemoteAddress().getPort());
    }

    /**
     * 连接断开的回调。
     *
     * <p>通知所有已注册的监听器连接断开。</p>
     *
     * @param speakable 指定连接实例。
     */
    @Override
    public void onQuitted(Speakable speakable) {
        this.capabilityRouter.disconnected(speakable);
        for (PerformerListener listener : this.listenerMap.values()) listener.onDisconnected(speakable);
        Logger.i(this.getClass(), "Quitted " + speakable.getRemoteAddress().getHostString() + ":" +
                speakable.getRemoteAddress().getPort());
    }

    /**
     * 连接失败的回调。
     *
     * <p>记录握手失败的连接以便后续重试，并通知所有已注册的监听器连接断开。</p>
     *
     * @param speakable 指定连接实例。
     * @param talkError 指定错误信息。
     */
    @Override
    public void onFailed(Speakable speakable, TalkError talkError) {
        this.capabilityRouter.disconnected(speakable);
        if (talkError.getErrorCode() == TalkError.HandshakeTimeout)
            this.failedHandshakes.put(speakable, Boolean.TRUE);
        for (PerformerListener listener : this.listenerMap.values()) listener.onDisconnected(speakable);
    }


    /**
     * 阻塞信息描述。
     */
    public class Block {

        /**
         * 阻塞记录的序列号。
         */
        public Long sn;

        /**
         * 服务单元回送的应答数据。
         */
        public volatile ActionDialect dialect;

        private Speakable source;

        /**
         * 构造函数。
         *
         * @param sn 指定序列号。
         */
        public Block(Long sn) {
            this.sn = sn;
        }
    }

    /**
     * 用于记录客户端经过网关通信后对应的由服务节点回送的数据的映射关系。
     */
    public class Transmission {

        /**
         * 数据传输记录的序列号。
         */
        public Long sn;

        /**
         * 对应的 Cellet 实例。
         */
        public Cellet cellet;

        /**
         * 客户端的会话上下文。
         */
        public TalkContext source;

        /**
         * 记录创建时的时间戳。
         */
        public long timestamp;

        /**
         * 构造函数。
         *
         * @param sn     指定序列号。
         * @param cellet 指定 Cellet 实例。
         * @param source 指定客户端的会话上下文。
         */
        public Transmission(Long sn, Cellet cellet, TalkContext source) {
            this.sn = sn;
            this.cellet = cellet;
            this.source = source;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
