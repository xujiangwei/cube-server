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
import cube.service.aigc.knowledge.KnowledgeBase;
import cube.service.aigc.knowledge.KnowledgeFramework;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除知识库文章任务。
 */
public class RemoveKnowledgeArticleTask extends ServiceTask {

    /** 构造任务实例，参数由单元统一注入。 */
    public RemoveKnowledgeArticleTask(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime) {
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
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
            return;
        }

        if (!packet.data.has("ids")) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
            return;
        }

        String baseName = KnowledgeFramework.DefaultName;
        if (packet.data.has("base")) {
            baseName = packet.data.getString("base");
        }

        AIGCService service = ((AIGCCellet) this.cellet).getService();
        KnowledgeBase base = service.getKnowledgeBase(tokenCode, baseName);
        if (null == base) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InconsistentToken.code, new JSONObject()));
            markResponseTime();
            return;
        }

        List<Long> ids = new ArrayList<>();
        try {
            JSONArray array = packet.data.getJSONArray("ids");
            for (int i = 0; i < array.length(); ++i) {
                ids.add(array.getLong(i));
            }
            // 批量删除
            ids = base.removeKnowledgeArticles(ids);
        } catch (Exception e) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.Failure.code, new JSONObject()));
            markResponseTime();
            return;
        }

        if (null == ids) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.Failure.code, new JSONObject()));
            markResponseTime();
            return;
        }

        JSONArray idArray = new JSONArray();
        for (Long id : ids) {
            idArray.put(id.longValue());
        }

        JSONObject payload = new JSONObject();
        payload.put("ids", idArray);
        payload.put("total", ids.size());

        this.cellet.speak(this.talkContext,
                this.makeResponse(dialect, packet, AIGCStateCode.Ok.code, payload));
        markResponseTime();
    }
}
