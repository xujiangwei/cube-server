package cube.service.aigc.task;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cube.aigc.completion.CompletionError;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.service.ServiceTask;
import cube.service.aigc.AIGCCellet;
import cube.service.aigc.completion.ChatCompletionService;
import org.json.JSONObject;

/**
 * 兼容层生成任务。
 *
 * <p>把单元的生成事件转发给兼容层服务，由后者按 OpenAI/Claude 协议转换后推送给客户端。</p>
 */
public class ChatCompletionTask extends ServiceTask {
    /** 构造任务实例，参数由单元统一注入。 */
    public ChatCompletionTask(Cellet cellet, TalkContext context, Primitive primitive, ResponseTime time) {
        super(cellet, context, primitive, time);
    }

    @Override public void run() {
        ActionDialect dialect = new ActionDialect(primitive);
        Packet packet = new Packet(dialect);
        ChatCompletionService completions = ((AIGCCellet) cellet).getService().getCompletions();
        String token = getTokenCode(dialect);
        try {
            if (AIGCAction.ChatCompletion.name.equals(packet.name)) {
                completions.generate(token, talkContext, dialect);
            }
            else if (AIGCAction.CancelChatCompletion.name.equals(packet.name)) {
                completions.cancel(token, packet.data.optString("requestId"), talkContext);
            }
            else {
                cellet.speak(talkContext, makeResponse(dialect, packet, 0, completions.models(token)));
            }
        } catch (CompletionError error) {
            cellet.speak(talkContext, makeResponse(dialect, packet, 9, new JSONObject().put("error", error.toJSON())));
        } finally { markResponseTime(); }
    }
}
