/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 文件资源。
 */
public class FileResource extends ComplexResource {

    private FileLabel fileLabel;

    public FileResource(FileLabel fileLabel) {
        super(Subject.File);
        this.fileLabel = fileLabel;
    }

    protected FileResource(JSONObject json) {
        super(Subject.File, json);
        this.fileLabel = new FileLabel(json.getJSONObject("fileLabel"));
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
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("fileLabel", this.fileLabel.toJSON());
        return json;
    }
}
