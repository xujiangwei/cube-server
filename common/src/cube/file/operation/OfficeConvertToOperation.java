/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.common.action.FileProcessorAction;
import cube.file.FileOperation;
import org.json.JSONObject;

/**
 * 办公文档转换操作。
 */
public class OfficeConvertToOperation implements FileOperation {

    public final static String OUTPUT_FORMAT_PDF = "pdf";

    public final static String OUTPUT_FORMAT_PNG = "png";

    /** 输出格式化文本。 */
    public final static String OUTPUT_FORMAT_TEXT = "txt";

    private String outputFormat;

    public OfficeConvertToOperation(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public OfficeConvertToOperation(JSONObject json) {
        this.outputFormat = json.getString("format");
    }

    /**
     * 获取输出格式化。
     *
     * @return 输出格式化。
     */
    public String getOutputFormat() {
        return this.outputFormat;
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
        json.put("format", this.outputFormat);
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

    /**
     * 获取处理动作。
     *
     * @return 处理动作。
     */
    @Override
    public String getProcessAction() {
        return FileProcessorAction.OfficeConvertTo.name;
    }
}
