/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.dispatcher.aigc;

import cell.api.Speakable;
import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.aigc.Consts;
import cube.aigc.ModelConfig;
import cube.aigc.SpecializedDetectionException;
import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionRequest;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AICapability;
import cube.common.entity.SpecializedDetectionInput;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/**
 * 服务端点能力路由器。
 *
 * <p>路由器周期性向本单元下的各服务端点查询能力快照并缓存在本地，用户请求不触发任何元数据 I/O：
 * 选路只在缓存快照上做匹配。除能力匹配外，路由器还记录通道、完成请求与检测会话的归属，
 * 保证需要延续上下文的请求始终回到创建它的端点。</p>
 *
 * <p>公开方法均为同步方法，内部状态只在持有本对象锁时读写；耗时查询一律投递到后台线程池执行。</p>
 */
public final class CapabilityRouter {

    /**
     * 快照刷新间隔、查询失败重试间隔、快照有效期与通道空闲回收时间，单位毫秒。
     */
    private static final long REFRESH = 30000, RETRY = 10000, TTL = 90000, CHANNEL_TTL = 30 * 60 * 1000;

    /**
     * 端点传输器，用于向服务端点发送能力查询。
     */
    private final Performer performer;

    /**
     * 各端点的路由状态，键为端点。
     */
    private final Map<Director, State> states = new HashMap<>();

    /**
     * 各类请求的轮转游标，键为路由键。
     */
    private final Map<String, Long> cursors = new HashMap<>();

    /**
     * 已建立的通道，键为通道标识。
     */
    private final Map<String, Owner> channels = new HashMap<>();

    /**
     * 已发出但未响应的通道申请，键为请求序号。
     */
    private final Map<Long, PendingChannel> pendingChannels = new HashMap<>();

    /**
     * 已发出但未结束的完成请求，键为请求标识。
     */
    private final Map<String, Owner> completions = new HashMap<>();

    /**
     * 已建立的检测会话，键为流标识。
     */
    private final Map<String, Owner> detectionStreams = new LinkedHashMap<>();

    /**
     * 已发出但未响应的检测请求，键为请求序号。
     */
    private final Map<Long, DetectionPending> detectionPending = new HashMap<>();

    /**
     * 能力查询线程池；为 {@code null} 表示路由器未启动。
     */
    private ExecutorService queries;

    /**
     * 构造方法。
     *
     * @param performer 端点传输器
     */
    public CapabilityRouter(Performer performer) {
        this.performer = performer;
    }

    /**
     * 启动路由器。
     *
     * <p>创建一个 2 线程的守护线程池，队列容量 16，用于向各服务端点查询能力快照。
     * 重复调用幂等。</p>
     */
    public synchronized void start() {
        if (queries != null) {
            return;
        }
        queries = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
            Thread t = new Thread(r, "aigc-capability-query");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 停止路由器并清空全部状态。
     *
     * <p>线程池立即中断，已排队的查询被丢弃；端点状态、游标、通道、待完成请求的映射一并清理。
     * 允许重复调用。</p>
     */
    public synchronized void stop() {
        if (queries != null) {
            queries.shutdownNow();
        }
        queries = null;
        states.clear();
        cursors.clear();
        channels.clear();
        pendingChannels.clear();
        completions.clear();
        detectionStreams.clear();
        detectionPending.clear();
    }

    /**
     * 登记端点连接。
     *
     * <p>同一端点重复连接时沿用已有状态并重新调度，不会产生重复状态。
     * 路由器尚未启动时直接返回，此时的能力快照由启动后的周期扫描补齐。</p>
     *
     * @param director 端点
     * @param source   与该端点通信的会话句柄
     */
    public synchronized void connected(Director director, Speakable source) {
        // 启动扫描会覆盖初始化之前就已连接的端点。
        if (queries == null) {
            return;
        }
        State existing = states.get(director);
        if (existing != null && existing.source == source && !existing.disconnected) {
            schedule(director, existing, System.currentTimeMillis());
            return;
        }
        State state = new State(source);
        states.put(director, state);
        schedule(director, state, System.currentTimeMillis());
    }

    /**
     * 处理端点断开。
     *
     * <p>标记状态为断开并丢弃快照，同时清理该会话持有的通道与完成订阅，
     * 避免把后续请求路由到已失效的连接。</p>
     *
     * @param source 断开的会话句柄
     */
    public synchronized void disconnected(Speakable source) {
        for (State state : states.values()) {
            if (state.source == source) {
                state.snapshot = null;
                state.disconnected = true;
            }
        }
        channels.entrySet().removeIf(e -> e.getValue().source == source);
        completions.entrySet().removeIf(e -> e.getValue().source == source);
        pendingChannels.entrySet().removeIf(e -> e.getValue().owner.source == source);
        detectionStreams.entrySet().removeIf(e -> e.getValue().source == source);
        detectionPending.entrySet().removeIf(e -> e.getValue().owner.source == source);
    }

    /**
     * 周期刷新端点能力。
     *
     * <p>遍历本单元的全部端点，跳过未就绪的连接；对新增或快照过期的端点重新发起查询。
     * 同时回收空闲超限的通道、完成请求、待完成通道与检测会话记录。
     * 路由器未启动时直接返回。</p>
     *
     * @param now 当前时间戳，单位毫秒
     */
    public synchronized void onTick(long now) {
        if (queries == null) {
            return;
        }
        for (Director director : performer.getDirectorsForCellet(AIGCCellet.NAME)) {
            Speakable source = director.speaker;
            if (source == null || !source.isReady()) {
                continue;
            }
            State state = states.get(director);
            if (state == null || state.source != source) {
                state = new State(source);
                states.put(director, state);
            }
            schedule(director, state, now);
        }
        channels.entrySet().removeIf(e -> now - e.getValue().touched > CHANNEL_TTL);
        completions.entrySet().removeIf(e -> now - e.getValue().touched > 60 * 60 * 1000);
        pendingChannels.entrySet().removeIf(e -> now - e.getValue().owner.touched > TTL);
        detectionStreams.entrySet().removeIf(e -> !e.getValue().live());
        detectionPending.entrySet().removeIf(e -> !e.getValue().owner.live() || now - e.getValue().owner.touched > 3600000);
    }

    /**
     * 安排一次能力查询。
     *
     * <p>仅在路由器已启动、状态未处于查询中、未断开且已过下次查询时间时投递任务；
     * 投递前先预置下次查询时间与在途标志，避免失败路径上反复重建状态。</p>
     *
     * @param director 目标端点
     * @param state    端点状态
     * @param now      当前时间戳，单位毫秒
     */
    private void schedule(Director director, State state, long now) {
        if (queries == null || state.inflight || state.disconnected || now < state.nextQuery) {
            return;
        }
        state.inflight = true;
        state.nextQuery = now + RETRY;
        try {
            queries.execute(() -> refresh(director, state));
        } catch (RejectedExecutionException e) {
            state.inflight = false;
        }
    }

    /**
     * 查询端点能力快照。
     *
     * <p>在后台线程执行：先确认状态仍是该端点的当前归属，再同步请求快照；
     * 返回后再次校验归属，避免把过期结果写回已被替换的状态。查询失败只记录一次告警，
     * 并按重试间隔再次调度。</p>
     *
     * @param director 目标端点
     * @param state    发起查询时的端点状态
     */
    private void refresh(Director director, State state) {
        synchronized (this) {
            if (states.get(director) != state || state.disconnected || director.speaker != state.source) {
                return;
            }
        }
        Snapshot snapshot = null;
        try {
            ActionDialect response = performer.syncTransmit(director, AIGCCellet.NAME,
                    new Packet(AIGCAction.GetCapabilitySnapshot.name, new JSONObject()).toDialect(), 3000);
            if (response != null) {
                Packet packet = new Packet(response);
                if (Packet.extractCode(packet) == 0) {
                    snapshot = new Snapshot(Packet.extractDataPayload(packet));
                }
            }
        } catch (RuntimeException e) {
            // 交给后台按重试间隔再试；用户请求路径上绝不查询。
        }
        synchronized (this) {
            if (states.get(director) != state || state.disconnected || director.speaker != state.source) {
                return;
            }
            state.inflight = false;
            long now = System.currentTimeMillis();
            if (snapshot != null) {
                String description = snapshot.json.toString();
                if (!description.equals(state.description)) {
                    Logger.i(getClass(), "Capabilities changed: " + director.endpoint + " ready=" + snapshot.ready
                            + " units=" + snapshot.units.length());
                    state.description = description;
                }
                state.snapshot = snapshot;
                state.received = now;
                state.failed = false;
                state.nextQuery = now + (snapshot.ready ? REFRESH : RETRY);
            } else if (!state.failed) {
                state.failed = true;
                Logger.w(getClass(), "Capability query failed: " + director.endpoint);
            }
        }
    }

    /**
     * 列出当前可用的候选端点。
     *
     * <p>只返回已连接、快照未过期且具备所需能力的端点。</p>
     *
     * @return 候选端点列表；无可用端点时为空列表
     */
    public synchronized List<Director> candidates() {
        List<Director> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Director director : performer.getDirectorsForCellet(AIGCCellet.NAME)) {
            if (valid(director, now)) {
                result.add(director);
            }
        }
        return result;
    }

    /**
     * 判断端点是否可作为路由候选。
     *
     * <p>要求状态存在、连接未断开且仍是同一会话、会话就绪、快照已就绪且未超过有效期。</p>
     *
     * @param director 端点
     * @param now      当前时间戳，单位毫秒
     * @return 可作为候选时返回 true
     */
    private boolean valid(Director director, long now) {
        State state = states.get(director);
        return state != null && !state.disconnected && director.speaker == state.source && state.source.isReady()
                && state.snapshot != null && state.snapshot.ready && now - state.received < TTL;
    }

    /**
     * 为指定请求选择目标端点。
     *
     * <p>普通的控制类动作不需要服务能力，返回 {@code null}；需要服务能力的动作在无可用端点时
     * 抛出能力不可用异常。通道类请求沿用通道创建时的端点，其余请求在候选端点间按路由键轮转。</p>
     *
     * @param dialect 请求方言
     * @return 目标端点；对无需路由的普通控制动作返回 {@code null}
     * @throws CapabilityUnavailableException 无可用端点或端点归属失效时抛出
     * @throws CompletionError                完成请求的结构或参数非法时抛出
     */
    public synchronized Director select(ActionDialect dialect) {
        String action = dialect.getName();
        JSONObject data = new Packet(dialect).data;
        if (AIGCAction.SpecializedDetection.name.equals(action)) {
            return selectDetection(new SpecializedDetectionInput(data.getJSONObject("input")));
        }
        if (AIGCAction.GetCapabilitySnapshot.name.equals(action)) {
            throw unavailable("Internal discovery action");
        }
        if (dialect.containsParam("moduleName")) {
            return selectModule(dialect, action);
        }
        String channel = data.optString("channel", data.optString("code", ""));
        boolean chat = AIGCAction.Chat.name.equals(action);
        boolean requestChannel = AIGCAction.RequestChannel.name.equals(action);
        boolean asr = AIGCAction.AutomaticSpeechRecognition.name.equals(action);
        boolean completion = AIGCAction.ChatCompletion.name.equals(action);
        boolean channelControl = AIGCAction.GetChannelInfo.name.equals(action)
                || AIGCAction.KeepAliveChannel.name.equals(action) || AIGCAction.StopChannel.name.equals(action);
        if (AIGCAction.CancelChatCompletion.name.equals(action)) {
            Owner owner = completions.get(data.optString("requestId"));
            if (owner == null || !owner.live()) {
                throw unavailable("Completion owner unavailable");
            }
            return owner.director;
        }
        if (!requestChannel && !asr && !completion && !chat && !channelControl) {
            return null;
        }
        String unit = data.optString("unit", ModelConfig.BAIZE_UNIT);
        JSONObject request = completion ? data.optJSONObject("request") : null;
        if (completion) {
            if (request == null) {
                throw CompletionError.invalid("request must be an object", "request");
            }
            try {
                CompletionRequest.validate(request);
            } catch (org.json.JSONException e) {
                throw CompletionError.invalid("Invalid structured request", null);
            }
        }
        boolean plainChat = chat && Consts.PATTERN_CHAT.equalsIgnoreCase(data.optString("pattern", Consts.PATTERN_CHAT));
        String key = asr ? "subtask:AutomaticSpeechRecognition" : completion ? completionKey(request)
                : "unit:" + unit.toLowerCase(Locale.ROOT);
        Owner owner = channels.get(channel);
        if (chat || channelControl) {
            if (owner == null || !owner.live()) {
                throw unavailable("Channel owner unavailable; request a new channel");
            }
            if (plainChat && !matches(owner.director, unit, null, false)) {
                throw unavailable("Channel unit unavailable: " + unit);
            }
            owner.touched = System.currentTimeMillis();
            return owner.director;
        }
        List<Director> eligible = new ArrayList<>();
        for (Director director : candidates()) {
            if (matches(director, unit, request, asr)) {
                eligible.add(director);
            }
        }
        if (eligible.isEmpty()) {
            throw unavailable("No service capability for " + key);
        }
        // 路由键集合有上限，避免任意模型名无限累积路由状态。
        if (cursors.size() > 4096) {
            cursors.clear();
        }
        long cursor = cursors.getOrDefault(key, 0L);
        Director director = eligible.get((int) Math.floorMod(cursor, (long) eligible.size()));
        cursors.put(key, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return director;
    }

    /**
     * 为检测请求选择端点。控制类请求必须回到会话创建时的端点；
     * 其余请求按可用的会话数或图像容量在候选端点间轮转。
     *
     * @param input 检测输入
     * @return 目标端点
     * @throws SpecializedDetectionException 会话不存在、端点失效或容量不足时抛出
     */
    private Director selectDetection(SpecializedDetectionInput input) {
        if (input.isControl()) {
            Owner owner = detectionStreams.get(input.streamId);
            if (owner == null) {
                throw SpecializedDetectionException.unit(3);
            }
            if (!owner.live()) {
                throw SpecializedDetectionException.unit(30);
            }
            return owner.director;
        }
        List<Director> eligible = new ArrayList<>();
        boolean registered = false;
        for (Director director : candidates()) {
            JSONObject caps = states.get(director).snapshot.json.optJSONObject("specializedDetection");
            if (caps == null || caps.optInt("version") != 1) {
                continue;
            }
            JSONArray providers = caps.optJSONArray("providers");
            if (providers == null) {
                continue;
            }
            boolean available = false;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject provider = providers.getJSONObject(i);
                registered = true;
                available |= provider.optInt("start".equals(input.operation) ? "availableSessions" : "availableImages") > 0;
            }
            if (available) {
                eligible.add(director);
            }
        }
        if (eligible.isEmpty()) {
            throw SpecializedDetectionException.unit(registered ? 7 : 30);
        }
        // Profile 字符串会原样转发给 Python，不按调用方传入的取值保留游标。
        String key = "specializedDetection:" + input.operation;
        long cursor = cursors.getOrDefault(key, 0L);
        cursors.put(key, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return eligible.get((int) Math.floorMod(cursor, (long) eligible.size()));
    }

    /**
     * 为插件模块动作选择端点。
     *
     * <p>候选端点需同时满足：上报的模块路由版本为 1、模块清单中包含该动作，
     * 以及指定了模块单元时单元清单中包含该单元。符合条件的端点按路由键轮转。</p>
     *
     * @param request 请求方言，从参数中读取模块名与模块单元
     * @param action  动作名
     * @return 目标端点
     * @throws CapabilityUnavailableException 无端点承载该模块动作时抛出
     */
    private Director selectModule(ActionDialect request, String action) {
        String module = request.getParamAsString("moduleName");
        String unit = request.containsParam("moduleUnit") ? request.getParamAsString("moduleUnit") : "";
        if (module == null || module.isEmpty()) {
            throw unavailable("Missing module name");
        }
        List<Director> eligible = new ArrayList<>();
        for (Director director : candidates()) {
            JSONObject snapshot = states.get(director).snapshot.json;
            if (snapshot.optInt("moduleRoutingVersion", 0) != 1) {
                continue;
            }
            JSONArray modules = snapshot.optJSONArray("modules");
            boolean ownsAction = false;
            if (modules != null) {
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject entry = modules.getJSONObject(i);
                    if (!module.equals(entry.optString("name"))) {
                        continue;
                    }
                    JSONArray actions = entry.optJSONArray("actions");
                    if (actions != null) {
                        for (int j = 0; j < actions.length(); j++) {
                            if (action.equals(actions.getString(j))) {
                                ownsAction = true;
                            }
                        }
                    }
                }
            }
            boolean hasUnit = unit == null || unit.isEmpty();
            JSONArray units = states.get(director).snapshot.units;
            if (!hasUnit) {
                for (int i = 0; i < units.length(); i++) {
                    if (unit.equals(units.getJSONObject(i).getJSONObject("capability").getString("name"))) {
                        hasUnit = true;
                    }
                }
            }
            if (ownsAction && hasUnit) {
                eligible.add(director);
            }
        }
        if (eligible.isEmpty()) {
            throw unavailable("Module action unavailable: " + module + "/" + action);
        }
        String key = "module:" + module + ":" + action + ":" + unit;
        if (cursors.size() > 4096) {
            cursors.clear();
        }
        long cursor = cursors.getOrDefault(key, 0L);
        cursors.put(key, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return eligible.get((int) Math.floorMod(cursor, (long) eligible.size()));
    }

    /**
     * 生成完成请求的路由键。
     *
     * <p>键中包含模型、流式标志、是否使用工具、是否含图像以及显式参数集合，
     * 使能力需求不同的请求各自独立轮转，避免把不支持相应能力的端点轮进候选。</p>
     *
     * @param request 结构化完成请求
     * @return 路由键
     */
    private String completionKey(JSONObject request) {
        boolean tools = request.optJSONArray("tools") != null && request.getJSONArray("tools").length() > 0;
        boolean images = false;
        JSONArray messages = request.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            tools |= message.has("tool_calls") || "tool".equals(message.optString("role"));
            JSONArray content = message.optJSONArray("content");
            if (content != null) {
                for (int j = 0; j < content.length(); j++) {
                    images |= "image_url".equals(content.getJSONObject(j).optString("type"));
                }
            }
        }
        Set<String> parameters = new TreeSet<>();
        JSONObject options = request.getJSONObject("options");
        for (String key : options.keySet()) {
            if (!options.isNull(key)) {
                parameters.add(key);
            }
        }
        return "model:" + request.getString("model") + ":stream=" + request.optBoolean("stream")
                + ":tools=" + tools + ":images=" + images + ":parameters=" + parameters;
    }

    /**
     * 判断端点是否满足请求的能力要求。
     *
     * <p>语音识别按子任务匹配；普通对话按单元名称匹配；结构化完成请求先按模型找到其所属单元，
     * 再逐单元校验能力约束。</p>
     *
     * @param director 端点
     * @param unitName 单元名称，仅在普通对话时参与匹配
     * @param request  结构化完成请求；为 {@code null} 表示按单元名匹配
     * @param asr      是否为语音识别请求
     * @return 端点可承载该请求时返回 true
     */
    private boolean matches(Director director, String unitName, JSONObject request, boolean asr) {
        if (!valid(director, System.currentTimeMillis())) {
            return false;
        }
        Snapshot snapshot = states.get(director).snapshot;
        Set<String> names = new HashSet<>();
        if (request != null) {
            for (int i = 0; i < snapshot.models.length(); i++) {
                JSONObject model = snapshot.models.getJSONObject(i);
                if (model.getString("id").equals(request.getString("model"))) {
                    names.add(model.getString("unit"));
                }
            }
        }
        for (int i = 0; i < snapshot.units.length(); i++) {
            AICapability capability = new AICapability(snapshot.units.getJSONObject(i).getJSONObject("capability"));
            if (asr && capability.containsSubtask(AICapability.AudioProcessing.AutomaticSpeechRecognition)) {
                return true;
            }
            if (!asr && request == null && unitName.equalsIgnoreCase(capability.getName())) {
                return true;
            }
            if (request != null && names.contains(capability.getName())) {
                try {
                    CompletionRequest.checkCapabilities(request, capability.getChatCompletion());
                    return true;
                } catch (CompletionError unsupported) {
                    // 某个单元不满足约束不影响其他单元的判定。
                }
            }
        }
        return false;
    }

    /**
     * 登记已发出的请求。
     *
     * <p>发送前复核端点归属，防止选路之后连接或能力发生变化。申请通道的请求会被单独跟踪，
     * 因为通道建立后需要维持更长的存活时间；完成请求按请求标识登记，用于取消与事件归并；
     * 检测请求除终止类操作外按序号登记，用于响应到达时还原路由归属。</p>
     *
     * @param director 目标端点
     * @param source   发送方的会话句柄
     * @param request  已发出的请求
     * @throws CapabilityUnavailableException 端点归属或能力在发送前发生变化时抛出
     * @throws SpecializedDetectionException  检测请求的会话或容量不满足时抛出
     */
    public synchronized void sent(Director director, Speakable source, ActionDialect request) {
        if (AIGCAction.SpecializedDetection.name.equals(request.getName())) {
            Packet packet = new Packet(request);
            SpecializedDetectionInput input = new SpecializedDetectionInput(packet.data.getJSONObject("input"));
            State state = states.get(director);
            if (state == null || state.source != source) {
                throw SpecializedDetectionException.unit(30);
            }
            if (input.isControl()) {
                Owner owner = detectionStreams.get(input.streamId);
                if (owner == null) {
                    throw SpecializedDetectionException.unit(3);
                }
                if (!owner.live() || owner.director != director || owner.source != source) {
                    throw SpecializedDetectionException.unit(30);
                }
            } else {
                if (!valid(director, System.currentTimeMillis())) {
                    throw SpecializedDetectionException.unit(30);
                }
                JSONObject caps = state.snapshot.json.optJSONObject("specializedDetection");
                if (caps == null || caps.optInt("version") != 1) {
                    throw SpecializedDetectionException.unit(30);
                }
            }
            if (!"detect".equals(input.operation)) {
                if (detectionPending.size() >= 10000 || (!input.isControl() && detectionStreams.size() >= 10000)) {
                    throw SpecializedDetectionException.unit(7);
                }
                detectionPending.put(packet.sn, new DetectionPending(new Owner(director, state, packet.sn), input));
            }
            return;
        }
        boolean channel = AIGCAction.RequestChannel.name.equals(request.getName());
        boolean completion = AIGCAction.ChatCompletion.name.equals(request.getName());
        boolean asr = AIGCAction.AutomaticSpeechRecognition.name.equals(request.getName());
        boolean chat = AIGCAction.Chat.name.equals(request.getName());
        boolean channelControl = AIGCAction.GetChannelInfo.name.equals(request.getName())
                || AIGCAction.KeepAliveChannel.name.equals(request.getName()) || AIGCAction.StopChannel.name.equals(request.getName());
        if (!channel && !completion && !asr && !chat && !channelControl) {
            return;
        }
        Packet packet = new Packet(request);
        String unit = packet.data.optString("unit", ModelConfig.BAIZE_UNIT);
        if (chat || channelControl) {
            Owner owner = channels.get(packet.data.optString("channel", packet.data.optString("code", "")));
            boolean plainChat = chat && Consts.PATTERN_CHAT.equalsIgnoreCase(packet.data.optString("pattern", Consts.PATTERN_CHAT));
            if (owner == null || !owner.live() || owner.director != director || owner.source != source
                    || (plainChat && !matches(director, unit, null, false))) {
                throw unavailable("Channel owner or unit changed before sending");
            }
            return;
        }
        State state = states.get(director);
        if (state == null || state.source != source || !matches(director, unit,
                completion ? packet.data.getJSONObject("request") : null, asr)) {
            throw unavailable("Service capability changed before sending");
        }
        if (!channel && !completion) {
            return;
        }
        if (channel) {
            if (channels.size() + pendingChannels.size() >= 10000) {
                throw unavailable("Channel routing limit reached");
            }
            pendingChannels.put(packet.sn, new PendingChannel(new Owner(director, state, packet.sn)));
        } else {
            String id = packet.data.getString("requestId");
            if (completions.containsKey(id)) {
                throw unavailable("Duplicate Completion request ID");
            }
            if (completions.size() >= 10000) {
                throw unavailable("Completion routing limit reached");
            }
            completions.put(id, new Owner(director, state, packet.sn));
        }
    }

    /**
     * 登记请求失败。
     *
     * <p>按序列号清理待完成记录，避免失败请求长期占用等待表。启动类检测请求的失败
     * 保留在等待表中，由响应路径或超时统一回收。</p>
     *
     * @param request 失败的请求
     */
    public synchronized void failed(ActionDialect request) {
        long sn = new Packet(request).sn;
        DetectionPending detection = detectionPending.get(sn);
        if (detection != null && !"start".equals(detection.input.operation)) {
            detectionPending.remove(sn);
        }
        pendingChannels.remove(sn);
        if (AIGCAction.ChatCompletion.name.equals(request.getName())) {
            String id = new Packet(request).data.getString("requestId");
            Owner owner = completions.get(id);
            if (owner != null && owner.sn == sn) {
                completions.remove(id);
            }
        }
    }

    /**
     * 处理端点响应。
     *
     * <p>检测响应先按序号还原归属，再按操作类型登记或结束会话；完成事件需要按订阅归并后
     * 才移除记录；通道申请响应成功后把待完成记录转为正式通道。其余响应忽略。</p>
     *
     * @param source   产生响应的会话句柄
     * @param response 响应方言
     */
    public synchronized void response(Speakable source, ActionDialect response) {
        if (AIGCAction.SpecializedDetection.name.equals(response.getName())) {
            Packet packet = new Packet(response);
            DetectionPending pending = detectionPending.get(packet.sn);
            if (pending == null || pending.owner.source != source || !pending.owner.live()) {
                return;
            }
            detectionPending.remove(packet.sn);
            if (Packet.extractCode(packet) == 0) {
                JSONObject payload = Packet.extractDataPayload(packet).optJSONObject("resultPayload");
                if (payload != null && !payload.optString("streamId").isEmpty()) {
                    String id = payload.getString("streamId");
                    if ("start".equals(pending.input.operation)) {
                        detectionStreams.putIfAbsent(id, pending.owner);
                    } else if (!id.equals(pending.input.streamId)) {
                        return;
                    }
                    Owner owner = detectionStreams.get(id);
                    if (owner != null && owner.source == source) {
                        owner.detectionTerminal |= "stopped".equals(payload.optString("status")) || "failed".equals(payload.optString("status"));
                    }
                }
            } else if (Packet.extractCode(packet) == 3 && pending.input.isControl()) {
                Owner owner = detectionStreams.get(pending.input.streamId);
                if (owner != null && owner.source == source) {
                    owner.detectionTerminal = true;
                }
            }
            trimDetectionRoutes(pending.owner);
            return;
        }
        if (AIGCAction.ChatCompletionEvent.name.equals(response.getName())) {
            JSONObject event = new Packet(response).data;
            if ("completed".equals(event.optString("kind")) || "error".equals(event.optString("kind"))) {
                Owner owner = completions.get(event.optString("requestId"));
                if (owner != null && owner.source == source) {
                    completions.remove(event.optString("requestId"));
                }
            }
        }
        if (!AIGCAction.RequestChannel.name.equals(response.getName())) {
            return;
        }
        Packet packet = new Packet(response);
        PendingChannel pending = pendingChannels.get(packet.sn);
        if (pending == null || pending.owner.source != source) {
            return;
        }
        pendingChannels.remove(packet.sn);
        if (Packet.extractCode(packet) == 0 && pending.owner.live()) {
            channels.put(Packet.extractDataPayload(packet).getString("code"), pending.owner);
        }
    }

    /**
     * 裁剪已终止的检测会话路由，每个 Python 提供方最多保留 20 条。
     *
     * @param provider 提供方端点
     */
    private void trimDetectionRoutes(Owner provider) {
        // 一个服务可能承载多个 Python 提供方，每个提供方各保留二十条终止会话。
        JSONObject caps = provider.state.snapshot.json.optJSONObject("specializedDetection");
        JSONArray nodes = caps == null ? null : caps.optJSONArray("providers");
        int limit = 20 * Math.max(1, nodes == null ? 1 : nodes.length());
        int terminals = 0;
        for (Owner owner : detectionStreams.values()) {
            if (owner.state == provider.state && owner.detectionTerminal) {
                terminals++;
            }
        }
        Iterator<Owner> iterator = detectionStreams.values().iterator();
        while (terminals > limit && iterator.hasNext()) {
            Owner owner = iterator.next();
            if (owner.state == provider.state && owner.detectionTerminal) {
                iterator.remove();
                terminals--;
            }
        }
    }

    /**
     * 标记请求已结束。
     *
     * @param sn 请求序列号
     */
    public synchronized void finished(long sn) {
        pendingChannels.remove(sn);
    }

    /**
     * 移除完成订阅。
     *
     * <p>订阅方主动取消或超时后调用，后续同名事件会被丢弃。</p>
     *
     * @param id 订阅标识
     */
    public synchronized void forgetCompletion(String id) {
        completions.remove(id);
    }

    /**
     * 构造能力不可用异常。
     *
     * <p>同时输出一条告警日志，便于定位选路失败的具体原因。</p>
     *
     * @param message 失败原因
     * @return 能力不可用异常
     */
    private CapabilityUnavailableException unavailable(String message) {
        Logger.w(getClass(), message);
        return new CapabilityUnavailableException(message);
    }

    /**
     * 请求的端点归属。
     *
     * <p>记录请求发出时的端点、状态、会话与实例标识，用于在后续请求或响应到达时
     * 判断归属是否仍然有效。</p>
     */
    private final class Owner {
        /**
         * 检测会话是否已进入终止状态。
         */
        boolean detectionTerminal;
        /**
         * 所属端点。
         */
        final Director director;
        /**
         * 发出请求时的端点状态。
         */
        final State state;
        /**
         * 端点对应的会话句柄。
         */
        final Speakable source;
        /**
         * 发出请求时的服务实例标识。
         */
        final String instanceId;
        /**
         * 请求序列号。
         */
        final long sn;
        /**
         * 最后一次被使用的时间戳，单位毫秒。
         */
        long touched = System.currentTimeMillis();

        /**
         * 构造方法。
         *
         * @param director 所属端点
         * @param state    发出请求时的端点状态
         * @param sn       请求序列号
         */
        Owner(Director director, State state, long sn) {
            this.director = director;
            this.state = state;
            this.source = state.source;
            this.instanceId = state.snapshot.instanceId;
            this.sn = sn;
        }

        /**
         * 判断归属是否仍然有效。
         *
         * <p>要求端点状态未被替换、连接未断开、会话就绪且服务实例未变化。</p>
         *
         * @return 仍然有效时返回 true
         */
        boolean live() {
            return states.get(director) == state && !state.disconnected && director.speaker == source
                    && source.isReady() && state.snapshot != null && instanceId.equals(state.snapshot.instanceId);
        }
    }

    /**
     * 已发出但尚未响应的通道申请。
     */
    private static final class PendingChannel {
        /**
         * 申请方的端点归属。
         */
        final Owner owner;

        /**
         * 构造方法。
         *
         * @param owner 申请方的端点归属
         */
        PendingChannel(Owner owner) {
            this.owner = owner;
        }
    }

    /**
     * 已发出的检测请求记录，用于响应到达时还原路由归属。
     */
    private final class DetectionPending {
        /**
         * 发起请求的端点归属。
         */
        final Owner owner;
        /**
         * 请求的检测输入。
         */
        final SpecializedDetectionInput input;

        /**
         * 构造方法。
         *
         * @param owner 发起请求的端点归属
         * @param input 请求的检测输入
         */
        DetectionPending(Owner owner, SpecializedDetectionInput input) {
            this.owner = owner;
            this.input = input;
        }
    }

    /**
     * 单个端点的路由状态。
     */
    private static final class State {
        /**
         * 端点对应的会话句柄。
         */
        final Speakable source;
        /**
         * 最近一次成功查询得到的能力快照。
         */
        Snapshot snapshot;
        /**
         * 快照接收时间与下次查询时间，单位毫秒。
         */
        long received, nextQuery;
        /**
         * 查询在途、已告警失败、已断开。
         */
        boolean inflight, failed, disconnected;
        /**
         * 上次快照的原文，用于判断能力是否发生变化。
         */
        String description;

        /**
         * 构造方法。
         *
         * @param source 端点对应的会话句柄
         */
        State(Speakable source) {
            this.source = source;
        }
    }

    /**
     * 端点上报的能力快照。
     *
     * <p>持有原始 JSON 的独立副本，构造时即校验版本、实例标识与单元、模型的必要字段，
     * 后续选路直接读取副本，不再访问端点。</p>
     */
    private static final class Snapshot {
        /**
         * 原始快照数据。
         */
        final JSONObject json;
        /**
         * 服务实例标识。
         */
        final String instanceId;
        /**
         * 服务是否已就绪。
         */
        final boolean ready;
        /**
         * 单元清单与模型清单。
         */
        final JSONArray units, models;

        /**
         * 构造方法。
         *
         * @param json 端点返回的快照数据
         * @throws IllegalArgumentException 快照版本不支持或缺少实例标识时抛出
         */
        Snapshot(JSONObject json) {
            this.json = new JSONObject(json.toString());
            if (json.getInt("version") != 1) {
                throw new IllegalArgumentException("Unsupported snapshot version");
            }
            instanceId = json.getString("instanceId");
            if (instanceId.isEmpty()) {
                throw new IllegalArgumentException("Missing service instance");
            }
            ready = json.getBoolean("ready");
            units = this.json.getJSONArray("units");
            models = this.json.getJSONArray("models");
            for (int i = 0; i < units.length(); i++) {
                new AICapability(units.getJSONObject(i).getJSONObject("capability"));
            }
            for (int i = 0; i < models.length(); i++) {
                models.getJSONObject(i).getString("id");
                models.getJSONObject(i).getString("unit");
            }
        }
    }
}
