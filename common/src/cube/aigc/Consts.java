/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.util.FileUtils;
import cube.util.TextUtils;

/**
 * AIGC 模块公共常量与应答文案。
 *
 * <p>集中定义用户类型、对话模式标记，以及各类“无结果”“联网中”“文件信息”等
 * 面向用户的中文应答模板。文案中如需拼接变量，统一用 {@link String#format} 占位。</p>
 */
public final class Consts {

    /** 用户类型：访客。 */
    public final static String USER_TYPE_VISITOR = "visitor";

    /** 用户类型：免费用户。 */
    public final static String USER_TYPE_FREE = "free";

    /** 对话模式：普通对话。 */
    public final static String PATTERN_CHAT = "chat";

    /** 对话模式：知识库问答。 */
    public final static String PATTERN_KNOWLEDGE = "knowledge";

    /** 无匹配内容时的标准应答。 */
    public final static String ANSWER_NO_CONTENT = "很抱歉，我目前没有符合该描述的信息提供给您。";

    /** 用户中断生成时的应答。 */
    public final static String ANSWER_INTERRUPTED = "我已停止了响应。";

    /** 静默应答，用于不需要回复的场景。 */
    public final static String ANSWER_SILENT = "";

    private final static String ANSWER_URL_FAILURE_FORMAT = "这是站点 %s 的访问链接：%s\n我没有访问到该链接数据，此链接可能已经失效了。";

    private final static String ANSWER_URL_PAGE_FORMAT = "这是来自站点 [%s](%s) 的页面链接。\n页面标题是：《 **%s** 》。\n页面正文约 %d 个字符。";

    private final static String ANSWER_URL_IMAGE_FORMAT = "这是张来自 %s 的 %s 图片。\n图片宽 %d 像素，高 %d 像素，文件大小是 %s 。";

    private final static String ANSWER_URL_PLAIN_FORMAT = "这是来自 %s 的文本格式的文件。\n文件包含 %d 个字符，文件大小是 %s 。";

    private final static String ANSWER_URL_VIDEO_FORMAT = "这是来自 %s 的视频文件。\n文件大小是 %s 。";

    private final static String ANSWER_URL_AUDIO_FORMAT = "这是来自 %s 的音频文件。\n文件大小是 %s 。";

    private final static String ANSWER_URL_OTHER_FORMAT = "这是来自 %s 的文件。\n文件大小是 %s 。";

    private final static String ANSWER_URL_SOME_FORMAT = "这是 **%d** 个链接地址，我帮您进行了整理：";

    private final static String ANSWER_URL_SOME_PAGE_FORMAT = "[%s](%s) 是**页面链接** 。";
    private final static String ANSWER_URL_SOME_IMAGE_FORMAT = "[%s](%s) 是**图片链接** 。";
    private final static String ANSWER_URL_SOME_PLAIN_FORMAT = "[%s](%s) 是**文本格式数据链接** 。";
    private final static String ANSWER_URL_SOME_VIDEO_FORMAT = "[%s](%s) 是**视频数据链接** 。";
    private final static String ANSWER_URL_SOME_AUDIO_FORMAT = "[%s](%s) 是**音频数据链接** 。";
    private final static String ANSWER_URL_SOME_OTHER_FORMAT = "[%s](%s) 链接的数据格式是 %s 。";
    private final static String ANSWER_URL_SOME_FAILURE_FORMAT = "链接 [%s](%s) 无法访问。";

    private final static String ANSWER_CHART_FORMAT = "这是**%s**的图表。";

    private final static String ANSWER_CHART_SOME_FORMAT = "目前可以为您提供 **%d** 张图表。";
    private final static String ANSWER_CHART_SOME_ONE_FORMAT = "**%s** 图表。";

    private final static int URL_ELLIPSIS_LIMIT = 56;

    /**
     * 知识库问答的提示词后缀。
     *
     * <p>约束模型只依据检索到的已知信息作答，禁止编造。</p>
     */
    public final static String PROMPT_SUFFIX_FORMAT = "根据上述已知信息回答用户的问题。如果无法从中得到答案，请说“没有提供足够的相关信息”，不允许在答案中添加编造成分。问题是：%s";

    private final static String ASK_FORMAT = "已知信息：\n%s\n\n" +
            "根据上述已知信息，专业的来回答用户的问题。如果无法从中得到答案，请说“没有提供足够的相关信息”，不允许在答案中添加编造成分。问题是：%s";

    private final static String EXTRACT_CONTENT_RELATIONSHIPS_FORMAT = "已知内容：\n%s\n\n" +
            "请分析上述已知内容，把上述内容里与“%s”相关的内容提取出来。如果没有相关内容，请说“没有提供足够的相关信息”";

    /**
     * 信息不足时模型应输出的句子。
     *
     * <p>用于判定回答是否命中知识库内容。</p>
     */
    public final static String NO_CONTENT_SENTENCE = "没有提供足够的相关信息。";

    /** 联网检索进行中的提示语。 */
    public final static String SEARCHING_INTERNET_FOR_INFORMATION = "正在联网搜索可用信息。";

    /** 知识库摘要段落的提示语。 */
    public final static String KNOWLEDGE_SECTION_PROMPT = "整理信息的主要观点，不超过5条观点。";

    // 修复下面这段文章的标点符号并分成段落：<文本内容>

    /** 未找到目标年份数据时的应答模板。 */
    public final static String ANSWER_FIND_SOME_YEAR_DATA_FORMAT = "没有找到%s的数据，但是找到了%s的数据。";

    /**
     * 年龄近义词。
     */
    public final static String[] AGE_SYNONYMS = new String[] { "年龄", "年纪", "岁数", "年岁", "年华", "岁" };

    /**
     * 性别近义词。
     */
    public final static String[] GENDER_SYNONYMS = new String[] { "性别", "姓别", "男女" };

    /**
     * 生成网页抓取失败的应答。
     *
     * @param domain 站点域名。
     * @param url    失败地址。
     * @return 应答文案。
     */
    public static String formatUrlFailureAnswer(String domain, String url) {
        return String.format(ANSWER_URL_FAILURE_FORMAT, domain, url);
    }

    /**
     * 生成网页资源的应答。
     *
     * @param url      页面地址。
     * @param domain   站点域名。
     * @param title    页面标题。
     * @param numWords 正文字数。
     * @return 应答文案。
     */
    public static String formatUrlPageAnswer(String url, String domain, String title, int numWords) {
        return String.format(ANSWER_URL_PAGE_FORMAT, domain, url, title, numWords);
    }

    /**
     * 生成图片资源的应答。
     *
     * @param domain 站点域名。
     * @param format 图片格式。
     * @param width  宽度，单位像素。
     * @param height 高度，单位像素。
     * @param size   字节大小。
     * @return 应答文案。
     */
    public static String formatUrlImageAnswer(String domain, String format, int width, int height, long size) {
        return String.format(ANSWER_URL_IMAGE_FORMAT, domain, format, width, height,
                FileUtils.scaleFileSize(size).toString());
    }

    /**
     * 生成纯文本资源的应答。
     *
     * @param domain   站点域名。
     * @param numWords 正文字数。
     * @param size     字节大小。
     * @return 应答文案。
     */
    public static String formatUrlPlainAnswer(String domain, int numWords, long size) {
        return String.format(ANSWER_URL_PLAIN_FORMAT, domain, numWords, FileUtils.scaleFileSize(size).toString());
    }

    /**
     * 生成视频资源的应答。
     *
     * @param domain 站点域名。
     * @param size   字节大小。
     * @return 应答文案。
     */
    public static String formatUrlVideoAnswer(String domain, long size) {
        return String.format(ANSWER_URL_VIDEO_FORMAT, domain, FileUtils.scaleFileSize(size).toString());
    }

    /**
     * 生成音频资源的应答。
     *
     * @param domain 站点域名。
     * @param size   字节大小。
     * @return 应答文案。
     */
    public static String formatUrlAudioAnswer(String domain, long size) {
        return String.format(ANSWER_URL_AUDIO_FORMAT, domain, FileUtils.scaleFileSize(size).toString());
    }

    /**
     * 生成其他类型资源的应答。
     *
     * @param domain 站点域名。
     * @param size   字节大小。
     * @return 应答文案。
     */
    public static String formatUrlOtherAnswer(String domain, long size) {
        return String.format(ANSWER_URL_OTHER_FORMAT, domain, FileUtils.scaleFileSize(size).toString());
    }

    /**
     * 生成“共检索到多个链接”的应答。
     *
     * @param numURLs 链接数量。
     * @return 应答文案。
     */
    public static String formatUrlSomeAnswer(int numURLs) {
        return String.format(ANSWER_URL_SOME_FORMAT, numURLs);
    }

    /**
     * 生成“多个网页结果之一”的应答。
     *
     * <p>链接会按固定长度截断显示，同时给出完整地址。</p>
     *
     * @param url 页面地址。
     * @return 应答文案。
     */
    public static String formatUrlSomePageAnswer(String url) {
        return String.format(ANSWER_URL_SOME_PAGE_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url);
    }

    /**
     * 生成“多个图片结果之一”的应答。
     *
     * @param url 图片地址。
     * @return 应答文案。
     */
    public static String formatUrlSomeImageAnswer(String url) {
        return String.format(ANSWER_URL_SOME_IMAGE_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url);
    }

    /**
     * 生成“多个文本结果之一”的应答。
     *
     * @param url 资源地址。
     * @return 应答文案。
     */
    public static String formatUrlSomePlainAnswer(String url) {
        return String.format(ANSWER_URL_SOME_PLAIN_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url);
    }

    /**
     * 生成“多个视频结果之一”的应答。
     *
     * @param url 视频地址。
     * @return 应答文案。
     */
    public static String formatUrlSomeVideoAnswer(String url) {
        return String.format(ANSWER_URL_SOME_VIDEO_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url);
    }

    /**
     * 生成“多个音频结果之一”的应答。
     *
     * @param url 音频地址。
     * @return 应答文案。
     */
    public static String formatUrlSomeAudioAnswer(String url) {
        return String.format(ANSWER_URL_SOME_AUDIO_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url);
    }

    /**
     * 生成“多个其他类型结果之一”的应答。
     *
     * @param url    资源地址。
     * @param format 资源格式。
     * @return 应答文案。
     */
    public static String formatUrlSomeOtherAnswer(String url, String format) {
        return String.format(ANSWER_URL_SOME_OTHER_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url, format);
    }

    /**
     * 生成“多个抓取失败结果之一”的应答。
     *
     * @param url 失败地址。
     * @return 应答文案。
     */
    public static String formatUrlSomeFailureAnswer(String url) {
        return String.format(ANSWER_URL_SOME_FAILURE_FORMAT, TextUtils.ellipsisURL(url, URL_ELLIPSIS_LIMIT), url);
    }

    /**
     * 生成图表结果的应答。
     *
     * @param desc 图表描述。
     * @return 应答文案。
     */
    public static String formatChartAnswer(String desc) {
        return String.format(ANSWER_CHART_FORMAT, desc);
    }

    /**
     * 生成“多个图表结果”的应答。
     *
     * @param num 图表数量。
     * @return 应答文案。
     */
    public static String formatChartSomeAnswer(int num) {
        return String.format(ANSWER_CHART_SOME_FORMAT, num);
    }

    /**
     * 生成单个图表结果的应答。
     *
     * @param desc 图表描述。
     * @return 应答文案。
     */
    public static String formatChartSomeOneAnswer(String desc) {
        return String.format(ANSWER_CHART_SOME_ONE_FORMAT, desc);
    }

    /**
     * 按模板拼装提问。
     *
     * @param data  已知信息。
     * @param query 用户问题。
     * @return 拼装后的提问文本。
     */
    public static String formatQuestion(String data, String query) {
        return String.format(ASK_FORMAT, data, query);
    }

    /**
     * 按模板拼装内容抽取请求。
     *
     * @param content     待抽取内容。
     * @param relatedText 关联文本。
     * @return 拼装后的抽取指令。
     */
    public static String formatExtractContent(String content, String relatedText) {
        return String.format(EXTRACT_CONTENT_RELATIONSHIPS_FORMAT, content, relatedText);
    }

    /**
     * 判断单词是否出现在数组中。
     *
     * <p>逐项精确比较，忽略大小写。</p>
     *
     * @param word 待查单词。
     * @param list 候选数组。
     * @return 命中返回 {@code true} 。
     */
    public static boolean contains(String word, String[] list) {
        for (String w : list) {
            if (word.equalsIgnoreCase(w)) {
                return true;
            }
        }
        return false;
    }
}
