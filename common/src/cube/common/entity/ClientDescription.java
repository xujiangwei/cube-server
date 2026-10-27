/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 客户端描述。
 */
public class ClientDescription extends Entity {

    private String name;

    private String password;

    private ClientState state;

    private Contact pretender;

    public ClientDescription(String name, String password) {
        this.name = name;
        this.password = password;
        this.state = ClientState.Normal;
    }

    public ClientDescription(String name, String password, ClientState state) {
        this.name = name;
        this.password = password;
        this.state = state;
    }

    public ClientDescription(JSONObject json) {
        this.name = json.getString("name");
        this.password = json.has("password") ? json.getString("password") : null;
        this.state = ClientState.parse(json.getInt("state"));

        if (json.has("pretender")) {
            this.pretender = new Contact(json.getJSONObject("pretender"));
        }
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取密码。
     *
     * @return 密码。
     */
    public String getPassword() {
        return this.password;
    }

    /**
     * 获取状态。
     *
     * @return 状态。
     */
    public ClientState getState() {
        return this.state;
    }

    /**
     * 获取冒充者。
     *
     * @return 冒充者。
     */
    public Contact getPretender() {
        return this.pretender;
    }

    /**
     * 设置冒充者。
     *
     * @param pretender 冒充者。
     */
    public void setPretender(Contact pretender) {
        this.pretender = pretender;
    }

    /**
     * 输出便于日志排查的摘要。
     *
     * @return 摘要字符串。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("(");
        buf.append(this.name);
        if (null != this.pretender) {
            buf.append("|");
            buf.append(this.pretender.getId());
        }
        buf.append(")");
        return buf.toString();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();
        json.put("password", this.password);
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
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        json.put("state", this.state.code);
        if (null != this.pretender) {
            json.put("pretender", this.pretender.toCompactJSON());
        }
        return json;
    }
}
