/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.riskmgmt;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.common.Packet;
import cube.common.action.RiskManagementAction;
import cube.common.entity.ContactRisk;
import cube.common.state.FileStorageStateCode;
import cube.dispatcher.Performer;
import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 联系人风险端点。
 *
 * <p>对外提供联系人风险的查询与评级接口，具体路径由父类统一管理。</p>
 */
public class ContactRiskHandler extends RiskManagementHandler {

    /** 端点路径。 */
    public final static String PATH = "/riskmgmt/contact/risk/";

    private Performer performer;

    public ContactRiskHandler(Performer performer) {
        super();
        this.performer = performer;
    }

    /**
     * 查询联系人风险。
     *
     * <p>按联系人 ID 返回风险等级与命中规则。</p>
     *
     * @param request  HTTP 请求。
     * @param response 响应对象。
     * @throws IOException      读取参数或写响应失败。
     * @throws ServletException 容器处理异常。
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        String token = this.getLastRequestPath(request);

        long contactId = 0;
        try {
            contactId = Long.parseLong(request.getParameter("cid"));
        } catch (Exception e) {
            this.respond(response, HttpStatus.FORBIDDEN_403, new JSONObject());
            this.complete();
            return;
        }

        JSONObject payload = new JSONObject();
        payload.put("contactId", contactId);
        Packet packet = new Packet(RiskManagementAction.GetContactRisk.name, payload);
        ActionDialect packetDialect = packet.toDialect();
        packetDialect.addParam("token", token);

        ActionDialect responseDialect = this.performer.syncTransmit(RiskManagementCellet.NAME, packetDialect);
        if (null == responseDialect) {
            this.respond(response, HttpStatus.BAD_REQUEST_400, packet.toJSON());
            this.complete();
            return;
        }

        Packet responsePacket = new Packet(responseDialect);
        int stateCode = Packet.extractCode(responsePacket);
        if (stateCode != FileStorageStateCode.Ok.code) {
            Logger.w(this.getClass(), "#doGet - Service state code : " + stateCode);
            this.respond(response, HttpStatus.NOT_FOUND_404, responsePacket.toJSON());
            this.complete();
            return;
        }

        int mask = Packet.extractDataPayload(responsePacket).getInt("mask");
        JSONObject responseData = ContactRisk.toJSON(mask);
        responseData.put("mask", mask);
        responseData.put("contactId", contactId);

        this.respondOk(response, responseData);
        this.complete();
    }

    /**
     * 提交联系人风险数据。
     *
     * @param request  HTTP 请求。
     * @param response 响应对象。
     * @throws IOException      读取请求体或写响应失败。
     * @throws ServletException 容器处理异常。
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        String token = this.getLastRequestPath(request);

        long contactId = 0;
        int originalMask = 0;
        int mask = 0;

        try {
            JSONObject data = this.readBodyAsJSONObject(request);
            contactId = data.getLong("contactId");
            originalMask = data.getInt("originalMask");

            if (data.has("mask")) {
                // 如果设置了 mask 则直接操作 mask
                mask = data.getInt("mask");
            }
            else {
                mask = ContactRisk.toMask(originalMask, data);
            }
        } catch (Exception e) {
            this.respond(response, HttpStatus.FORBIDDEN_403, new JSONObject());
            this.complete();
            return;
        }

        JSONObject payload = new JSONObject();
        payload.put("contactId", contactId);
        payload.put("mask", mask);
        Packet packet = new Packet(RiskManagementAction.ModifyContactRisk.name, payload);
        ActionDialect packetDialect = packet.toDialect();
        packetDialect.addParam("token", token);

        ActionDialect responseDialect = this.performer.syncTransmit(RiskManagementCellet.NAME, packetDialect);
        if (null == responseDialect) {
            this.respond(response, HttpStatus.BAD_REQUEST_400, packet.toJSON());
            this.complete();
            return;
        }

        Packet responsePacket = new Packet(responseDialect);
        int stateCode = Packet.extractCode(responsePacket);
        if (stateCode != FileStorageStateCode.Ok.code) {
            Logger.w(this.getClass(), "#doPost - Service state code : " + stateCode);
            this.respond(response, HttpStatus.NOT_FOUND_404, responsePacket.toJSON());
            this.complete();
            return;
        }

        mask = Packet.extractDataPayload(responsePacket).getInt("mask");
        JSONObject responseData = ContactRisk.toJSON(mask);
        responseData.put("mask", mask);
        responseData.put("contactId", contactId);

        this.respondOk(response, responseData);
        this.complete();
    }
}
