/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 重置知识库进度。
 */
public class ResetKnowledgeProgress implements JSONable {

    /** 阶段：开始。 */
    public final static String PROGRESS_START = "Start";

    /** 阶段：备份存储。 */
    public final static String PROGRESS_BACKUP_STORE = "BackupStore";

    /** 阶段：删除存储。 */
    public final static String PROGRESS_DELETE_STORE = "DeleteStore";

    /** 阶段：激活文档。 */
    public final static String PROGRESS_ACTIVATE_DOC = "ActivateDoc";

    /** 阶段：结束。 */
    public final static String PROGRESS_END = "End";

    private final long sn;

    private String progress;

    private long startTime;

    private long endTime;

    private int totalDocs;

    private int processedDocs;

    private KnowledgeDocument activatingDoc;

    private int stateCode;

    public ResetKnowledgeProgress() {
        this.sn = Utils.generateSerialNumber();
        this.progress = PROGRESS_START;
        this.startTime = System.currentTimeMillis();
        this.totalDocs = -1;
        this.processedDocs = -1;
    }

    public ResetKnowledgeProgress(JSONObject json) {
        this.sn = json.getLong("sn");
        this.progress = json.getString("progress");
        this.startTime = json.getLong("startTime");
        this.endTime = json.getLong("endTime");
        this.totalDocs = json.getInt("totalDocs");
        this.processedDocs = json.getInt("processedDocs");
        if (json.has("activatingDoc")) {
            this.activatingDoc = new KnowledgeDocument(json.getJSONObject("activatingDoc"));
        }
        this.stateCode = json.getInt("stateCode");
    }

    /**
     * 获取任务序列号。
     *
     * @return 序列号。
     */
    public long getSN() {
        return this.sn;
    }

    /**
     * 设置进度。
     *
     * @param value 进度。
     */
    public void setProgress(String value) {
        this.progress = value;
        if (PROGRESS_END.equalsIgnoreCase(value)) {
            this.endTime = System.currentTimeMillis();
        }
    }

    /**
     * 获取进度。
     *
     * @return 进度。
     */
    public String getProgress() {
        return this.progress;
    }

    /**
     * 设置文档总数。
     *
     * @param value 文档总数。
     */
    public void setTotalDocs(int value) {
        this.totalDocs = value;
    }

    /**
     * 获取已处理文档。
     *
     * @return 已处理文档。
     */
    public int getProcessedDocs() {
        return this.processedDocs;
    }

    /**
     * 设置已处理文档。
     *
     * @param value 已处理文档。
     */
    public void setProcessedDocs(int value) {
        this.processedDocs = value;
    }

    /**
     * 设置当前正在处理的文档。
     *
     * @param doc 文档对象。
     */
    public void setActivatingDoc(KnowledgeDocument doc) {
        this.activatingDoc = doc;
    }

    /**
     * 设置状态码。
     *
     * @param code 状态码。
     */
    public void setStateCode(int code) {
        this.stateCode = code;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("progress", this.progress);
        json.put("startTime", this.startTime);
        json.put("endTime", this.endTime);
        json.put("totalDocs", this.totalDocs);
        json.put("processedDocs", this.processedDocs);
        if (null != this.activatingDoc) {
            json.put("activatingDoc", this.activatingDoc.toJSON());
        }
        json.put("stateCode", this.stateCode);
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
