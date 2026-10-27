/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文章。
 *
 * <p>由标题、段落与激活状态组成，激活后才参与检索。</p>
 */
public class Article implements JSONable {

    /** 序列号。 */
    public long sn;

    /** 标题。 */
    protected String title;

    /** 内容。 */
    protected String content;

    /** 段落列表。 */
    protected List<Paragraph> paragraphs;

    public Article(String title) {
        this.title = title;
        this.paragraphs = new ArrayList<>();
    }

    /**
     * 获取标题。
     *
     * @return 标题。
     */
    public String getTitle() {
        return this.title;
    }

    /**
     * 设置内容。
     *
     * @param content 内容。
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 获取内容。
     *
     * @return 内容。
     */
    public String getContent() {
        return this.content;
    }

    /**
     * 添加段落。
     *
     * @param json 段落。
     */
    public void addParagraph(JSONObject json) {
        Paragraph paragraph = new Paragraph(json);
        this.paragraphs.add(paragraph);
    }

    /**
     * 添加段落。
     *
     * @param title 段落。
     * @param content 段落。
     */
    public void addParagraph(String title, String content) {
        this.paragraphs.add(new Paragraph(title, content));
    }

    /**
     * 获取段落列表。
     *
     * @return 段落列表。
     */
    public List<Paragraph> getParagraphs() {
        return this.paragraphs;
    }

    /**
     * 获取段落。
     *
     * @param index 段落。
     * @return 段落。
     */
    public Paragraph getParagraph(int index) {
        if (this.paragraphs.size() <= index) {
            return null;
        }
        return this.paragraphs.get(index);
    }

    /**
     * 拼接全部段落内容。
     *
     * @return 拼接后的文本。
     */
    public String spliceParagraphContent() {
        StringBuilder buf = new StringBuilder();
        for (Paragraph paragraph : this.paragraphs) {
            buf.append(paragraph.content);
            buf.append("\n");
        }
        return buf.toString();
    }

    /**
     * 转换为 Markdown 文本。
     *
     * <p>按标题与段落层级输出，供导出与二次加工。</p>
     *
     * @return Markdown 文本。
     */
    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        if (null != this.title) {
            buf.append("# ").append(this.title).append("\n\n");
        }
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
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("title", this.title);
        if (null != this.content && this.content.length() > 0) {
            json.put("content", this.content);
        }
        if (!this.paragraphs.isEmpty()) {
            JSONArray array = new JSONArray();
            for (Paragraph paragraph : this.paragraphs) {
                array.put(paragraph.toJSON());
            }
            json.put("paragraphs", array);
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


    public class Paragraph implements JSONable {

        public final String title;

        public final String content;

        public Paragraph(String title, String content) {
            this.title = title;
            this.content = content;
        }

        public Paragraph(JSONObject json) {
            this.title = json.getString("title");
            this.content = json.getString("content");
        }

        public String toMarkdown() {
            StringBuilder buf = new StringBuilder();
            buf.append("## ").append(this.title).append("\n");
            buf.append(this.content).append("\n\n");
            return buf.toString();
        }

        @Override
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("title", this.title);
            json.put("content", this.content);
            return json;
        }

        @Override
        public JSONObject toCompactJSON() {
            return this.toJSON();
        }
    }
}
