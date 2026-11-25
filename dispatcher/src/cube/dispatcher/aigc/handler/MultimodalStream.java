/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc.handler;

import cell.util.log.Logger;
import cube.common.Packet;
import cube.common.entity.MultimodalInput;
import cube.common.entity.MultimodalOutput;
import cube.common.state.AIGCStateCode;
import cube.dispatcher.aigc.CapabilityUnavailableException;
import cube.dispatcher.aigc.Manager;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * 多模态流式调用。
 */
public class MultimodalStream extends ContextHandler {

    public MultimodalStream() {
        super("/multimodal/stream/");
        setHandler(new Handler());
    }

    private static class Handler extends AIGCHandler {

        public Handler() {
            super();
        }

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) {
            Request.getBaseRequest(request).setHandled(true);
            String token = this.getApiToken(request);
            if (!Manager.getInstance().checkToken(token, this.getDevice(request))) {
                this.respondError(response, AIGCStateCode.NoToken, null);
                return;
            }

            String channelCode;
            MultimodalInput multimodalInput;
            try {
                JSONObject json = this.readBodyAsJSONObject(request);
                channelCode = json.getString("channel");
                multimodalInput = new MultimodalInput(json.getJSONObject("input"));
            } catch (Exception e) {
                Logger.w(MultimodalStream.class, "#doPost - Invalid request body");
                this.respondError(response, AIGCStateCode.InvalidParameter, null);
                return;
            }

            if (channelCode.trim().isEmpty()) {
                this.respondError(response, AIGCStateCode.InvalidParameter, null);
                return;
            }

            String reservedStreamId = "OmniVL".equals(multimodalInput.unit) && multimodalInput.hasRtspSource()
                    && !multimodalInput.isStreamControl() ? UUID.randomUUID().toString() : null;
            try {
                Packet reply = Manager.getInstance().executeMultimodalStream(token, channelCode, multimodalInput,
                        reservedStreamId);
                AIGCStateCode code = reply == null ? AIGCStateCode.Timeout
                        : AIGCStateCode.parse(Packet.extractCode(reply));
                if (code != AIGCStateCode.Ok) {
                    this.respondError(response, code, reservedStreamId);
                    return;
                }
                this.respondOk(response, new MultimodalOutput(Packet.extractDataPayload(reply)).toCompactJSON());
            } catch (CapabilityUnavailableException unavailable) {
                this.respondError(response, AIGCStateCode.UnitNoReady, null);
            } catch (org.json.JSONException invalidReply) {
                Logger.w(MultimodalStream.class, "#doPost - Invalid upstream response");
                this.respondError(response, AIGCStateCode.UnitError, null);
            }
        }

        private void respondError(HttpServletResponse response, AIGCStateCode code, String reservedStreamId) {
            int status;
            switch (code) {
                case NoToken: status = HttpStatus.UNAUTHORIZED_401; break;
                case InconsistentToken: status = HttpStatus.FORBIDDEN_403; break;
                case NotFound: status = HttpStatus.NOT_FOUND_404; break;
                case Processing:
                case Busy:
                case UnitNoReady:
                case UsageUnavailable: status = HttpStatus.SERVICE_UNAVAILABLE_503; break;
                case Timeout: status = HttpStatus.GATEWAY_TIMEOUT_504; break;
                case InvalidParameter:
                case DataStructureError:
                case InvalidData:
                case FileError:
                case IllegalOperation: status = HttpStatus.BAD_REQUEST_400; break;
                default: status = HttpStatus.BAD_GATEWAY_502;
            }
            JSONObject error = new JSONObject().put("state", status).put("code", code.code)
                    .put("message", code.name());
            if (code == AIGCStateCode.Timeout && reservedStreamId != null) {
                error.put("streamId", reservedStreamId);
            }
            this.respond(response, status, new JSONObject().put("error", error));
        }

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response) {
            Request.getBaseRequest(request).setHandled(true);
            String token = this.getApiToken(request);
            if (!Manager.getInstance().checkToken(token, this.getDevice(request))) {
                this.respond(response, HttpStatus.UNAUTHORIZED_401);
                return;
            }

            try {
                String channel = request.getParameter("channel");
                String strSN = request.getParameter("sn");
                long sn = Long.parseLong(strSN);

                MultimodalOutput multimodalOutput = Manager.getInstance().queryMultimodal(token, channel, sn);
                if (null == multimodalOutput) {
                    this.respond(response, HttpStatus.NOT_FOUND_404);
                    return;
                }

                this.respondOk(response, multimodalOutput.toCompactJSON());
            } catch (Exception e) {
                this.respond(response, HttpStatus.BAD_REQUEST_400);
            }
        }
    }
}
