/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.robodroid.handler;

import cube.dispatcher.Performer;
import cube.util.CrossDomainHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Robodroid HTTP handler
 */
public abstract class RobodroidHandler extends CrossDomainHandler {

    /** 执行机，用于把请求转发给机器人单元。 */
    protected Performer performer;

    public RobodroidHandler(Performer performer) {
        super();
        this.performer = performer;
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

        return path.substring(1).trim();
    }

    /**
     * 转发请求并回写结果。
     *
     * <p>向机器人单元同步发送一次空载荷请求，失败时按错误响应处理。</p>
     *
     * @param response 响应对象。
     */
    protected void syncTransmit(HttpServletResponse response) {

    }
}
