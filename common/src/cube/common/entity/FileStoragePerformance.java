/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.common.JSONable;
import org.json.JSONObject;

/**
 * 文件存储服务偏好配置。
 */
public class FileStoragePerformance implements JSONable {

    private long contactId;

    private long spaceSize;

    private long maxSpaceSize;

    private long uploadThreshold;

    private long downloadThreshold;

    private int maxSharingNum = 0;

    private boolean sharingWatermarkEnabled = true;

    private boolean sharingPreviewEnabled = true;

    public FileStoragePerformance(long contactId, long maxSpaceSize,
                                  long uploadThreshold, long downloadThreshold,
                                  int maxSharingNum, boolean sharingWatermarkEnabled,
                                  boolean sharingPreviewEnabled) {
        this.contactId = contactId;
        this.spaceSize = -1;
        this.maxSpaceSize = maxSpaceSize;
        this.uploadThreshold = uploadThreshold;
        this.downloadThreshold = downloadThreshold;
        this.maxSharingNum = maxSharingNum;
        this.sharingWatermarkEnabled = sharingWatermarkEnabled;
        this.sharingPreviewEnabled = sharingPreviewEnabled;
    }

    public FileStoragePerformance(JSONObject json) {
        this.contactId = json.getLong("contactId");
        this.spaceSize = json.has("spaceSize") ? json.getLong("spaceSize") : -1;
        this.maxSpaceSize = json.getLong("maxSpaceSize");
        this.uploadThreshold = json.getLong("uploadThreshold");
        this.downloadThreshold = json.getLong("downloadThreshold");
        this.maxSharingNum = json.getInt("maxSharingNum");
        this.sharingWatermarkEnabled = json.getBoolean("sharingWatermarkEnabled");
        this.sharingPreviewEnabled = json.getBoolean("sharingPreviewEnabled");
    }

    /**
     * 获取联系人 ID。
     *
     * @return 联系人 ID。
     */
    public long getContactId() {
        return this.contactId;
    }

    /**
     * 获取空间容量。
     *
     * @return 空间容量。
     */
    public long getSpaceSize() {
        return this.spaceSize;
    }

    /**
     * 设置空间容量。
     *
     * @param spaceSize 空间容量。
     */
    public void setSpaceSize(long spaceSize) {
        this.spaceSize = spaceSize;
    }

    /**
     * 获取空间容量上限。
     *
     * @return 空间容量上限。
     */
    public long getMaxSpaceSize() {
        return this.maxSpaceSize;
    }

    /**
     * 设置空间容量上限。
     *
     * @param value 空间容量上限。
     */
    public void setMaxSpaceSize(long value) {
        this.maxSpaceSize = value;
    }

    /**
     * 获取上传阈值。
     *
     * @return 上传阈值。
     */
    public long getUploadThreshold() {
        return this.uploadThreshold;
    }

    /**
     * 设置上传阈值。
     *
     * @param value 上传阈值。
     */
    public void setUploadThreshold(long value) {
        this.uploadThreshold = value;
    }

    /**
     * 获取下载阈值。
     *
     * @return 下载阈值。
     */
    public long getDownloadThreshold() {
        return this.downloadThreshold;
    }

    /**
     * 设置下载阈值。
     *
     * @param value 下载阈值。
     */
    public void setDownloadThreshold(long value) {
        this.downloadThreshold = value;
    }

    /**
     * 获取分享数量上限。
     *
     * @return 分享数量上限。
     */
    public int getMaxSharingNum() {
        return this.maxSharingNum;
    }

    /**
     * 设置分享数量上限。
     *
     * @param value 分享数量上限。
     */
    public void setMaxSharingNum(int value) {
        this.maxSharingNum = value;
    }

    /**
     * 判断是否是否启用分享水印。
     *
     * @return 是否启用分享水印。
     */
    public boolean isSharingWatermarkEnabled() {
        return this.sharingWatermarkEnabled;
    }

    /**
     * 设置是否启用分享水印。
     *
     * @param value 是否启用分享水印。
     */
    public void setSharingWatermarkEnabled(boolean value) {
        this.sharingWatermarkEnabled = value;
    }

    /**
     * 判断是否是否启用分享预览。
     *
     * @return 是否启用分享预览。
     */
    public boolean isSharingPreviewEnabled() {
        return this.sharingPreviewEnabled;
    }

    /**
     * 设置是否启用分享预览。
     *
     * @param value 是否启用分享预览。
     */
    public void setSharingPreviewEnabled(boolean value) {
        this.sharingPreviewEnabled = value;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("contactId", this.contactId);
        json.put("maxSpaceSize", this.maxSpaceSize);
        json.put("uploadThreshold", this.uploadThreshold);
        json.put("downloadThreshold", this.downloadThreshold);
        json.put("maxSharingNum", this.maxSharingNum);
        json.put("sharingWatermarkEnabled", this.sharingWatermarkEnabled);
        json.put("sharingPreviewEnabled", this.sharingPreviewEnabled);

        if (this.spaceSize >= 0) {
            json.put("spaceSize", this.spaceSize);
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
