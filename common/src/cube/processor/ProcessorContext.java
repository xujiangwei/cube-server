/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.processor;

import cube.common.JSONable;
import cube.common.entity.FileResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理器上下文。
 */
public abstract class ProcessorContext implements JSONable {

    private boolean successful = false;

    private List<String> stdOutput;

    private List<FileResult> resultList;

    protected ProcessorContext() {
        this.resultList = new ArrayList<>();
    }

    protected ProcessorContext(JSONObject json) {
        this.successful = json.getBoolean("success");
        this.resultList = new ArrayList<>();

        if (json.has("resultList")) {
            JSONArray array = json.getJSONArray("resultList");
            for (int i = 0; i < array.length(); ++i) {
                this.resultList.add(new FileResult(array.getJSONObject(i)));
            }
        }
    }

    /**
     * 设置处理是否成功。
     *
     * @param value 成功为 {@code true} 。
     */
    public void setSuccessful(boolean value) {
        this.successful = value;
    }

    /**
     * 判断处理是否成功。
     *
     * @return 成功返回 {@code true} 。
     */
    public boolean isSuccessful() {
        return this.successful;
    }

    /**
     * 添加处理产物。
     *
     * @param result 文件结果。
     */
    public void addResult(FileResult result) {
        this.resultList.add(result);
    }

    /**
     * 获取全部处理产物。
     *
     * @return 结果列表；无产物时为空列表。
     */
    public List<FileResult> getResultList() {
        return this.resultList;
    }

    /**
     * 追加一行标准输出。
     *
     * <p>方法加锁，允许多个读取线程并发写入。</p>
     *
     * @param line 输出行。
     */
    public synchronized void appendStdOutput(String line) {
        if (null == this.stdOutput) {
            this.stdOutput = new ArrayList<>();
        }

        this.stdOutput.add(line);
    }

    /**
     * 获取累积的标准输出。
     *
     * @return 输出行列表。
     */
    public List<String> getStdOutput() {
        return this.stdOutput;
    }

    /**
     * 序列化为 JSON 。
     *
     * @param process 处理标识，写入结果以便溯源。
     * @return JSON 表示。
     */
    public JSONObject toJSON(String process) {
        JSONObject json = new JSONObject();

        json.put("process", process);
        json.put("success", this.successful);

        if (!this.resultList.isEmpty()) {
            JSONArray array = new JSONArray();
            for (FileResult result : this.resultList) {
                array.put(result.toJSON());
            }
            json.put("resultList", array);
        }

        return json;
    }
}
