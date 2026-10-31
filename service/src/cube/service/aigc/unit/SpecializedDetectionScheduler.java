/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.unit;

import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.SpecializedDetectionDefaults;
import cube.aigc.SpecializedDetectionException;
import cube.auth.AuthToken;
import cube.common.Packet;
import cube.common.entity.AIGCUnit;
import cube.common.entity.SpecializedDetectionInput;
import cube.common.entity.SpecializedDetectionOutput;
import cube.service.aigc.AIGCService;
import cube.service.aigc.event.Event;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 专用检测的准入调度器。预算按已注册的提供方连接划分，最终裁决权仍在 Python 侧。
 */
public final class SpecializedDetectionScheduler {
    /**
     * 宿主 AIGC 服务。
     */
    private final AIGCService service;
    /**
     * 已注册的检测提供方，按注册顺序保存。
     */
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    /**
     * 活跃的检测会话，按创建顺序保存。
     */
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    /**
     * 已发出但尚未收到响应的请求，键为请求序号。
     */
    private final Map<Long, SpecializedDetectionUnitMeta> pending = new HashMap<>();
    /**
     * 先于响应到达的停止事件，用于判定会话立即终止。
     */
    private final LinkedHashMap<String, Provider> earlyStops = new LinkedHashMap<>();
    /**
     * 当前生效的配置。
     */
    private volatile Properties config = new Properties();
    /**
     * 协调定时器。
     */
    private ScheduledExecutorService timer;
    /**
     * 是否已关闭。
     */
    private boolean closed;
    /**
     * 轮转游标，用于等负载候选者的确定性轮转。
     */
    private long cursor;

    /**
     * 检测提供方，对应一个已注册的 Python 单元连接。
     */
    static final class Provider {
        /**
         * 单元实例。
         */
        final AIGCUnit unit;
        /**
         * 与单元通信的上下文。
         */
        final TalkContext context;
        /**
         * 正在启动的会话数。
         */
        int starting;
        /**
         * 正在处理的图像请求数。
         */
        int images;

        /**
         * 构造方法。
         *
         * @param unit 单元实例
         */
        Provider(AIGCUnit unit) {
            this.unit = unit;
            this.context = unit.getContext();
        }
    }

    /**
     * 检测会话，对应一路后台视频流。
     */
    static final class Session {
        /**
         * 会话标识，即流标识。
         */
        final String id;
        /**
         * 承载该会话的提供方。
         */
        final Provider provider;
        /**
         * 创建该会话的原始请求。
         */
        final SpecializedDetectionUnitMeta origin;
        /**
         * 最近一次会话快照。
         */
        JSONObject snapshot;
        /**
         * 会话是否已终止。
         */
        boolean terminal;
        /**
         * 是否正在轮询。
         */
        boolean polling;
        /**
         * 是否为无主会话，即原始请求已超时。
         */
        boolean orphan;
        /**
         * 最近一次轮询的时间戳。
         */
        long lastPoll;

        /**
         * 构造方法。
         *
         * @param id       会话标识
         * @param provider 承载该会话的提供方
         * @param origin   创建该会话的原始请求
         * @param snapshot 会话快照
         */
        Session(String id, Provider provider, SpecializedDetectionUnitMeta origin, JSONObject snapshot) {
            this.id = id;
            this.provider = provider;
            this.origin = origin;
            this.snapshot = snapshot;
        }
    }

    /**
     * 准入预留，记录一次请求占用的提供方容量。
     */
    public static final class Reservation {
        /**
         * 提供方。
         */
        final Provider provider;
        /**
         * 关联的会话，图像检测请求为 null。
         */
        final Session session;
        /**
         * 请求的操作。
         */
        final String operation;
        /**
         * 是否已释放预留。
         */
        boolean released;

        /**
         * 构造方法。
         *
         * @param provider  提供方
         * @param session   关联的会话，可为 null
         * @param operation 请求的操作
         */
        Reservation(Provider provider, Session session, String operation) {
            this.provider = provider;
            this.session = session;
            this.operation = operation;
        }

        /**
         * 获取承载本次请求的单元。
         *
         * @return 单元实例
         */
        public AIGCUnit unit() {
            return provider.unit;
        }

        /**
         * 获取与单元通信的上下文。
         *
         * @return 通信上下文
         */
        public TalkContext context() {
            return provider.context;
        }
    }

    /**
     * 构造方法。
     *
     * @param service 宿主 AIGC 服务
     */
    public SpecializedDetectionScheduler(AIGCService service) {
        this.service = service;
    }

    /**
     * 校验并应用配置。校验通过后才发布新配置，避免热加载失败时污染当前配置。
     *
     * @param properties 配置项
     * @throws IllegalArgumentException 当配置项越界时抛出
     */
    public void configure(Properties properties) {
        Properties copy = new Properties();
        copy.putAll(properties);
        // 发布热加载配置之前先校验默认值。
        positive(copy, "specialized.detection.max.sessions", SpecializedDetectionDefaults.MAX_SESSIONS, 64);
        positive(copy, "specialized.detection.data.timeout.ms", SpecializedDetectionDefaults.DATA_TIMEOUT_MS, 3600000);
        positive(copy, "specialized.detection.control.timeout.ms", SpecializedDetectionDefaults.CONTROL_TIMEOUT_MS, 3600000);
        config = copy;
    }

    /**
     * 读取并校验正整数配置项。
     *
     * @param p        配置项集合
     * @param key      配置键
     * @param fallback 缺省值
     * @param max      允许的最大值
     * @return 校验通过的配置值
     * @throws IllegalArgumentException 当配置值不在 [1, max] 区间内时抛出
     */
    private static int positive(Properties p, String key, int fallback, int max) {
        int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
        if (value < 1 || value > max) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return value;
    }

    /**
     * 按输入类型返回本次请求的超时时间。
     *
     * @param input 检测输入
     * @return 超时时间，单位毫秒
     */
    public long timeout(SpecializedDetectionInput input) {
        return positive(config, "specialized.detection." + (input.isControl() ? "control" : "data") + ".timeout.ms",
                input.isControl() ? SpecializedDetectionDefaults.CONTROL_TIMEOUT_MS : SpecializedDetectionDefaults.DATA_TIMEOUT_MS, 3600000);
    }

    /**
     * 读取提供方维度的配置项，未配置时回退到全局配置，再回退到缺省值。
     *
     * @param provider 提供方
     * @param key      配置键
     * @param fallback 缺省值
     * @return 配置值
     */
    private String setting(Provider provider, String key, String fallback) {
        String prefix = "specialized.detection.node." + provider.unit.getContact().getDomain().getName()
                + "." + provider.unit.getContact().getId() + ".";
        return config.getProperty(prefix + key, config.getProperty("specialized.detection." + key, fallback));
    }

    /**
     * 获取提供方的最大会话数，取值限定在 [1, 64]。
     *
     * @param provider 提供方
     * @return 最大会话数
     */
    private int max(Provider provider) {
        try {
            int n = Integer.parseInt(setting(provider, "max.sessions", Integer.toString(SpecializedDetectionDefaults.MAX_SESSIONS)));
            return Math.max(1, Math.min(64, n));
        } catch (NumberFormatException error) {
            return SpecializedDetectionDefaults.MAX_SESSIONS;
        }
    }

    /**
     * 判断两个通信上下文是否指向同一个会话。
     *
     * @param a 上下文 a
     * @param b 上下文 b
     * @return 指向同一会话时返回 true
     */
    static boolean same(TalkContext a, TalkContext b) {
        return a == b || (a != null && b != null && a.getSession() != null && a.getSession() == b.getSession());
    }

    /**
     * 判断提供方是否仍然有效，即单元仍在位且连接可用。
     *
     * @param p     提供方
     * @param units 当前全部单元
     * @return 有效返回 true
     */
    private boolean live(Provider p, List<AIGCUnit> units) {
        return units.stream().anyMatch(unit -> unit == p.unit) && p.context != null && p.context.isValid()
                && same(p.context, p.unit.getContext());
    }

    /**
     * 清理已失效的提供方，并登记新出现的检测单元。
     */
    private void clean() {
        List<AIGCUnit> units = service.getAllUnits();
        Set<Provider> removed = new HashSet<>();
        Iterator<Provider> iterator = providers.values().iterator();
        while (iterator.hasNext()) {
            Provider p = iterator.next();
            if (!live(p, units)) {
                removed.add(p);
                iterator.remove();
            }
        }
        sessions.values().removeIf(s -> removed.contains(s.provider));
        earlyStops.values().removeIf(removed::contains);
        pending.values().removeIf(meta -> removed.contains(meta.reservation.provider));
        for (AIGCUnit unit : units) {
            if (SpecializedDetectionInput.UNIT.equals(unit.getCapability().getName())
                    && unit.getContext() != null && unit.getContext().isValid()) {
                providers.computeIfAbsent(unit.getQueryKey(), key -> new Provider(unit));
            }
        }
    }

    /**
     * 统计提供方上未终止的会话数。
     *
     * @param p 提供方
     * @return 活跃会话数
     */
    private int active(Provider p) {
        int count = 0;
        for (Session s : sessions.values()) {
            if (s.provider == p && !s.terminal) {
                count++;
            }
        }
        return count;
    }

    /**
     * 启动协调定时器，已启动或已关闭时直接返回。
     */
    private synchronized void startTimer() {
        if (timer != null || closed) {
            return;
        }
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, job -> {
            Thread t = new Thread(job, "specialized-detection-reconcile");
            t.setDaemon(true);
            return t;
        });
        scheduled.setRemoveOnCancelPolicy(true);
        timer = scheduled;
        timer.scheduleWithFixedDelay(() -> {
            try {
                reconcile();
            } catch (RuntimeException error) {
                cell.util.log.Logger.w(getClass(), "Detection reconciliation failed", error);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 为请求注册超时监控。
     *
     * @param meta  请求元数据
     * @param delay 延迟时间，单位毫秒
     * @return 可取消的定时任务
     * @throws SpecializedDetectionException 调度器已关闭时抛出
     */
    public synchronized ScheduledFuture<?> watch(SpecializedDetectionUnitMeta meta, long delay) {
        startTimer();
        if (closed) {
            throw SpecializedDetectionException.unit(30);
        }
        return timer.schedule(meta::expire, Math.max(0, delay), TimeUnit.MILLISECONDS);
    }

    /**
     * 为请求预留提供方容量。
     *
     * @param input 检测输入
     * @param token 访问令牌
     * @return 准入预留
     * @throws SpecializedDetectionException 调度器已关闭、会话不存在、越权或容量不足时抛出
     */
    public synchronized Reservation reserve(SpecializedDetectionInput input, AuthToken token) {
        if (closed) {
            throw SpecializedDetectionException.unit(30);
        }
        clean();
        startTimer();
        if (input.isControl()) {
            Session s = sessions.get(input.streamId);
            if (s == null) {
                throw SpecializedDetectionException.unit(3);
            }
            if (!s.origin.usageContext.domain.equals(token.getDomain()) || s.origin.usageContext.contactId != token.getContactId()) {
                throw SpecializedDetectionException.unit(25);
            }
            return new Reservation(s.provider, s, input.operation);
        }
        List<Provider> candidates = new ArrayList<>();
        for (Provider p : providers.values()) {
            if ("start".equals(input.operation) ? active(p) + p.starting < max(p) : p.images == 0) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            throw SpecializedDetectionException.unit(providers.isEmpty() ? 30 : 7);
        }
        // 排序前先轮转，使等负载的候选者获得确定性的轮询次序。
        Collections.rotate(candidates, -(int) Math.floorMod(cursor++, (long) candidates.size()));
        candidates.sort(Comparator.comparingInt(p -> active(p) + p.starting + p.images));
        Provider p = candidates.get(0);
        if ("start".equals(input.operation)) {
            p.starting++;
        } else {
            p.images++;
        }
        return new Reservation(p, null, input.operation);
    }

    /**
     * 判断预留是否仍然有效。
     *
     * @param r 准入预留
     * @return 有效返回 true
     */
    public synchronized boolean valid(Reservation r) {
        clean();
        return !closed && providers.get(r.provider.unit.getQueryKey()) == r.provider;
    }

    /**
     * 登记已发出的请求，等待响应关联。
     *
     * @param meta 请求元数据
     * @throws SpecializedDetectionException 预留已失效时抛出
     */
    public synchronized void sent(SpecializedDetectionUnitMeta meta) {
        if (!valid(meta.reservation)) {
            throw SpecializedDetectionException.unit(30);
        }
        pending.put(meta.sn, meta);
    }

    /**
     * 处理单元响应。在移除同步响应者之前调用，迟到的响应同样适用。
     *
     * @param context 通信上下文
     * @param dialect 响应动作
     */
    public void onResponse(TalkContext context, ActionDialect dialect) {
        if (!"specializedDetection".equals(dialect.getName())) {
            return;
        }
        Packet packet;
        try {
            packet = new Packet(dialect);
        } catch (RuntimeException error) {
            return;
        }
        SpecializedDetectionUnitMeta meta;
        synchronized (this) {
            meta = pending.get(packet.sn);
        }
        if (meta != null && same(context, meta.reservation.context()) && valid(meta.reservation)) {
            meta.captureResponse(packet);
        }
    }

    /**
     * 释放预留占用的容量。重复释放不会产生副作用。
     *
     * @param r 准入预留
     */
    public synchronized void release(Reservation r) {
        if (r.released) {
            return;
        }
        r.released = true;
        if ("start".equals(r.operation)) {
            r.provider.starting = Math.max(0, r.provider.starting - 1);
        } else if ("detect".equals(r.operation)) {
            r.provider.images = Math.max(0, r.provider.images - 1);
        }
    }

    /**
     * 撤销尚未发出的请求，并释放其预留。
     *
     * @param meta 请求元数据
     */
    public synchronized void unsent(SpecializedDetectionUnitMeta meta) {
        pending.remove(meta.sn);
        release(meta.reservation);
    }

    /**
     * 控制请求超时后从等待表中移除。控制超时不会产生未知会话，后续证据由协调器补齐。
     *
     * @param meta 请求元数据
     */
    public synchronized void forgetControl(SpecializedDetectionUnitMeta meta) {
        if (meta.input.isControl()) {
            pending.remove(meta.sn);
        }
    }

    /**
     * 应用一次完全关联的响应。启动结果不确定时保留预留，避免容量被错误回收。
     *
     * @param meta    请求元数据
     * @param code    单元返回的状态码
     * @param payload 响应载荷
     */
    public synchronized void completed(SpecializedDetectionUnitMeta meta, int code, JSONObject payload) {
        Reservation r = meta.reservation;
        if (!valid(r)) {
            pending.remove(meta.sn);
            return;
        }
        if ("start".equals(r.operation) && code == 0) {
            String id = payload.getString("streamId");
            Session old = sessions.get(id);
            if (old != null && old.origin != meta) {
                throw new IllegalArgumentException("Duplicate stream identity");
            }
            Session s = old == null ? new Session(id, r.provider, meta, new JSONObject(payload.toString())) : old;
            s.orphan = meta.isTimedOut();
            s.terminal = terminal(payload) || earlyStops.get(id) == r.provider;
            earlyStops.remove(id);
            sessions.put(id, s);
            release(r);
            trim(r.provider);
        } else if (r.session != null) {
            if (code == 0) {
                update(r.session, payload);
            } else if (code == 3) {
                r.session.terminal = true;
            }
            trim(r.provider);
        } else if ("detect".equals(r.operation) || Arrays.asList(1, 3, 7, 30, 32).contains(code)) {
            release(r);
        }
        // 内部启动失败无法证明没有创建后台会话，因此保留会话与预留。
        if (!"start".equals(r.operation) || r.released) {
            pending.remove(meta.sn);
        }
    }

    /**
     * 判断会话快照是否已处于终止状态。
     *
     * @param payload 会话快照
     * @return 已停止或已失败返回 true
     */
    private static boolean terminal(JSONObject payload) {
        return "stopped".equals(payload.optString("status")) || "failed".equals(payload.optString("status"));
    }

    /**
     * 用最新的会话快照更新会话状态。
     *
     * @param s       会话
     * @param payload 会话快照
     * @throws IllegalArgumentException 当快照中的流标识与会话不一致时抛出
     */
    private void update(Session s, JSONObject payload) {
        if (!s.id.equals(payload.getString("streamId"))) {
            throw new IllegalArgumentException("Stream identity changed");
        }
        if (!s.terminal) {
            s.snapshot = new JSONObject(payload.toString());
        }
        s.terminal |= terminal(payload);
    }

    /**
     * 裁剪提供方上已终止的会话，每个提供方最多保留 20 条。
     *
     * @param p 提供方
     */
    private void trim(Provider p) {
        int count = 0;
        for (Session s : sessions.values()) {
            if (s.provider == p && s.terminal) {
                count++;
            }
        }
        Iterator<Session> iterator = sessions.values().iterator();
        while (count > 20 && iterator.hasNext()) {
            Session s = iterator.next();
            if (s.provider == p && s.terminal) {
                iterator.remove();
                count--;
            }
        }
    }

    /**
     * 处理单元推送的生命周期事件，用于提前标记会话终止。
     *
     * @param context 通信上下文
     * @param event   事件
     */
    public synchronized void event(TalkContext context, Event event) {
        if (!SpecializedDetectionInput.UNIT.equals(event.source) || !SpecializedDetectionInput.UNIT.equals(event.payload.optString("unit"))
                || !("streamStarted".equals(event.name) || "streamStopped".equals(event.name))) {
            return;
        }
        clean();
        String id = event.payload.optString("streamId", "");
        if (id.isEmpty()) {
            return;
        }
        Session s = sessions.get(id);
        if (s != null) {
            if (!same(context, s.provider.context)) {
                return;
            }
            if ("streamStopped".equals(event.name)) {
                s.terminal = true;
                trim(s.provider);
            }
        } else if ("streamStopped".equals(event.name)) {
            for (Provider p : providers.values()) {
                if (same(context, p.context) && p.starting > 0) {
                    earlyStops.put(id, p);
                    while (earlyStops.size() > 1024) {
                        earlyStops.remove(earlyStops.keySet().iterator().next());
                    }
                    break;
                }
            }
        }
    }

    /**
     * 输出当前容量视图，供能力快照上报使用。
     *
     * @return 容量快照 JSON
     */
    public synchronized JSONObject snapshot() {
        clean();
        JSONArray nodes = new JSONArray();
        for (Provider p : providers.values()) {
            nodes.put(new JSONObject().put("maxSessions", max(p)).put("availableSessions", Math.max(0, max(p) - active(p) - p.starting))
                    .put("activeSessions", active(p)).put("pendingStarts", p.starting).put("availableImages", p.images == 0 ? 1 : 0));
        }
        return new JSONObject().put("version", 1).put("readiness", "unknown").put("budgetSource", "java-config")
                .put("providers", nodes);
    }

    /**
     * 协调一轮状态：清理失效提供方、补投迟到响应，并对到期的会话发起状态轮询。
     */
    public void reconcile() {
        List<SpecializedDetectionUnitMeta> late;
        List<Session> poll = new ArrayList<>();
        synchronized (this) {
            if (closed) {
                return;
            }
            clean();
            late = new ArrayList<>(pending.values());
            long now = System.currentTimeMillis();
            for (Session s : sessions.values()) {
                if (!s.terminal && !s.polling && now - s.lastPoll >= 5000) {
                    s.polling = true;
                    s.lastPoll = now;
                    poll.add(s);
                }
            }
        }
        for (SpecializedDetectionUnitMeta meta : late) {
            meta.drainLateResponse();
        }
        for (Session s : poll) {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    positive(config, "specialized.detection.control.timeout.ms", SpecializedDetectionDefaults.CONTROL_TIMEOUT_MS, 3600000));
            if (!service.submitDetectionControl(() -> poll(s, deadline))) {
                synchronized (this) {
                    s.polling = false;
                }
            }
        }
    }

    /**
     * 轮询单个会话的状态。无主会话直接下发停止指令，避免遗留后台任务。
     *
     * @param s        会话
     * @param deadline 本次轮询的截止时刻，单位纳秒
     */
    private void poll(Session s, long deadline) {
        try {
            synchronized (this) {
                clean();
                if (closed || sessions.get(s.id) != s || s.terminal) {
                    return;
                }
            }
            JSONObject option = new JSONObject().put("operation", s.orphan ? "stop" : "status").put("streamId", s.id);
            Packet request = new Packet("specializedDetection", new JSONObject().put("unit", SpecializedDetectionInput.UNIT)
                    .put("files", new JSONArray()).put("option", option));
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return;
            }
            ActionDialect dialect = service.getCellet().transmit(s.provider.context, request.toDialect(), remaining, request.sn);
            if (dialect == null) {
                return;
            }
            Packet reply = new Packet(dialect);
            if (!request.sn.equals(reply.sn) || !request.name.equals(reply.name)) {
                return;
            }
            synchronized (this) {
                if (!live(s.provider, service.getAllUnits()) || sessions.get(s.id) != s) {
                    return;
                }
                int code = Packet.extractCode(reply);
                if (code == 3) {
                    s.terminal = true;
                } else if (code == 0) {
                    JSONObject payload = Packet.extractDataPayload(reply);
                    new SpecializedDetectionOutput(request.sn, payload);
                    update(s, payload.getJSONObject("resultPayload"));
                }
                trim(s.provider);
            }
        } catch (RuntimeException error) {
            cell.util.log.Logger.w(getClass(), "Detection status reconciliation failed");
        } finally {
            synchronized (this) {
                s.polling = false;
            }
        }
    }

    /**
     * 关闭调度器并清空全部状态。
     */
    public synchronized void close() {
        closed = true;
        if (timer != null) {
            timer.shutdownNow();
        }
        sessions.clear();
        providers.clear();
        pending.clear();
        earlyStops.clear();
    }
}
