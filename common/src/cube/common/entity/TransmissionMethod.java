/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 传输方式。
 */
public class TransmissionMethod extends Entity {

    /**
     * 消息实体。
     */
    private Message message;

    /**
     * 传输目标。
     */
    private AbstractContact target;

    /**
     * 分享标签。
     */
    private SharingTag sharingTag;

    /**
     * 操作时使用的设备。
     */
    private Device device;

    public TransmissionMethod(Message message, AbstractContact target, Device device) {
        super();
        this.message = message;
        this.target = target;
        this.device = device;
    }

    public TransmissionMethod(SharingTag sharingTag) {
        super();
        this.sharingTag = sharingTag;
    }

    public TransmissionMethod(JSONObject json) {
        super();

        if (json.has("message")) {
            this.message = new Message(json.getJSONObject("message"));
        }

        if (json.has("target")) {
            JSONObject data = json.getJSONObject("target");
            this.target = ContactHelper.create(data);
        }

        if (json.has("device")) {
            this.device = new Device(json.getJSONObject("device"));
        }

        if (json.has("sharingTag")) {
            this.sharingTag = new SharingTag(json.getJSONObject("sharingTag"));
        }
    }

    /**
     * 获取消息。
     *
     * @return 消息。
     */
    public Message getMessage() {
        return this.message;
    }

    /**
     * 获取目标。
     *
     * @return 目标。
     */
    public AbstractContact getTarget() {
        return this.target;
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
     * 获取分享标签。
     *
     * @return 分享标签。
     */
    public SharingTag getSharingTag() {
        return this.sharingTag;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (null != this.message) {
            json.put("message", this.message.toJSON());
        }
        if (null != this.target) {
            json.put("target", this.target.toJSON());
        }
        if (null != this.device) {
            json.put("device", this.device.toJSON());
        }
        if (null != this.sharingTag) {
            json.put("sharingTag", this.sharingTag.toCompactJSON());
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
