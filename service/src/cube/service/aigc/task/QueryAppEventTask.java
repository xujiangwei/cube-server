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
import cube.aigc.AppEvent;
import cube.auth.AuthToken;
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
 * 查询应用事件任务。
 */
public class QueryAppEventTask extends ServiceTask {

    /** 构造任务实例，参数由单元统一注入。 */
    public QueryAppEventTask(Cellet cellet, TalkContext talkContext, Primitive primitive, ResponseTime responseTime) {
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
                    this.makeResponse(dialect, packet, AIGCStateCode.NoToken.code, new JSONObject()));
            markResponseTime();
            return;
        }

        AIGCService service = ((AIGCCellet) this.cellet).getService();
        AuthToken token = service.getToken(tokenCode);
        if (null == token) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InconsistentToken.code, new JSONObject()));
            markResponseTime();
            return;
        }

        long contactId = 0;
        String event = null;
        long start = 0;
        long end = 0;
        int page = 0;
        int size = 10;

        try {
            contactId = packet.data.getLong("contactId");
            event = packet.data.getString("event");
            start = packet.data.getLong("start");
            if (packet.data.has("end")) {
                end = packet.data.getLong("end");
            }
            else {
                end = System.currentTimeMillis();
            }
            if (packet.data.has("page")) {
                page = packet.data.getInt("page");
            }
            if (packet.data.has("size")) {
                size = packet.data.getInt("size");
            }
        } catch (Exception e) {
            this.cellet.speak(this.talkContext,
                    this.makeResponse(dialect, packet, AIGCStateCode.InvalidParameter.code, new JSONObject()));
            markResponseTime();
            return;
        }

        JSONArray array = new JSONArray();
        List<AppEvent> list = service.getStorage().readAppEvents(contactId, event, start, end);
        for (int i = size * page; i < size * page + size && i < list.size(); ++i) {
            array.put(list.get(i).toJSON());
        }

        JSONObject data = new JSONObject();
        data.put("contactId", contactId);
        data.put("event", event);
        data.put("start", start);
        data.put("end", end);
        data.put("pageIndex", page);
        data.put("pageSize", size);
        data.put("total", list.size());
        data.put("list", array);

        this.cellet.speak(this.talkContext,
                this.makeResponse(dialect, packet, AIGCStateCode.Ok.code, data));
        markResponseTime();
    }
}
