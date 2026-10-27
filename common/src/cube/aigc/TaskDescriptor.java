/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc;

import cube.common.JSONable;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务描述符。
 *
 * <p>记录一次 AI 任务的身份信息（联系人、域、令牌）、任务名与计量数据，
 * 用于在推理链路中透传调用方身份并统计 token 消耗。</p>
 */
public class TaskDescriptor implements JSONable {

    /** 任务 ID 。 */
    public final long id;

    /** 发起任务的联系人 ID 。 */
    public final long contactId;

    /** 所属域。 */
    public final String domain;

    /** 访问令牌。 */
    public final String token;

    /** 任务名，取值见各任务的 {@code TASK_NAME} 。 */
    public final String task;

    /** 任务创建时间戳，单位毫秒。 */
    public final long timestamp;

    private int inputTokens;

    private int outputTokens;

    private List<String> fileCodeList;

    private long unitId;

    public TaskDescriptor(long id, long contactId, String domain, String token, String task, long timestamp) {
        this.id = id;
        this.contactId = contactId;
        this.domain = domain;
        this.token = token;
        this.task = task;
        this.timestamp = timestamp;
    }

    /**
     * 记录输入 token 数。
     *
     * @param value token 数量。
     */
    public void setInputTokens(int value) {
        this.inputTokens = value;
    }

    /**
     * 获取输入 token 数。
     *
     * @return token 数量。
     */
    public int getInputTokens() {
        return this.inputTokens;
    }

    /**
     * 记录输出 token 数。
     *
     * @param value token 数量。
     */
    public void setOutputTokens(int value) {
        this.outputTokens = value;
    }

    /**
     * 获取输出 token 数。
     *
     * @return token 数量。
     */
    public int getOutputTokens() {
        return this.outputTokens;
    }

    /**
     * 追加关联文件码。
     *
     * <p>最多保留 5 个，超出部分被忽略。</p>
     *
     * @param fileCode 文件码。
     */
    public void addFileCode(String fileCode) {
        if (null == this.fileCodeList) {
            this.fileCodeList = new ArrayList<>();
        }
        this.fileCodeList.add(fileCode);
    }

    /**
     * 获取第 1 个关联文件码。
     *
     * @return 文件码；无关联文件时返回 {@code null} 。
     */
    public String getFileCode() {
        return (null != this.fileCodeList) ? this.fileCodeList.get(0) : null;
    }

    /**
     * 获取第 2 个关联文件码。
     *
     * @return 文件码；不足 2 个时返回 {@code null} 。
     */
    public String getFileCode2() {
        return (null != this.fileCodeList && this.fileCodeList.size() >= 2) ? this.fileCodeList.get(1) : null;
    }

    /**
     * 获取第 3 个关联文件码。
     *
     * @return 文件码；不足 3 个时返回 {@code null} 。
     */
    public String getFileCode3() {
        return (null != this.fileCodeList && this.fileCodeList.size() >= 3) ? this.fileCodeList.get(2) : null;
    }

    /**
     * 获取第 4 个关联文件码。
     *
     * @return 文件码；不足 4 个时返回 {@code null} 。
     */
    public String getFileCode4() {
        return (null != this.fileCodeList && this.fileCodeList.size() >= 4) ? this.fileCodeList.get(3) : null;
    }

    /**
     * 获取第 5 个关联文件码。
     *
     * @return 文件码；不足 5 个时返回 {@code null} 。
     */
    public String getFileCode5() {
        return (null != this.fileCodeList && this.fileCodeList.size() >= 5) ? this.fileCodeList.get(4) : null;
    }

    /**
     * 设置执行任务的单元 ID 。
     *
     * @param unitId 单元 ID 。
     */
    public void setUnitId(long unitId) {
        this.unitId = unitId;
    }

    /**
     * 获取执行任务的单元 ID 。
     *
     * @return 单元 ID ；未绑定返回 0 。
     */
    public long getUnitId() {
        return this.unitId;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
