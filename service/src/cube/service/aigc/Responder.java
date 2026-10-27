/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc;

import cell.core.talk.dialect.ActionDialect;
import org.json.JSONObject;

/**
 * 应答机。
 */
public class Responder {

    /** 响应中携带的通知标识参数名。 */
    public final static String NotifierKey = "_notifier";

    private long sn;

    private JSONObject notifier;

    private ActionDialect response;

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
     * <p>在通知对象上等待，超时或收到通知后返回；超时返回 {@code null} 。</p>
     *
     * @param timeout 等待时长，单位毫秒。
     * @return 响应方言；超时返回 {@code null} 。
     */
    public ActionDialect waitingFor(long timeout) {
        synchronized (this.notifier) {
            try {
                this.notifier.wait(timeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return this.response;
    }

    /**
     * 投递响应并唤醒等待方。
     *
     * <p>投递前会移除内部的通知标识参数，避免泄漏给客户端。</p>
     *
     * @param response 响应方言。
     */
    public void notifyResponse(ActionDialect response) {
        response.removeParam(Responder.NotifierKey);

        this.response = response;
        synchronized (this.notifier) {
            this.notifier.notify();
        }
    }

    /**
     * 结束等待。
     *
     * <p>唤醒仍阻塞的等待方，用于上游异常时主动收尾。</p>
     */
    public void finish() {
        synchronized (this.notifier) {
            this.notifier.notify();
        }
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
