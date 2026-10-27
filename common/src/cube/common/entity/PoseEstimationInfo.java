/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 姿势估算。
 */
public class PoseEstimationInfo implements JSONable {

    /** 文件码。 */
    public final String fileCode;

    /** 耗时。 */
    public final long elapsed;

    /** 文件标签。 */
    public FileLabel fileLabel;

    private List<PoseEstimation> poseEstimations = new ArrayList<>();

    private FileLabel visualization;

    public PoseEstimationInfo(JSONObject json) {
        this.fileCode = json.getString("fileCode");
        this.elapsed = json.getLong("elapsed");
        JSONArray keypoints = json.getJSONArray("keypoints");
        for (int i = 0; i < keypoints.length(); ++i) {
            JSONObject keypoint = keypoints.getJSONObject(i);
            PoseEstimation poseEstimation = new PoseEstimation(keypoint);
            this.poseEstimations.add(poseEstimation);
        }
        if (json.has("visualization")) {
            this.visualization = new FileLabel(json.getJSONObject("visualization"));
        }
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
        json.put("fileCode", this.fileCode);
        json.put("elapsed", this.elapsed);

        if (null != this.fileLabel) {
            json.put("fileLabel", this.fileLabel.toJSON());
        }

        JSONArray array = new JSONArray();
        for (PoseEstimation pe : this.poseEstimations) {
            array.put(pe.toJSON());
        }
        json.put("keypoints", array);

        if (null != this.visualization) {
            json.put("visualization", this.visualization.toJSON());
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
