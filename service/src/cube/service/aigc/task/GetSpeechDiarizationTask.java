/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.aigc.task;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.auth.AuthToken;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.entity.VoiceDiarization;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;

/**
 * 删除说话者分析数据。
 */
public class GetSpeechDiarizationTask extends ServiceTask {

    /** 构造任务实例，参数由单元统一注入。 */
    public GetSpeechDiarizationTask(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime) {
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

        String token = this.getTokenCode(dialect);
        if (null == token) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.NoToken.code, packet.data));
            markResponseTime();
            return;
        }

        String fileCode = packet.data.getString("fileCode");
        if (null == fileCode) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, packet.data));
            markResponseTime();
            return;
        }

        AIGCService service = ((AIGCCellet) this.cellet).getService();
        AuthToken authToken = service.getToken(token);
        // 获取数据
        VoiceDiarization voiceDiarization = service.getVoiceDiarization(authToken, fileCode);
        if (null == voiceDiarization) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.NoData.code, packet.data));
            markResponseTime();
            return;
        }

        this.cellet.speak(this.talkContext,
                this.makeResponse(dialect, packet, AIGCStateCode.Ok.code, voiceDiarization.toJSON()));
        markResponseTime();
    }
}
