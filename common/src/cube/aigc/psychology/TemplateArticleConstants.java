/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.aigc.psychology;

/**
 * 模板文章常量。
 *
 * <p>集中定义按性别与年龄拼接的测评任务描述模板。</p>
 */
public class TemplateArticleConstants {

    /**
     * 通俗化。
     */
    public final static String Popularization = "popularization";

    /**
     * 抑郁。
     */
    public final static String Depression = "depression";

    /** 抑郁倾向测评的任务描述模板，含性别与年龄占位符。 */
    public final static String FormatDepressionTaskDesc = "该测评是使用绘画投射方式进行的抑郁倾向测评，受测人是%s性，%s。" +
            "抑郁倾向级别是 **%s** （测评级别从低到高依次分为：很低、低、中等、高）。\n\n画面内容如下：\n%s\n";

    /**
     * 焦虑。
     */
    public final static String Anxiety = "anxiety";

    /** 焦虑情绪测评的任务描述模板。 */
    public final static String FormatAnxietyTaskDesc = "该测评是使用绘画投射方式进行的焦虑情绪测评，受测人是%s性，%s。" +
            "焦虑情绪级别是 **%s** （测评级别从低到高依次分为：很低、低、中等、高）。\n\n画面内容如下：\n%s\n";

    /**
     * 强迫。
     */
    public final static String Obsession = "obsession";

    /** 强迫程度测评的任务描述模板。 */
    public final static String FormatObsessionTaskDesc = "该测评是使用绘画投射方式进行的强迫程度测评，受测人是%s性，%s。" +
            "强迫程度级别是 **%s** （测评级别从低到高依次分为：很低、低、中等、高）。\n\n画面内容如下：\n%s\n";

    /**
     * 压力。
     */
    public final static String Stress = "stress";

    /** 压力感受与应对策略测评的任务描述模板（雨中人绘画）。 */
    public final static String FormatStressTaskDesc = "该测评是使用雨中人绘画投射方式进行压力感受和应对策略测评，受测人是%s性，%s。" +
            "压力程度级别是 **%s** （测评级别从低到高依次分为：很低、低、中等、高）。\n\n画面内容如下：\n%s\n";
}
