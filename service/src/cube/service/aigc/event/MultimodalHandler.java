/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.event;

import cell.util.log.Logger;
import cube.aigc.Usage;
import cube.service.aigc.AIGCService;
import cube.service.aigc.unit.MultimodalUnitMeta;
import cube.service.aigc.unit.UnitMeta;
import org.json.JSONObject;

/**
 * 多模态事件处理器。
 *
 * <p>接收分段事件并驱动多模态单元完成一次推理，结果通过单元回写。</p>
 */
public class MultimodalHandler implements EventListener {

    private final AIGCService service;

    public MultimodalHandler(AIGCService service) {
        this.service = service;
    }

    /**
     * 处理事件。
     *
     * <p>只处理分段事件；找不到对应单元或处理失败时记录日志，不影响其他监听器。</p>
     *
     * @param center 事件中心。
     * @param event  事件对象。
     */
    @Override
    public void onEvent(EventCenter center, Event event) {
        try {
            if (Events.StreamStarted.equalsIgnoreCase(event.name)) {
                // Nothing
            }
            else if (Events.StreamStopped.equalsIgnoreCase(event.name)) {
                JSONObject payload = event.payload;
                String id = payload.getString("streamId");
                // 解除流占用，保留路由供最终状态查询和重复停止使用。
                center.markStreamStopped(id);
            }
            else if (Events.Segment.equalsIgnoreCase(event.name)) {
                // 分段内容
                JSONObject payload = event.payload;
                String id = payload.getString("streamId");
                UnitMeta unitMeta = center.getUnitMeta(id);
                if (unitMeta instanceof MultimodalUnitMeta) {
                    MultimodalUnitMeta multimodalUnitMeta = (MultimodalUnitMeta) unitMeta;
                    Usage usage = new Usage(payload.getJSONObject("performance"));
                    Logger.d(MultimodalUnitMeta.class, "Update token usage: " +
                            usage.inputTokens + "/" + usage.outputTokens);
                    this.service.getStorage().updateUsage(multimodalUnitMeta.getChannel().getAuthToken().getContactId(),
                            multimodalUnitMeta.unit.getCapability().getName(),
                            usage.outputTokens,
                            usage.inputTokens);
                }
            }
        } catch (Exception e) {
            Logger.e(this.getClass(), "", e);
        }
    }
}
