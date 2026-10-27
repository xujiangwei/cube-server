/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import org.json.JSONObject;

/**
 * 情绪枚举。
 *
 * <p>定义可识别的情绪类型，提供常用子集与按词解析。</p>
 */
public enum Emotion {

    Angry("愤怒", "生气"),

    Fearful("恐惧", "害怕"),

    Happy("快乐", "开心"),

    Neutral("中性", "平和"),

    Sad("悲伤", "伤心"),

    Surprise("惊讶", "诧异"),

    Disgusted("厌恶", "讨厌"),

    Other("其他", "其他"),

    Unknown("未知", "未知"),

    None("无", "无"),

    ;

    /** 主要词。 */
    public final String primaryWord;

    /** 次要词。 */
    public final String secondaryWord;

    Emotion(String primaryWord, String secondaryWord) {
        this.primaryWord = primaryWord;
        this.secondaryWord = secondaryWord;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("name", this.name());
        json.put("primaryWord", this.primaryWord);
        json.put("secondaryWord", this.secondaryWord);
        return json;
    }

    /**
     * 获取常用情绪集合。
     *
     * @return 常用情绪数组。
     */
    public static Emotion[] commonEmotions() {
        return new Emotion[]{
                Emotion.Angry,
                Emotion.Fearful,
                Emotion.Happy,
                Emotion.Neutral,
                Emotion.Sad,
                Emotion.Surprise
        };
    }

    /**
     * 按编码解析。
     *
     * <p>遍历全部枚举值逐个匹配，未命中时返回 {@code null} 。</p>
     *
     * @param word 编码值。
     * @return 对应枚举；未命中返回 {@code null} 。
     */
    public static Emotion parse(String word) {
        for (Emotion emotion : Emotion.values()) {
            if (emotion.name().equalsIgnoreCase(word) ||
                    emotion.primaryWord.equalsIgnoreCase(word) ||
                    emotion.secondaryWord.equalsIgnoreCase(word)) {
                return emotion;
            }
        }
        return None;
    }
}
