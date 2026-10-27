/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.completion.CompletionError;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.dispatcher.DispatcherTask;
import cube.dispatcher.Performer;
import org.json.JSONObject;

/**
 * 透传数据给服务层。
 */
public class PassThroughTask extends DispatcherTask {

    private boolean waitResponse = true;

    public PassThroughTask(AIGCCellet cellet, TalkContext talkContext, Primitive primitive
            , Performer performer) {
        super(cellet, talkContext, primitive, performer);
    }

    public PassThroughTask(AIGCCellet cellet, TalkContext talkContext, Primitive primitive
            , Performer performer, boolean sync) {
        super(cellet, talkContext, primitive, performer);
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
        try {
            if (this.waitResponse) {
                ActionDialect response = this.performer.syncTransmit(this.talkContext, this.cellet.getName(), this.getAction());

                if (null == response) {
                    Packet request = this.getRequest();
                    // 发生错误
                    Packet packet = new Packet(request.sn, request.name, new JSONObject());
                    response = this.makeGatewayErrorResponse(packet);
                } else {
                    response = this.makeResponse(response);
                }

                this.cellet.speak(this.talkContext, response);
            } else {
                this.performer.transmit(this.talkContext, this.cellet, this.getAction());
            }

        } catch (CapabilityUnavailableException e) {
            if (AIGCAction.ChatCompletion.name.equals(this.getRequest().name)) {
                this.respondCompletionError(new CompletionError(503, "service_unavailable", e.getMessage(), null));
            } else {
                this.cellet.speak(this.talkContext, this.packResponse(new JSONObject().put("message", e.getMessage()),
                        cube.common.state.AIGCStateCode.UnitNoReady.code, cube.common.StateCode.OK, "OK"));
            }
        } catch (CompletionError e) {
            this.respondCompletionError(e);
        } finally {
            ((AIGCCellet) this.cellet).returnTask(this);
        }
    }

    private void respondCompletionError(CompletionError error) {
        JSONObject event = new JSONObject().put("requestId", this.getRequest().data.optString("requestId"))
                .put("sequence", 1).put("kind", "error").put("payload", new JSONObject().put("error", error.toJSON()));
        this.cellet.speak(this.talkContext, this.makeResponse(new Packet(AIGCAction.ChatCompletionEvent.name, event).toDialect()));
    }
}
