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

    /** 服务端点，同时作为本对象的相等性标识。 */
    public final Endpoint endpoint;

    /** 文件服务端点。 */
    public final Endpoint fileEndpoint;

    /** 该端点负责的 Cellet 范围。 */
    public final Scope scope;

    /**
     * 与端点通信的会话句柄。
     *
     * <p>连接建立后由调度器回填，连接断开时置空，因此读取前需判空。</p>
     */
    public volatile Speakable speaker;

    public Director(Endpoint endpoint, Endpoint fileEndpoint, Scope scope) {
        this.endpoint = endpoint;
        this.fileEndpoint = fileEndpoint;
        this.scope = scope;
    }

    /**
     * 判断该端点是否承载指定 Cellet 。
     *
     * @param celletName Cellet 名称。
     * @return 在范围内返回 {@code true} 。
     */
    public boolean supports(String celletName) {
        return this.scope.cellets.contains(celletName);
    }

    /**
     * 按端点判断相等。
     *
     * <p>同一端点即视为同一个调度目标，不比较文件端点与会话句柄。</p>
     *
     * @param object 待比较对象。
     * @return 端点相同返回 {@code true} 。
     */
    @Override
    public boolean equals(Object object) {
        if (object instanceof Director) {
            Director other = (Director) object;
            return this.endpoint.equals(other.endpoint);
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 基于端点的哈希值。
     */
    @Override
    public int hashCode() {
        return this.endpoint.hashCode();
    }
}
