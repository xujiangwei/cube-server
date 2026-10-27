/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file;

import cube.common.JSONable;
import cube.common.action.FileProcessorAction;
import org.json.JSONObject;

import java.io.File;

/**
 * 视频操作。
 */
public abstract class VideoOperation implements FileOperation, JSONable {

    private File inputFile;

    public VideoOperation() {
    }

    /**
     * 获取具体的视频操作。
     * @return 处理结果。
     */
    public abstract String getOperation();

    /**
     * 获取处理动作。
     *
     * @return 处理动作。
     */
    @Override
    public String getProcessAction() {
        return FileProcessorAction.Video.name;
    }

    public File getInputFile() {
        return this.inputFile;
    }

    public void setInputFile(File inputFile) {
        this.inputFile = inputFile;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("process", this.getProcessAction());
        json.put("operation", this.getOperation());
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
