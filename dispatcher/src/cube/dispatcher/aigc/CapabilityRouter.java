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
import cube.aigc.completion.CompletionError;
import cube.aigc.completion.CompletionRequest;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AICapability;
import cube.common.entity.SpecializedDetectionInput;
import cube.aigc.SpecializedDetectionException;
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/**
 * Cached service discovery. User requests never perform metadata I/O.
 */
public final class CapabilityRouter {

    private static final long REFRESH = 30000, RETRY = 10000, TTL = 90000, CHANNEL_TTL = 30 * 60 * 1000;

    private final Performer performer;

    private final Map<Director, State> states = new HashMap<>();

    private final Map<String, Long> cursors = new HashMap<>();

    private final Map<String, Owner> channels = new HashMap<>();

    private final Map<Long, PendingChannel> pendingChannels = new HashMap<>();

    private final Map<String, Owner> completions = new HashMap<>();
    private final Map<String, Owner> detectionStreams = new LinkedHashMap<>();
    private final Map<Long, DetectionPending> detectionPending = new HashMap<>();

    private ExecutorService queries;

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
        if (queries != null) return;
        queries = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
            Thread t = new Thread(r, "aigc-capability-query");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 停止路由器并清空全部状态。
     *
     * <p>线程池立即中断，已排队的查询被丢弃；连接、通道、待完成请求的映射一并清理。
     * 允许重复调用。</p>
     */
    public synchronized void stop() {
        if (queries != null) queries.shutdownNow();
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
     * 路由器尚未启动时直接返回，此时的能力快照由启动扫描补齐。</p>
     *
     * @param director 端点。
     * @param source   与该端点通信的会话句柄。
     */
    public synchronized void connected(Director director, Speakable source) {
        if (queries == null) return; // Startup scan covers contacts before initialization.
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
     * @param source 断开的会话句柄。
     */
    public synchronized void disconnected(Speakable source) {
        for (State state : states.values())
            if (state.source == source) {
                state.snapshot = null;
                state.disconnected = true;
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
     * 路由器未启动时直接返回。</p>
     *
     * @param now 当前时间戳，单位毫秒。
     */
    public synchronized void onTick(long now) {
        if (queries == null) return;
        for (Director director : performer.getDirectorsForCellet(AIGCCellet.NAME)) {
            Speakable source = director.speaker;
            if (source == null || !source.isReady()) continue;
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

    private void schedule(Director director, State state, long now) {
        if (queries == null || state.inflight || state.disconnected || now < state.nextQuery) return;
        state.inflight = true;
        state.nextQuery = now + RETRY;
        try {
            queries.execute(() -> refresh(director, state));
        } catch (RejectedExecutionException e) {
            state.inflight = false;
        }
    }

    private void refresh(Director director, State state) {
        synchronized (this) {
            if (states.get(director) != state || state.disconnected || director.speaker != state.source) return;
        }
        Snapshot snapshot = null;
        try {
            ActionDialect response = performer.syncTransmit(director, AIGCCellet.NAME,
                    new Packet(AIGCAction.GetCapabilitySnapshot.name, new JSONObject()).toDialect(), 3000);
            if (response != null) {
                Packet packet = new Packet(response);
                if (Packet.extractCode(packet) == 0) snapshot = new Snapshot(Packet.extractDataPayload(packet));
            }
        } catch (RuntimeException e) { /* Retry in the background; never query on a user request. */ }
        synchronized (this) {
            if (states.get(director) != state || state.disconnected || director.speaker != state.source) return;
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
     * @return 候选端点列表；无可用端点时为空列表。
     */
    public synchronized List<Director> candidates() {
        List<Director> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Director director : performer.getDirectorsForCellet(AIGCCellet.NAME))
            if (valid(director, now)) result.add(director);
        return result;
    }

    private boolean valid(Director director, long now) {
        State state = states.get(director);
        return state != null && !state.disconnected && director.speaker == state.source && state.source.isReady()
                && state.snapshot != null && state.snapshot.ready && now - state.received < TTL;
    }

    /**
     * Returns null for ordinary control actions; capability failures are explicit exceptions.
     */
    public synchronized Director select(ActionDialect dialect) {
        String action = dialect.getName();
        JSONObject data = new Packet(dialect).data;
        if (AIGCAction.SpecializedDetection.name.equals(action)) return selectDetection(new SpecializedDetectionInput(data.getJSONObject("input")));
        if (AIGCAction.GetCapabilitySnapshot.name.equals(action)) throw unavailable("Internal discovery action");
        if (dialect.containsParam("moduleName")) return selectModule(dialect, action);
        String channel = data.optString("channel", data.optString("code", ""));
        boolean chat = AIGCAction.Chat.name.equals(action);
        boolean requestChannel = AIGCAction.RequestChannel.name.equals(action);
        boolean asr = AIGCAction.AutomaticSpeechRecognition.name.equals(action);
        boolean completion = AIGCAction.ChatCompletion.name.equals(action);
        boolean channelControl = AIGCAction.GetChannelInfo.name.equals(action)
                || AIGCAction.KeepAliveChannel.name.equals(action) || AIGCAction.StopChannel.name.equals(action);
        if (AIGCAction.CancelChatCompletion.name.equals(action)) {
            Owner owner = completions.get(data.optString("requestId"));
            if (owner == null || !owner.live()) throw unavailable("Completion owner unavailable");
            return owner.director;
        }
        if (!requestChannel && !asr && !completion && !chat && !channelControl) return null;
        String unit = data.optString("unit", ModelConfig.BAIZE_UNIT);
        JSONObject request = completion ? data.optJSONObject("request") : null;
        if (completion) {
            if (request == null) throw CompletionError.invalid("request must be an object", "request");
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
            if (owner == null || !owner.live()) throw unavailable("Channel owner unavailable; request a new channel");
            if (plainChat && !matches(owner.director, unit, null, false))
                throw unavailable("Channel unit unavailable: " + unit);
            owner.touched = System.currentTimeMillis();
            return owner.director;
        }
        List<Director> eligible = new ArrayList<>();
        for (Director director : candidates()) if (matches(director, unit, request, asr)) eligible.add(director);
        if (eligible.isEmpty()) throw unavailable("No service capability for " + key);
        // A bounded key set prevents arbitrary model names from accumulating routing state.
        if (cursors.size() > 4096) cursors.clear();
        long cursor = cursors.getOrDefault(key, 0L);
        Director director = eligible.get((int) Math.floorMod(cursor, (long) eligible.size()));
        cursors.put(key, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return director;
    }

    private Director selectDetection(SpecializedDetectionInput input) {
        if (input.isControl()) {
            Owner owner = detectionStreams.get(input.streamId);
            if (owner == null) throw SpecializedDetectionException.unit(3);
            if (!owner.live()) throw SpecializedDetectionException.unit(30);
            return owner.director;
        }
        List<Director> eligible = new ArrayList<>();
        boolean supported = false;
        for (Director director : candidates()) {
            JSONObject caps = states.get(director).snapshot.json.optJSONObject("specializedDetection");
            if (caps == null || caps.optInt("version") != 1) continue;
            JSONArray providers = caps.optJSONArray("providers");
            if (providers == null) continue;
            boolean available = false;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject provider = providers.getJSONObject(i);
                JSONArray profiles = provider.optJSONArray("profiles");
                boolean matches = false;
                if (profiles != null) for (int j = 0; j < profiles.length(); j++) matches |= input.profile.equals(profiles.getString(j));
                if (!matches) continue;
                supported = true;
                available |= provider.optInt("start".equals(input.operation) ? "availableSessions" : "availableImages") > 0;
            }
            if (available) eligible.add(director);
        }
        if (eligible.isEmpty()) throw SpecializedDetectionException.unit(supported ? 7 : 30);
        String key = "specializedDetection:" + input.operation + ":" + input.profile;
        long cursor = cursors.getOrDefault(key, 0L);
        cursors.put(key, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return eligible.get((int) Math.floorMod(cursor, (long) eligible.size()));
    }

    private Director selectModule(ActionDialect request, String action) {
        String module = request.getParamAsString("moduleName");
        String unit = request.containsParam("moduleUnit") ? request.getParamAsString("moduleUnit") : "";
        if (module == null || module.isEmpty()) throw unavailable("Missing module name");
        List<Director> eligible = new ArrayList<>();
        for (Director director : candidates()) {
            JSONObject snapshot = states.get(director).snapshot.json;
            if (snapshot.optInt("moduleRoutingVersion", 0) != 1) continue;
            JSONArray modules = snapshot.optJSONArray("modules");
            boolean ownsAction = false;
            if (modules != null) for (int i = 0; i < modules.length(); i++) {
                JSONObject entry = modules.getJSONObject(i);
                if (!module.equals(entry.optString("name"))) continue;
                JSONArray actions = entry.optJSONArray("actions");
                if (actions != null) for (int j = 0; j < actions.length(); j++)
                    if (action.equals(actions.getString(j))) ownsAction = true;
            }
            boolean hasUnit = unit == null || unit.isEmpty();
            JSONArray units = states.get(director).snapshot.units;
            if (!hasUnit) for (int i = 0; i < units.length(); i++)
                if (unit.equals(units.getJSONObject(i).getJSONObject("capability").getString("name"))) hasUnit = true;
            if (ownsAction && hasUnit) eligible.add(director);
        }
        if (eligible.isEmpty()) throw unavailable("Module action unavailable: " + module + "/" + action);
        String key = "module:" + module + ":" + action + ":" + unit;
        if (cursors.size() > 4096) cursors.clear();
        long cursor = cursors.getOrDefault(key, 0L);
        cursors.put(key, cursor == Long.MAX_VALUE ? 0 : cursor + 1);
        return eligible.get((int) Math.floorMod(cursor, (long) eligible.size()));
    }

    private String completionKey(JSONObject request) {
        boolean tools = request.optJSONArray("tools") != null && request.getJSONArray("tools").length() > 0;
        boolean images = false;
        JSONArray messages = request.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            tools |= message.has("tool_calls") || "tool".equals(message.optString("role"));
            JSONArray content = message.optJSONArray("content");
            if (content != null) for (int j = 0; j < content.length(); j++)
                images |= "image_url".equals(content.getJSONObject(j).optString("type"));
        }
        Set<String> parameters = new TreeSet<>();
        JSONObject options = request.getJSONObject("options");
        for (String key : options.keySet()) if (!options.isNull(key)) parameters.add(key);
        return "model:" + request.getString("model") + ":stream=" + request.optBoolean("stream")
                + ":tools=" + tools + ":images=" + images + ":parameters=" + parameters;
    }

    private boolean matches(Director director, String unitName, JSONObject request, boolean asr) {
        if (!valid(director, System.currentTimeMillis())) return false;
        Snapshot snapshot = states.get(director).snapshot;
        Set<String> names = new HashSet<>();
        if (request != null) for (int i = 0; i < snapshot.models.length(); i++) {
            JSONObject model = snapshot.models.getJSONObject(i);
            if (model.getString("id").equals(request.getString("model"))) names.add(model.getString("unit"));
        }
        for (int i = 0; i < snapshot.units.length(); i++) {
            AICapability capability = new AICapability(snapshot.units.getJSONObject(i).getJSONObject("capability"));
            if (asr && capability.containsSubtask(AICapability.AudioProcessing.AutomaticSpeechRecognition)) return true;
            if (!asr && request == null && unitName.equalsIgnoreCase(capability.getName())) return true;
            if (request != null && names.contains(capability.getName())) try {
                CompletionRequest.checkCapabilities(request, capability.getChatCompletion());
                return true;
            } catch (CompletionError unsupported) { /* Check each unit independently. */ }
        }
        return false;
    }

    /**
     * 登记已发出的请求。
     *
     * <p>申请通道的请求会被单独跟踪，因为通道建立后需要维持更长的存活时间。</p>
     *
     * @param director 目标端点。
     * @param source   发送方的会话句柄。
     * @param request  已发出的请求。
     */
    public synchronized void sent(Director director, Speakable source, ActionDialect request) {
        if (AIGCAction.SpecializedDetection.name.equals(request.getName())) {
            Packet packet = new Packet(request);
            SpecializedDetectionInput input = new SpecializedDetectionInput(packet.data.getJSONObject("input"));
            State state = states.get(director);
            if (state == null || state.source != source) throw SpecializedDetectionException.unit(30);
            if (input.isControl()) {
                Owner owner = detectionStreams.get(input.streamId);
                if (owner == null) throw SpecializedDetectionException.unit(3);
                if (!owner.live() || owner.director != director || owner.source != source) throw SpecializedDetectionException.unit(30);
            } else {
                if (!valid(director, System.currentTimeMillis())) throw SpecializedDetectionException.unit(30);
                JSONObject caps = state.snapshot.json.optJSONObject("specializedDetection");
                if (caps == null || caps.optInt("version") != 1) throw SpecializedDetectionException.unit(30);
            }
            if (!"detect".equals(input.operation)) {
                if (detectionPending.size() >= 10000 || (!input.isControl() && detectionStreams.size() >= 10000))
                    throw SpecializedDetectionException.unit(7);
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
        if (!channel && !completion && !asr && !chat && !channelControl) return;
        Packet packet = new Packet(request);
        String unit = packet.data.optString("unit", ModelConfig.BAIZE_UNIT);
        if (chat || channelControl) {
            Owner owner = channels.get(packet.data.optString("channel", packet.data.optString("code", "")));
            boolean plainChat = chat && Consts.PATTERN_CHAT.equalsIgnoreCase(packet.data.optString("pattern", Consts.PATTERN_CHAT));
            if (owner == null || !owner.live() || owner.director != director || owner.source != source
                    || (plainChat && !matches(director, unit, null, false)))
                throw unavailable("Channel owner or unit changed before sending");
            return;
        }
        State state = states.get(director);
        if (state == null || state.source != source || !matches(director, unit,
                completion ? packet.data.getJSONObject("request") : null, asr))
            throw unavailable("Service capability changed before sending");
        if (!channel && !completion) return;
        if (channel) {
            if (channels.size() + pendingChannels.size() >= 10000) throw unavailable("Channel routing limit reached");
            pendingChannels.put(packet.sn, new PendingChannel(new Owner(director, state, packet.sn)));
        } else {
            String id = packet.data.getString("requestId");
            if (completions.containsKey(id)) throw unavailable("Duplicate Completion request ID");
            if (completions.size() >= 10000) throw unavailable("Completion routing limit reached");
            completions.put(id, new Owner(director, state, packet.sn));
        }
    }

    /**
     * 登记请求失败。
     *
     * <p>按序列号清理待完成记录，避免失败请求长期占用等待表。</p>
     *
     * @param request 失败的请求。
     */
    public synchronized void failed(ActionDialect request) {
        long sn = new Packet(request).sn;
        DetectionPending detection = detectionPending.get(sn);
        if (detection != null && !"start".equals(detection.input.operation)) detectionPending.remove(sn);
        pendingChannels.remove(sn);
        if (AIGCAction.ChatCompletion.name.equals(request.getName())) {
            String id = new Packet(request).data.getString("requestId");
            Owner owner = completions.get(id);
            if (owner != null && owner.sn == sn) completions.remove(id);
        }
    }

    /**
     * 处理端点响应。
     *
     * <p>完成事件需要按订阅归并后再回调，其余响应按序列号直接结束等待。</p>
     *
     * @param source   产生响应的会话句柄。
     * @param response 响应方言。
     */
    public synchronized void response(Speakable source, ActionDialect response) {
        if (AIGCAction.SpecializedDetection.name.equals(response.getName())) {
            Packet packet = new Packet(response);
            DetectionPending pending = detectionPending.get(packet.sn);
            if (pending == null || pending.owner.source != source || !pending.owner.live()) return;
            detectionPending.remove(packet.sn);
            if (Packet.extractCode(packet) == 0) {
                JSONObject payload = Packet.extractDataPayload(packet).optJSONObject("resultPayload");
                if (payload != null && !payload.optString("streamId").isEmpty()) {
                    String id = payload.getString("streamId");
                    if ("start".equals(pending.input.operation)) detectionStreams.putIfAbsent(id, pending.owner);
                    else if (!id.equals(pending.input.streamId)) return;
                    Owner owner = detectionStreams.get(id);
                    if (owner != null && owner.source == source) {
                        owner.detectionTerminal |= "stopped".equals(payload.optString("status")) || "failed".equals(payload.optString("status"));
                    }
                }
            } else if (Packet.extractCode(packet) == 3 && pending.input.isControl()) {
                Owner owner = detectionStreams.get(pending.input.streamId);
                if (owner != null && owner.source == source) owner.detectionTerminal = true;
            }
            trimDetectionRoutes(pending.owner);
            return;
        }
        if (AIGCAction.ChatCompletionEvent.name.equals(response.getName())) {
            JSONObject event = new Packet(response).data;
            if ("completed".equals(event.optString("kind")) || "error".equals(event.optString("kind"))) {
                Owner owner = completions.get(event.optString("requestId"));
                if (owner != null && owner.source == source) completions.remove(event.optString("requestId"));
            }
        }
        if (!AIGCAction.RequestChannel.name.equals(response.getName())) return;
        Packet packet = new Packet(response);
        PendingChannel pending = pendingChannels.get(packet.sn);
        if (pending == null || pending.owner.source != source) return;
        pendingChannels.remove(packet.sn);
        if (Packet.extractCode(packet) == 0 && pending.owner.live()) {
            channels.put(Packet.extractDataPayload(packet).getString("code"), pending.owner);
        }
    }

    private void trimDetectionRoutes(Owner provider) {
        // A service can own several Python providers, each retaining twenty terminal sessions.
        JSONObject caps = provider.state.snapshot.json.optJSONObject("specializedDetection");
        JSONArray nodes = caps == null ? null : caps.optJSONArray("providers");
        int limit = 20 * Math.max(1, nodes == null ? 1 : nodes.length());
        int terminals = 0;
        for (Owner owner : detectionStreams.values()) if (owner.state == provider.state && owner.detectionTerminal) terminals++;
        Iterator<Owner> iterator = detectionStreams.values().iterator();
        while (terminals > limit && iterator.hasNext()) {
            Owner owner = iterator.next();
            if (owner.state == provider.state && owner.detectionTerminal) { iterator.remove(); terminals--; }
        }
    }

    /**
     * 标记请求已结束。
     *
     * @param sn 请求序列号。
     */
    public synchronized void finished(long sn) {
        pendingChannels.remove(sn);
    }

    /**
     * 移除完成订阅。
     *
     * <p>订阅方主动取消或超时后调用，后续同名事件会被丢弃。</p>
     *
     * @param id 订阅标识。
     */
    public synchronized void forgetCompletion(String id) {
        completions.remove(id);
    }

    private CapabilityUnavailableException unavailable(String message) {
        Logger.w(getClass(), message);
        return new CapabilityUnavailableException(message);
    }

    private final class Owner {
        boolean detectionTerminal;
        final Director director;
        final State state;
        final Speakable source;
        final String instanceId;
        final long sn;
        long touched = System.currentTimeMillis();

        Owner(Director director, State state, long sn) {
            this.director = director;
            this.state = state;
            this.source = state.source;
            this.instanceId = state.snapshot.instanceId;
            this.sn = sn;
        }

        boolean live() {
            return states.get(director) == state && !state.disconnected && director.speaker == source
                    && source.isReady() && state.snapshot != null && instanceId.equals(state.snapshot.instanceId);
        }
    }

    private static final class PendingChannel {
        final Owner owner;

        PendingChannel(Owner owner) {
            this.owner = owner;
        }
    }

    private final class DetectionPending {
        final Owner owner;
        final SpecializedDetectionInput input;
        DetectionPending(Owner owner, SpecializedDetectionInput input) { this.owner = owner; this.input = input; }
    }

    private static final class State {
        final Speakable source;
        Snapshot snapshot;
        long received, nextQuery;
        boolean inflight, failed, disconnected;
        String description;

        State(Speakable source) {
            this.source = source;
        }
    }

    private static final class Snapshot {
        final JSONObject json;
        final String instanceId;
        final boolean ready;
        final JSONArray units, models;

        Snapshot(JSONObject json) {
            this.json = new JSONObject(json.toString());
            if (json.getInt("version") != 1) throw new IllegalArgumentException("Unsupported snapshot version");
            instanceId = json.getString("instanceId");
            if (instanceId.isEmpty()) throw new IllegalArgumentException("Missing service instance");
            ready = json.getBoolean("ready");
            units = this.json.getJSONArray("units");
            models = this.json.getJSONArray("models");
            for (int i = 0; i < units.length(); i++)
                new AICapability(units.getJSONObject(i).getJSONObject("capability"));
            for (int i = 0; i < models.length(); i++) {
                models.getJSONObject(i).getString("id");
                models.getJSONObject(i).getString("unit");
            }
        }
    }
}
