/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import org.json.JSONObject;

/**
 * 匿名联系人。
 */
public class AnonymousContact extends AbstractContact {

    private Device device;

    public AnonymousContact(String domainName, Device device) {
        super(Utils.generateSerialNumber(), domainName, "");
        this.device = device;
    }

    public AnonymousContact(JSONObject json) {
        super(json, null);
        this.device = new Device(json.getJSONObject("device"));
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
        JSONObject json = super.toJSON();
        json.put("device", this.device.toJSON());
        json.put("type", "anonymous");
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

    /**
     * 判断 JSON 数据结构是否是匿名联系人格式。
     * @param json JSON 数据。
     * @return 处理结果。
     */
    public static boolean isAnonymous(JSONObject json) {
        return (json.has("type") && json.getString("type").equals("anonymous"));
    }
}
