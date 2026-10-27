/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.filestorage;

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

    public PassThroughTask(FileStorageCellet cellet, TalkContext talkContext, Primitive primitive
            , Performer performer) {
        super(cellet, talkContext, primitive, performer);
    }

    public PassThroughTask(FileStorageCellet cellet, TalkContext talkContext, Primitive primitive
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
     * <p>先校验令牌，缺失时直接回 {@code NoAuthToken}；否则按同步或单向模式转发文件存储请求。</p>
     */
    @Override
    public void run() {
        String tokenCode = this.getTokenCode(this.getAction());
        if (null == tokenCode) {
            // 无令牌码
            ActionDialect response = this.makeResponse(new JSONObject(), StateCode.NoAuthToken, "No token code");
            this.cellet.speak(this.talkContext, response);
            ((FileStorageCellet)this.cellet).returnTask(this);
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

        ((FileStorageCellet)this.cellet).returnTask(this);
    }
}
