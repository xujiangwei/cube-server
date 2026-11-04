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
 * {@code script}/{@code style}/{@code head} 里的内容一律丢弃。</p>
 */
final class HtmlText {
    /**
     * 块级元素在正文中留下的换行，避免相邻段落粘连。
     */
    private static final char BLOCK_BREAK = '\n';

    /**
     * 连续空白与不换行空格的归一化规则。
     */
    private static final String WHITESPACE = "[\\s\\u00a0]+";

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
        // 记录当前处于哪一层隐藏元素内，嵌套时逐层退栈。
        final Deque<String> hidden = new ArrayDeque<>();
        // 匿名回调需要写入状态，用单元素数组承载。
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
                        body.append(BLOCK_BREAK);
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
                        body.append(BLOCK_BREAK);
                    }
                }

                @Override
                public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    // br 与 hr 没有结束标签，单独补换行。
                    if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) {
                        body.append(BLOCK_BREAK);
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
        return value.replaceAll(WHITESPACE, " ").trim();
    }
}
