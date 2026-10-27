/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.signal;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.common.StateCode;
import cube.dispatcher.DispatcherTask;
import cube.dispatcher.Performer;
import org.json.JSONObject;

/**
 * 透传数据任务。
 */
public class PassThroughTask extends DispatcherTask {

    private boolean waitResponse = true;

    public PassThroughTask(SignalCellet cellet, TalkContext talkContext, Primitive primitive
            , Performer performer) {
        super(cellet, talkContext, primitive, performer);
    }

    public PassThroughTask(SignalCellet cellet, TalkContext talkContext, Primitive primitive
            , Performer performer, boolean sync) {
        super(cellet, talkContext, primitive, performer);
        this.waitResponse = sync;
    }

    /**
     * 重置任务以复用。
     *
     * <p>同时更新同步/单向标记，避免把上一次的等待模式带到下一次请求。</p>
     *
     * @param talkContext 新的会话上下文。
     * @param primitive   新的原始消息。
     * @param sync        是否同步等待结果。
     */
    protected void reset(TalkContext talkContext, Primitive primitive, boolean sync) {
        super.reset(talkContext, primitive);
        this.waitResponse = sync;
    }

    /**
     * 执行任务。
     *
     * <p>同步模式下把请求转发到服务层并把结果回写客户端，转发失败时返回网关错误；
     * 单向模式下只透传不等待结果。结束后把任务对象归还对象池。</p>
     */
    @Override
    public void run() {
        String tokenCode = this.getTokenCode(this.getAction());
        if (null == tokenCode) {
            // 无令牌码
            ActionDialect response = this.makeResponse(new JSONObject(), StateCode.NoAuthToken, "No token code");
            this.cellet.speak(this.talkContext, response);
            ((SignalCellet)this.cellet).returnTask(this);
            return;
        }

        if (this.waitResponse) {
            ActionDialect response = this.performer.syncTransmit(this.talkContext, this.cellet.getName(), this.getAction());

            if (null == response) {
                response = this.makeResponse(this.getRequest().data, StateCode.GatewayError, "Service failed");
            }
            else {
                response = this.makeResponse(response);
            }

            this.cellet.speak(this.talkContext, response);
        }
        else {
            this.performer.transmit(this.talkContext, this.cellet, this.getAction());
        }

        ((SignalCellet)this.cellet).returnTask(this);
    }
}
