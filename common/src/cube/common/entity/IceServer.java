/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * ICE 服务器。
 */
public class IceServer implements JSONable {

    /** 地址。 */
    public final String url;

    /** 用户名。 */
    public final String username;

    /** 凭证。 */
    public final String credential;

    public IceServer(String url, String username, String credential) {
        this.url = url;
        this.username = username;
        this.credential = credential;
    }

    public IceServer(JSONObject json) {
        this.url = json.getString("urls");
        this.username = json.getString("username");
        this.credential = json.getString("credential");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("urls", this.url);
        json.put("username", this.username);
        json.put("credential", this.credential);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return toJSON();
    }
}
