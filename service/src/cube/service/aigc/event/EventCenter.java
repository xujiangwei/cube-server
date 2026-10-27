/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.event;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.common.entity.AIGCUnit;
import cube.service.aigc.AIGCService;
import cube.service.aigc.unit.MultimodalUnitMeta;
import cube.service.aigc.unit.UnitMeta;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件中心。
 *
 * <p>维护单元元数据表与事件监听器表：前者用于按单元 ID 找到对应的处理实现，
 * 后者把上游推送的事件分发给订阅方。以单例存在，随服务启停。</p>
 */
public class EventCenter {

    private final static EventCenter sInstance = new EventCenter();

    private final Map<String, EventListener> listenerMap;

    private final Map<String, UnitMeta> unitMetaMap;

    // 保留数量与 Python Unit 一致，每个单元保留最近 20 个已结束流的路由。
    private static final int TERMINAL_STREAM_RETENTION = 20;

    // 空值用于记录早于启动应答到达的 streamStopped 事件。
    private final Map<String, UnitMeta> stoppedUnitMetaMap;

    private static final int PENDING_STOP_RETENTION = 1024;

    private EventCenter() {
        this.listenerMap = new ConcurrentHashMap<>();
        this.unitMetaMap = new ConcurrentHashMap<>();
        this.stoppedUnitMetaMap = new LinkedHashMap<>();
    }

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public static EventCenter getInstance() {
        return EventCenter.sInstance;
    }

    /**
     * 启动事件中心。
     *
     * <p>创建并注册多模态处理器，开始接收流事件。</p>
     *
     * @param service AIGC 服务实例。
     */
    public void start(AIGCService service) {
        MultimodalHandler multimodalHandler = new MultimodalHandler(service);
        this.addListener(Events.StreamStarted, multimodalHandler);
        this.addListener(Events.StreamStopped, multimodalHandler);
        this.addListener(Events.Segment, multimodalHandler);
    }

    /**
     * 停止事件中心。
     *
     * <p>移除已注册的监听器并标记停止；重复调用幂等。</p>
     */
    public synchronized void stop() {
        this.removeListener(Events.StreamStarted);
        this.removeListener(Events.StreamStopped);
        this.removeListener(Events.Segment);
        this.unitMetaMap.clear();
        this.stoppedUnitMetaMap.clear();
    }

    /**
     * 登记单元元数据。
     *
     * <p>已标记停止的单元不会被重新登记，避免残留状态复活。</p>
     *
     * @param id       单元标识。
     * @param unitMeta 单元元数据。
     */
    public synchronized void putUnitMeta(String id, UnitMeta unitMeta) {
        if (this.stoppedUnitMetaMap.containsKey(id)) {
            if (null == this.stoppedUnitMetaMap.get(id)) {
                this.stoppedUnitMetaMap.put(id, unitMeta);
                this.trimStoppedUnitMetas(unitMeta.unit);
            }
        }
        else {
            // 控制应答不能覆盖启动任务的频道和用量归属。
            this.unitMetaMap.putIfAbsent(id, unitMeta);
        }
    }

    /**
     * 移除单元元数据。
     *
     * @param id 单元标识。
     */
    public synchronized void removeUnitMeta(String id) {
        this.unitMetaMap.remove(id);
        this.stoppedUnitMetaMap.remove(id);
    }

    /**
     * 按单元实例移除元数据。
     *
     * <p>遍历匹配单元 ID ；单元不在表中时为空操作。</p>
     *
     * @param unit 单元实例。
     */
    public synchronized void removeUnitMeta(AIGCUnit unit) {
        for (Map.Entry<String, UnitMeta> item : this.unitMetaMap.entrySet()) {
            if (item.getValue().unit.getQueryKey().equalsIgnoreCase(unit.getQueryKey())) {
                this.unitMetaMap.remove(item.getKey());
            }
        }
        Iterator<UnitMeta> iter = this.stoppedUnitMetaMap.values().iterator();
        while (iter.hasNext()) {
            UnitMeta item = iter.next();
            if (null != item && item.unit.getQueryKey().equalsIgnoreCase(unit.getQueryKey())) {
                iter.remove();
            }
        }
    }

    /**
     * 获取单元元数据。
     *
     * @param id 单元标识。
     * @return 元数据；不存在返回 {@code null} 。
     */
    public synchronized UnitMeta getUnitMeta(String id) {
        UnitMeta meta = this.unitMetaMap.get(id);
        return (null != meta) ? meta : this.stoppedUnitMetaMap.get(id);
    }

    /**
     * 查找多模态单元元数据。
     *
     * <p>只返回类型为多模态的元数据，其余类型跳过。</p>
     *
     * @param unit 单元实例。
     * @return 多模态元数据；不存在返回 {@code null} 。
     */
    public synchronized MultimodalUnitMeta searchMultimodalUnitMeta(AIGCUnit unit) {
        for (UnitMeta item : this.unitMetaMap.values()) {
            if (item.unit.getQueryKey().equalsIgnoreCase(unit.getQueryKey())) {
                return (item instanceof MultimodalUnitMeta) ? (MultimodalUnitMeta) item : null;
            }
        }

        return null;
    }

    /**
     * 标记流已停止。
     *
     * <p>从活动表中移除并记入已停止集合，阻止后续事件再唤醒该单元。</p>
     *
     * @param id 单元标识。
     */
    public synchronized void markStreamStopped(String id) {
        UnitMeta meta = this.unitMetaMap.remove(id);
        if (!this.stoppedUnitMetaMap.containsKey(id)) {
            this.stoppedUnitMetaMap.put(id, meta);
        }
        if (null != meta) {
            this.trimStoppedUnitMetas(meta.unit);
        }
        else {
            // 尚未收到启动应答的停止标记也须有界。
            int pending = 0;
            for (UnitMeta item : this.stoppedUnitMetaMap.values()) {
                if (null == item) {
                    ++pending;
                }
            }
            Iterator<UnitMeta> iter = this.stoppedUnitMetaMap.values().iterator();
            while (pending > PENDING_STOP_RETENTION && iter.hasNext()) {
                if (null == iter.next()) {
                    iter.remove();
                    --pending;
                }
            }
        }
    }

    private void trimStoppedUnitMetas(AIGCUnit unit) {
        int count = 0;
        for (UnitMeta item : this.stoppedUnitMetaMap.values()) {
            if (null != item && item.unit.getQueryKey().equalsIgnoreCase(unit.getQueryKey())) {
                ++count;
            }
        }
        Iterator<UnitMeta> iter = this.stoppedUnitMetaMap.values().iterator();
        while (count > TERMINAL_STREAM_RETENTION && iter.hasNext()) {
            UnitMeta item = iter.next();
            if (null != item && item.unit.getQueryKey().equalsIgnoreCase(unit.getQueryKey())) {
                iter.remove();
                --count;
            }
        }
    }

    /**
     * 注册事件监听器。
     *
     * <p>同名监听器会被覆盖。</p>
     *
     * @param name     事件名称。
     * @param listener 监听器。
     */
    public void addListener(String name, EventListener listener) {
        this.listenerMap.put(name, listener);
    }

    /**
     * 移除事件监听器。
     *
     * @param name 事件名称。
     */
    public void removeListener(String name) {
        this.listenerMap.remove(name);
    }

    /**
     * 分发事件。
     *
     * <p>按事件名称找到监听器并回调；无监听器或解析失败时只记录日志，不向上抛出。</p>
     *
     * @param dialect 事件方言。
     */
    public void notifyEvent(ActionDialect dialect) {
        try {
            Event event = new Event(dialect.getParamAsJson("data"));
            EventListener listener = this.listenerMap.get(event.name);
            if (null != listener) {
                listener.onEvent(this, event);
            }
        } catch (Exception e) {
            Logger.e(this.getClass(), "#notifyEvent", e);
        }
    }
}
