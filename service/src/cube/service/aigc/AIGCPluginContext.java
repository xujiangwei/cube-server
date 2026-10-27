/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc;

import cell.util.Utils;
import cube.aigc.AppEvent;
import cube.aigc.TaskDescriptor;
import cube.auth.AuthToken;
import cube.common.entity.AIGCUnit;
import cube.common.entity.FileLabel;
import cube.common.entity.KnowledgeDocument;
import cube.plugin.PluginContext;
import cube.service.aigc.knowledge.KnowledgeBase;

import java.util.ArrayList;
import java.util.List;

/**
 * AIGC 插件上下文。
 */
public class AIGCPluginContext extends PluginContext {

    private AuthToken authToken;

    private String task;

    private List<FileLabel> fileLabelList;

    private AIGCUnit unit;

    private int inputTokens;

    private int outputTokens;

    private KnowledgeBase knowledgeBase;

    private KnowledgeDocument knowledgeDocument;

    private AppEvent appEvent;

    public AIGCPluginContext(AuthToken authToken, String task) {
        this.authToken = authToken;
        this.task = task;
    }

    public AIGCPluginContext(KnowledgeBase knowledgeBase, KnowledgeDocument knowledgeDocument) {
        this.knowledgeBase = knowledgeBase;
        this.knowledgeDocument = knowledgeDocument;
    }

    public AIGCPluginContext(AppEvent appEvent) {
        this.appEvent = appEvent;
    }

    /**
     * 设置当前知识库。
     *
     * @param knowledgeBase 知识库实例。
     */
    public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 获取当前知识库。
     *
     * @return 知识库；本次上下文不涉及知识库时为 {@code null} 。
     */
    public KnowledgeBase getKnowledgeBase() {
        return this.knowledgeBase;
    }

    /**
     * 获取当前知识文档。
     *
     * @return 文档；不涉及文档时为 {@code null} 。
     */
    public KnowledgeDocument getKnowledgeDoc() {
        return this.knowledgeDocument;
    }

    /**
     * 获取应用事件。
     *
     * @return 事件；本次上下文不涉及应用事件时为 {@code null} 。
     */
    public AppEvent getAppEvent() {
        return this.appEvent;
    }

    /**
     * 获取访问令牌。
     *
     * @return 令牌；未提供时为 {@code null} 。
     */
    public AuthToken getAuthToken() {
        return this.authToken;
    }

    /**
     * 获取任务名。
     *
     * @return 任务名；未设置时为 {@code null} 。
     */
    public String getTask() {
        return this.task;
    }

    /**
     * 设置任务名。
     *
     * @param task 任务名。
     */
    public void setTask(String task) {
        this.task = task;
    }

    /**
     * 获取执行单元。
     *
     * @return 单元；未绑定时为 {@code null} 。
     */
    public AIGCUnit getUnit() {
        return this.unit;
    }

    /**
     * 设置执行单元。
     *
     * @param unit 单元实例。
     */
    public void setUnit(AIGCUnit unit) {
        this.unit = unit;
    }

    /**
     * 获取关联文件列表。
     *
     * @return 文件标签列表；无关联文件时为 {@code null} 。
     */
    public List<FileLabel> getFileLabelList() {
        return this.fileLabelList;
    }

    /**
     * 追加关联文件。
     *
     * <p>列表惰性创建，重复添加同一文件不会去重。</p>
     *
     * @param fileLabel 文件标签。
     */
    public void addFileLabel(FileLabel fileLabel) {
        if (null == this.fileLabelList) {
            this.fileLabelList = new ArrayList<>();
        }
        this.fileLabelList.add(fileLabel);
    }

    /**
     * 移除关联文件。
     *
     * @param fileLabel 文件标签。
     */
    public void removeFileLabel(FileLabel fileLabel) {
        if (null == this.fileLabelList) {
            return;
        }
        this.fileLabelList.remove(fileLabel);
    }

    /**
     * 记录输入 token 数。
     *
     * @param tokens token 数量。
     */
    public void setInputTokens(int tokens) {
        this.inputTokens = tokens;
    }

    /**
     * 记录输出 token 数。
     *
     * @param tokens token 数量。
     */
    public void setOutputTokens(int tokens) {
        this.outputTokens = tokens;
    }

    /**
     * 构造任务描述符。
     *
     * <p>令牌或任务名缺失时返回 {@code null} ，表示本次上下文不足以登记任务。</p>
     *
     * @return 任务描述符；信息不全时返回 {@code null} 。
     */
    public TaskDescriptor makeTaskDescriptor() {
        if (null == this.authToken || null == this.task) {
            return null;
        }

        TaskDescriptor descriptor = new TaskDescriptor(Utils.generateSerialNumber(), this.authToken.getContactId(),
                this.authToken.getDomain(), this.authToken.getCode(), this.task, System.currentTimeMillis());
        descriptor.setInputTokens(this.inputTokens);
        descriptor.setOutputTokens(this.outputTokens);
        if (null != this.fileLabelList) {
            for (FileLabel fileLabel : this.fileLabelList) {
                descriptor.addFileCode(fileLabel.getFileCode());
            }
        }
        if (null != this.unit) {
            descriptor.setUnitId(this.unit.getId());
        }

        return descriptor;
    }

    /**
     * 按名称读取上下文属性。
     *
     * @param name 属性名。
     * @return 属性值；未知名称返回 {@code null} 。
     */
    @Override
    public Object get(String name) {
        if (name.equalsIgnoreCase("appEvent")) {
            return this.appEvent;
        }
        else if (name.equalsIgnoreCase("knowledgeBase")) {
            return this.knowledgeBase;
        }
        else if (name.equalsIgnoreCase("knowledgeDoc")) {
            return this.knowledgeDocument;
        }

        return null;
    }

    /**
     * 按名称写入上下文属性。
     *
     * @param name  属性名。
     * @param value 属性值。
     */
    @Override
    public void set(String name, Object value) {
    }
}
