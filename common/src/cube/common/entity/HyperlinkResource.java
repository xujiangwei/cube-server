/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.util.TextUtils;
import org.json.JSONObject;

/**
 * 超链接资源。
 */
public class HyperlinkResource extends ComplexResource {

    /**
     * 类型：HTML 页面。
     */
    public final static String TYPE_PAGE = "page";

    /**
     * 类型：纯文本。
     */
    public final static String TYPE_PLAIN = "plain";

    /**
     * 类型：图片。
     */
    public final static String TYPE_IMAGE = "image";

    /**
     * 类型：视频。
     */
    public final static String TYPE_VIDEO = "video";

    /**
     * 类型：音频。
     */
    public final static String TYPE_AUDIO = "audio";

    /**
     * 类型：其他未归类的资源。
     */
    public final static String TYPE_OTHER = "other";

    /**
     * 类型：抓取失败。
     *
     * <p>该类型下只有 {@link #url} 有意义，其余字段为空。</p>
     */
    public final static String TYPE_FAILURE = "failure";

    /**
     * 资源原始地址。
     */
    public final String url;

    /**
     * 资源的 MIME 类型，例如 {@code text/html} 。
     */
    public String mimeType;

    /**
     * 资源的业务分类，取值见本类的 {@code TYPE_*} 常量。
     */
    public String metaType;

    /**
     * 资源所属站点名称。
     */
    public String site;

    /**
     * 资源正文内容。
     *
     * <p>页面类资源为可读文本；媒体类资源通常为空。</p>
     */
    public String content;

    /**
     * 资源标题。
     */
    public String title;

    /**
     * 资源配图地址。
     */
    public String illustration;

    /**
     * 资源在本地存储中的路径。
     */
    public String path;

    /**
     * 资源字节大小。
     */
    public long size;

    /**
     * 正文字数。
     *
     * <p>抓取时未统计则为 0 ，此时由 {@link #getNumWords()} 回退为按字符数计算。</p>
     */
    public int numWords;

    /**
     * 媒体宽度，单位像素。
     */
    public int width;
    /**
     * 媒体高度，单位像素。
     */
    public int height;
    /**
     * 媒体格式，例如 {@code mp4} 。
     */
    public String format;

    /**
     * 缩略图地址。
     */
    public String thumbnail;

    /**
     * 未经处理的原始文本。
     */
    public String rawText;

    public HyperlinkResource(String url, String metaType) {
        super(Subject.Hyperlink);
        this.url = url;
        this.metaType = metaType;
        this.mimeType = "text/plain";
        this.site = TextUtils.extractDomain(url);
        this.content = "[" + this.site + "](" + url + ")";
    }

    public HyperlinkResource(JSONObject json) {
        super(Subject.Hyperlink, json);

        JSONObject payload = json.getJSONObject("payload");

        this.url = payload.has("url") ? payload.getString("url") : "";
        this.metaType = payload.getString("metaType");
        this.mimeType = payload.getString("mimeType");

        if (payload.has("site")) {
            this.site = payload.getString("site");
        }
        else {
            this.site = TextUtils.extractDomain(this.url);
        }

        if (payload.has("title")) {
            this.title = payload.getString("title");
        }
        if (payload.has("content")) {
            this.content = payload.getString("content");
        }

        if (payload.has("illustration")) {
            this.illustration = payload.getString("illustration");
        }

        if (payload.has("path")) {
            this.path = payload.getString("path");
        }
        if (payload.has("size")) {
            this.size = payload.getLong("size");
        }

        if (payload.has("numWords")) {
            this.numWords = payload.getInt("numWords");
        }

        if (payload.has("width")) {
            this.width = payload.getInt("width");
        }
        if (payload.has("height")) {
            this.height = payload.getInt("height");
        }
        if (payload.has("format")) {
            this.format = payload.getString("format");
        }

        if (payload.has("thumbnail")) {
            this.thumbnail = payload.getString("thumbnail");
        }

        if (payload.has("rawText")) {
            this.rawText = payload.getString("rawText");
        }
    }

    /**
     * 获取正文字数。
     *
     * <p>抓取阶段未统计字数时，退化为按正文字符数估算，因此返回的是近似值。</p>
     *
     * @return 正文字数；正文为空时返回 0 。
     */
    public int getNumWords() {
        if (this.numWords == 0 && null != this.content) {
            return this.content.length();
        }
        else {
            return this.numWords;
        }
    }

    /**
     * 规整正文内容。
     *
     * <p>逐行去除首尾空白、丢弃长度不足 2 的空行，并去掉末尾多余换行。
     * 对超长抓取结果做预处理，避免无意义的空白占满存储。</p>
     */
    public void fixContent() {
        if (null != this.content) {
            this.content = this.filterContent(this.content);
        }
    }

    private String filterContent(String content) {
        StringBuilder result = new StringBuilder();
        String[] array = content.split("\n");
        for (String line : array) {
            if (line.trim().length() < 2) {
                continue;
            }
            result.append(line.trim()).append("\n");
        }
        if (result.length() > 1) {
            result.delete(result.length() - 1, result.length());
        }
        return result.toString();
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 超链接资源 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();

        JSONObject payload = new JSONObject();
        payload.put("url", this.url);
        payload.put("site", this.site);
        payload.put("metaType", this.metaType);
        payload.put("mimeType", this.mimeType);

        if (null != this.title) {
            payload.put("title", this.title);
        }
        if (null != this.content) {
            payload.put("content", this.content);
        }
        if (null != this.illustration) {
            payload.put("illustration", this.illustration);
        }

        if (this.numWords > 0) {
            payload.put("numWords", this.numWords);
        }

        if (null != this.path) {
            payload.put("path", this.path);
        }

        if (this.size > 0) {
            payload.put("size", this.size);
        }

        if (null != this.format) {
            payload.put("width", this.width);
            payload.put("height", this.height);
            payload.put("format", this.format);
        }

        if (null != this.thumbnail) {
            payload.put("thumbnail", this.thumbnail);
        }

        json.put("payload", payload);
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return 超链接资源 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        if (json.getJSONObject("payload").has("path")) {
            json.getJSONObject("payload").remove("path");
        }
        return json;
    }
}
