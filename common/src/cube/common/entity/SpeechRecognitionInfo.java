/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import cube.util.JSONUtils;
import cube.util.TextUtils;
import org.json.JSONObject;

import java.util.List;

/**
 * 语音识别结果。
 */
public class SpeechRecognitionInfo implements JSONable {

    /** 文件。 */
    public final FileLabel file;

    /** 耗时。 */
    public final long elapsed;

    /** 文本。 */
    public String text;

    /** 词列表。 */
    public final List<String> words;

    /** 语言。 */
    public final String lang;

    /** 时长（秒）。 */
    public final double durationInSeconds;


    public SpeechRecognitionInfo(JSONObject json) {
        this.file = json.has("file") ? new FileLabel(json.getJSONObject("file")) : null;
        this.elapsed = json.getLong("elapsed");
        this.text = json.getString("text");
        this.words = JSONUtils.toStringList(json.getJSONArray("words"));
        this.lang = json.getString("lang");
        this.durationInSeconds = Double.parseDouble(json.get("duration").toString());
    }

    public SpeechRecognitionInfo(SpeechRecognitionInfo info, String text) {
        this.file = info.file;
        this.elapsed = info.elapsed;
        this.text = text;
        this.words = info.words;
        this.lang = info.lang;
        this.durationInSeconds = info.durationInSeconds;
    }

    /**
     * 重置词列表。
     *
     * @param words 词列表。
     */
    public void resetWords(List<String> words) {
        this.words.clear();
        for (String word : words) {
            if (TextUtils.containsChinesePunctuation(word) || TextUtils.containsEnglishPunctuation(word)) {
                continue;
            }

            this.words.add(word);
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (null != this.file) {
            json.put("file", this.file.toCompactJSON());
        }
        json.put("elapsed", this.elapsed);
        json.put("text", this.text);
        json.put("words", JSONUtils.toStringArray(this.words));
        json.put("lang", this.lang);
        json.put("duration", this.durationInSeconds);
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
