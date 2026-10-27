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
 * 音频操作。
 */
public abstract class AudioOperation implements FileOperation, JSONable {

    private File inputFile;

    public AudioOperation() {
    }

    /**
     * 获取具体的音频操作。
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
        return FileProcessorAction.Audio.name;
    }

    /**
     * 获取输入文件。
     *
     * @return 输入音频文件。
     */
    public File getInputFile() {
        return this.inputFile;
    }

    /**
     * 设置输入文件。
     *
     * @param inputFile 输入音频文件。
     */
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
