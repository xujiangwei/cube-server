/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.filestorage;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cube.auth.AuthToken;
import cube.common.action.FileStorageAction;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.util.FileUtils;
import cube.util.HttpServer;
import org.eclipse.jetty.server.handler.ContextHandler;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 文件存储模块的 Cellet 单元。
 */
public class FileStorageCellet extends AbstractCellet {

    /**
     * Cellet 名称。
     */
    public final static String NAME = "FileStorage";

    /**
     * App 登录地址，默认指向本机控制台。
     *
     * <p>可由配置项 {@code app.login} 覆盖，用于在分享链接的落地页跳转。</p>
     */
    protected static String APP_LOGIN_URL = "https://127.0.0.1:8080/index.html";

    /**
     * 文件块存储。
     */
    private FileChunkStorage fileChunkStorage;

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * 任务缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> taskQueue;

    public FileStorageCellet() {
        super(NAME);
        this.fileChunkStorage = new FileChunkStorage("cube-fs-files");
        this.taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：打开文件块存储并注册文件、操作、分享三个端点。
     *
     * <p>顺序要求：必须先把存储管理器打开，端点才能安全地访问文件块。
     * {@code app.login} 存在时覆盖默认的 App 登录地址。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) nucleus.getParameter("performer");

        // 配置 App
        if (this.performer.getProperties().containsKey("app.login")) {
            APP_LOGIN_URL = this.performer.getProperties().getProperty("app.login").trim();
        }

        // 打开存储管理器
        this.fileChunkStorage.open(this, this.performer);

        // 配置 HTTP/HTTPS 服务的句柄
        HttpServer httpServer = this.performer.getHttpServer();

        // 添加句柄
        ContextHandler fileHandler = new ContextHandler();
        fileHandler.setContextPath(FileHandler.PATH);
        fileHandler.setHandler(new FileHandler(this.fileChunkStorage, this.performer));
        httpServer.addContextHandler(fileHandler);

        ContextHandler operationHandler = new ContextHandler();
        operationHandler.setContextPath(FileOperationHandler.PATH);
        operationHandler.setHandler(new FileOperationHandler(this.performer));
        httpServer.addContextHandler(operationHandler);

        ContextHandler sharingHandler = new ContextHandler();
        sharingHandler.setContextPath(FileSharingHandler.PATH);
        sharingHandler.setHandler(new FileSharingHandler(this.performer));
        httpServer.addContextHandler(sharingHandler);

        return true;
    }

    /**
     * 卸载单元：关闭文件块存储。
     *
     * <p>关闭后未完成的分块写入会被丢弃，因此应在 HTTP 端点停止后再调用。</p>
     */
    @Override
    public void uninstall() {
        this.fileChunkStorage.close();
    }

    /**
     * 收到网络消息时触发。
     *
     * <p>创建分享标签是长任务，独占一个 {@code MarathonTask} 且不复用，
     * 启动失败时立即回“系统忙”；其余动作走对象池复用的透传任务。</p>
     */
    @Override
    public void onListened(TalkContext talkContext, Primitive primitive) {
        super.onListened(talkContext, primitive);

        String action = primitive.getStuff(0).getValueAsString();
        if (FileStorageAction.CreateSharingTag.name.equals(action)) {
            MarathonTask task = new MarathonTask(this, talkContext, primitive, this.performer);
            task.responseTime = this.markResponseTime(task.getAction().getName());
            if (!task.start()) {
                // 应答系统忙
                task.responseBusy();
            }
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

    /**
     * 把内存数据写入文件存储。
     *
     * <p>文件码由联系人 ID 、域与文件名共同生成，同一用户同名文件会相互覆盖。
     * 本方法用于服务内部转存，不经过 HTTP 端点。</p>
     *
     * @param authToken 调用方令牌，提供联系人与域信息。
     * @param filename  文件名。
     * @param data      文件内容。
     * @return 由存储层返回的文件码。
     */
    public String transfer(AuthToken authToken, String filename, byte[] data) {
        final String fileCode = FileUtils.makeFileCode(authToken.getContactId(), authToken.getDomain(), filename);

        FileChunk chunk = new FileChunk(authToken.getContactId(), authToken.getDomain(), authToken.getCode(),
                filename, data.length, System.currentTimeMillis(), 0, data.length, data);
        return this.fileChunkStorage.append(chunk, fileCode);
    }
}
