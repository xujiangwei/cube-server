/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.contact.handler;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.common.Packet;
import cube.common.action.ContactAction;
import cube.common.state.ContactStateCode;
import cube.dispatcher.Performer;
import cube.dispatcher.contact.ContactCellet;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 新建联系人端点。
 *
 * <p>按提交的资料创建联系人，并返回包含联系人 ID 与初始令牌的结果。</p>
 */
public class NewContact extends ContextHandler {

    public NewContact(Performer performer) {
        super("/contact/new/");
        setHandler(new Handler(performer));
    }

    private class Handler extends ContactHandler {

        public Handler(Performer performer) {
            super(performer);
        }

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) {
            String tokenCode = getLastRequestPath(request);
            if (null == tokenCode) {
                this.respond(response, HttpStatus.FORBIDDEN_403);
                this.complete();
                return;
            }

            try {
                JSONObject data = this.readBodyAsJSONObject(request);

                // 参数校验前置，保证参数错误不会被后续的节点/超时判断掩盖。
                if (!data.has("id")) {
                    this.respond(response, HttpStatus.BAD_REQUEST_400, this.makeError(HttpStatus.BAD_REQUEST_400));
                    this.complete();
                    return;
                }
                try {
                    data.getLong("id");
                } catch (Exception e) {
                    this.respond(response, HttpStatus.BAD_REQUEST_400, this.makeError(HttpStatus.BAD_REQUEST_400));
                    this.complete();
                    return;
                }

                Packet requestPacket = new Packet(ContactAction.NewContact.name, data);
                ActionDialect dialect = requestPacket.toDialect();
                dialect.addParam("token", tokenCode);

                // 无可用节点与服务超时语义不同，分开应答，便于调用方定位与重试。
                if (!this.performer.hasReadyDirector(ContactCellet.NAME, dialect)) {
                    Logger.w(NewContact.class, "#doPost - No ready director for '" + ContactCellet.NAME + "'");
                    this.respond(response, HttpStatus.SERVICE_UNAVAILABLE_503,
                            this.makeError(HttpStatus.SERVICE_UNAVAILABLE_503));
                    this.complete();
                    return;
                }

                ActionDialect responseDialect = this.performer.syncTransmit(ContactCellet.NAME, dialect);
                if (null == responseDialect) {
                    Logger.w(NewContact.class, "#doPost - Backend timeout for '" + ContactCellet.NAME + "'");
                    this.respond(response, HttpStatus.GATEWAY_TIMEOUT_504,
                            this.makeError(HttpStatus.GATEWAY_TIMEOUT_504));
                    this.complete();
                    return;
                }

                Packet responsePacket = new Packet(responseDialect);
                int code = Packet.extractCode(responsePacket);
                if (code == ContactStateCode.InvalidParameter.code) {
                    this.respond(response, HttpStatus.BAD_REQUEST_400, this.makeError(HttpStatus.BAD_REQUEST_400));
                    this.complete();
                    return;
                }
                if (code != ContactStateCode.Ok.code) {
                    this.respond(response, HttpStatus.NOT_FOUND_404, this.makeError(HttpStatus.NOT_FOUND_404));
                    this.complete();
                    return;
                }

                this.respondOk(response, Packet.extractDataPayload(responsePacket));
                this.complete();
            } catch (Exception e) {
                Logger.w(NewContact.class, "#doPost", e);
                this.respond(response, HttpStatus.BAD_REQUEST_400, this.makeError(HttpStatus.BAD_REQUEST_400));
                this.complete();
            }
        }
    }
}
