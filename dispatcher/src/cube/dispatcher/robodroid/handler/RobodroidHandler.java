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

    protected Performer performer;

    public RobodroidHandler(Performer performer) {
        super();
        this.performer = performer;
    }

    protected String getRequestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path.length() < 2) {
            return null;
        }

        return path.substring(1).trim();
    }

    protected void syncTransmit(HttpServletResponse response) {

    }
}
