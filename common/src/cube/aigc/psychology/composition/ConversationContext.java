/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.guidance.AbstractGuideFlow;
import cube.aigc.memory.SubtaskMemory;
import cube.aigc.psychology.Attribute;
import cube.aigc.psychology.PaintingReport;
import cube.auth.AuthToken;
import cube.common.JSONable;
import cube.common.entity.Appointment;
import cube.common.entity.FileLabel;
import cube.common.entity.GeneratingRecord;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话上下文。
 */
public class ConversationContext implements JSONable {

    private ConversationRelation relation;

    private AuthToken authToken;

    /**
     * 仅用于实时推理。
     */
    private FileLabel currentFile;

    /**
     * 仅用于实时推理。
     */
    private boolean currentPaintingValidity;

    /**
     * 仅用于实时推理。
     */
    private Attribute currentAttribute;

    /**
     * 仅用于实时推理。
     */
    private PaintingReport currentReport;

    private Subtask subtask;

    private SubtaskMemory subtaskMemory;

    private List<PaintingReport> reportList;

    private AbstractGuideFlow guideFlow;

    private Appointment appointment;

    private String strategy;

    private List<GeneratingRecord> normalRecords = new ArrayList<>();

    private boolean superAdmin;

    public ConversationContext(ConversationRelation relation, AuthToken authToken) {
        this.relation = relation;
        this.authToken = authToken;
    }

    /**
     * 获取关系。
     *
     * @return 关系。
     */
    public ConversationRelation getRelation() {
        return this.relation;
    }

    /**
     * 获取访问令牌。
     *
     * @return 访问令牌。
     */
    public AuthToken getAuthToken() {
        return this.authToken;
    }

    /**
     * 激活子任务。
     *
     * @param subtask 子任务。
     */
    public void activateSubtask(Subtask subtask) {
        this.subtask = subtask;
        this.subtaskMemory = new SubtaskMemory();
    }

    /**
     * 停用子任务。
     *
     */
    public void deactivateSubtask() {
        this.subtask = null;
        this.subtaskMemory = null;
    }

    /**
     * 获取当前子任务。
     *
     * @return 当前子任务。
     */
    public Subtask getCurrentSubtask() {
        return this.subtask;
    }

    /**
     * 设置当前属性。
     *
     * @param attribute 当前属性。
     */
    public void setCurrentAttribute(Attribute attribute) {
        this.currentAttribute = attribute;
    }

    /**
     * 获取当前属性。
     *
     * @return 当前属性。
     */
    public Attribute getCurrentAttribute() {
        return this.currentAttribute;
    }

    /**
     * 设置当前文件。
     *
     * @param fileLabel 当前文件。
     */
    public void setCurrentFile(FileLabel fileLabel) {
        this.currentFile = fileLabel;
    }

    /**
     * 获取当前文件。
     *
     * @return 当前文件。
     */
    public FileLabel getCurrentFile() {
        return this.currentFile;
    }

    /**
     * 设置当前绘画有效性。
     *
     * @param valid 当前绘画有效性。
     */
    public void setCurrentPaintingValidity(boolean valid) {
        this.currentPaintingValidity = valid;
    }

    /**
     * 获取当前绘画有效性。
     *
     * @return 当前绘画有效性。
     */
    public boolean getCurrentPaintingValidity() {
        return this.currentPaintingValidity;
    }

    /**
     * 设置当前报告。
     *
     * @param report 当前报告。
     */
    public void setCurrentReport(PaintingReport report) {
        this.currentReport = report;
    }

    /**
     * 获取当前报告。
     *
     * @return 当前报告。
     */
    public PaintingReport getCurrentReport() {
        return this.currentReport;
    }

    /**
     * 设置报告列表。
     *
     * @param list 报告列表。
     */
    public void setReportList(List<PaintingReport> list) {
        this.reportList = list;
    }

    /**
     * 获取报告列表。
     *
     * @return 报告列表。
     */
    public List<PaintingReport> getReportList() {
        return this.reportList;
    }

    /**
     * 设置引导流程。
     *
     * @param guideFlow 引导流程。
     */
    public void setGuideFlow(AbstractGuideFlow guideFlow) {
        this.guideFlow = guideFlow;
    }

    /**
     * 获取引导流程。
     *
     * @return 引导流程。
     */
    public AbstractGuideFlow getGuideFlow() {
        return this.guideFlow;
    }

    /**
     * 设置预约。
     *
     * @param appointment 预约。
     */
    public void setAppointment(Appointment appointment) {
        this.appointment = appointment;
    }

    /**
     * 获取预约。
     *
     * @return 预约。
     */
    public Appointment getAppointment() {
        return this.appointment;
    }

    /**
     * 设置策略。
     *
     * @param strategy 策略。
     */
    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    /**
     * 获取策略。
     *
     * @return 策略。
     */
    public String getStrategy() {
        return this.strategy;
    }

    /**
     * 设置超级管理员。
     *
     * @param value 超级管理员。
     */
    public void setSuperAdmin(boolean value) {
        this.superAdmin = value;
    }

    /**
     * 判断是否超级管理员。
     *
     * @return 超级管理员。
     */
    public boolean isSuperAdmin() {
        return this.superAdmin;
    }

    /**
     * 取消全部。
     *
     */
    public void cancelAll() {
        this.currentFile = null;
        this.currentPaintingValidity = false;
        this.currentAttribute = null;
        this.currentReport = null;
        this.subtask = null;
        this.subtaskMemory = null;
        this.reportList = null;
        this.guideFlow = null;
        this.appointment = null;
    }

    /**
     * 取消当前预测。
     *
     */
    public void cancelCurrentPredict() {
        this.subtask = null;
        this.subtaskMemory = null;
        this.currentAttribute = null;
        this.currentFile = null;
        this.currentPaintingValidity = false;
    }

    /**
     * 获取最近文件。
     *
     * @return 最近文件。
     */
    public FileLabel getRecentFile() {
        for (int i = this.normalRecords.size() - 1; i >= 0; --i) {
            GeneratingRecord record = this.normalRecords.get(i);
            if (null != record.queryFileLabels && !record.queryFileLabels.isEmpty()) {
                return record.queryFileLabels.get(0);
            }
        }

        return this.subtaskMemory.getRecentFile();
    }

    /**
     * 获取子任务记忆。
     *
     * @return 子任务记忆。
     */
    public SubtaskMemory getSubtaskMemory() {
        return this.subtaskMemory;
    }

    /**
     * 记录一次常规生成结果。
     *
     * @param record 生成记录。
     */
    public void recordNormal(GeneratingRecord record) {
        this.normalRecords.add(record);
    }

    /**
     * 获取时间倒序的历史记录。
     * @param num num。
     * @return 列表；无数据时为空列表。
     */
    public List<GeneratingRecord> getNormalHistories(int num) {
        List<GeneratingRecord> list = new ArrayList<>();
        for (int i = this.normalRecords.size() - 1; i >= 0; --i) {
            GeneratingRecord record = this.normalRecords.get(i);
            list.add(record);
            if (list.size() >= num) {
                break;
            }
        }
        return list;
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
     * <p>用于传输体积敏感的场合，字段较完整序列化更少。</p>
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
