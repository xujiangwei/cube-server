/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.hub.handler;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.dispatcher.Performer;
import cube.dispatcher.hub.Controller;
import cube.dispatcher.hub.HubCellet;
import cube.hub.EventBuilder;
import cube.hub.HubAction;
import cube.hub.HubStateCode;
import cube.hub.event.Event;
import cube.hub.signal.Signal;
import cube.util.CrossDomainHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Hub HTTP handler
 */
public abstract class HubHandler extends CrossDomainHandler {

    /** 执行机，用于把请求转发给 Hub 单元。 */
    protected Performer performer;

    /** Hub 控制器，提供通道与会话状态。 */
    protected Controller controller;

    public HubHandler(Performer performer, Controller controller) {
        super();
        this.performer = performer;
        this.controller = controller;
    }

    /**
     * 获取请求路径。
     *
     * @param request HTTP 请求。
     * @return 去掉上下文前缀后的路径。
     */
    protected String getRequestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path.substring(1).trim();
    }

    /**
     * 把信令同步转发到后端并返回事件。
     *
     * <p>失败时直接向响应写入错误并返回 {@code null} ，调用方无需再处理异常。</p>
     *
     * @param response 响应对象，失败时用于输出错误。
     * @param signal   待转发的信令。
     * @return 后端返回的事件；失败返回 {@code null} 。
     */
    protected Event syncTransmit(HttpServletResponse response, Signal signal) {
        ActionDialect actionDialect = new ActionDialect(HubAction.Channel.name);
        actionDialect.addParam("signal", signal.toJSON());

        ActionDialect result = this.performer.syncTransmit(HubCellet.NAME, actionDialect, 60 * 1000);
        if (null == result) {
            response.setStatus(HttpStatus.FORBIDDEN_403);
            return null;
        }

        int stateCode = result.getParamAsInt("code");
        if (HubStateCode.Ok.code != stateCode) {
            Logger.w(this.getClass(), "#syncTransmit - state : " + stateCode);
            JSONObject data = new JSONObject();
            data.put("code", stateCode);
            this.respond(response, HttpStatus.UNAUTHORIZED_401, data);
            return null;
        }

        if (result.containsParam("event")) {
            return EventBuilder.build(result.getParamAsJson("event"));
        }
        else {
            JSONObject data = new JSONObject();
            data.put("code", HubStateCode.Failure.code);
            this.respond(response, HttpStatus.NOT_FOUND_404, data);
            return null;
        }
    }
}
