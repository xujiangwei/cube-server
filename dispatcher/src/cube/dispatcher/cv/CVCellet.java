/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.cv;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.dispatcher.cv.handler.*;
import cube.util.HttpServer;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 计算机视觉服务单元。
 *
 * <p>挂载条码、目标检测、姿态估计、手势估计与纸张裁切等 HTTP 端点，
 * 把请求转交 {@link PassThroughTask} 发往 CV 服务后端。</p>
 */
public class CVCellet extends AbstractCellet {

    /** 单元名称，同时作为端点注册名。 */
    public final static String NAME = "CV";

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> taskQueue;

    public CVCellet() {
        super(NAME);
        this.taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：向 HTTP 服务器注册全部 CV 端点。
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");

        HttpServer httpServer = this.performer.getHttpServer();
        httpServer.addContextHandler(new MakeBarCode(this.performer));
        httpServer.addContextHandler(new DetectBarCode(this.performer));
        httpServer.addContextHandler(new ObjectDetection(this.performer));
        httpServer.addContextHandler(new PoseEstimation(this.performer));
        httpServer.addContextHandler(new HandEstimation(this.performer));
        httpServer.addContextHandler(new ClipPaper(this.performer));

        return true;
    }

    /**
     * 卸载单元。
     *
     * <p>当前无事可做：端点随 HTTP 服务器生命周期结束而失效，任务队列由 GC 回收。</p>
     */
    @Override
    public void uninstall() {
    }

    /**
     * 收到网络消息时触发。
     *
     * <p>取出（或复用）一个透传任务并交给执行机异步处理，不在 IO 线程内阻塞。</p>
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
