package cube.service.aigc.task;

import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.common.Packet;
import cube.common.state.AIGCStateCode;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.event.EventCenter;
import cube.service.aigc.unit.MultimodalUnitMeta;
import cube.service.aigc.unit.UnitMeta;
import org.json.JSONObject;

/** Unit-authenticated, synchronous admission and terminal journal acknowledgement. */
public final class UsageSegmentTask extends ServiceTask {
    public UsageSegmentTask(AIGCCellet cellet, TalkContext context, Primitive primitive, cube.benchmark.ResponseTime beginning) {
        super(cellet,context,primitive,beginning);
    }
    @Override public void run() {
        ActionDialect request=new ActionDialect(primitive);Packet packet=new Packet(request);
        JSONObject result=new JSONObject();int code=AIGCStateCode.Ok.code;
        try {
            JSONObject event=packet.data,payload=event.getJSONObject("payload");
            UnitMeta meta=EventCenter.getInstance().getUnitMeta(payload.getString("streamId"));
            if("segmentUsage".equals(event.getString("name"))) {
                // A terminal receipt may arrive after service restart or stream route eviction.
                // Recover its server-stored identity and authenticate the registered physical Unit.
                cube.aigc.usage.UsageWriter writer=((AIGCCellet)cellet).getService().getStorage().getUsageWriter();
                String id=payload.getString("meterId");
                JSONObject call=writer.ledger.transaction(c -> cube.aigc.usage.UsageLedger.one(c,
                        "SELECT unit_id FROM aigc_ai_usage_call WHERE meter_id=?",id));
                boolean authorized=false;
                if(call!=null) for(cube.common.entity.AIGCUnit unit:((AIGCCellet)cellet).getService().getAllUnits())
                    if(talkContext.equals(unit.getContext()) && unit.getQueryKey().equals(call.getString("unit_id"))) authorized=true;
                if(!authorized) throw new SecurityException("INVALID_SEGMENT_UNIT");
                long[] tokens=cube.aigc.usage.UsageTokens.read(payload);
                writer.finish(cube.aigc.usage.UsageLedger.evidence(id,tokens==null?1:2,payload.optString("status","succeeded"),tokens==null?"unknown":"actual",
                        tokens==null?null:tokens[0],tokens==null?null:tokens[1],payload.getLong("timestamp")));
                result.put("accepted",true);
            } else if(!(meta instanceof MultimodalUnitMeta) || !talkContext.equals(meta.unit.getContext())) {
                code=AIGCStateCode.InconsistentToken.code;
            } else {
                JSONObject call=((MultimodalUnitMeta)meta).beginSegment(payload);
                result.put("meterId",call.getString("meter_id")).put("startedAt",call.getLong("started_at"));
            }
        } catch (RuntimeException unavailable) {
            code=AIGCStateCode.UsageUnavailable.code;
            result.put("error","AI_USAGE_TEMPORARILY_UNAVAILABLE");
        }
        cellet.speak(talkContext,makeResponse(request,packet,code,result));markResponseTime();
    }
}
