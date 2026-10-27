/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cell.util.Utils;
import cube.common.entity.Entity;
import org.json.JSONObject;

/**
 * 报告。
 */
public class BoxReport extends Entity {

    /**
     * 数据空间大小。
     */
    private long dataSpaceSize;

    private long imageFilesUsedSize;

    private long docFilesUsedSize;

    private long videoFilesUsedSize;

    private long audioFilesUsedSize;

    private long packageFilesUsedSize;

    private long otherFilesUsedSize;

    /**
     * 空闲磁盘空间。
     */
    private long freeDiskSize;

    private int numImageFiles;

    private int numDocFiles;

    private int numVideoFiles;

    private int numAudioFiles;

    private int numPackageFiles;

    private int numOtherFiles;

    /**
     * 消息数量。
     */
    private int totalMessages;

    public BoxReport(String domainName) {
        super(Utils.randomUnsignedLong(), domainName);
    }

    public BoxReport(JSONObject json) {
        super(json);
        this.dataSpaceSize = json.getLong("dataSpaceSize");
        this.freeDiskSize = json.getLong("freeDiskSize");
        this.imageFilesUsedSize = json.getLong("imageFilesUsedSize");
        this.docFilesUsedSize = json.getLong("docFilesUsedSize");
        this.videoFilesUsedSize = json.getLong("videoFilesUsedSize");
        this.audioFilesUsedSize = json.getLong("audioFilesUsedSize");
        this.packageFilesUsedSize = json.getLong("packageFilesUsedSize");
        this.otherFilesUsedSize = json.getLong("otherFilesUsedSize");

        this.numImageFiles = json.getInt("numImageFiles");
        this.numDocFiles = json.getInt("numDocFiles");
        this.numVideoFiles = json.getInt("numVideoFiles");
        this.numAudioFiles = json.getInt("numAudioFiles");
        this.numPackageFiles = json.getInt("numPackageFiles");
        this.numOtherFiles = json.getInt("numOtherFiles");

        this.totalMessages = json.getInt("totalMessages");
    }

    /**
     * 设置数据空间容量。
     *
     * @param size 数据空间容量。
     */
    public void setDataSpaceSize(long size) {
        this.dataSpaceSize = size;
    }

    /**
     * 获取数据空间容量。
     *
     * @return 数据空间容量。
     */
    public long getDataSpaceSize() {
        return this.dataSpaceSize;
    }

    /**
     * 设置剩余磁盘容量。
     *
     * @param size 字节数。
     */
    public void setFreeDiskSize(long size) {
        this.freeDiskSize = size;
    }

    /**
     * 获取剩余磁盘容量。
     *
     * @return 字节数。
     */
    public long getFreeDiskSize() {
        return this.freeDiskSize;
    }

    /**
     * 设置图片占用容量。
     *
     * @param size 字节数。
     */
    public void setImageFilesUsedSize(long size) {
        this.imageFilesUsedSize = size;
    }

    /**
     * 获取图片占用容量。
     *
     * @return 字节数。
     */
    public long getImageFilesUsedSize() {
        return this.imageFilesUsedSize;
    }

    /**
     * 设置文档占用容量。
     *
     * @param size 字节数。
     */
    public void setDocFilesUsedSize(long size) {
        this.docFilesUsedSize = size;
    }

    /**
     * 获取文档占用容量。
     *
     * @return 字节数。
     */
    public long getDocFilesUsedSize() {
        return this.docFilesUsedSize;
    }

    /**
     * 设置视频占用容量。
     *
     * @param size 字节数。
     */
    public void setVideoFilesUsedSize(long size) {
        this.videoFilesUsedSize = size;
    }

    /**
     * 获取视频占用容量。
     *
     * @return 字节数。
     */
    public long getVideoFilesUsedSize() {
        return this.videoFilesUsedSize;
    }

    /**
     * 设置音频占用容量。
     *
     * @param size 字节数。
     */
    public void setAudioFilesUsedSize(long size) {
        this.audioFilesUsedSize = size;
    }

    /**
     * 获取音频占用容量。
     *
     * @return 字节数。
     */
    public long getAudioFilesUsedSize() {
        return this.audioFilesUsedSize;
    }

    /**
     * 设置压缩包占用容量。
     *
     * @param size 字节数。
     */
    public void setPackageFilesUsedSize(long size) {
        this.packageFilesUsedSize = size;
    }

    /**
     * 获取压缩包占用容量。
     *
     * @return 字节数。
     */
    public long getPackageFilesUsedSize() {
        return this.packageFilesUsedSize;
    }

    /**
     * 设置其他文件占用容量。
     *
     * @param size 字节数。
     */
    public void setOtherFilesUsedSize(long size) {
        this.otherFilesUsedSize = size;
    }

    /**
     * 获取其他文件占用容量。
     *
     * @return 字节数。
     */
    public long getOtherFilesUsedSize() {
        return this.otherFilesUsedSize;
    }

    /**
     * 设置数量图像文件列表。
     *
     * @param num 数量图像文件列表。
     */
    public void setNumImageFiles(int num) {
        this.numImageFiles = num;
    }

    /**
     * 获取数量图像文件列表。
     *
     * @return 数量图像文件列表。
     */
    public int getNumImageFiles() {
        return this.numImageFiles;
    }

    /**
     * 设置数量文档文件列表。
     *
     * @param num 数量文档文件列表。
     */
    public void setNumDocFiles(int num) {
        this.numDocFiles = num;
    }

    /**
     * 获取数量文档文件列表。
     *
     * @return 数量文档文件列表。
     */
    public int getNumDocFiles() {
        return this.numDocFiles;
    }

    /**
     * 设置数量视频文件列表。
     *
     * @param num 数量视频文件列表。
     */
    public void setNumVideoFiles(int num) {
        this.numVideoFiles = num;
    }

    /**
     * 获取数量视频文件列表。
     *
     * @return 数量视频文件列表。
     */
    public int getNumVideoFiles() {
        return this.numVideoFiles;
    }

    /**
     * 设置数量音频文件列表。
     *
     * @param num 数量音频文件列表。
     */
    public void setNumAudioFiles(int num) {
        this.numAudioFiles = num;
    }

    /**
     * 获取数量音频文件列表。
     *
     * @return 数量音频文件列表。
     */
    public int getNumAudioFiles() {
        return this.numAudioFiles;
    }

    /**
     * 设置压缩包数量。
     *
     * @param num 数量。
     */
    public void setNumPackageFiles(int num) {
        this.numPackageFiles = num;
    }

    /**
     * 获取压缩包数量。
     *
     * @return 数量。
     */
    public int getNumPackageFiles() {
        return this.numPackageFiles;
    }

    /**
     * 设置数量其他文件列表。
     *
     * @param num 数量其他文件列表。
     */
    public void setNumOtherFiles(int num) {
        this.numOtherFiles = num;
    }

    /**
     * 获取数量其他文件列表。
     *
     * @return 数量其他文件列表。
     */
    public int getNumOtherFiles() {
        return this.numOtherFiles;
    }

    /**
     * 设置总数消息列表。
     *
     * @param total 总数消息列表。
     */
    public void setTotalMessages(int total) {
        this.totalMessages = total;
    }

    /**
     * 获取总数消息列表。
     *
     * @return 总数消息列表。
     */
    public int getTotalMessages() {
        return this.totalMessages;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("dataSpaceSize", this.dataSpaceSize);
        json.put("freeDiskSize", this.freeDiskSize);
        json.put("imageFilesUsedSize", this.imageFilesUsedSize);
        json.put("docFilesUsedSize", this.docFilesUsedSize);
        json.put("videoFilesUsedSize", this.videoFilesUsedSize);
        json.put("audioFilesUsedSize", this.audioFilesUsedSize);
        json.put("packageFilesUsedSize", this.packageFilesUsedSize);
        json.put("otherFilesUsedSize", this.otherFilesUsedSize);

        json.put("numImageFiles", this.numImageFiles);
        json.put("numDocFiles", this.numDocFiles);
        json.put("numVideoFiles", this.numVideoFiles);
        json.put("numAudioFiles", this.numAudioFiles);
        json.put("numPackageFiles", this.numPackageFiles);
        json.put("numOtherFiles", this.numOtherFiles);

        json.put("totalMessages", this.totalMessages);
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
