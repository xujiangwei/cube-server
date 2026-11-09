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

/** Detection readiness is updated independently of setup and plugin status. */
public final class SpecializedDetectionStatusTask extends ServiceTask {
    public SpecializedDetectionStatusTask(AIGCCellet cellet, TalkContext context, Primitive primitive, ResponseTime time) {
        super(cellet, context, primitive, time);
    }
    @Override public void run() {
        ActionDialect request = new ActionDialect(this.primitive);
        Packet packet = new Packet(request);
        boolean accepted = ((AIGCCellet) this.cellet).getService().getSpecializedDetections().report(this.talkContext, packet.data);
        this.cellet.speak(this.talkContext, this.makeResponse(request, packet,
                accepted ? AIGCStateCode.Ok.code : AIGCStateCode.InvalidParameter.code, new JSONObject()));
        this.markResponseTime();
    }
}
