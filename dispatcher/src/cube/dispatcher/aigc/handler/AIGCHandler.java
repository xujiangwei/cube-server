/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc.handler;

import cube.common.entity.Device;
import cube.util.CrossDomainHandler;
import cube.util.HttpHandler;
import org.eclipse.jetty.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * AIGC REST 接口基类。
 */
public abstract class AIGCHandler extends CrossDomainHandler {

    @Override
    public void handle(String target, org.eclipse.jetty.server.Request baseRequest,
                       HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException, javax.servlet.ServletException {
        try {
            super.handle(target, baseRequest, request, response);
        } catch (cube.dispatcher.aigc.CapabilityUnavailableException e) {
            respondCapabilityUnavailable(response, e);
        }
    }

    protected void respondCapabilityUnavailable(HttpServletResponse response, RuntimeException e) {
        this.respond(response, 503, new org.json.JSONObject().put("error", new org.json.JSONObject()
                .put("state", 503).put("code", cube.common.state.AIGCStateCode.UnitNoReady.code)
                .put("reason", "CAPABILITY_UNAVAILABLE").put("message", e.getMessage())));
        this.complete();
    }

    public AIGCHandler() {
        super();
    }

    protected String getApiToken(HttpServletRequest request) {
        String token = this.getLastRequestPath(request);
        if (null == token || token.length() < 32) {
            try {
                token = request.getHeader(HttpHandler.HEADER_X_BAIZE_API_TOKEN);
                if (null == token || token.length() < 16) {
                    token = request.getParameter("token");
                }
                if (null != token) {
                    token = token.trim();
                }
            } catch (Exception e) {
                // Nothing
            }
        }
        return token;
    }

    protected Device getDevice(HttpServletRequest request) {
        String deviceName = request.getHeader(HttpHandler.HEADER_X_BAIZE_API_DEVICE);
        String devicePlatform = request.getHeader(HttpHandler.HEADER_X_BAIZE_API_PLATFORM);
        if (null == deviceName) {
            deviceName = "Unknown";
        }
        if (null == devicePlatform) {
            devicePlatform = "Unknown";
        }
        Device device = new Device(deviceName, devicePlatform);
        String address = request.getRemoteAddr();
        if (null != address) {
            device.setAddress(address);
        }
        return device;
    }

    protected String getRequestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path.length() < 2) {
            return null;
        }

        path = path.substring(1).trim();
        int index = path.indexOf("/");
        if (index > 0) {
            path = path.substring(0, index);
        }
        return path;
    }

    protected String getLastRequestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path.length() < 2) {
            return null;
        }

        path = path.substring(1).trim();
        String[] paths = path.split("/");
        path = paths[paths.length - 1];
        return path;
    }

    protected void responseRefreshPage(HttpServletResponse response) {
        String html = "<!DOCTYPE html>\n" +
                "<html>" +
                "<head><meta http-equiv=\"refresh\" content=\"5\"/><title></title></head>" +
                "<body><script type=\"text/javascript\">" +
                "setTimeout(function() {" +
                " window.location.reload(); " +
                "}, 5000);" +
                "</script></body>" +
                "</html>\n";
        response.setContentType("text/html");
        response.setStatus(HttpStatus.OK_200);
        try {
            response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
