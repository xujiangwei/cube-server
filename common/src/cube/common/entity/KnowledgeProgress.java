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
 * 知识库进度。
 */
public class KnowledgeProgress implements JSONable {

    private final long sn;

    private long startTime;

    private long endTime;

    private int totalDocs;

    private int processedDocs;

    private KnowledgeDocument processingDoc;

    private int stateCode;

    public KnowledgeProgress(int stateCode) {
        this.sn = Utils.generateSerialNumber();
        this.startTime = System.currentTimeMillis();
        this.totalDocs = 0;
        this.processedDocs = 0;
        this.stateCode = stateCode;
    }

    public KnowledgeProgress(JSONObject json) {
        this.sn = json.getLong("sn");
        this.startTime = json.getLong("startTime");
        this.endTime = json.getLong("endTime");
        this.totalDocs = json.getInt("totalDocs");
        this.processedDocs = json.getInt("processedDocs");
        if (json.has("processingDoc")) {
            this.processingDoc = new KnowledgeDocument(json.getJSONObject("processingDoc"));
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
     * 设置处理中的文档。
     *
     * @param doc 处理中的文档。
     */
    public void setProcessingDoc(KnowledgeDocument doc) {
        this.processingDoc = doc;
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
     * 设置结束时间。
     *
     * @param time 结束时间。
     */
    public void setEndTime(long time) {
        this.endTime = time;
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
        json.put("startTime", this.startTime);
        json.put("endTime", this.endTime);
        json.put("totalDocs", this.totalDocs);
        json.put("processedDocs", this.processedDocs);
        if (null != this.processingDoc) {
            json.put("activatingDoc", this.processingDoc.toJSON());
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
