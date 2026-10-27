/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import cube.file.misc.MediaAttribute;
import org.json.JSONObject;

import java.io.File;

/**
 * 处理结果流描述。
 */
public class FileResult implements JSONable {

    /** 文件。 */
    public File file;

    /** 完整路径。 */
    public String fullPath;

    /** 流名称。 */
    public String streamName;

    /** 文件名。 */
    public String fileName;

    /** 文件大小。 */
    public long fileSize;

    /** 媒体属性。 */
    public MediaAttribute mediaAttribute;

    public FileResult(File file) {
        this.file = file;
        this.fullPath = file.getAbsolutePath();
        this.fileName = file.getName();
        this.fileSize = file.length();
        this.streamName = this.fileName;
    }

    public FileResult(JSONObject json) {
        this.fullPath = json.getString("fullPath");
        this.streamName = json.getString("streamName");
        this.fileName = json.getString("fileName");
        this.fileSize = json.getLong("fileSize");
        this.file = new File(this.fullPath);

        if (json.has("mediaAttribute")) {
            this.mediaAttribute = new MediaAttribute(json.getJSONObject("mediaAttribute"));
        }
    }

    /**
     * 重置文件。
     *
     * @param file 文件。
     */
    public void resetFile(File file) {
        this.file = file;
        this.fullPath = file.getAbsolutePath();
        this.fileName = file.getName();
        this.fileSize = file.length();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("fullPath", this.fullPath);
        json.put("streamName", this.streamName);
        json.put("fileName", this.fileName);
        json.put("fileSize", this.fileSize);

        if (null != this.mediaAttribute) {
            json.put("mediaAttribute", this.mediaAttribute.toJSON());
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
