/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.cv.handler;

import cube.util.CrossDomainHandler;

import javax.servlet.http.HttpServletRequest;

/**
 * CV RESTful 接口基类。
 */
public abstract class CVHandler extends CrossDomainHandler {

    public CVHandler() {
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
                token = request.getHeader("x-baize-api-token").trim();
            } catch (Exception e) {
                // Nothing
            }
        }
        return token;
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
}
