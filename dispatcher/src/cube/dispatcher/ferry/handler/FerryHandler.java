/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.ferry.handler;

import cube.common.entity.Device;
import cube.util.CrossDomainHandler;
import cube.util.HttpHandler;
import org.eclipse.jetty.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * Ferry REST 接口基类。
 */
public abstract class FerryHandler extends CrossDomainHandler {

    public FerryHandler() {
        super();
    }

    /**
     * 解析访问令牌。
     *
     * <p>按优先级依次尝试：路径最后一段（长度不少于 32）、请求头、查询参数。</p>
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
     * <p>从请求头读取设备名与平台，缺失时填 {@code Unknown} 。</p>
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
     * <p>浏览器直接访问接口地址时返回提示页，避免用户看到裸 JSON。</p>
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
