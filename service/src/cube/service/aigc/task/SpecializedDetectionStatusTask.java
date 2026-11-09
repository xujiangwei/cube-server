/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.task;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 专用检测单元的状态上报任务。
 *
 * <p>接收已注册检测单元周期性上报的完整状态快照，转交
 * {@code SpecializedDetectionScheduler#report} 校验并更新就绪状态。该通道独立于单元注册
 * （setup）与插件状态（pluginStatus），因此调度器只接受<b>本连接已注册</b>、且序列号
 * 单调递增的上报。</p>
 *
 * <p>可靠性契约：每个请求<b>恰好应答一次</b>，且响应时间统计必然结束。服务未装载、
 * 载荷缺失或处理链路抛出运行时异常时，转为明确的状态码应答并记录日志，避免请求
 * 无人应答而悬挂。</p>
 */
public final class SpecializedDetectionStatusTask extends ServiceTask {

    /**
     * 是否已应答，保证正常路径与异常兜底路径不会对同一请求重复应答。
     */
    private final AtomicBoolean responded = new AtomicBoolean();

    /**
     * 构造状态上报任务。
     *
     * @param cellet    所属 AIGC Cellet。
     * @param context   上报方的通信上下文，用于校验连接归属。
     * @param primitive 原始请求原语。
     * @param time      响应时间统计，可为 {@code null}。
     */
    public SpecializedDetectionStatusTask(AIGCCellet cellet, TalkContext context, Primitive primitive, ResponseTime time) {
        super(cellet, context, primitive, time);
    }

    /**
     * 处理状态上报并回写应答。
     *
     * <p>状态码映射：上报被接受返回 {@code Ok}；载荷缺失或上报被调度器拒绝返回
     * {@code InvalidParameter}；服务未装载或处理链路异常返回 {@code Failure}。
     * 正常路径的应答数据与改动前一致。</p>
     *
     * <p>请求原语完全不可解析时无法构造可关联的应答，此时只记录日志并结束计时；
     * Cellet 派发前已对同一原语做过解析，该分支实际不可达。</p>
     */
    @Override
    public void run() {
        ActionDialect request = null;
        Packet packet = null;
        try {
            request = new ActionDialect(this.primitive);
            packet = new Packet(request);
            AIGCService service = ((AIGCCellet) this.cellet).getService();
            if (service == null) {
                Logger.w(getClass(), "#run - AIGC service is not loaded");
                this.reply(request, packet, AIGCStateCode.Failure.code);
                return;
            }
            if (packet.data == null) {
                this.reply(request, packet, AIGCStateCode.InvalidParameter.code);
                return;
            }
            boolean accepted = service.getSpecializedDetections().report(this.talkContext, packet.data);
            this.reply(request, packet, accepted ? AIGCStateCode.Ok.code : AIGCStateCode.InvalidParameter.code);
        } catch (RuntimeException failure) {
            Logger.w(getClass(), "#run - Detection status report failed", failure);
            if (request == null || packet == null) {
                return;
            }
            this.reply(request, packet, AIGCStateCode.Failure.code);
        } finally {
            this.markResponseTime();
        }
    }

    /**
     * 应答客户端，同一请求只生效一次。
     *
     * @param request 请求动作方言，用于保持 performer 元数据。
     * @param packet  请求包，用于保持 sn 与动作名一致。
     * @param code    应答状态码。
     */
    private void reply(ActionDialect request, Packet packet, int code) {
        if (this.responded.compareAndSet(false, true)) {
            this.cellet.speak(this.talkContext, this.makeResponse(request, packet, code, new JSONObject()));
        }
    }
}
