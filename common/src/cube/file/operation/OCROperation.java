/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file.operation;

import cube.common.JSONable;
import cube.common.action.FileProcessorAction;
import cube.file.FileOperation;
import org.json.JSONObject;

/**
 * OCR 操作配置。
 */
public class OCROperation implements FileOperation, JSONable {

    /** 语言中文。 */
    public final static String LANG_CHINESE = "chi_sim";

    /** 语言英文。 */
    public final static String LANG_ENGLISH = "eng";

    private String language;

    private boolean singleTextLine = false;

    public OCROperation() {
    }

    public OCROperation(String language) {
        this.language = language;
    }

    public OCROperation(boolean singleTextLine) {
        this.singleTextLine = singleTextLine;
    }

    public OCROperation(String language, boolean singleTextLine) {
        this.language = language;
        this.singleTextLine = singleTextLine;
    }

    public OCROperation(JSONObject json) {
        if (json.has("lang")) {
            this.language = json.getString("lang");
        }

        if (json.has("singleLine")) {
            this.singleTextLine = json.getBoolean("singleLine");
        }
    }

    /**
     * 获取语言。
     *
     * @return 语言。
     */
    public String getLanguage() {
        return this.language;
    }

    /**
     * 判断是否按单行文本识别。
     *
     * <p>单行模式下不做事后分行合并，适用于票据、编号等短文本。</p>
     *
     * @return 单行模式返回 {@code true} 。
     */
    public boolean isSingleTextLine() {
        return this.singleTextLine;
    }

    /**
     * 获取处理动作。
     *
     * @return 处理动作。
     */
    @Override
    public String getProcessAction() {
        return FileProcessorAction.OCR.name;
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

        json.put("singleLine", this.singleTextLine);

        if (null != this.language) {
            json.put("lang", this.language);
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
