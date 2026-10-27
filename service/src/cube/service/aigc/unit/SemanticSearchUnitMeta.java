/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.unit;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.common.Packet;
import cube.common.action.AIGCAction;
import cube.common.entity.AIGCUnit;
import cube.common.entity.QuestionAnswer;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCService;
import cube.aigc.SemanticSearchListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义检索单元任务。
 *
 * <p>把查询向量化后在知识库中做相似度检索，返回匹配片段。</p>
 */
public class SemanticSearchUnitMeta extends UnitMeta {

    private String query;

    private SemanticSearchListener listener;

    public SemanticSearchUnitMeta(AIGCService service, AIGCUnit unit, String query, SemanticSearchListener listener) {
        super(service, unit);
        this.query = query;
        this.listener = listener;
    }

    /**
     * 执行语义检索。
     *
     * <p>组装检索载荷并调用单元，结果按通道回写。</p>
     */
    @Override
    public void process() {
        JSONObject data = new JSONObject();
        data.put("query", this.query);
        Packet request = new Packet(AIGCAction.SemanticSearch.name, data);
        ActionDialect dialect = this.service.getCellet().transmit(this.unit.getContext(), request.toDialect());
        if (null == dialect) {
            Logger.w(AIGCService.class, "The semantic search unit error");
            // 回调错误
            this.listener.onFailed(this.query, AIGCStateCode.UnitError);
            return;
        }

        List<QuestionAnswer> qaList = new ArrayList<>();

        Packet response = new Packet(dialect);
        JSONObject payload = Packet.extractDataPayload(response);
        JSONArray resultList = payload.getJSONArray("result");
        for (int i = 0; i < resultList.length(); ++i) {
            QuestionAnswer qa = new QuestionAnswer(resultList.getJSONObject(i));
            qaList.add(qa);
        }

        this.listener.onCompleted(this.query, qaList);
    }
}
