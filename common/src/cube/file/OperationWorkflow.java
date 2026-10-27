/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file;

import cell.util.Utils;
import cube.common.JSONable;
import cube.common.entity.FileLabel;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 文件处理流。
 */
public class OperationWorkflow implements JSONable {

    /**
     * 序号。
     */
    private long sn;

    /**
     * 域名称。
     */
    private String domain;

    /**
     * 源文件的文件码。
     */
    private String sourceFileCode;

    /**
     * 源本地文件。
     */
    private File sourceFile;

    /**
     * 联系人 ID 。
     */
    private Long contactId;

    /**
     * 服务器客户端的 ID 。
     */
    private long clientId;

    /**
     * 操作工作列表。
     */
    private List<OperationWork> workList;

    /**
     * 结果文件名。
     */
    private String resultFilename;

    /**
     * 结果文件的文件标签。
     */
    private FileLabel resultFileLabel;

    /**
     * 是否删除过程文件。
     */
    private boolean deleteProcessedFile = true;

    public OperationWorkflow() {
        this.sn = Utils.generateSerialNumber();
        this.workList = new LinkedList<>();
    }

    public OperationWorkflow(File file) {
        this.sn = Utils.generateSerialNumber();
        this.workList = new LinkedList<>();
        this.sourceFile = file;
    }

    public OperationWorkflow(JSONObject json) {
        this.sn = json.getLong("sn");
        this.domain = json.getString("domain");

        if (json.has("source")) {
            this.sourceFileCode = json.getString("source");
        }

        if (json.has("sourceFile")) {
            this.sourceFile = new File(json.getString("sourceFile"));
        }

        if (json.has("contactId")) {
            this.contactId = json.getLong("contactId");
        }

        if (json.has("clientId")) {
            this.clientId = json.getLong("clientId");
        }

        this.workList = new LinkedList<>();
        JSONArray works = json.getJSONArray("works");
        for (int i = 0; i < works.length(); ++i) {
            JSONObject workJson = works.getJSONObject(i);
            this.workList.add(new OperationWork(workJson));
        }

        if (json.has("resultFilename")) {
            this.resultFilename = json.getString("resultFilename");
        }

        if (json.has("resultFileLabel")) {
            this.resultFileLabel = new FileLabel(json.getJSONObject("resultFileLabel"));
        }
    }

    /**
     * 获取序列号。
     *
     * @return 序列号。
     */
    public long getSN() {
        return this.sn;
    }

    /**
     * 获取域。
     *
     * @return 域。
     */
    public String getDomain() {
        return this.domain;
    }

    /**
     * 设置域。
     *
     * @param domain 域。
     */
    public void setDomain(String domain) {
        this.domain = domain;
    }

    /**
     * 获取联系人 ID。
     *
     * @return 联系人 ID。
     */
    public Long getContactId() {
        return this.contactId;
    }

    /**
     * 设置联系人 ID。
     *
     * @param contactId 联系人 ID。
     */
    public void setContactId(Long contactId) {
        this.contactId = contactId;
    }

    /**
     * 获取客户端 ID 。
     *
     * @return 客户端标识。
     */
    public long getClientId() {
        return this.clientId;
    }

    /**
     * 设置客户端 ID 。
     *
     * @param clientId 客户端标识。
     */
    public void setClientId(long clientId) {
        this.clientId = clientId;
    }

    /**
     * 获取来源文件码。
     *
     * @return 来源文件码。
     */
    public String getSourceFileCode() {
        return this.sourceFileCode;
    }

    /**
     * 设置来源文件码。
     *
     * @param fileCode 来源文件码。
     */
    public void setSourceFileCode(String fileCode) {
        this.sourceFileCode = fileCode;
    }

    /**
     * 获取来源文件。
     *
     * @return 来源文件。
     */
    public File getSourceFile() {
        return this.sourceFile;
    }

    /**
     * 设置来源文件。
     *
     * @param sourceFile 来源文件。
     */
    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * 添加工作节点。
     * @param operationWork operationWork。
     */
    public void append(OperationWork operationWork) {
        this.workList.add(operationWork);
    }

    /**
     * 移除工作流。
     * @param operationWork operationWork。
     */
    public void remove(OperationWork operationWork) {
        this.workList.remove(operationWork);
    }

    /**
     * 获取全部工作项。
     *
     * @return 工作项列表；未编排操作时为空列表。
     */
    public List<OperationWork> getWorkList() {
        return new ArrayList<>(this.workList);
    }

    /**
     * 获取第一个工作项。
     *
     * @return 首个工作项；工作项列表为空时返回 {@code null} 。
     */
    public OperationWork getFirstWork() {
        return this.workList.get(0);
    }

    /**
     * 设置结果文件名。
     *
     * @param filename 结果文件名，不含目录。
     */
    public void setResultFilename(String filename) {
        this.resultFilename = filename;
    }

    /**
     * 获取结果文件名。
     *
     * @return 结果文件名；未指定时返回 {@code null} 。
     */
    public String getResultFilename() {
        return this.resultFilename;
    }

    /**
     * 设置结果文件标签。
     *
     * @param fileLabel 结果文件标签。
     */
    public void setResultFileLabel(FileLabel fileLabel) {
        this.resultFileLabel = fileLabel;
    }

    /**
     * 获取结果文件标签。
     *
     * @return 结果文件标签。
     */
    public FileLabel getResultFileLabel() {
        return this.resultFileLabel;
    }

    /**
     * 判断是否在处理后删除中间文件。
     *
     * @return 删除返回 {@code true} 。
     */
    public boolean isDeleteProcessedFile() {
        return this.deleteProcessedFile;
    }

    /**
     * 设置是否在处理后删除中间文件。
     *
     * @param value 为 {@code true} 表示删除。
     */
    public void setDeleteProcessedFile(boolean value) {
        this.deleteProcessedFile = value;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = this.toCompactJSON();

        JSONArray works = new JSONArray();
        for (OperationWork work : this.workList) {
            works.put(work.toJSON());
        }
        json.put("works", works);

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
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("domain", this.domain);

        if (null != this.sourceFileCode) {
            json.put("source", this.sourceFileCode);
        }

        if (null != this.sourceFile) {
            json.put("sourceFile", this.sourceFile.getAbsolutePath());
        }

        if (null != this.contactId) {
            json.put("contactId", this.contactId.longValue());
        }

        if (this.clientId > 0) {
            json.put("clientId", this.clientId);
        }

        if (null != this.resultFilename) {
            json.put("resultFilename", this.resultFilename);
        }

        if (null != this.resultFileLabel) {
            json.put("resultFileLabel", this.resultFileLabel.toCompactJSON());
        }

        return json;
    }
}
