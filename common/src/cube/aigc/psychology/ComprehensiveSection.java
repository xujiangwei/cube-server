/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology;

import cube.aigc.psychology.indicator.Indicable;
import cube.aigc.psychology.indicator.SRBCIndicator;
import cube.common.JSONable;
import cube.util.JSONUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 综合报告章节。
 *
 * <p>描述报告中的一个章节及其内容片段。</p>
 */
public class ComprehensiveSection implements JSONable {

    /** 标题。 */
    public final String title;

    /** 内容列表。 */
    public final List<String> contents;

    /** 指标。 */
    public Indicable indicator;

    public ComprehensiveSection(String title, String content) {
        this(title, content, null);
    }

    public ComprehensiveSection(String title, String content, Indicable indicator) {
        this.title = title;
        this.contents = new ArrayList<>();
        this.contents.add(content);
        this.indicator = indicator;
    }

    public ComprehensiveSection(JSONObject json) {
        this.title = json.getString("title");
        JSONArray array = json.getJSONArray("contents");
        this.contents = JSONUtils.toStringList(array);
        if (json.has("indicator")) {
            String value = json.getString("indicator");
            this.indicator = SRBCIndicator.parse(value);
        }
    }

    /**
     * 获取内容。
     *
     * @return 内容。
     */
    public String getContent() {
        return this.contents.get(0);
    }

    /**
     * 转换为Markdown。
     *
     * @return Markdown。
     */
    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        buf.append("**标题** ：").append(this.title).append("\n");
        buf.append("**内容** ：").append(this.contents.get(0)).append("\n\n");
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
        json.put("title", this.title);
        json.put("contents", JSONUtils.toStringArray(this.contents));
        if (null != this.indicator) {
            json.put("indicator", this.indicator.getCode());
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
