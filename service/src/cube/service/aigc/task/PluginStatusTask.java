package cube.service.aigc.task;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import org.json.JSONObject;

/** Readiness reports are bound to already registered units on this connection. */
public final class PluginStatusTask extends ServiceTask {
    /** 构造任务实例，参数由单元统一注入。 */
    public PluginStatusTask(AIGCCellet cellet, TalkContext context, Primitive primitive, ResponseTime time) {
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
        Packet packet = new Packet(request);
        boolean accepted = ((AIGCCellet) this.cellet).getService().getPluginUnits().report(this.talkContext, packet.data);
        this.cellet.speak(this.talkContext, this.makeResponse(request, packet,
                accepted ? AIGCStateCode.Ok.code : AIGCStateCode.InvalidParameter.code, new JSONObject()));
        this.markResponseTime();
    }
}
