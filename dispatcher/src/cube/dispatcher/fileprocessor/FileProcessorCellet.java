/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.fileprocessor;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.core.talk.dialect.DialectFactory;
import cube.common.action.FileProcessorAction;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.dispatcher.fileprocessor.handler.GetMediaSourceHandler;
import cube.dispatcher.fileprocessor.handler.MediaStreamHandler;
import cube.dispatcher.fileprocessor.handler.SteganographicHandler;
import cube.util.HttpServer;
import org.eclipse.jetty.server.handler.ContextHandler;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 文件处理模块网关的 Cellet 服务单元。
 */
public class FileProcessorCellet extends AbstractCellet {

    /**
     * Cellet 名称。
     */
    public final static String NAME = "FileProcessor";

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * 任务缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> taskQueue;

    public FileProcessorCellet() {
        super(NAME);
        this.taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：注册媒体流、媒体源与隐写三个端点，并初始化媒体文件管理器。
     *
     * <p>{@code MediaFileManager} 初始化时会扫描并校验本地媒体文件索引。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");

        // 配置 HTTP/HTTPS 服务的句柄
        HttpServer httpServer = this.performer.getHttpServer();

        // 媒体流处理句柄
        ContextHandler mediaListHandler = new ContextHandler();
        mediaListHandler.setContextPath(MediaStreamHandler.CONTEXT_PATH);
        mediaListHandler.setHandler(new MediaStreamHandler(this.performer));
        httpServer.addContextHandler(mediaListHandler);

        // 获取媒体源句柄
        ContextHandler getMediaSourceHandler = new ContextHandler();
        getMediaSourceHandler.setContextPath(GetMediaSourceHandler.CONTEXT_PATH);
        getMediaSourceHandler.setHandler(new GetMediaSourceHandler(this.performer));
        httpServer.addContextHandler(getMediaSourceHandler);

        // 隐写数据句柄
        ContextHandler steganoHandler = new ContextHandler();
        steganoHandler.setContextPath(SteganographicHandler.CONTEXT_PATH);
        steganoHandler.setHandler(new SteganographicHandler(this.performer));
        httpServer.addContextHandler(steganoHandler);

        MediaFileManager.getInstance().setPerformer(this.performer);

        // 校验工具
        MediaFileManager.getInstance().check();

        return true;
    }

    /**
     * 卸载单元。
     *
     * <p>无需释放资源：端点随 HTTP 服务器生命周期结束而失效。</p>
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

        ActionDialect actionDialect = DialectFactory.getInstance().createActionDialect(primitive);
        String action = actionDialect.getName();

        if (FileProcessorAction.GetMediaSource.name.equals(action)) {
            this.performer.execute(new GetMediaSourceTask(this, talkContext, primitive, this.performer));
        }
        else {
            this.performer.execute(this.borrowTask(talkContext, primitive, true));
        }
    }

    /**
     * 借出一个任务对象。
     *
     * <p>优先复用队列中的空闲任务，队列为空时新建；两种情况都会记录响应时间起点。</p>
     *
     * @param talkContext 会话上下文。
     * @param primitive   原始消息。
     * @param sync        是否同步等待后端响应。
     * @return 可直接提交给执行机的任务。
     */
    protected PassThroughTask borrowTask(TalkContext talkContext, Primitive primitive, boolean sync) {
        PassThroughTask task = this.taskQueue.poll();
        if (null == task) {
            task = new PassThroughTask(this, talkContext, primitive, this.performer, sync);
            task.responseTime = this.markResponseTime(task.getAction().getName());
            return task;
        }

        task.reset(talkContext, primitive, sync);
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
