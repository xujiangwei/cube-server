/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 文件锚点。
 */
public class FileAnchor implements JSONable {

    /** 文件码。 */
    public final String fileCode;

    /** 文件名。 */
    public final String fileName;

    /** 文件大小。 */
    public final long fileSize;

    /** 最后修改时间。 */
    public final long lastModified;

    /** 位置。 */
    public final long position;

    public FileAnchor(JSONObject json) {
        this.fileCode = json.getString("fileCode");
        this.fileName = json.getString("fileName");
        this.fileSize = json.getLong("fileSize");
        this.lastModified = json.getLong("lastModified");
        this.position = json.getLong("position");
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("fileCode", this.fileCode);
        json.put("fileName", this.fileName);
        json.put("fileSize", this.fileSize);
        json.put("lastModified", this.lastModified);
        json.put("position", this.position);
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
