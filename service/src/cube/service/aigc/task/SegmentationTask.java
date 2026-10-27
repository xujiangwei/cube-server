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
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 分词任务。
 */
public class SegmentationTask extends ServiceTask {

    /** 构造任务实例，参数由单元统一注入。 */
    public SegmentationTask(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime) {
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

        if (!packet.data.has("text")) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
            return;
        }

        String text = packet.data.getString("text");

        AIGCService service = ((AIGCCellet) this.cellet).getService();

        long time = System.currentTimeMillis();
        List<String> result = service.segmentText(text);
        long elapsed = System.currentTimeMillis() - time;

        JSONArray array = new JSONArray();
        for (String content : result) {
            array.put(content);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("total", result.size());
        responseData.put("result", array);
        responseData.put("elapsed", elapsed);

        this.cellet.speak(this.talkContext,
                this.makeResponse(dialect, packet, AIGCStateCode.Ok.code, responseData));
        markResponseTime();
    }
}
