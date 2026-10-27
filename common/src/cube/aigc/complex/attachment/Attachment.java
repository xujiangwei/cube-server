/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.complex.attachment;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 内容附件。
 */
public abstract class Attachment implements JSONable {

    /** 附件 ID 。 */
    protected long id;

    /** 附件类型，对应各子类的 {@code TYPE} 常量。 */
    protected String type;

    public Attachment(String type) {
        this.id = Utils.generateSerialNumber();
        this.type = type;
    }

    public Attachment(String type, long id) {
        this.id = id;
        this.type = type;
    }

    public Attachment(JSONObject json) {
        this.id = json.getLong("id");
        this.type = json.getString("type");
    }

    /**
     * 获取附件 ID 。
     *
     * @return 附件 ID 。
     */
    public long getId() {
        return this.id;
    }

    /**
     * 获取附件类型。
     *
     * @return 类型标识。
     */
    public String getType() {
        return this.type;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("type", this.type);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
