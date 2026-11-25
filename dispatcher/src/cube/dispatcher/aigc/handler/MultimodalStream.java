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
import org.json.JSONException;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * 多模态流式调用。
 *
 * <p>提供流式请求的提交端点与结果查询端点：提交后由服务单元异步处理，
 * 客户端凭通道码与序号轮询结果。</p>
 */
public class MultimodalStream extends ContextHandler {

    /**
     * 创建多模态流式端点，绑定上下文路径。
     */
    public MultimodalStream() {
        super("/multimodal/stream/");
        setHandler(new Handler());
    }

    /**
     * 处理多模态流式提交与结果查询的 HTTP 处理器。
     */
    private static class Handler extends AIGCHandler {

        /**
         * 创建处理器。
         */
        public Handler() {
            super();
        }

        /**
         * 提交多模态流式请求。
         *
         * <p>校验令牌与请求体后转交管理器处理；RTSP 源的非控制类请求会预先保留流 ID，
         * 便于超时后客户端据此查询结果。</p>
         *
         * @param request  HTTP 请求。
         * @param response HTTP 响应。
         */
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
            } catch (JSONException invalidReply) {
                Logger.w(MultimodalStream.class, "#doPost - Invalid upstream response");
                this.respondError(response, AIGCStateCode.UnitError, null);
            }
        }

        /**
         * 按状态映射 HTTP 码并下发错误响应。
         *
         * <p>超时且已保留流 ID 时，在错误体中附带该 ID 供客户端查询。</p>
         *
         * @param response        HTTP 响应。
         * @param code            状态码。
         * @param reservedStreamId 已保留的流 ID，可为 {@code null} 。
         */
        private void respondError(HttpServletResponse response, AIGCStateCode code, String reservedStreamId) {
            int status;
            switch (code) {
                case NoToken:
                    status = HttpStatus.UNAUTHORIZED_401;
                    break;
                case InconsistentToken:
                    status = HttpStatus.FORBIDDEN_403;
                    break;
                case NotFound:
                    status = HttpStatus.NOT_FOUND_404;
                    break;
                case Processing:
                case Busy:
                case UnitNoReady:
                case UsageUnavailable:
                    status = HttpStatus.SERVICE_UNAVAILABLE_503;
                    break;
                case Timeout:
                    status = HttpStatus.GATEWAY_TIMEOUT_504;
                    break;
                case InvalidParameter:
                case DataStructureError:
                case InvalidData:
                case FileError:
                case IllegalOperation:
                    status = HttpStatus.BAD_REQUEST_400;
                    break;
                default:
                    status = HttpStatus.BAD_GATEWAY_502;
            }
            JSONObject error = new JSONObject().put("state", status).put("code", code.code)
                    .put("message", code.name());
            if (code == AIGCStateCode.Timeout && reservedStreamId != null) {
                error.put("streamId", reservedStreamId);
            }
            this.respond(response, status, new JSONObject().put("error", error));
        }

        /**
         * 按通道码与序号查询已提交请求的结果。
         *
         * @param request  HTTP 请求，须含 {@code channel} 与 {@code sn} 参数。
         * @param response HTTP 响应。
         */
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
