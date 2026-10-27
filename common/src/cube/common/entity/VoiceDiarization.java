/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.Language;
import cube.util.ConfigUtils;
import cube.util.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音分离结果。
 *
 * <p>承载一次语音转写与说话人分离的完整结果：原始文件、分段轨迹、
 * 时长与发言统计，以及基于转写文本的分析与建议。</p>
 */
public class VoiceDiarization extends Entity {

    /**
     * 说话人默认名称池。
     *
     * <p>按分离出的说话人顺序依次取名，长度即为支持的最大说话人数。</p>
     */
    public final static String[] SPEAKER_NAMES = new String[] {
            "张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十", "徐大", "田二"
    };

    /**
     * 说话人默认显示名池。
     *
     * <p>与 {@link #SPEAKER_NAMES} 一一对应，用于界面展示。</p>
     */
    public final static String[] SPEAKER_DISPLAY_NAMES = new String[] {
            "说话人1", "说话人2", "说话人3", "说话人4", "说话人5", "说话人6", "说话人7", "说话人8", "说话人9", "说话人10"
    };

    /**
     * 所属联系人 ID 。
     */
    public long contactId;

    /**
     * 标题。
     */
    public String title;

    /**
     * 备注。
     */
    public String remark;

    /**
     * 源文件码。
     */
    public String fileCode;

    /**
     * 源文件标签。
     */
    public FileLabel file;

    /**
     * 单位：秒。
     */
    public double duration;

    /**
     * 处理耗时，单位毫秒。
     */
    public long elapsed;

    /**
     * 说话人轨迹列表。
     *
     * <p>每条轨迹对应一段连续发言，按时间顺序排列。</p>
     */
    public List<VoiceTrack> tracks;

    /**
     * 语音指标，包含语速、情绪等统计。
     */
    public VoiceIndicator indicator;

    /**
     * 基于转写文本的分析结论。
     */
    public String analysis;

    /**
     * 基于分析结论给出的建议。
     */
    public String suggestion;

    public VoiceDiarization(JSONObject json) {
        super(json);

        // 处理 ID
        if (!json.has("id")) {
            this.id = ConfigUtils.generateSerialNumber();
        }

        if (json.has("contactId")) {
            this.contactId = json.getLong("contactId");
        }
        if (json.has("title")) {
            this.title = json.getString("title");
        }
        if (json.has("remark")) {
            this.remark = json.getString("remark");
        }
        if (json.has("fileCode")) {
            this.fileCode = json.getString("fileCode");
        }
        if (json.has("file")) {
            this.file = new FileLabel(json.getJSONObject("file"));
            this.fileCode = this.file.getFileCode();
        }
        this.duration = json.getDouble("duration");
        this.elapsed = json.getLong("elapsed");
        this.tracks = new ArrayList<>();
        JSONArray array = json.getJSONArray("tracks");
        for (int i = 0; i < array.length(); ++i) {
            this.tracks.add(new VoiceTrack(array.getJSONObject(i)));
        }

        if (json.has("indicator")) {
            this.indicator = new VoiceIndicator(json.getJSONObject("indicator"));
        }

        if (json.has("analysis")) {
            this.analysis = json.getString("analysis");
        }

        if (json.has("suggestion")) {
            this.suggestion = json.getString("suggestion");
        }
    }

    public VoiceDiarization(long id, long timestamp, long contactId, String title, String remark, String fileCode,
                            double duration, long elapsed) {
        super(id, "", timestamp);
        this.contactId = contactId;
        this.title = title;
        this.remark = remark;
        this.fileCode = fileCode;
        this.duration = duration;
        this.elapsed = elapsed;
        this.tracks = new ArrayList<>();
    }

    /**
     * 重命名说话人标签。
     *
     * <p>把所有匹配 {@code oldLabel} 的轨迹改为 {@code newLabel} ，用于人工校正分离结果。</p>
     *
     * @param oldLabel 原标签。
     * @param newLabel 新标签。
     */
    public void setTrackLabel(String oldLabel, String newLabel) {
        for (VoiceTrack track : this.tracks) {
            if (track.label.equals(oldLabel)) {
                track.label = newLabel;
            }
        }
    }

    /**
     * 提取全部说话人标签。
     *
     * @return 按轨迹顺序去重前的标签列表，可能包含重复项。
     */
    public List<String> extractTrackLabels() {
        List<String> result = new ArrayList<>();
        for (VoiceTrack track : this.tracks) {
            if (result.contains(track.label)) {
                continue;
            }
            result.add(track.label);
        }
        return result;
    }

    /**
     * 按说话人重新分配标签。
     *
     * <p>先按原标签聚类，再依次套用 {@link #SPEAKER_NAMES} ，同时为每条轨迹回填序号。
     * 说话人数量超过名称池长度时会抛出下标越界异常。</p>
     *
     * @return 实际识别出的说话人数量。
     */
    public int alignSpeakerLabels() {
        HashMap<String, List<VoiceTrack>> map = new HashMap<>();
        for (int i = 0; i < this.tracks.size(); ++i) {
            VoiceTrack track = this.tracks.get(i);
            track.track = String.valueOf(i + 1);

            if (map.containsKey(track.label)) {
                List<VoiceTrack> trackList = map.get(track.label);
                trackList.add(track);
            }
            else {
                List<VoiceTrack> trackList = new ArrayList<>();
                trackList.add(track);
                map.put(track.label, trackList);
            }
        }

        int index = 0;
        for (String key : map.keySet()) {
            String newName = SPEAKER_NAMES[index];
            ++index;
            List<VoiceTrack> trackList = map.get(key);
            for (VoiceTrack track : trackList) {
                track.label = newName;
            }
        }

        return map.size();
    }

    /**
     * 优化说话者标签显示。
     *
     * @param language
     */
    public void enhanceSpeakerDisplayNames(Language language) {
        Map<String, String> usedNameMap = new HashMap<>();
        for (int i = 0; i < this.tracks.size(); ++i) {
            VoiceTrack track = this.tracks.get(i);
            if (usedNameMap.containsKey(track.display)) {
                track.display = usedNameMap.get(track.display);
            }
            else {
                String oldDisplay = track.display;
                String newDisplay = SPEAKER_DISPLAY_NAMES[usedNameMap.size()];
                usedNameMap.put(oldDisplay, newDisplay);
                track.display = newDisplay;
            }
        }
    }

    /**
     * 拼接可读的对话文本。
     *
     * <p>{@code merge} 为 {@code true} 时合并同一说话人的连续发言，只保留一次说话人前缀。</p>
     *
     * @param merge 是否合并同一说话人的连续片段。
     * @return 形如 {@code 说话人：内容} 的多行文本。
     */
    public String buildSpeechText(boolean merge) {
        StringBuilder buf = new StringBuilder();
        if (merge) {
            String lastDisplay = null;
            for (VoiceTrack track : this.tracks) {
                if (null != lastDisplay && lastDisplay.equalsIgnoreCase(track.display)) {
                    buf.delete(buf.length() - 1, buf.length());
                    buf.append(track.recognition.text);
                    buf.append("\n");
                }
                else {
                    buf.append(track.display).append(TextUtils.gColonInChinese).append(track.recognition.text);
                    buf.append("\n");
                }
                lastDisplay = track.display;
            }
        }
        else {
            for (VoiceTrack track : this.tracks) {
                buf.append(track.display).append(TextUtils.gColonInChinese).append(track.recognition.text);
                buf.append("\n");
            }
        }
        return buf.toString();
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 语音分离结果 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("contactId", this.contactId);

        if (null != this.title) {
            json.put("title", this.title);
        }
        else {
            json.put("title", "");
        }

        if (null != this.remark) {
            json.put("remark", this.remark);
        }
        else {
            json.put("remark", "");
        }

        if (null != this.file) {
            json.put("file", this.file.toJSON());
        }
        json.put("fileCode", this.fileCode);
        json.put("duration", this.duration);
        json.put("elapsed", this.elapsed);
        JSONArray array = new JSONArray();
        for (VoiceTrack track : this.tracks) {
            array.put(track.toJSON());
        }
        json.put("tracks", array);

        if (null != this.indicator) {
            json.put("indicator", this.indicator.toJSON());
        }

        if (null != this.analysis) {
            json.put("analysis", this.analysis);
        }

        if (null != this.suggestion) {
            json.put("suggestion", this.suggestion);
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 语音分离结果 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        return this.toJSON();
    }
}
