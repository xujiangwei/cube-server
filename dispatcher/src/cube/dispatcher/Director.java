/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher;

import cell.api.Speakable;
import cell.core.net.Endpoint;

/**
 * 导演机。
 */
public class Director {

    public final Endpoint endpoint;

    public final Endpoint fileEndpoint;

    public final Scope scope;

    public volatile Speakable speaker;

    public Director(Endpoint endpoint, Endpoint fileEndpoint, Scope scope) {
        this.endpoint = endpoint;
        this.fileEndpoint = fileEndpoint;
        this.scope = scope;
    }

    public boolean supports(String celletName) {
        return this.scope.cellets.contains(celletName);
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof Director) {
            Director other = (Director) object;
            return this.endpoint.equals(other.endpoint);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return this.endpoint.hashCode();
    }
}
