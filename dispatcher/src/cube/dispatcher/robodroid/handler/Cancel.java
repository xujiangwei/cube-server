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
 * 取消执行。
 */
public class Cancel extends ContextHandler {

    public Cancel(Performer performer) {
        super("/robodroid/cancel/");
        setHandler(new Handler(performer));
    }

    private class Handler extends RobodroidHandler {

        public Handler(Performer performer) {
            super(performer);
        }

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String code = this.getRequestPath(request);
            if (null == code || code.length() < 8) {
                this.respond(response, HttpStatus.UNAUTHORIZED_401);
                this.complete();
                return;
            }

            JSONObject data = this.readBodyAsJSONObject(request);
            if (null == data) {
                this.respond(response, HttpStatus.FORBIDDEN_403);
                this.complete();
                return;
            }

            if (!data.has("accountId") || !data.has("name")) {
                this.respond(response, HttpStatus.FORBIDDEN_403);
                this.complete();
                return;
            }

            long accountId = data.getLong("accountId");
            String name = data.getString("name");

            ActionDialect dialect = new ActionDialect(RobodroidAction.Cancel.name);
            dialect.addParam("accountId", accountId);
            dialect.addParam("name", name);

            ActionDialect responseDialect = this.performer.syncTransmit(RobodroidCellet.NAME, dialect);
            if (null == responseDialect) {
                this.respond(response, HttpStatus.NOT_FOUND_404);
                this.complete();
                return;
            }

            int stateCode = responseDialect.getParamAsInt("code");
            if (stateCode != RobodroidStateCode.Ok.code) {
                this.respond(response, HttpStatus.BAD_REQUEST_400);
                this.complete();
                return;
            }

            data = responseDialect.getParamAsJson("data");
            this.respondOk(response, data);
            this.complete();
        }
    }
}
