/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2020-2024 Ambrose Xu.
 */

package cube.common.entity;

import cube.vision.Size;
import org.json.JSONObject;

/**
 * 已加工的文件。
 */
public class ProcessedFile extends Entity {

    private String fileCode;

    private FileLabel fileLabel;

    private Size size;

    private FileLabel processed;

    public ProcessedFile(JSONObject json) {
        this.fileCode = json.getString("fileCode");
        this.size = new Size(json.getJSONObject("size"));
        this.processed = new FileLabel(json.getJSONObject("processed"));
        if (json.has("fileLabel")) {
            this.fileLabel = new FileLabel(json.getJSONObject("fileLabel"));
        }
    }

    /**
     * 获取文件码。
     *
     * @return 文件码。
     */
    public String getFileCode() {
        return this.fileCode;
    }

    /**
     * 获取大小。
     *
     * @return 大小。
     */
    public Size getSize() {
        return this.size;
    }

    /**
     * 获取是否已处理。
     *
     * @return 是否已处理。
     */
    public FileLabel getProcessed() {
        return this.processed;
    }

    /**
     * 获取文件标签。
     *
     * @return 文件标签。
     */
    public FileLabel getFileLabel() {
        return this.fileLabel;
    }

    /**
     * 设置文件标签。
     *
     * @param fileLabel 文件标签。
     */
    public void setFileLabel(FileLabel fileLabel) {
        this.fileLabel = fileLabel;
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
        json.put("size", this.size.toJSON());
        json.put("processed", this.processed.toJSON());

        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toJSON());
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
