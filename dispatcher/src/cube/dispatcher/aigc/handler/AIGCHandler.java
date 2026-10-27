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

    /**
     * 统一入口，兜底处理能力不可用。
     *
     * <p>把 {@code CapabilityUnavailableException} 转换为 503 响应，
     * 避免异常向上冒泡导致连接被直接断开、客户端拿不到结构化错误。</p>
     */
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

    /**
     * 输出能力不可用响应。
     *
     * <p>固定返回 503 与 {@code CAPABILITY_UNAVAILABLE} ，并带出底层异常信息便于定位。</p>
     *
     * @param response 响应对象。
     * @param e        触发不可用的异常。
     */
    protected void respondCapabilityUnavailable(HttpServletResponse response, RuntimeException e) {
        this.respond(response, 503, new org.json.JSONObject().put("error", new org.json.JSONObject()
                .put("state", 503).put("code", cube.common.state.AIGCStateCode.UnitNoReady.code)
                .put("reason", "CAPABILITY_UNAVAILABLE").put("message", e.getMessage())));
        this.complete();
    }

    public AIGCHandler() {
        super();
    }

    /**
     * 解析访问令牌。
     *
     * <p>按优先级依次尝试：路径最后一段（长度不少于 32）、请求头、查询参数。
     * 路径优先是为了兼容把令牌直接拼在 URL 里的旧客户端。</p>
     *
     * @param request HTTP 请求。
     * @return 令牌；都取不到时返回 {@code null} 。
     */
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

    /**
     * 解析设备信息。
     *
     * <p>从请求头读取设备名与平台，缺失时统一填 {@code Unknown} ，保证下游不为空。</p>
     *
     * @param request HTTP 请求。
     * @return 设备信息。
     */
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

    /**
     * 获取请求路径。
     *
     * @param request HTTP 请求。
     * @return 去掉上下文前缀后的路径。
     */
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

    /**
     * 获取路径的最后一段。
     *
     * <p>常用于从形如 {@code /aigc/chat/<token>} 的地址中取出令牌。</p>
     *
     * @param request HTTP 请求。
     * @return 最后一段路径；无路径信息时返回 {@code null} 。
     */
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

    /**
     * 输出自动刷新页。
     *
     * <p>用于浏览器直接访问接口地址的场景：返回一个提示页而不是裸 JSON，
     * 避免用户看到无意义的报错。</p>
     *
     * @param response 响应对象。
     */
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
