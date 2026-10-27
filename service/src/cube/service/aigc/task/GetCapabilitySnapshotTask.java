/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.task;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;

/**
 * Internal metadata query. No user token or inference operation is needed.
 */
public final class GetCapabilitySnapshotTask extends ServiceTask {
    /** 构造任务实例，参数由单元统一注入。 */
    public GetCapabilitySnapshotTask(AIGCCellet cellet, TalkContext context, Primitive primitive, ResponseTime time) {
        super(cellet, context, primitive, time);
    }

    /**
     * 执行任务。
     *
     * <p>解析请求载荷后转交服务层处理，并把结果回写客户端。</p>
     */
    @Override
    public void run() {
        ActionDialect request = new ActionDialect(this.primitive);
        this.cellet.speak(this.talkContext, this.makeResponse(request, new Packet(request),
                AIGCStateCode.Ok.code, ((AIGCCellet) this.cellet).getService().getCapabilitySnapshot()));
        this.markResponseTime();
    }
}
