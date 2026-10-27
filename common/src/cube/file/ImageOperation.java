/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file;

import cube.common.action.FileProcessorAction;
import org.json.JSONObject;

import java.io.File;

/**
 * 图片操作。
 */
public abstract class ImageOperation implements FileOperation {

    private File inputFile;

    private String outputFilename;

    public ImageOperation() {
    }

    public ImageOperation(JSONObject json) {
        if (json.has("outputFilename")) {
            this.outputFilename = json.getString("outputFilename");
        }
    }

    /**
     * 获取具体的图像操作。
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
        return FileProcessorAction.Image.name;
    }

    public File getInputFile() {
        return this.inputFile;
    }

    public void setInputFile(File inputFile) {
        this.inputFile = inputFile;
    }

    public void setOutputFilename(String outputFilename) {
        this.outputFilename = outputFilename;
    }

    public String getOutputFilename() {
        return this.outputFilename;
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

        if (null != this.outputFilename) {
            json.put("outputFilename", this.outputFilename);
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
