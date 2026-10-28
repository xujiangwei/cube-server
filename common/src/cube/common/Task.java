/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.core.Kernel;

/**
 * Cellet 通信任务描述。
 */
public abstract class Task implements Runnable {
    private volatile cube.monitor.ServiceMetrics.Sample monitoring;
    private volatile boolean monitoringDeferred;
    private volatile String monitoringOutcome = "unknown";

    public String monitoringModule() { return this.cellet.getName(); }
    public String monitoringAction() {
        try { return new ActionDialect(this.primitive).getName(); }
        catch (RuntimeException e) { return this.getClass().getSimpleName(); }
    }
    /** Preserve asynchronous work until its actual terminal callback. */
    public void deferMonitoring(boolean streaming) {
        this.monitoringDeferred = true;
        if (streaming && this.kernel != null) this.kernel.getServiceMetrics().streaming(this.monitoring);
    }
    public void monitoringStreaming() { if (this.kernel != null) this.kernel.getServiceMetrics().streaming(this.monitoring); }
    public void monitoringFirst() { if (this.kernel != null) this.kernel.getServiceMetrics().first(this.monitoring); }
    public void monitoringResult(String result) { this.monitoringOutcome = result; }
    public void finishMonitoring(String result) {
        if (this.kernel != null) this.kernel.getServiceMetrics().complete(this.monitoring, result);
    }
    /** Called by the shared Cellet submission path, before the executor can start the task. */
    public void executeObserved(java.util.concurrent.ExecutorService executor) {
        if (this.kernel == null) { executor.execute(this); return; }
        cube.monitor.ServiceMetrics metrics = this.kernel.getServiceMetrics();
        String action = monitoringAction();
        this.monitoring = metrics.submit(monitoringModule(), action, cube.monitor.ServiceMetrics.control(action));
        try {
            if (executor == null) throw new java.util.concurrent.RejectedExecutionException("Executor unavailable");
            executor.execute(() -> {
                metrics.start(this.monitoring);
                try { this.run(); }
                catch (RuntimeException | Error failure) { this.finishMonitoring("failed"); throw failure; }
                finally { if (!this.monitoringDeferred) this.finishMonitoring(this.monitoringOutcome); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            this.finishMonitoring("rejected"); throw failure;
        }
    }

    /**
     * 框架的内核实例。
     */
    protected Kernel kernel;

    /**
     * 对应的 Cellet 实例。
     */
    protected Cellet cellet;

    /**
     * 当前任务对应的上下文。
     */
    protected TalkContext talkContext;

    /**
     * 当前接收到的原语数据。
     */
    protected Primitive primitive;

    /**
     * 构造函数。
     *
     * @param cellet 指定 Cellet 实例。
     * @param talkContext 指定会话上下文。
     * @param primitive 指定原语数据。
     */
    public Task(Cellet cellet, TalkContext talkContext, Primitive primitive) {
        this.cellet = cellet;
        this.talkContext = talkContext;
        this.primitive = primitive;
        this.kernel = (Kernel) cellet.getNucleus().getParameter("kernel");
    }

    /**
     * 获取令牌码。
     * @param actionDialect actionDialect。
     * @return 如果在动作方言中找不到令牌码返回 {@code null} 值。
     */
    public String getTokenCode(ActionDialect actionDialect) {
        if (actionDialect.containsParam("token")) {
            return actionDialect.getParamAsString("token");
        }

        return null;
    }

    @Override
    abstract public void run();
}
