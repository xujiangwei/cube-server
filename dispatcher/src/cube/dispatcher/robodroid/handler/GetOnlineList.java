/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.robodroid.handler;

import cell.core.talk.dialect.ActionDialect;
import cube.dispatcher.Performer;
import cube.dispatcher.robodroid.RobodroidCellet;
import cube.robodroid.RobodroidAction;
import cube.robodroid.RobodroidStateCode;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 获取在线设备列表。
 */
public class GetOnlineList extends ContextHandler {

    public GetOnlineList(Performer performer) {
        super("/robodroid/online/");
        setHandler(new Handler(performer));
    }

    private class Handler extends RobodroidHandler {

        public Handler(Performer performer) {
            super(performer);
        }

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String code = this.getRequestPath(request);
            if (null == code || code.length() < 8) {
                this.respond(response, HttpStatus.UNAUTHORIZED_401);
                this.complete();
                return;
            }

            ActionDialect actionDialect = new ActionDialect(RobodroidAction.GetOnlineList.name);
            ActionDialect responseDialect = this.performer.syncTransmit(RobodroidCellet.NAME, actionDialect);
            if (null == responseDialect) {
                this.respond(response, HttpStatus.BAD_REQUEST_400);
                this.complete();
                return;
            }

            int stateCode = responseDialect.getParamAsInt("code");
            if (stateCode != RobodroidStateCode.Ok.code) {
                this.respond(response, HttpStatus.BAD_REQUEST_400);
                this.complete();
                return;
            }

            JSONObject data = responseDialect.getParamAsJson("data");
            this.respondOk(response, data);
            this.complete();
        }
    }
}
