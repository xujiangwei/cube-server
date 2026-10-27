/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.psychology.Attribute;
import cube.aigc.psychology.ComprehensiveSection;
import cube.aigc.psychology.Painting;
import cube.common.JSONable;
import cube.common.entity.FileLabel;
import cube.util.JSONUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合评测里表示单一被测实体的类。
 */
public class Comprehensive implements JSONable {

    private String name;

    private String role;

    private Attribute attribute;

    private List<String> fileCodes;

    private List<String> fileUrls;

    private List<FileLabel> fileLabels;

    private Map<String, Painting> paintingMap;

    private List<String> choices;

    private List<Scale> scales;

    private List<ComprehensiveSection> sections;

    public Comprehensive(JSONObject json) {
        this.name = json.getString("name");
        this.role = json.has("role") ? json.getString("role") : null;
        this.attribute = new Attribute(json.getJSONObject("attribute"));

        this.fileCodes = new ArrayList<>();
        this.fileUrls = new ArrayList<>();
        this.fileLabels = new ArrayList<>();
        if (json.has("files")) {
            JSONArray fileLabelArray = json.getJSONArray("files");
            for (int i = 0; i < fileLabelArray.length(); ++i) {
                JSONObject fileLabelJson = fileLabelArray.getJSONObject(i);
                FileLabel fileLabel = new FileLabel(fileLabelJson);
                this.fileLabels.add(fileLabel);
                this.fileCodes.add(fileLabel.getFileCode());
            }
        }
        else if (json.has("fileCodes")) {
            JSONArray fileCodeArray = json.getJSONArray("fileCodes");
            this.fileCodes.addAll(JSONUtils.toStringList(fileCodeArray));
        }
        else if (json.has("fileUrls")) {
            JSONArray fileUrlArray = json.getJSONArray("fileUrls");
            this.fileUrls.addAll(JSONUtils.toStringList(fileUrlArray));
        }

        this.paintingMap = new HashMap<>();

        this.scales = new ArrayList<>();
        if (json.has("scales")) {
            JSONArray scaleArray = json.getJSONArray("scales");
            for (int i = 0; i< scaleArray.length(); ++i) {
                JSONObject scaleJson = scaleArray.getJSONObject(i);
                this.scales.add(new Scale(scaleJson));
            }
        }

        if (json.has("choices")) {
            this.choices = JSONUtils.toStringList(json.getJSONArray("choices"));
        }
        else {
            this.choices = new ArrayList<>();
        }

        if (json.has("sections")) {
            this.sections = new ArrayList<>();
            JSONArray array = json.getJSONArray("sections");
            for (int i = 0; i < array.length(); ++i) {
                this.sections.add(new ComprehensiveSection(array.getJSONObject(i)));
            }
        }
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取角色。
     *
     * @return 角色。
     */
    public String getRole() {
        return this.role;
    }

    /**
     * 获取属性。
     *
     * @return 属性。
     */
    public Attribute getAttribute() {
        return this.attribute;
    }

    /**
     * 判断是否有效文件。
     *
     * @return 有效文件。
     */
    public boolean isValidFile() {
        return (!this.fileLabels.isEmpty()) || (!this.fileCodes.isEmpty()) || (!this.fileUrls.isEmpty());
    }

    /**
     * 判断是否文件标签列表。
     *
     * @return 文件标签列表。
     */
    public boolean hasFileLabels() {
        return (!this.fileLabels.isEmpty());
    }

    /**
     * 获取文件码列表。
     *
     * @return 文件码列表。
     */
    public List<String> getFileCodes() {
        return this.fileCodes;
    }

    /**
     * 获取文件地址列表。
     *
     * @return 文件地址列表。
     */
    public List<String> getFileUrls() {
        return this.fileUrls;
    }

    /**
     * 添加文件标签。
     *
     * @param fileLabel 文件标签。
     */
    public void addFileLabel(FileLabel fileLabel) {
        this.fileLabels.add(fileLabel);
    }

    /**
     * 获取按性别区分的关键词。
     *
     * @return 按性别区分的关键词。
     */
    public String getKeywordWithGender() {
        if (this.attribute.isMale()) {
            return "男方" + this.sections.get(0).title;
        }
        else {
            return "女方" + this.sections.get(0).title;
        }
    }

    /**
     * 获取文件标签。
     *
     * @return 文件标签。
     */
    public FileLabel getFileLabel() {
        if (this.fileLabels.isEmpty()) {
            return null;
        }

        return this.fileLabels.get(0);
    }

    /**
     * 获取选项列表。
     *
     * @return 选项列表。
     */
    public List<String> getChoices() {
        return this.choices;
    }

    /**
     * 构建选项列表字符串。
     *
     * @return 选项列表字符串。
     */
    public String buildChoicesString() {
        StringBuilder buf = new StringBuilder();
        for (String word : this.choices) {
            buf.append(word);
            buf.append("，");
        }
        buf.delete(buf.length() - 1, buf.length());
        return buf.toString();
    }

    /**
     * 判断是否量表列表。
     *
     * @return 量表列表。
     */
    public boolean hasScales() {
        return (!this.scales.isEmpty());
    }

    /**
     * 添加量表。
     *
     * @param scale 量表。
     */
    public void addScale(Scale scale) {
        this.scales.add(scale);
    }

    /**
     * 获取量表。
     *
     * @return 量表。
     */
    public Scale getScale() {
        if (this.scales.isEmpty()) {
            return null;
        }

        return this.scales.get(0);
    }

    /**
     * 设置绘画。
     *
     * @param fileCode 绘画。
     * @param painting 绘画。
     */
    public void setPainting(String fileCode, Painting painting) {
        this.paintingMap.put(fileCode, painting);
    }

    /**
     * 添加综合章节。
     *
     * @param section 综合章节。
     */
    public void addComprehensiveSection(ComprehensiveSection section) {
        if (null == this.sections) {
            this.sections = new ArrayList<>();
        }
        this.sections.add(section);
    }

    /**
     * 获取综合章节。
     *
     * @return 综合章节。
     */
    public ComprehensiveSection getComprehensiveSection() {
        return this.sections.get(0);
    }

    /**
     * 获取综合章节列表。
     *
     * @return 综合章节列表。
     */
    public List<ComprehensiveSection> getComprehensiveSections() {
        return this.sections;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name);
        json.put("attribute", this.attribute.toJSON());

        if (null != this.role) {
            json.put("role", this.role);
        }

        if (!this.fileLabels.isEmpty()) {
            JSONArray array = new JSONArray();
            for (FileLabel fileLabel : this.fileLabels) {
                array.put(fileLabel.toCompactJSON());
            }
            json.put("files", array);
        }
        else if (!this.fileCodes.isEmpty()) {
            json.put("fileCodes", JSONUtils.toStringArray(this.fileCodes));
        }
        else if (!this.fileUrls.isEmpty()) {
            json.put("fileUrls", JSONUtils.toStringArray(this.fileUrls));
        }

        JSONArray array = new JSONArray();
        for (Scale scale : this.scales) {
            array.put(scale.toJSON());
        }
        json.put("scales", array);

        json.put("choices", JSONUtils.toStringArray(this.choices));

        if (null != this.sections) {
            JSONArray sectionArray = new JSONArray();
            for (ComprehensiveSection section : this.sections) {
                sectionArray.put(section.toJSON());
            }
            json.put("sections", sectionArray);
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
