/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.robodroid;

import cell.core.talk.dialect.ActionDialect;
import cube.dispatcher.Performer;
import cube.robodroid.RobodroidAction;
import cube.util.CrossDomainHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Roboengine 事件监听处理器。
 */
public class RoboengineEventHandler extends CrossDomainHandler {

    /** 端点路径。 */
    public final static String CONTEXT_PATH = "/robodroid/event/";

    private String token = "kLBNGSmrTbmNlBfTYcIaKqYQiDPKoTkE";

    private Performer performer;

    public RoboengineEventHandler(Performer performer) {
        this.performer = performer;
    }

    /**
     * 接收 Roboengine 的事件推送。
     *
     * <p>校验令牌后把事件转交机器人管理器分发；事件格式错误时返回 403。</p>
     *
     * @param request  HTTP 请求。
     * @param response 响应对象。
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        if (null == token) {
            this.respond(response, HttpStatus.FORBIDDEN_403);
            this.complete();
            return;
        }

        if (!this.token.equals(token)) {
            this.respond(response, HttpStatus.FORBIDDEN_403);
            this.complete();
            return;
        }

        try {
            JSONObject data = this.readBodyAsJSONObject(request);
            String eventName = data.getString("name");
            JSONObject eventData = data.getJSONObject("data");

            this.performer.execute(new Runnable() {
                @Override
                public void run() {
                    Manager.getInstance().callback(eventName, eventData);
                }
            });

            ActionDialect dialect = new ActionDialect(RobodroidAction.Event.name);
            dialect.addParam("name", eventName);
            dialect.addParam("data", eventData);
            this.performer.transmit(RobodroidCellet.NAME, dialect);
        } catch (IOException e) {
            this.respond(response, HttpStatus.BAD_REQUEST_400);
        }

        this.complete();
    }

    private String extractToken(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path.length() < 32) {
            return null;
        }
        return path.substring(1, 33);
    }
}
