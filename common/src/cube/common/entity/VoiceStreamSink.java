/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.auth.AuthToken;
import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 语音流接收器。
 *
 * <p>接收语音流分片并累积，供后续分析。</p>
 */
public class VoiceStreamSink implements JSONable {

    private String streamName;

    private int index;

    private String fileCode;

    private long timestamp;

    private VoiceDiarization diarization;

    private FileLabel fileLabel;

    /** 访问令牌。 */
    public AuthToken authToken;

    public VoiceStreamSink(String streamName, int index, String fileCode) {
        this.streamName = streamName;
        this.index = index;
        this.fileCode = fileCode;
        this.timestamp = System.currentTimeMillis();
    }

    public VoiceStreamSink(JSONObject json) {
        this.streamName = json.getString("streamName");
        this.index = json.getInt("index");
        this.timestamp = json.getLong("timestamp");
        if (json.has("diarization")) {
            this.diarization = new VoiceDiarization(json.getJSONObject("diarization"));
        }
        if (json.has("file")) {
            this.fileLabel = new FileLabel(json.getJSONObject("file"));
        }
    }

    /**
     * 获取流名称。
     *
     * @return 流名称。
     */
    public String getStreamName() {
        return this.streamName;
    }

    /**
     * 获取索引。
     *
     * @return 索引。
     */
    public int getIndex() {
        return this.index;
    }

    /**
     * 获取文件码。
     *
     * @return 文件码。
     */
    public String getFileCode() {
        return this.fileCode;
    }

    /**
     * 获取时间戳。
     *
     * @return 时间戳。
     */
    public long getTimestamp() {
        return this.timestamp;
    }

    /**
     * 获取语音分离。
     *
     * @return 语音分离。
     */
    public VoiceDiarization getDiarization() {
        return this.diarization;
    }

    /**
     * 获取文件标签。
     *
     * @return 文件标签。
     */
    public FileLabel getFileLabel() {
        return this.fileLabel;
    }

    /**
     * 设置时间戳。
     *
     * @param timestamp 时间戳。
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 设置语音分离。
     *
     * @param diarization 语音分离。
     */
    public void setDiarization(VoiceDiarization diarization) {
        this.diarization = diarization;
    }

    /**
     * 设置文件标签。
     *
     * @param fileLabel 文件标签。
     */
    public void setFileLabel(FileLabel fileLabel) {
        this.fileLabel = fileLabel;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("streamName", this.streamName);
        json.put("index", this.index);
        json.put("timestamp", this.timestamp);
        if (null != this.diarization) {
            json.put("diarization", this.diarization.toJSON());
        }
        if (null != this.fileLabel) {
            json.put("file", this.fileLabel.toCompactJSON());
        }
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
