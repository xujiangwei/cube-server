/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.log.Logger;
import cube.common.JSONable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 目标上下文。
 */
public class TargetContext implements JSONable {

    private Contact contact;

    private Device device;

    public TargetContext(Contact contact, Device device) {
        this.contact = contact;
        this.device = device;
    }

    public TargetContext(JSONObject json) {
        try {
            this.contact = new Contact(json.getJSONObject("contact"));
            this.device = new Device(json.getJSONObject("device"));
        } catch (JSONException e) {
            Logger.e(TargetContext.class, "#TargetContext - JSON error", e);
        }
    }

    /**
     * 获取联系人。
     *
     * @return 联系人。
     */
    public Contact getContact() {
        return this.contact;
    }

    /**
     * 获取设备。
     *
     * @return 设备。
     */
    public Device getDevice() {
        return this.device;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        try {
            json.put("contact", this.contact.toBasicJSON());
            json.put("device", this.device.toCompactJSON());
        } catch (JSONException e) {
            Logger.e(TargetContext.class, "#toJSON - JSON error", e);
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
