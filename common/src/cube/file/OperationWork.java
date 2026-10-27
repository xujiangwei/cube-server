/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file;

import cube.common.JSONable;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * 文件操作工作。
 */
public class OperationWork implements JSONable {

    private FileOperation fileOperation;

    private List<File> inputList;

    private List<File> outputList;

    private FileProcessResult processResult;

    public OperationWork(FileOperation fileOperation) {
        this.fileOperation = fileOperation;
    }

    public OperationWork(JSONObject json) {
        this.fileOperation = FileOperationHelper.parseFileOperation(json.getJSONObject("operation"));
    }

    /**
     * 设置本步执行的文件操作。
     *
     * @param fileOperation 文件操作。
     */
    public void setOperation(FileOperation fileOperation) {
        this.fileOperation = fileOperation;
    }

    /**
     * 获取本步执行的文件操作。
     *
     * @return 文件操作。
     */
    public FileOperation getFileOperation() {
        return this.fileOperation;
    }

    /**
     * 设置本步的输入文件。
     *
     * @param files 输入文件列表。
     */
    public void setInputFiles(List<File> files) {
        this.inputList = files;
    }

    /**
     * 获取本步的输入文件。
     *
     * @return 输入文件列表；无输入时为空列表。
     */
    public List<File> getInputFiles() {
        return this.inputList;
    }

    /**
     * 设置输出文件列表。
     *
     * @param files 输出文件列表。
     */
    public void setOutputFiles(List<File> files) {
        this.outputList = files;
    }

    /**
     * 获取输出文件列表。
     *
     * @return 输出文件列表。
     */
    public List<File> getOutputFiles() {
        return this.outputList;
    }

    /**
     * 设置处理结果。
     *
     * @param result 处理结果。
     */
    public void setProcessResult(FileProcessResult result) {
        this.processResult = result;
    }

    /**
     * 获取处理结果。
     *
     * @return 处理结果。
     */
    public FileProcessResult getProcessResult() {
        return this.processResult;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("operation", this.fileOperation.toJSON());
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
