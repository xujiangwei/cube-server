/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.widget;

import cube.aigc.complex.attachment.Attachment;
import cube.common.JSONable;
import cube.common.entity.AttachmentResource;
import cube.common.entity.ComplexResource;
import org.json.JSONObject;

/**
 * 事件描述。
 */
public class Event implements JSONable {

    /** 关联资源的序列号。 */
    public long resourceSn;

    /** 附件 ID 。 */
    public long attachmentId;

    /** 触发事件的控件 ID 。 */
    public long componentId;

    /** 事件名。 */
    public String name;

    /** 事件参数。 */
    public JSONObject parameter;

    /** 事件所属的资源。 */
    public AttachmentResource resource;

    /** 事件关联的附件。 */
    public Attachment attachment;

    /** 事件作用的控件。 */
    public Widget target;

    /** 事件处理产生的资源结果。 */
    public ComplexResource resultResource;

    public Event() {
    }

    public Event(JSONObject json) {
        this.resourceSn = json.getLong("resourceSn");
        this.attachmentId = json.getLong("attachmentId");
        this.componentId = json.getLong("componentId");
        this.name = json.getString("name");
        if (json.has("parameter")) {
            this.parameter = json.getJSONObject("parameter");
        }
    }

    /**
     * 以指定资源结束事件。
     *
     * <p>填充结果资源后由调用方序列化下发，重复调用以最后一次为准。</p>
     *
     * @param resource 结果资源。
     */
    public void finish(ComplexResource resource) {
        this.resultResource = resource;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("resourceSn", this.resourceSn);
        json.put("attachmentId", this.attachmentId);
        json.put("componentId", this.componentId);
        json.put("name", this.name);
        if (null != this.parameter) {
            json.put("parameter", this.parameter);
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        if (json.has("parameter")) {
            json.remove("parameter");
        }
        return json;
    }
}
