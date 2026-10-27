/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.task;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.auth.AuthToken;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.entity.Membership;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;
import org.json.JSONObject;

/**
 * 应用层激活会员任务。
 */
public class AppActivateMembershipTask extends ServiceTask {

    /** 构造任务实例，参数由单元统一注入。 */
    public AppActivateMembershipTask(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime) {
        super(cellet, talkContext, primitive, responseTime);
    }

    /**
     * 执行任务。
     *
     * <p>解析请求载荷后转交服务层处理，并把结果回写客户端。</p>
     */
    @Override
    public void run() {
        ActionDialect dialect = new ActionDialect(this.primitive);
        Packet packet = new Packet(dialect);

        String tokenCode = this.getTokenCode(dialect);
        if (null == tokenCode) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.NoToken.code, packet.data));
            markResponseTime();
            return;
        }

        AIGCService service = ((AIGCCellet) this.cellet).getService();
        AuthToken token = service.getToken(tokenCode);
        if (null == token) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InconsistentToken.code, packet.data));
            markResponseTime();
            return;
        }

        try {
            String channel = packet.data.getString("channel");
            String invitation = packet.data.getString("invitation");

            Membership membership = service.activateMembership(token, channel, invitation);
            if (null == membership) {
                Logger.w(this.getClass(), "#run - Failed: " + invitation);
                this.cellet.speak(this.talkContext,
                        this.makeResponse(dialect, packet, AIGCStateCode.Failure.code, packet.data));
                markResponseTime();
                return;
            }

            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.Ok.code, membership.toCompactJSON()));
            markResponseTime();
        } catch (Exception e) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
        }
    }
}
