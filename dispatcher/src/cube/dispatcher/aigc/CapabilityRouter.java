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
import cube.dispatcher.Director;
import cube.dispatcher.Performer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/** Cached service discovery. User requests never perform metadata I/O. */
public final class CapabilityRouter {
    private static final long REFRESH = 30000, RETRY = 10000, TTL = 90000, CHANNEL_TTL = 30 * 60 * 1000;
    private final Performer performer;
    private final Map<Director, State> states = new HashMap<>();
    private final Map<String, Long> cursors = new HashMap<>();
    private final Map<String, Owner> channels = new HashMap<>();
    private final Map<Long, PendingChannel> pendingChannels = new HashMap<>();
    private final Map<String, Owner> completions = new HashMap<>();
    private ExecutorService queries;

    public CapabilityRouter(Performer performer) { this.performer = performer; }

    public synchronized void start() {
        if (queries != null) return;
        queries = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
            Thread t = new Thread(r, "aigc-capability-query");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void stop() {
        if (queries != null) queries.shutdownNow();
        queries = null;
        states.clear(); cursors.clear(); channels.clear(); pendingChannels.clear(); completions.clear();
    }

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

    public synchronized void disconnected(Speakable source) {
        for (State state : states.values()) if (state.source == source) {
            state.snapshot = null;
            state.disconnected = true;
        }
        channels.entrySet().removeIf(e -> e.getValue().source == source);
        completions.entrySet().removeIf(e -> e.getValue().source == source);
        pendingChannels.entrySet().removeIf(e -> e.getValue().owner.source == source);
    }

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
    }

    private void schedule(Director director, State state, long now) {
        if (queries == null || state.inflight || state.disconnected || now < state.nextQuery) return;
        state.inflight = true;
        state.nextQuery = now + RETRY;
        try {
            queries.execute(() -> refresh(director, state));
        } catch (RejectedExecutionException e) { state.inflight = false; }
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

    /** Returns null for ordinary control actions; capability failures are explicit exceptions. */
    public synchronized Director select(ActionDialect dialect) {
        String action = dialect.getName();
        JSONObject data = new Packet(dialect).data;
        if (AIGCAction.GetCapabilitySnapshot.name.equals(action)) throw unavailable("Internal discovery action");
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
            try { CompletionRequest.validate(request); }
            catch (org.json.JSONException e) { throw CompletionError.invalid("Invalid structured request", null); }
        }
        boolean plainChat = chat && Consts.PATTERN_CHAT.equalsIgnoreCase(data.optString("pattern", Consts.PATTERN_CHAT));
        String key = asr ? "subtask:AutomaticSpeechRecognition" : completion ? completionKey(request)
                : "unit:" + unit.toLowerCase(Locale.ROOT);
        Owner owner = channels.get(channel);
        if (chat || channelControl) {
            if (owner == null || !owner.live()) throw unavailable("Channel owner unavailable; request a new channel");
            if (plainChat && !matches(owner.director, unit, null, false)) throw unavailable("Channel unit unavailable: " + unit);
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

    public synchronized void sent(Director director, Speakable source, ActionDialect request) {
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

    public synchronized void failed(ActionDialect request) {
        long sn = new Packet(request).sn;
        pendingChannels.remove(sn);
        if (AIGCAction.ChatCompletion.name.equals(request.getName())) {
            String id = new Packet(request).data.getString("requestId");
            Owner owner = completions.get(id);
            if (owner != null && owner.sn == sn) completions.remove(id);
        }
    }

    public synchronized void response(Speakable source, ActionDialect response) {
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

    public synchronized void finished(long sn) { pendingChannels.remove(sn); }

    public synchronized void forgetCompletion(String id) { completions.remove(id); }

    private CapabilityUnavailableException unavailable(String message) {
        Logger.w(getClass(), message);
        return new CapabilityUnavailableException(message);
    }

    private final class Owner {
        final Director director;
        final State state;
        final Speakable source;
        final String instanceId;
        final long sn;
        long touched = System.currentTimeMillis();
        Owner(Director director, State state, long sn) {
            this.director = director; this.state = state; this.source = state.source;
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
        PendingChannel(Owner owner) { this.owner = owner; }
    }

    private static final class State {
        final Speakable source;
        Snapshot snapshot;
        long received, nextQuery;
        boolean inflight, failed, disconnected;
        String description;
        State(Speakable source) { this.source = source; }
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
            units = this.json.getJSONArray("units"); models = this.json.getJSONArray("models");
            for (int i = 0; i < units.length(); i++) new AICapability(units.getJSONObject(i).getJSONObject("capability"));
            for (int i = 0; i < models.length(); i++) {
                models.getJSONObject(i).getString("id"); models.getJSONObject(i).getString("unit");
            }
        }
    }
}
