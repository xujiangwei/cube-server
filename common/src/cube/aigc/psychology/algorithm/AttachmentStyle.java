/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.algorithm;

import cube.aigc.psychology.indicator.Indicator;
import cube.common.Language;

/**
 * 依恋风格枚举。
 *
 * <p>按成对场景描述四种依恋类型，提供中英文名称与场景文案。</p>
 */
public enum AttachmentStyle {

    /**
     * 安全型。
     */
    Secure("Secure", "安全型依恋", "Secure Attachment",
            "吵架", "", "表达需求", "", "处理分歧", "",
            new String[] { "情感稳定", "亲密舒适", "善于沟通", "信任他人", "界限清晰", "支持性伴侣", "积极乐观", "关系有信心",
                    "灵活适应", "自我完整", "坦诚表达", "情绪健康", "有效共情", "冲突解决者", "内在安全感", "容易宽恕", "关系持久",
                    "共同成长", "平和从容", "值得信赖" },
            new String[] {}),

    /**
     * 焦虑型。
     */
    AnxiousPreoccupied("AnxiousPreoccupied", "焦虑型依恋", "Anxious-Preoccupied Attachment",
            "吵架", "", "关系进度", "", "冲突后的和解", "",
            new String[] { "思虑过度", "害怕被弃", "寻求认可", "情感依赖", "高度敏感", "情绪波动", "关系中心", "过度付出",
                    "患得患失", "需要保证", "解读信号", "担心失去", "情绪饥渴", "粘连倾向", "自我怀疑", "讨好倾向", "即时满足",
                    "戏剧化", "缺乏安全感", "占有欲强" },
            new String[] {}),

    /**
     * 回避型。
     */
    DismissiveAvoidant("DismissiveAvoidant", "回避型依恋", "Dismissive-Avoidant Attachment",
            "吵架", "", "亲密举动", "", "自己的脆弱", "",
            new String[] { "高度独立", "情感疏离", "自我依赖", "注重边界", "崇尚自由", "不适亲密", "保持距离", "情绪抑制",
                    "重视自我空间", "理性分析", "自给自足", "不喜依赖", "被动退缩", "情感自主", "不擅表达", "回避冲突",
                    "强调自控", "沉浸工作", "情感保守", "不轻易承诺" },
            new String[] {}),

    /**
     * 混乱型。
     */
    Disorganized("Disorganized", "混乱型依恋", "Disorganized Attachment",
            "吵架", "", "关系推进", "", "过去的创伤", "",
            new String[] { "内心矛盾", "渴望又恐惧", "信任困难", "情绪混乱", "行为反复", "极度不安", "悲观预期", "界限模糊",
                    "自我否定", "关系动荡", "爱恨交织", "难以安抚", "试探底线", "害怕受伤", "自我破坏", "信任测试", "强烈占有",
                    "创伤敏感", "犹豫不决", "极端冲动" },
            new String[] {}),

    ;

    /** 风格。 */
    public final String style;

    /** 名称（中文）。 */
    public final String nameCN;

    /** 名称（英文）。 */
    public final String nameEN;

    /** 场景一（中文）。 */
    public final String scene1CN;

    /** 场景一（英文）。 */
    public final String scene1EN;

    /** 场景二（中文）。 */
    public final String scene2CN;

    /** 场景二（英文）。 */
    public final String scene2EN;

    /** 场景三（中文）。 */
    public final String scene3CN;

    /** 场景三（英文）。 */
    public final String scene3EN;

    /** 关键词列表（中文）。 */
    public final String[] keywordsCN;

    /** 关键词列表（英文）。 */
    public final String[] keywordsEN;

    AttachmentStyle(String style, String nameCN, String nameEN,
                    String scene1CN, String scene1EN,
                    String scene2CN, String scene2EN,
                    String scene3CN, String scene3EN,
                    String[] keywordsCN, String[] keywordsEN) {
        this.style = style;
        this.nameCN = nameCN;
        this.nameEN = nameEN;
        this.scene1CN = scene1CN;
        this.scene1EN = scene1EN;
        this.scene2CN = scene2CN;
        this.scene2EN = scene2EN;
        this.scene3CN = scene3CN;
        this.scene3EN = scene3EN;
        this.keywordsCN = keywordsCN;
        this.keywordsEN = keywordsEN;
    }

    /**
     * 获取场景一标题。
     *
     * @param language 场景一标题。
     * @return 场景一标题。
     */
    public String getScene1Title(Language language) {
        return language.isChinese() ? this.nameCN + this.scene1CN + "场景" :
                "The scene of " + this.nameEN + this.scene1EN;
    }

    /**
     * 获取场景二标题。
     *
     * @param language 场景二标题。
     * @return 场景二标题。
     */
    public String getScene2Title(Language language) {
        return language.isChinese() ? this.nameCN + this.scene2CN + "场景" :
                "The scene of " + this.nameEN + this.scene2EN;
    }

    /**
     * 获取场景三标题。
     *
     * @param language 场景三标题。
     * @return 场景三标题。
     */
    public String getScene3Title(Language language) {
        return language.isChinese() ? this.nameCN + this.scene3CN + "场景" :
                "The scene of " + this.nameEN + this.scene3EN;
    }

    /**
     * 获取关键词列表。
     *
     * @param language 关键词列表。
     * @return 关键词列表。
     */
    public String[] getKeywords(Language language) {
        return language.isChinese() ? this.keywordsCN : this.keywordsEN;
    }

    /**
     * 解析为枚举值。
     *
     * <p>遍历全部枚举值逐个匹配，未识别时返回 {@code null} 。</p>
     *
     * @param indicator 编码值。
     * @return 解析结果；无法识别时返回 {@code null} 。
     */
    public static AttachmentStyle parse(Indicator indicator) {
        switch (indicator) {
            case SecureAttachment:
                return AttachmentStyle.Secure;
            case AnxiousPreoccupiedAttachment:
                return AttachmentStyle.AnxiousPreoccupied;
            case DismissiveAvoidantAttachment:
                return AttachmentStyle.DismissiveAvoidant;
            case DisorganizedAttachment:
                return AttachmentStyle.Disorganized;
            default:
                return null;
        }
    }
}
