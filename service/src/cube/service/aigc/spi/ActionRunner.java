/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.spi;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.aigc.spi.AIGCHost;
import cube.aigc.spi.ActionBinding;
import cube.aigc.spi.ActionContext;
import cube.aigc.spi.ActionModule;
import cube.auth.AuthToken;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件动作任务骨架。
 *
 * <p>把 {@link ActionBinding} 中的无状态处理器包装成宿主线程池可执行的
 * {@link Runnable}，同时作为交给插件的 {@link ActionContext} 实现。
 * 本类是 SPI 与宿主之间的<b>唯一桥</b>。</p>
 *
 * <p>本类代办的样板（对应既有 109 个任务类中约 25 行重复代码）：
 * 构造方言与封包 → 解析令牌 → 校验令牌 → 异常兜底 → 应答封装 →
 * 应答计时。</p>
 *
 * <p>与既有任务类的一个刻意差异：应答计时以 <code>try/finally</code> 收口，
 * 而既有任务类要求每个 <code>return</code> 前手动调用
 * {@link #markResponseTime()} 且无finally 保护。本类不复刻该脆弱范式。</p>
 */
public final class ActionRunner extends ServiceTask implements ActionContext {

    private final AIGCHost host;

    private final ActionBinding binding;

    private final ActionModule module;

    private final Packet request;

    private final ActionDialect dialect;

    private final AtomicBoolean responded = new AtomicBoolean(false);

    private volatile boolean cancelled = false;
    private volatile boolean deferred = false;

    /**
     * 声明延迟响应。
     *
     * <p>调用后本次任务不会自动回写响应，由业务在异步流程结束时自行调用 {@code respond} 。</p>
     */
    @Override
    public void deferResponse() {
        this.deferred = true;
        this.deferMonitoring(this.binding.streaming);
    }

    private AuthToken authToken;

    /**
     * 构造函数。
     *
     * @param cellet       Cellet 实例。
     * @param talkContext  会话上下文。
     * @param primitive    原始数据。
     * @param responseTime 应答计时记录，由
     *                     <code>AIGCCellet#markResponseTime</code> 在派发前创建。
     * @param host         宿主能力接口，可为 <code>null</code>。
     * @param binding      动作绑定。
     * @param module       所属模块实例。
     */
    public ActionRunner(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime,
                        AIGCHost host, ActionBinding binding, ActionModule module) {
        super(cellet, talkContext, primitive, responseTime);
        this.host = host;
        this.binding = binding;
        this.module = module;
        this.dialect = new ActionDialect(primitive);
        this.request = new Packet(this.dialect);
    }

    @Override public void completeResponse(AIGCStateCode code) {
        this.finishMonitoring(code == AIGCStateCode.Ok ? "succeeded" : code == AIGCStateCode.Cancelled ? "cancelled" : "failed");
    }

    @Override public String monitoringModule() { return this.module.getName(); }

    /**
     * 执行插件动作。
     *
     * <p>解析动作与令牌后交给目标模块处理；未延迟响应时统一回写状态与数据。</p>
     */
    @Override
    public void run() {
        if (this.binding.streaming) this.monitoringStreaming();
        AIGCStateCode stateCode;

        try {
            if (this.binding.requiresToken) {
                String tokenCode = this.getTokenCode(this.dialect);
                if (null == tokenCode) {
                    this.respond(AIGCStateCode.InvalidParameter, new JSONObject());
                    return;
                }

                this.authToken = this.extractAuthToken(this.dialect);
                if (null == this.authToken) {
                    this.respond(AIGCStateCode.InconsistentToken, new JSONObject());
                    return;
                }
            }

            stateCode = this.binding.task.handle(this);
            if (null == stateCode) {
                stateCode = AIGCStateCode.Ok;
            }

            if (this.cancelled) {
                stateCode = AIGCStateCode.Cancelled;
            }

            // 处理器既未应答也未取消时，按返回码补一次空应答，避免调用方无限等待。
            // ⚠️ 流式动作不补：处理器交回控制权时首段可能仍在生成中，
            //    此时补一个空成功会让客户端把后续片段当成 unsolicited。
            //    流式动作的「必须应答」由处理器自己负责。
            if (!this.binding.streaming && !this.deferred && !this.isResponded()) {
                this.respondEmpty(stateCode);
            }
        } catch (Exception e) {
            this.finishMonitoring("failed");
            Logger.e(ActionRunner.class, "#run - Action \"" + this.binding.action
                    + "\" of module \"" + this.module.getName() + "\" failed", e);

            if (!this.isResponded()) {
                this.respondEmpty(AIGCStateCode.Failure);
            }
        } finally {
            // 与既有任务类的语义一致，但用 finally 收口，避免处理器异常导致计时记录缺少结束标记
            if (!this.deferred) { this.markResponseTime(); if (this.binding.streaming) this.monitoringResult("unknown"); }
        }
    }

    /**
     * 获取动作名称。
     *
     * @return 动作名。
     */
    @Override
    public String getAction() {
        return this.binding.action;
    }

    /**
     * 获取访问令牌。
     *
     * @return 令牌；未提供时为 {@code null} 。
     */
    @Override
    public AuthToken getToken() {
        return this.authToken;
    }

    /**
     * 获取请求参数。
     *
     * @return 参数对象；无参数时返回空对象。
     */
    @Override
    public JSONObject getParams() {
        return (null != this.request.data) ? this.request.data : new JSONObject();
    }

    /**
     * 获取原始请求包。
     *
     * @return 请求包。
     */
    @Override
    public Packet getRequest() {
        return this.request;
    }

    /**
     * 获取请求方言。
     *
     * @return 动作方言。
     */
    @Override
    public ActionDialect getDialect() {
        return this.dialect;
    }

    /**
     * 获取处理本动作的模块。
     *
     * @return 模块实例。
     */
    @Override
    public ActionModule getModule() {
        return this.module;
    }

    /**
     * 获取宿主接口。
     *
     * @return 宿主实例，供插件访问存储、单元与通道能力。
     */
    @Override
    public AIGCHost getHost() {
        return this.host;
    }

    /**
     * 回写响应。
     *
     * <p>流式动作把数据作为分片推送，非流式动作一次性返回；
     * 重复调用只有第一次生效。</p>
     *
     * @param code 状态码。
     * @param data 响应数据。
     */
    @Override
    public void respond(AIGCStateCode code, JSONObject data) {
        if (this.binding.streaming) {
            // 流式：记录「已应答过」但不据其拒绝后续片段。
            // CAS 仍然执行，使 isResponded() 如实反映「至少应答过一次」，
            // 供处理器自查；只是不再充当闸门。
            this.responded.compareAndSet(false, true);
        } else if (!this.responded.compareAndSet(false, true)) {
            Logger.w(ActionRunner.class, "#respond - Action \"" + this.binding.action
                    + "\" has already responded, ignore the duplicated one");
            return;
        }

        // makeResponse 内部会把请求上的 _performer 复制到应答，dispatcher 依此回送
        try {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(this.dialect, this.request, code.code, data));
        } finally {
            if (this.deferred) this.markResponseTime();
            if (this.deferred && !this.binding.streaming) this.finishMonitoring(code.code == 0 ? "succeeded" : code == AIGCStateCode.Cancelled ? "cancelled" : "failed");
        }
    }

    /**
     * 回写空响应。
     *
     * @param code 状态码。
     */
    @Override
    public void respondEmpty(AIGCStateCode code) {
        this.respond(code, new JSONObject());
    }

    /**
     * 判断是否已回写响应。
     *
     * @return 已回写返回 {@code true} 。
     */
    @Override
    public boolean isResponded() {
        return this.responded.get();
    }

    /**
     * 提交异步任务。
     *
     * <p>任务在独立线程执行，异常只记录日志；{@code taskKey} 用于日志定位。</p>
     *
     * @param taskKey 任务标识。
     * @param job     待执行任务。
     */
    @Override
    public void async(String taskKey, Runnable job) {
        if (null == job) {
            return;
        }

        if (null == this.host) {
            Logger.w(ActionRunner.class, "#async - Host is NOT available, task \"" + taskKey + "\" is dropped");
            return;
        }

        this.host.schedule(taskKey, 0L, job);
    }

    /**
     * 判断请求是否已被取消。
     *
     * <p>长时间任务应在关键步骤间检查该标记，及时退出。</p>
     *
     * @return 已取消返回 {@code true} 。
     */
    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * 标记本请求已取消。
     *
     * <p>当前阶段无取消来源，方法保留供后续取消通道使用。</p>
     */
    public void cancel() {
        this.cancelled = true;
        this.finishMonitoring("cancelled");
    }
}
