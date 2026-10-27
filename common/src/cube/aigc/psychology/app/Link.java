/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.app;

public class Link {

    /** 深链协议前缀，用于唤起客户端的提示词页面。 */
    public final static String PromptDirect = "aixinli://prompt.direct/";

    /**
     * 拼接提示词深链。
     *
     * @param value 提示词内容。
     * @return 完整深链地址。
     */
    public final static String formatPromptDirect(String value) {
        return String.format("%s%s", PromptDirect, value.replaceAll(" ", ""));
    }

    /**
     * 拼接提示词深链的 Markdown 形式。
     *
     * @param title 标题。
     * @param value 提示词内容。
     * @return Markdown 链接。
     */
    public final static String formatPromptDirectMarkdown(String title, String value) {
        return String.format("[%s](%s%s)", title.replaceAll(" ", ""),
                PromptDirect, value.replaceAll(" ", ""));
    }

    /** 量表回答。 */
    public final static String ScaleAnswer = "aixinli://scale.answer/";

    /** 引导回答。 */
    public final static String GuideAnswer = "aixinli://guide.answer/";

    private Link() {
    }
}
