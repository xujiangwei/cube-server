/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.riskmgmt;

import cube.util.CrossDomainHandler;

import javax.servlet.http.HttpServletRequest;

/**
 * RESTful 接口基类。
 */
public abstract class RiskManagementHandler extends CrossDomainHandler {

    public RiskManagementHandler() {
        super();
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
