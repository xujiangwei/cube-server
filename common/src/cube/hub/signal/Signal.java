/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cell.util.Utils;
import cube.common.JSONable;
import cube.common.entity.ClientDescription;
import cube.hub.event.Event;
import org.json.JSONObject;

/**
 * 信号。
 */
public abstract class Signal implements JSONable {

    private long sn;

    private String code;

    private final String name;

    private ClientDescription description;

    /** 事件。 */
    public Event event;

    public Signal(String name) {
        this.sn = Utils.generateSerialNumber();
        this.name = name;
    }

    public Signal(String name, ClientDescription description) {
        this.sn = Utils.generateSerialNumber();
        this.name = name;
        this.description = description;
    }

    public Signal(JSONObject json) {
        this.sn = json.getLong("sn");
        this.name = json.getString("name");

        if (json.has("code")) {
            this.code = json.getString("code");
        }

        if (json.has("description")) {
            this.description = new ClientDescription(json.getJSONObject("description"));
        }
    }

    /**
     * 获取信号序列号。
     *
     * @return 序列号。
     */
    public long getSerialNumber() {
        return this.sn;
    }

    /**
     * 设置码。
     *
     * @param code 码。
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * 获取码。
     *
     * @return 码。
     */
    public String getCode() {
        return this.code;
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
     * 设置描述。
     *
     * @param description 描述。
     */
    public void setDescription(ClientDescription description) {
        this.description = description;
    }

    /**
     * 获取描述。
     *
     * @return 描述。
     */
    public ClientDescription getDescription() {
        return this.description;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("name", this.name);

        if (null != this.code) {
            json.put("code", this.code);
        }

        if (null != this.description) {
            json.put("description", this.description.toCompactJSON());
        }

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
        return this.toJSON();
    }
}
