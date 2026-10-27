/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.aigc.TextSplitter;
import cube.util.JSONUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档。
 */
public class KnowledgeDocument extends Entity {

    /** 联系人 ID。 */
    public final long contactId;

    /** 文件码。 */
    public final String fileCode;

    /**
     * 文档所属库名。
     */
    public String baseName = "document";

    /** 文件名。 */
    public String fileName;

    /** 是否激活。 */
    public boolean activated;

    private FileLabel fileLabel;

    /**
     * 文本分割器。
     */
    public TextSplitter splitter = TextSplitter.Auto;

    /**
     * 分割的内容段落数量。
     */
    public int numSegments = -1;

    /**
     * 作用范围。
     */
    public KnowledgeScope scope = KnowledgeScope.Private;

    /**
     * 知识标签。
     */
    public List<String> knowledgeLabels = new ArrayList<>();

    public KnowledgeDocument(long id, String domain, long contactId, String fileCode, String baseName,
                             String fileName, boolean activated, int numSegments, KnowledgeScope scope) {
        super(id, domain);
        this.contactId = contactId;
        this.fileCode = fileCode;
        if (null != baseName) {
            this.baseName = baseName;
        }
        this.fileName = fileName;
        this.activated = activated;
        this.numSegments = numSegments;
        this.scope = scope;

        if (null != fileName) {
            this.knowledgeLabels.add(fileName.split("\\.")[0]);
        }
    }

    public KnowledgeDocument(JSONObject json) {
        super(json);
        this.contactId = json.getLong("contactId");
        this.fileCode = json.getString("fileCode");
        this.baseName = json.getString("baseName");
        this.activated = json.getBoolean("activated");
        this.splitter = TextSplitter.parse(json.getString("splitter"));
        this.numSegments = json.getInt("numSegments");
        this.scope = KnowledgeScope.parse(json.getString("scope"));

        if (json.has("fileName")) {
            this.fileName = json.getString("fileName");
        }

        if (json.has("fileLabel")) {
            this.fileLabel = new FileLabel(json.getJSONObject("fileLabel"));
        }

        if (json.has("knowledgeLabels")) {
            this.knowledgeLabels = JSONUtils.toStringList(json.getJSONArray("knowledgeLabels"));
        }
    }

    /**
     * 获取文件标签。
     *
     * @return 文件标签。
     */
    public FileLabel getFileLabel() {
        return this.fileLabel;
    }

    /**
     * 设置文件标签。
     *
     * @param fileLabel 文件标签。
     */
    public void setFileLabel(FileLabel fileLabel) {
        this.fileLabel = fileLabel;
        this.fileName = fileLabel.getFileName();
    }

    /**
     * 判断指定对象是否与本对象相等。
     *
     * @param object 待比较对象。
     * @return 相等返回 {@code true} 。
     */
    @Override
    public boolean equals(Object object) {
        if (object instanceof KnowledgeDocument) {
            KnowledgeDocument other = (KnowledgeDocument) object;
            return other.fileCode.equals(this.fileCode);
        }

        return false;
    }

    /**
     * 计算哈希值。
     *
     * @return 哈希值。
     */
    @Override
    public int hashCode() {
        return this.fileCode.hashCode();
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contactId", this.contactId);
        json.put("fileCode", this.fileCode);
        json.put("baseName", this.baseName);
        json.put("activated", this.activated);
        json.put("splitter", this.splitter.name);
        json.put("numSegments", this.numSegments);
        json.put("scope", this.scope.name);

        if (null != this.fileName) {
            json.put("fileName", this.fileName);
        }

        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toJSON());
        }

        json.put("knowledgeLabels", JSONUtils.toStringArray(this.knowledgeLabels));
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
        JSONObject json = this.toJSON();
        if (json.has("fileLabel")) {
            json.remove("fileLabel");
            json.put("fileLabel", this.fileLabel.toCompactJSON());
        }
        return json;
    }
}
