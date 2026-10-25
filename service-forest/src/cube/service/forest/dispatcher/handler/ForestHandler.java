/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.service.forest.dispatcher.handler;

import cube.aigc.spi.PluginCallException;
import cube.common.Packet;
import cube.dispatcher.aigc.CapabilityUnavailableException;
import cube.dispatcher.aigc.Manager;
import cube.dispatcher.aigc.handler.AIGCHandler;
import cube.service.forest.ForestConfig;
import cube.service.forest.ForestModule;
import cube.service.forest.scene.ForestDetectionService;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The dedicated endpoint preserves business errors and performs normal gateway authentication.
 */
public final class ForestHandler extends ContextHandler {
    public ForestHandler(boolean detect) {
        super(detect ? "/aigc/forest/detect" : "/aigc/forest/capability");
        setHandler(new Handler(detect));
    }

    private static final class Handler extends AIGCHandler {
        private final boolean detect;

        Handler(boolean detect) {
            this.detect = detect;
        }

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) {
            if (!detect) {
                fail(response, 405, 1, "Use GET for capability");
                return;
            }
            invoke(request, response);
        }

        @Override
        public void doGet(HttpServletRequest request, HttpServletResponse response) {
            if (detect) {
                fail(response, 405, 1, "Use POST for detection");
                return;
            }
            invoke(request, response);
        }

        private void fail(HttpServletResponse response, int http, int code, String message) {
            respond(response, http, new JSONObject().put("code", code).put("data", new JSONObject().put("message", message)));
            complete();
        }

        private JSONObject readJson(HttpServletRequest request) throws IOException {
            final int limit = 16384;
            if (request.getContentLengthLong() > limit)
                throw new PluginCallException(cube.common.state.AIGCStateCode.ContentLengthOverflow, 413, "Forest request body too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = request.getInputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > limit)
                    throw new PluginCallException(cube.common.state.AIGCStateCode.ContentLengthOverflow, 413, "Forest request body too large");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }

        private void invoke(HttpServletRequest request, HttpServletResponse response) {
            Manager manager = Manager.getInstance();
            String token = getApiToken(request);
            if (!manager.checkToken(token, getDevice(request))) {
                fail(response, 401, 21, "Invalid token or device");
                return;
            }
            try {
                JSONObject body = detect ? readJson(request) : new JSONObject();
                Packet reply = manager.syncModuleRequest(token, ForestModule.NAME,
                        detect ? ForestModule.DETECT : ForestModule.CAPABILITY,
                        detect ? ForestConfig.UNIT : null, body, 90000);
                int code = Packet.extractCode(reply);
                JSONObject payload = Packet.extractDataPayload(reply);
                int http = ForestDetectionService.httpStatus(code);
                if (code != 0 && payload.has("httpStatus")) {
                    int explicit = payload.optInt("httpStatus");
                    if (explicit >= 400 && explicit <= 599) http = explicit;
                }
                respond(response, http, new JSONObject().put("code", code).put("data", payload));
                complete();
            } catch (CapabilityUnavailableException e) {
                fail(response, 503, 30, "Forest capability unavailable");
            } catch (PluginCallException e) {
                fail(response, e.httpStatus, e.state.code, e.getMessage());
            } catch (IllegalArgumentException | org.json.JSONException e) {
                fail(response, 400, 1, "Invalid forest request");
            } catch (Exception e) {
                fail(response, 502, 31, "Forest request failed");
            }
        }
    }
}
