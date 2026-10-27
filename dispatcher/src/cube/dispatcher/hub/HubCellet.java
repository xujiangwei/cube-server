/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.hub;

import cell.core.talk.Primitive;
import cell.core.talk.PrimitiveInputStream;
import cell.core.talk.TalkContext;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.dispatcher.hub.handler.*;
import cube.util.HttpServer;
import org.eclipse.jetty.server.handler.ContextHandler;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hub 模块的 Cellet 服务单元。
 */
public class HubCellet extends AbstractCellet {

    /**
     * Cellet 名称。
     */
    public final static String NAME = "Hub";

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * 访问控制器。
     */
    private Controller controller;

    /**
     * 任务缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> taskQueue;

    /**
     * 守护任务定时器。
     */
    private Timer daemonTimer;

    public HubCellet() {
        super(HubCellet.NAME);
        this.taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：创建控制器、注册端点处理器并启动守护定时器。
     *
     * <p>定时器首次延迟 5 秒，之后每 10 分钟执行一次 {@code onTick} ，用于周期性维护。
     * 该定时器随单元卸载而取消，不会阻止进程退出。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");

        this.controller = new Controller();

        setupHandler();

        this.daemonTimer = new Timer();
        this.daemonTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                onTick();
            }
        }, 5000, 10 * 60 * 1000);

        return true;
    }

    /**
     * 卸载单元：取消守护定时器。
     *
     * <p>允许重复调用，定时器已取消时直接返回。</p>
     */
    @Override
    public void uninstall() {
        if (null != this.daemonTimer) {
            this.daemonTimer.cancel();
            this.daemonTimer = null;
        }
    }

    /**
     * 收到网络消息时触发。
     *
     * <p>Hub 的动作依赖控制器状态，统一借出同步任务按序处理。</p>
     */
    @Override
    public void onListened(TalkContext talkContext, Primitive primitive) {
        super.onListened(talkContext, primitive);

        this.performer.execute(this.borrowTask(talkContext, primitive, true));
    }

    /**
     * 收到流式消息时触发。
     *
     * <p>当前不处理流式消息，仅调用父类实现保持监听链路完整。</p>
     */
    @Override
    public void onListened(TalkContext talkContext, PrimitiveInputStream primitiveStream) {
        super.onListened(talkContext, primitiveStream);
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

    private void onTick() {
        CacheCenter.getInstance().selfChecking();
    }

    private void setupHandler() {
        HttpServer httpServer = this.performer.getHttpServer();

        // 文件下载
        ContextHandler fileHandler = new ContextHandler();
        fileHandler.setContextPath(FileHandler.CONTEXT_PATH);
        fileHandler.setHandler(new FileHandler(this.performer, this.controller));
        httpServer.addContextHandler(fileHandler);

        // 打开管道
        ContextHandler openHandler = new ContextHandler();
        openHandler.setContextPath(OpenChannel.CONTEXT_PATH);
        openHandler.setHandler(new OpenChannel(this.performer, this.controller));
        httpServer.addContextHandler(openHandler);

        // 关闭管道
        ContextHandler closeHandler = new ContextHandler();
        closeHandler.setContextPath(CloseChannel.CONTEXT_PATH);
        closeHandler.setHandler(new CloseChannel(this.performer, this.controller));
        httpServer.addContextHandler(closeHandler);

        // 获取账号数据
        ContextHandler accountHandler = new ContextHandler();
        accountHandler.setContextPath(AccountHandler.CONTEXT_PATH);
        accountHandler.setHandler(new AccountHandler(this.performer, this.controller));
        httpServer.addContextHandler(accountHandler);

        // 获取会话数据
        ContextHandler convsHandler = new ContextHandler();
        convsHandler.setContextPath(ConversationsHandler.CONTEXT_PATH);
        convsHandler.setHandler(new ConversationsHandler(this.performer, this.controller));
        httpServer.addContextHandler(convsHandler);

        // 轮询消息数据
        ContextHandler rollPollingHandler = new ContextHandler();
        rollPollingHandler.setContextPath(RollPollingHandler.CONTEXT_PATH);
        rollPollingHandler.setHandler(new RollPollingHandler(this.performer, this.controller));
        httpServer.addContextHandler(rollPollingHandler);

        // 获取消息数据
        ContextHandler messagesHandler = new ContextHandler();
        messagesHandler.setContextPath(MessagesHandler.CONTEXT_PATH);
        messagesHandler.setHandler(new MessagesHandler(this.performer, this.controller));
        httpServer.addContextHandler(messagesHandler);

        // 获取通讯录数据
        ContextHandler bookHandler = new ContextHandler();
        bookHandler.setContextPath(ContactBookHandler.CONTEXT_PATH);
        bookHandler.setHandler(new ContactBookHandler(this.performer, this.controller));
        httpServer.addContextHandler(bookHandler);

        // 获取群组数据
        ContextHandler groupHandler = new ContextHandler();
        groupHandler.setContextPath(GroupHandler.CONTEXT_PATH);
        groupHandler.setHandler(new GroupHandler(this.performer, this.controller));
        httpServer.addContextHandler(groupHandler);

        // 发送消息操作
        ContextHandler sendMsgHandler = new ContextHandler();
        sendMsgHandler.setContextPath(SendMessageHandler.CONTEXT_PATH);
        sendMsgHandler.setHandler(new SendMessageHandler(this.performer, this.controller));
        httpServer.addContextHandler(sendMsgHandler);

        // 添加、删除朋友
        ContextHandler friendHandler = new ContextHandler();
        friendHandler.setContextPath(FriendHandler.CONTEXT_PATH);
        friendHandler.setHandler(new FriendHandler(this.performer, this.controller));
        httpServer.addContextHandler(friendHandler);
    }
}
