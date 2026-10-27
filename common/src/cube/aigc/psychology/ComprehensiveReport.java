/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology;

import cube.aigc.psychology.composition.Comprehensive;
import cube.common.JSONable;
import cube.common.Language;
import cube.common.state.AIGCStateCode;
import cube.util.ConfigUtils;
import cube.util.Gender;
import cube.util.TimeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 心理融合报告。
 */
public class ComprehensiveReport implements JSONable {

    /** 序列号。 */
    public final long sn;

    /** 时间戳。 */
    public final long timestamp;

    /** 主题。 */
    public final Theme theme;

    /** 综合列表。 */
    public final List<Comprehensive> comprehensives;

    /** 状态。 */
    public AIGCStateCode state = AIGCStateCode.Processing;

    /** 是否完成。 */
    public boolean finished = false;

    /** 耗时。 */
    public long elapsed;

    private String summary;

    private List<ComprehensiveSection> sections;

    public ComprehensiveReport(Theme theme, List<Comprehensive> comprehensives) {
        this.sn = ConfigUtils.generateSerialNumber();
        this.timestamp = System.currentTimeMillis();
        this.theme = theme;
        this.comprehensives = comprehensives;
        this.summary = "";
        this.sections = new ArrayList<>();
    }

    public ComprehensiveReport(JSONObject json) {
        this.sn = json.getLong("sn");
        this.timestamp = json.getLong("timestamp");
        this.elapsed = json.getLong("elapsed");
        this.theme = Theme.parse(json.getString("theme"));

        this.comprehensives = new ArrayList<>();
        JSONArray array = json.getJSONArray("comprehensives");
        for (int i = 0; i < array.length(); ++i) {
            this.comprehensives.add(new Comprehensive(array.getJSONObject(i)));
        }

        this.state = AIGCStateCode.parse(json.getInt("state"));
        this.finished = json.getBoolean("finished");

        this.summary = json.has("summary") ? json.getString("summary") : "";
        this.sections = new ArrayList<>();
        array = json.getJSONArray("sections");
        for (int i = 0; i < array.length(); ++i) {
            this.sections.add(new ComprehensiveSection(array.getJSONObject(i)));
        }
    }

    public ComprehensiveReport(long sn, long timestamp, long elapsed, Theme theme, AIGCStateCode state) {
        this.sn = sn;
        this.timestamp = timestamp;
        this.elapsed = elapsed;
        this.theme = theme;
        this.comprehensives = new ArrayList<>();
        this.state = state;
        this.finished = true;
        this.summary = "";
        this.sections = new ArrayList<>();
    }

    /**
     * 添加综合。
     *
     * @param comprehensive 综合。
     */
    public void addComprehensive(Comprehensive comprehensive) {
        this.comprehensives.add(comprehensive);
    }

    /**
     * 按性别获取综合结果。
     *
     * @param gender 性别。
     * @return 综合结果；无匹配返回 {@code null} 。
     */
    public Comprehensive getComprehensiveByGender(Gender gender) {
        for (Comprehensive comprehensive : this.comprehensives) {
            Gender current = Gender.parse(comprehensive.getAttribute().gender);
            if (current == gender) {
                return comprehensive;
            }
        }
        return null;
    }

    /**
     * 设置摘要。
     *
     * @param text 摘要。
     */
    public void setSummary(String text) {
        this.summary = text;
    }

    /**
     * 获取摘要。
     *
     * @return 摘要。
     */
    public String getSummary() {
        return this.summary;
    }

    /**
     * 添加章节。
     *
     * @param section 章节。
     */
    public void addSection(ComprehensiveSection section) {
        this.sections.add(section);
    }

    /**
     * 输出全部章节。
     *
     * @return 章节数组。
     */
    public JSONArray outputSections() {
        JSONArray array = new JSONArray();
        for (ComprehensiveSection section : this.sections) {
            array.put(section.toJSON());
        }
        return array;
    }

    /**
     * 转换为Markdown。
     *
     * @return Markdown。
     */
    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        buf.append("## 报告基础信息\n\n");
        buf.append("- 测评主题：").append(this.theme.name).append("\n");
        buf.append("- 测评指导语：").append(this.theme.instruction).append("\n");
        buf.append("- 测评日期：").append(TimeUtils.formatDateString(this.timestamp, Language.Chinese)).append("\n");
        buf.append("- 测评对象：共").append(this.comprehensives.size()).append("个测评对象参与。分别是：\n");
        int sn = 1;
        for (Comprehensive comprehensive : this.comprehensives) {
            buf.append(sn++).append(". ");
            buf.append("性别").append(comprehensive.getAttribute().getGenderText());
            buf.append("，");
            buf.append("年龄").append(comprehensive.getAttribute().getAgeText());
            buf.append("\n");
        }
        buf.append("\n");
        buf.append("## 报告摘要\n\n");
        buf.append(this.summary);
        buf.append("\n\n");

        buf.append("## 各测评对象详细报告\n\n");
        for (Comprehensive comprehensive : this.comprehensives) {
            buf.append("### 测评人：");
            buf.append(comprehensive.getName()).append("，");
            buf.append("性别").append(comprehensive.getAttribute().getGenderText());
            buf.append("，");
            buf.append("年龄").append(comprehensive.getAttribute().getAgeText());
            buf.append("\n\n");
            int resultSn = 1;
            for (ComprehensiveSection section : comprehensive.getComprehensiveSections()) {
                buf.append("测评结果").append(resultSn++).append("：\n");
                buf.append(section.toMarkdown());
            }
        }
        return buf.toString();
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
        json.put("timestamp", this.timestamp);
        json.put("elapsed", this.elapsed);
        json.put("theme", this.theme.code);

        JSONArray array = new JSONArray();
        for (Comprehensive comprehensive : this.comprehensives) {
            array.put(comprehensive.toJSON());
        }
        json.put("comprehensives", array);

        json.put("state", this.state.code);
        json.put("finished", this.finished);

        json.put("summary", this.summary);
        json.put("sections", this.outputSections());
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
