/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

import cell.core.cellet.Cellet;
import cell.core.talk.Primitive;
import cell.core.talk.TalkContext;
import cell.core.talk.dialect.ActionDialect;
import cell.core.talk.dialect.DialectFactory;
import cube.benchmark.ResponseTime;
import cube.common.Packet;
import cube.common.StateCode;
import cube.common.Task;
import org.json.JSONObject;

/**
 * 调度机线程任务抽象。
 */
public abstract class DispatcherTask extends Task {

    /** 执行机引用，任务通过它访问其他单元与后端连接。 */
    protected Performer performer;

    /**
     * 响应耗时统计。
     *
     * <p>由单元在借出任务时创建，任务完成时回填结束时间；为 {@code null} 表示不统计。</p>
     */
    public ResponseTime responseTime;

    private Packet request;

    private ActionDialect action;

    public DispatcherTask(Cellet cellet, TalkContext talkContext, Primitive primitive, Performer performer) {
        super(cellet, talkContext, primitive);
        this.performer = performer;

        if (null != primitive) {
            this.action = DialectFactory.getInstance().createActionDialect(this.primitive);
            this.request = new Packet(this.action);
        }
    }

    /**
     * 重置任务以复用。
     *
     * <p>任务对象池复用的入口：替换会话与消息，并重新解析动作与请求包。
     * 不重置响应耗时，由调用方在重置后重新打点。</p>
     *
     * @param talkContext 新的会话上下文。
     * @param primitive   新的原始消息。
     */
    public void reset(TalkContext talkContext, Primitive primitive) {
        this.talkContext = talkContext;
        this.primitive = primitive;

        if (null != primitive) {
            this.action = DialectFactory.getInstance().createActionDialect(this.primitive);
            this.request = new Packet(this.action);
        }
    }

    /**
     * 获取本次请求的动作方言。
     *
     * @return 动作方言；构造时未提供消息则为 {@code null} 。
     */
    public ActionDialect getAction() {
        return this.action;
    }

    /**
     * 获取本次请求的数据包。
     *
     * @return 请求包；构造时未提供消息则为 {@code null} 。
     */
    public Packet getRequest() {
        return this.request;
    }

    /**
     * 记录响应结束时间。
     *
     * <p>未启用耗时统计时为空操作。</p>
     */
    public void markResponseTime() {
        if (null != this.responseTime) {
            this.responseTime.ending = System.currentTimeMillis();
        }
    }

    /**
     * 为响应补充成功状态。
     *
     * @param response 待补充的响应方言。
     * @return 同一个响应对象，便于链式调用。
     */
    protected ActionDialect makeResponse(ActionDialect response) {
        response.addParam("state", StateCode.makeState(StateCode.OK, "OK"));
        return response;
    }

    /**
     * 构造带指定状态码的响应。
     *
     * @param payload   响应载荷。
     * @param stateCode 状态码。
     * @param desc      状态描述。
     * @return 组装完成的响应方言。
     */
    protected ActionDialect makeResponse(JSONObject payload, int stateCode, String desc) {
        Packet packet = new Packet(this.request.sn, this.request.name, payload);
        ActionDialect response = packet.toDialect();
        response.addParam("state", StateCode.makeState(stateCode, desc));
        return response;
    }

    /**
     * 构造网关错误响应。
     *
     * <p>用于后端不可达或响应无法解析的情况，统一返回 {@code GatewayError} 。</p>
     *
     * @param response 后端返回的原始包。
     * @return 带网关错误状态的响应方言。
     */
    protected ActionDialect makeGatewayErrorResponse(Packet response) {
        ActionDialect result = response.toDialect();
        result.addParam("state", StateCode.makeState(StateCode.GatewayError, "Gateway error"));
        return result;
    }

    /**
     * 打包为客户端的格式。
     *
     * <p>载荷固定为 {@code {data, code}} 两字段，状态码固定为成功。</p>
     *
     * @param data 业务数据。
     * @param code 业务码，与 HTTP 状态码无关。
     * @return 组装完成的响应方言。
     */
    protected ActionDialect packResponse(JSONObject data, int code) {
        JSONObject payload = new JSONObject();
        payload.put("data", data);
        payload.put("code", code);
        Packet packet = new Packet(this.request.sn, this.request.name, payload);
        ActionDialect response = packet.toDialect();
        response.addParam("state", StateCode.makeState(StateCode.OK, "OK"));
        return response;
    }

    /**
     * 打包为客户端的格式，并指定状态。
     *
     * @param data      业务数据。
     * @param code      业务码，与 HTTP 状态码无关。
     * @param stateCode 状态码。
     * @param stateDesc 状态描述。
     * @return 组装完成的响应方言。
     */
    protected ActionDialect packResponse(JSONObject data, int code, int stateCode, String stateDesc) {
        JSONObject payload = new JSONObject();
        payload.put("data", data);
        payload.put("code", code);
        Packet packet = new Packet(this.request.sn, this.request.name, payload);
        ActionDialect response = packet.toDialect();
        response.addParam("state", StateCode.makeState(stateCode, stateDesc));
        return response;
    }

    /**
     * 标记状态码为成功。
     *
     * @param response 待标记的响应方言。
     * @return 同一个响应对象，便于链式调用。
     */
    public static ActionDialect appendState(ActionDialect response) {
        response.addParam("state", StateCode.makeState(StateCode.OK, "OK"));
        return response;
    }
}
