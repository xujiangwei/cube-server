/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology.composition;

import cube.aigc.psychology.Theme;
import cube.aigc.psychology.algorithm.IndicatorRate;
import cube.common.Language;
import cube.common.entity.Article;
import cube.common.state.AIGCStateCode;
import cube.util.TimeUtils;
import org.json.JSONObject;

public class ReportArticle extends Article {

    /** 主题。 */
    public final Theme theme;

    /** 模板名称。 */
    public final String templateName;

    /** 时间戳。 */
    public final long timestamp;

    /** 比率。 */
    public IndicatorRate rate = IndicatorRate.Lowest;

    /** 状态。 */
    public AIGCStateCode state = AIGCStateCode.Ok;

    public ReportArticle(String title, Theme theme, String templateName, long timestamp) {
        super(title);
        this.theme = theme;
        this.templateName = templateName;
        this.timestamp = timestamp;
    }

    /**
     * 转换为Markdown。
     *
     * @return Markdown。
     */
    @Override
    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        if (null != this.title) {
            buf.append("# ").append(this.title).append("\n\n");
        }

        buf.append("> ").append("报告日期：");
        buf.append(TimeUtils.formatDateString(this.timestamp, Language.Chinese));
        buf.append("\n\n");

        if (null != this.content) {
            buf.append(this.content).append("\n\n");
        }
        if (!this.paragraphs.isEmpty()) {
            for (Paragraph paragraph : this.paragraphs) {
                buf.append(paragraph.toMarkdown());
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
        JSONObject json = super.toJSON();
        json.put("theme", this.theme.code);
        json.put("templateName", this.templateName);
        json.put("timestamp", this.timestamp);
        json.put("rate", this.rate.value);
        json.put("state", this.state.code);
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
        return super.toCompactJSON();
    }
}
