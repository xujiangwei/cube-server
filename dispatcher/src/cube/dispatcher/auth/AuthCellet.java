/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.auth;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 授权模块网关的 Cellet 服务单元。
 */
public class AuthCellet extends AbstractCellet {

    /**
     * Cellet 名称。
     */
    public final static String NAME = "Auth";

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> taskQueue;

    public AuthCellet() {
        super(AuthCellet.NAME);
        this.taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：取得执行机引用。
     *
     * <p>端点由 {@code AbstractCellet} 依据单元名统一注册，此处无需额外注册处理器。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");
        return true;
    }

    /**
     * 卸载单元。
     *
     * <p>无需释放资源：单元只持有执行机引用，无独立连接与线程。</p>
     */
    @Override
    public void uninstall() {
    }

    /**
     * 收到网络消息时触发。
     *
     * <p>借出一个透传任务并交给执行机异步处理，避免占用 IO 线程。</p>
     */
    @Override
    public void onListened(TalkContext talkContext, Primitive primitive) {
        super.onListened(talkContext, primitive);

        this.performer.execute(this.borrowTask(talkContext, primitive));
    }

    /**
     * 借出一个任务对象。
     *
     * <p>优先复用队列中的空闲任务，队列为空时新建；两种情况都会记录响应时间起点。</p>
     *
     * @param talkContext 会话上下文。
     * @param primitive   原始消息。
     * @return 可直接提交给执行机的任务。
     */
    protected PassThroughTask borrowTask(TalkContext talkContext, Primitive primitive) {
        PassThroughTask task = this.taskQueue.poll();
        if (null == task) {
            task = new PassThroughTask(this, talkContext, primitive, this.performer);
            task.responseTime = this.markResponseTime(task.getAction().getName());
            return task;
        }

        task.reset(talkContext, primitive);
        task.responseTime = this.markResponseTime(task.getAction().getName());
        return task;
    }

    /**
     * 归还任务对象。
     *
     * <p>归还前记录响应耗时，随后放回队列等待复用。</p>
     *
     * @param task 已执行完毕的任务。
     */
    protected void returnTask(PassThroughTask task) {
        task.markResponseTime();

        this.taskQueue.offer(task);
    }
}
