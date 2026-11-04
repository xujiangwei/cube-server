/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc;

import cell.core.talk.dialect.ActionDialect;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 应答机。
 */
public class Responder {

    /** 响应中携带的通知标识参数名。 */
    public final static String NotifierKey = "_notifier";

    private long sn;

    private JSONObject notifier;

    private ActionDialect response;

    private final CountDownLatch completion = new CountDownLatch(1);

    private cell.core.talk.TalkContext expectedContext;

    public Responder(long sn, ActionDialect dialect, cell.core.talk.TalkContext context) {
        this(sn, dialect);
        this.expectedContext = context;
    }

    public boolean isResponse(cell.core.talk.TalkContext context, ActionDialect response) {
        if (expectedContext != null && expectedContext != context
                && (context == null || expectedContext.getSession() == null || expectedContext.getSession() != context.getSession()))
            return false;
        return isResponse(response);
    }

    public Responder(long sn, ActionDialect actionDialect) {
        this.sn = sn;
        this.notifier = createNotifier();
        actionDialect.addParam(Responder.NotifierKey, this.notifier);
    }

    /**
     * 获取响应序列号。
     *
     * @return 序列号。
     */
    public long getSN() {
        return this.sn;
    }

    /**
     * 等待响应。
     *
     * <p>等待已保存的完成状态，应答或结束信号先到时立即返回。
     * 超时、被中断或无应答结束时返回 {@code null} 。</p>
     *
     * @param timeout 等待时长，单位毫秒；为 0 时无限等待。
     * @return 响应方言；超时返回 {@code null} 。
     */
    public ActionDialect waitingFor(long timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        try {
            if (timeout == 0) {
                this.completion.await();
            }
            else if (!this.completion.await(timeout, TimeUnit.MILLISECONDS)) {
                return null;
            }
            return this.response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 投递响应并唤醒等待方。
     *
     * <p>投递前会移除内部的通知标识参数，避免泄漏给客户端。</p>
     *
     * @param response 响应方言。
     */
    public synchronized void notifyResponse(ActionDialect response) {
        response.removeParam(Responder.NotifierKey);
        if (this.completion.getCount() != 0) {
            this.response = response;
            this.completion.countDown();
        }
    }

    /**
     * 结束等待。
     *
     * <p>唤醒仍阻塞的等待方，用于上游异常时主动收尾。</p>
     */
    public synchronized void finish() {
        this.completion.countDown();
    }

    /**
     * 判断响应是否属于当前等待者。
     *
     * @param response 响应方言。
     * @return 序列号匹配返回 {@code true} 。
     */
    public boolean isResponse(ActionDialect response) {
        JSONObject notifier = response.getParamAsJson(Responder.NotifierKey);
        if (notifier.getLong("sn") == this.sn) {
            return true;
        }

        return false;
    }

    private JSONObject createNotifier() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("ts", System.currentTimeMillis());
        return json;
    }
}
