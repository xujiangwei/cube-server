/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.ferry;

import cell.util.Utils;
import cube.common.entity.Entity;
import cube.common.entity.FileLabel;
import org.json.JSONObject;

/**
 * 域信息。
 */
public class DomainInfo extends Entity {

    /**
     * 一般状态。
     */
    public final static int STATE_NORMAL = 0;

    /**
     * 禁用状态。
     */
    public final static int STATE_DISABLED = 1;

    private long beginning;

    private long duration;

    private int limit;

    private String address;

    private String invitationCode;

    private FileLabel qrCodeFileLabel;

    private int state;

    private int flag;

    public DomainInfo(String domainName, long beginning, long duration, int limit,
                      FileLabel qrCodeFileLabel, int state, int flag) {
        super(Utils.generateSerialNumber(), domainName);
        this.beginning = beginning;
        this.duration = duration;
        this.limit = limit;
        this.qrCodeFileLabel = qrCodeFileLabel;
        this.state = state;
        this.flag = flag;
    }

    public DomainInfo(JSONObject json) {
        super(json);
        this.beginning = json.getLong("beginning");
        this.duration = json.getLong("duration");
        this.limit = json.getInt("limit");

        if (json.has("address")) {
            this.address = json.getString("address");
        }

        if (json.has("invitationCode")) {
            this.invitationCode = json.getString("invitationCode");
        }

        if (json.has("qrCodeFileLabel")) {
            this.qrCodeFileLabel = new FileLabel(json.getJSONObject("qrCodeFileLabel"));
        }

        if (json.has("state")) {
            this.state = json.getInt("state");
        }
        else {
            this.state = STATE_NORMAL;
        }

        if (json.has("flag")) {
            this.flag = json.getInt("flag");
        }
        else {
            this.flag = FerryHouseFlag.STANDARD;
        }
    }

    /**
     * 获取起始。
     *
     * @return 起始。
     */
    public long getBeginning() {
        return this.beginning;
    }

    /**
     * 设置起始。
     *
     * @param value 起始。
     */
    public void setBeginning(long value) {
        this.beginning = value;
    }

    /**
     * 获取时长。
     *
     * @return 时长。
     */
    public long getDuration() {
        return this.duration;
    }

    /**
     * 获取域容量上限。
     *
     * @return 上限值。
     */
    public int getLimit() {
        return this.limit;
    }

    /**
     * 设置服务地址。
     *
     * @param address 地址。
     */
    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * 获取服务地址。
     *
     * @return 地址。
     */
    public String getAddress() {
        return this.address;
    }

    /**
     * 设置邀请码。
     *
     * @param invitationCode 邀请码。
     */
    public void setInvitationCode(String invitationCode) {
        this.invitationCode = invitationCode;
    }

    /**
     * 获取邀请码。
     *
     * @return 邀请码。
     */
    public String getInvitationCode() {
        return this.invitationCode;
    }

    /**
     * 设置二维码文件。
     *
     * @param fileLabel 文件标签。
     */
    public void setQRCodeFileLabel(FileLabel fileLabel) {
        this.qrCodeFileLabel = fileLabel;
    }

    /**
     * 获取二维码文件。
     *
     * @return 文件标签；未设置返回 {@code null} 。
     */
    public FileLabel getQRCodeFileLabel() {
        return this.qrCodeFileLabel;
    }

    /**
     * 获取状态。
     *
     * @return 状态。
     */
    public int getState() {
        return this.state;
    }

    /**
     * 获取域标志位。
     *
     * @return 标志值。
     */
    public int getFlag() {
        return this.flag;
    }

    /**
     * 导出为授权信息。
     *
     * @return 授权 JSON 。
     */
    public JSONObject toLicence() {
        JSONObject data = new JSONObject();
        data.put("domain", this.domain.getName());
        data.put("beginning", this.beginning);
        data.put("duration", this.duration);
        data.put("limit", this.limit);
        return data;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("beginning", this.beginning);
        json.put("duration", this.duration);
        json.put("limit", this.limit);

        if (null != this.address) {
            json.put("address", this.address);
        }

        if (null != this.invitationCode) {
            json.put("invitationCode", this.invitationCode);
        }

        if (null != this.qrCodeFileLabel) {
            json.put("qrCodeFileLabel", this.qrCodeFileLabel.toJSON());
        }

        json.put("state", this.state);

        json.put("flag", this.flag);

        return json;
    }
}
