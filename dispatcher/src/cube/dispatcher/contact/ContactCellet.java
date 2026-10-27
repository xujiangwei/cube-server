/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.contact;

import cell.api.Servable;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.core.talk.dialect.DialectFactory;
import cube.common.action.ContactAction;
import cube.core.AbstractCellet;
import cube.dispatcher.Performer;
import cube.dispatcher.contact.handler.*;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 联系人模块网关的 Cellet 服务单元。
 */
public class ContactCellet extends AbstractCellet {

    /**
     * Cellet 名称。
     */
    public final static String NAME = "Contact";

    /**
     * 执行机。
     */
    private Performer performer;

    /**
     * Sign in 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<SignInTask> signInTaskQueue;

    /**
     * Sign out 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<SignOutTask> signOutTaskQueue;

    /**
     * Comeback 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<ComebackTask> comebackTaskQueue;

    /**
     * Pass through 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<PassThroughTask> passTaskQueue;

    /**
     * Disconnect 任务对象的缓存队列。
     */
    private ConcurrentLinkedQueue<DisconnectTask> disconnTaskQueue;

    public ContactCellet() {
        super(NAME);
        this.signInTaskQueue = new ConcurrentLinkedQueue<>();
        this.signOutTaskQueue = new ConcurrentLinkedQueue<>();
        this.comebackTaskQueue = new ConcurrentLinkedQueue<>();
        this.passTaskQueue = new ConcurrentLinkedQueue<>();
        this.disconnTaskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 装载单元：取得执行机并注册联系人相关的 HTTP 端点。
     *
     * <p>端点包括查询、存在性判断、新建、删除联系人与验证码。</p>
     *
     * @return 恒定返回 {@code true} 。
     */
    @Override
    public boolean install() {
        this.performer = (Performer) this.getNucleus().getParameter("performer");
        this.setupHandlers();
        return true;
    }

    /**
     * 卸载单元。
     *
     * <p>无需释放资源：端点随 HTTP 服务器生命周期结束而失效，任务队列由 GC 回收。</p>
     */
    @Override
    public void uninstall() {
    }

    /**
     * 收到网络消息时触发。
     *
     * <p>按动作名分派到不同的任务队列：签到与返场需要独占任务对象，
     * 列举群组走异步，其余动作走同步。</p>
     */
    @Override
    public void onListened(TalkContext talkContext, Primitive primitive) {
        super.onListened(talkContext, primitive);

        ActionDialect actionDialect = DialectFactory.getInstance().createActionDialect(primitive);
        String action = actionDialect.getName();

        if (ContactAction.Comeback.name.equals(action)) {
            this.performer.execute(this.borrowComebackTask(talkContext, primitive));
        }
        else if (ContactAction.SignIn.name.equals(action)) {
            this.performer.execute(this.borrowSignInTask(talkContext, primitive));
        }
        else if (ContactAction.SignOut.name.equals(action)) {
            this.performer.execute(this.borrowSignOutTask(talkContext, primitive));
        }
        else if (ContactAction.ListGroups.name.equals(action)) {
            this.performer.execute(this.borrowPassTask(talkContext, primitive, false));
        }
        else {
            this.performer.execute(this.borrowPassTask(talkContext, primitive, true));
        }
    }

    /**
     * 连接断开时触发。
     *
     * <p>投递一个断连任务，由后端清理该会话的在线状态。</p>
     */
    @Override
    public void onQuitted(TalkContext context, Servable server) {
        this.performer.execute(this.borrowDisconnectTask(context));
    }

    private void setupHandlers() {
        this.performer.getHttpServer().addContextHandler(new GetContact(this.performer));
        this.performer.getHttpServer().addContextHandler(new ExistsContact(this.performer));
        this.performer.getHttpServer().addContextHandler(new NewContact(this.performer));
        this.performer.getHttpServer().addContextHandler(new DeleteContact(this.performer));
        this.performer.getHttpServer().addContextHandler(new VerificationCode(this.performer));
    }

    /**
     * 借出一个签到任务。
     *
     * <p>签到任务持有独立的会话状态，无法与其他动作共用对象池。</p>
     *
     * @param talkContext 会话上下文。
     * @param primitive   原始消息。
     * @return 可直接提交给执行机的任务。
     */
    protected SignInTask borrowSignInTask(TalkContext talkContext, Primitive primitive) {
        SignInTask task = this.signInTaskQueue.poll();
        if (null == task) {
            task = new SignInTask(this, talkContext, primitive, this.performer);
            task.responseTime = this.markResponseTime(task.getAction().getName());
            return task;
        }

        task.reset(talkContext, primitive);
        task.responseTime = this.markResponseTime(task.getAction().getName());
        return task;
    }

    /**
     * 归还签到任务。
     *
     * @param task 已执行完毕的任务。
     */
    protected void returnSignInTask(SignInTask task) {
        task.markResponseTime();

        this.signInTaskQueue.offer(task);
    }

    /**
     * 借出一个签退任务。
     *
     * @param talkContext 会话上下文。
     * @param primitive   原始消息。
     * @return 可直接提交给执行机的任务。
     */
    protected SignOutTask borrowSignOutTask(TalkContext talkContext, Primitive primitive) {
        SignOutTask task = this.signOutTaskQueue.poll();
        if (null == task) {
            task = new SignOutTask(this, talkContext, primitive, this.performer);
            task.responseTime = this.markResponseTime(task.getAction().getName());
            return task;
        }

        task.reset(talkContext, primitive);
        task.responseTime = this.markResponseTime(task.getAction().getName());
        return task;
    }

    /**
     * 归还签退任务。
     *
     * @param task 已执行完毕的任务。
     */
    protected void returnSignOutTask(SignOutTask task) {
        task.markResponseTime();

        this.signOutTaskQueue.offer(task);
    }

    /**
     * 借出一个返场任务。
     *
     * @param talkContext 会话上下文。
     * @param primitive   原始消息。
     * @return 可直接提交给执行机的任务。
     */
    protected ComebackTask borrowComebackTask(TalkContext talkContext, Primitive primitive) {
        ComebackTask task = this.comebackTaskQueue.poll();
        if (null == task) {
            task = new ComebackTask(this, talkContext, primitive, this.performer);
            task.responseTime = this.markResponseTime(task.getAction().getName());
            return task;
        }

        task.reset(talkContext, primitive);
        task.responseTime = this.markResponseTime(task.getAction().getName());
        return task;
    }

    /**
     * 归还返场任务。
     *
     * @param task 已执行完毕的任务。
     */
    protected void returnComebackTask(ComebackTask task) {
        task.markResponseTime();

        this.comebackTaskQueue.offer(task);
    }

    /**
     * 借出一个透传任务。
     *
     * <p>用于不需要独立状态的普通动作。</p>
     *
     * @param talkContext 会话上下文。
     * @param primitive   原始消息。
     * @param sync        是否同步等待后端响应。
     * @return 可直接提交给执行机的任务。
     */
    protected PassThroughTask borrowPassTask(TalkContext talkContext, Primitive primitive, boolean sync) {
        PassThroughTask task = this.passTaskQueue.poll();
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
     * 归还透传任务。
     *
     * @param task 已执行完毕的任务。
     */
    protected void returnPassTask(PassThroughTask task) {
        task.markResponseTime();

        this.passTaskQueue.offer(task);
    }

    /**
     * 借出一个断连任务。
     *
     * <p>断连任务只依赖会话上下文，没有请求消息。</p>
     *
     * @param talkContext 会话上下文。
     * @return 可直接提交给执行机的任务。
     */
    protected DisconnectTask borrowDisconnectTask(TalkContext talkContext) {
        DisconnectTask task = this.disconnTaskQueue.poll();
        if (null == task) {
            return new DisconnectTask(this, talkContext, this.performer);
        }

        task.reset(talkContext);
        return task;
    }

    /**
     * 归还断连任务。
     *
     * @param task 已执行完毕的任务。
     */
    protected void returnDisconnectTask(DisconnectTask task) {
        this.disconnTaskQueue.offer(task);
    }
}
