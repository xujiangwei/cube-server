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
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.AIGCService;
import org.json.JSONObject;

/**
 * 评估回答。
 */
public class EvaluateTask extends ServiceTask {

    /** 构造任务实例，参数由单元统一注入。 */
    public EvaluateTask(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime) {
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
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
            return;
        }

        if (!packet.data.has("sn") || !packet.data.has("feedback")) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
            return;
        }

        long sn = packet.data.getLong("sn");
        int feedback = packet.data.getInt("feedback");

        AIGCService service = ((AIGCCellet) this.cellet).getService();
        service.evaluate(token, sn, feedback);

        this.cellet.speak(this.talkContext,
                this.makeResponse(dialect, packet, AIGCStateCode.Ok.code, packet.data));
        markResponseTime();
    }
}
