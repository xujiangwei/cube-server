/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.harness.skill;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 静态 HTML 文本提取器。
 *
 * <p>只解析传入的 HTML 字符串，绝不加载 URL、内嵌资源、脚本、样式表或浏览器；
 * script/style/head 里的内容一律丢弃。</p>
 */
final class HtmlText {
    /**
     * 页面标题。
     */
    final String title;
    /**
     * 正文纯文本。
     */
    final String text;

    /**
     * 构造函数。
     *
     * @param title 页面标题。
     * @param text  正文纯文本。
     */
    private HtmlText(String title, String text) {
        this.title = title;
        this.text = text;
    }

    /**
     * 解析 HTML 文本，抽取标题与正文。
     *
     * @param html HTML 源码。
     * @return 返回解析结果。
     */
    static HtmlText parse(String html) {
        final StringBuilder body = new StringBuilder();
        final StringBuilder title = new StringBuilder();
        final Deque<String> hidden = new ArrayDeque<>();
        final boolean[] inTitle = {false};
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                @Override
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    String name = tag.toString();
                    if (name.equals("script") || name.equals("style") || name.equals("head")) {
                        hidden.push(name);
                    }
                    if (name.equals("title")) {
                        inTitle[0] = true;
                    }
                    if (tag.isBlock()) {
                        body.append('\n');
                    }
                }

                @Override
                public void handleEndTag(HTML.Tag tag, int pos) {
                    String name = tag.toString();
                    if (!hidden.isEmpty() && hidden.peek().equals(name)) {
                        hidden.pop();
                    }
                    if (name.equals("title")) {
                        inTitle[0] = false;
                    }
                    if (tag.isBlock()) {
                        body.append('\n');
                    }
                }

                @Override
                public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) {
                        body.append('\n');
                    }
                }

                @Override
                public void handleText(char[] data, int pos) {
                    if (inTitle[0]) {
                        title.append(data).append(' ');
                    } else if (hidden.isEmpty()) {
                        body.append(data).append(' ');
                    }
                }
            }, true);
        } catch (IOException e) {
            throw new IllegalArgumentException("HTML_PARSE_FAILED");
        }
        return new HtmlText(clean(title.toString()), clean(body.toString()));
    }

    /**
     * 归一化空白字符并去除首尾空格。
     *
     * @param value 原始文本。
     * @return 返回归一化后的文本。
     */
    private static String clean(String value) {
        return value.replaceAll("[\\s\\u00a0]+", " ").trim();
    }
}
