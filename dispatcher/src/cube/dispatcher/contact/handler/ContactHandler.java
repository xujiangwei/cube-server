/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.contact.handler;

import cube.common.entity.Device;
import cube.dispatcher.Performer;
import cube.util.CrossDomainHandler;
import cube.util.HttpHandler;

import javax.servlet.http.HttpServletRequest;

/**
 * RESTful 接口基类。
 */
public abstract class ContactHandler extends CrossDomainHandler {

    protected final Performer performer;

    public ContactHandler(Performer performer) {
        super();
        this.performer = performer;
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
     * <p>常用于从形如 {@code /contact/<action>/<token>} 的地址中取出令牌。</p>
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
        return path.trim();
    }
}
