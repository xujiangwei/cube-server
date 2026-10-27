/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import org.json.JSONObject;

/**
 * 信号。
 */
public class Signal extends Entity {

    private Contact destContact;

    private Device destDevice;

    private Contact srcContact;

    private Device srcDevice;

    private JSONObject payload;

    public Signal() {
        super(Utils.generateSerialNumber());
    }

    public Signal(JSONObject json) {
        super(json);

        if (json.has("destContact")) {
            this.destContact = new Contact(json.getJSONObject("destContact"));
        }
        if (json.has("destDevice")) {
            this.destDevice = new Device(json.getJSONObject("destDevice"));
        }
        if (json.has("srcContact")) {
            this.srcContact = new Contact(json.getJSONObject("srcContact"));
        }
        if (json.has("srcDevice")) {
            this.srcDevice = new Device(json.getJSONObject("srcDevice"));
        }

        if (json.has("payload")) {
            this.payload = json.getJSONObject("payload");
        }
    }

    /**
     * 获取目标联系人。
     *
     * @return 目标联系人。
     */
    public Contact getDestContact() {
        return this.destContact;
    }

    /**
     * 设置目标联系人。
     *
     * @param contact 目标联系人。
     */
    public void setDestContact(Contact contact) {
        this.destContact = contact;
    }

    /**
     * 获取目标设备。
     *
     * @return 目标设备。
     */
    public Device getDestDevice() {
        return this.destDevice;
    }

    /**
     * 获取源联系人。
     *
     * @return 源联系人。
     */
    public Contact getSrcContact() {
        return this.srcContact;
    }

    /**
     * 获取源设备。
     *
     * @return 源设备。
     */
    public Device getSrcDevice() {
        return this.srcDevice;
    }

    /**
     * 获取载荷。
     *
     * @return 载荷。
     */
    public JSONObject getPayload() {
        return this.payload;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id.longValue());

        if (null != this.destContact) {
            json.put("destContact", this.destContact.toBasicJSON());
        }

        if (null != this.destDevice) {
            json.put("destDevice", this.destDevice.toCompactJSON());
        }

        if (null != this.srcContact) {
            json.put("srcContact", this.srcContact.toBasicJSON());
        }

        if (null != this.srcDevice) {
            json.put("srcDevice", this.srcDevice.toCompactJSON());
        }

        if (null != this.payload) {
            json.put("payload", this.payload);
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
        JSONObject json = this.toJSON();
        json.remove("payload");
        return json;
    }
}
