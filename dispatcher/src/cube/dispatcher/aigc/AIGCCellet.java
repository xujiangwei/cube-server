/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.dispatcher.filestorage.FileStorageCellet;
import cube.dispatcher.stream.Stream;
import cube.dispatcher.stream.StreamListener;
import cube.dispatcher.stream.Track;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AIGC 服务单元。
 */
public class AIGCCellet extends AbstractCellet {

    /** 单元名称，同时作为端点注册名。 */
    public final static String NAME = "AIGC";

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> taskQueue;

    private StreamProcessor processor;

    public AIGCCellet() {
        super(NAME);
        this.taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：启动 AIGC 管理器、创建流处理器并注册流监听器。
     *
     * <p>三步顺序不能调换：管理器先就绪，流处理器才能依赖它分发流任务；
     * 监听器最后挂到流服务器上，避免提前收到无人处理的分片。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");
        Manager.getInstance().start(this.performer);

        this.processor = new StreamProcessor(this.performer,
                (FileStorageCellet) this.performer.getCellet(FileStorageCellet.NAME));

        this.performer.getStreamServer().setListener(new DefaultStreamListener(this.processor));

        return true;
    }

    /**
     * 卸载单元：与装载顺序相反地拆除。
     *
     * <p>先停流处理器并摘除监听器，再停管理器，确保没有正在处理的分片被打断在中间状态。
     * 本方法假定 {@link #install()} 已执行过。</p>
     */
    @Override
    public void uninstall() {
        this.processor.stop();

        this.performer.getStreamServer().removeListener();

        Manager.getInstance().stop();
    }

    /**
     * 收到网络消息时触发。
     *
     * <p>借出透传任务交给执行机异步处理，避免占用 IO 线程。</p>
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

    /**
     * 获取流处理器。
     *
     * @return 单元装载后创建的流处理器；未装载时为 {@code null} 。
     */
    public StreamProcessor getStreamProcessor() {
        return this.processor;
    }


    /**
     * 流监听器。
     */
    protected class DefaultStreamListener implements StreamListener {

        private final StreamProcessor processor;

        public DefaultStreamListener(StreamProcessor processor) {
            this.processor = processor;
        }

        @Override
        public void onStream(Track track, Stream stream) {
            this.processor.receive(track, stream);
        }
    }
}
