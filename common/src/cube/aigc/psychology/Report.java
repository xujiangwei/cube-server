/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology;

import cell.util.Utils;
import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 报告基类。
 */
public abstract class Report implements JSONable {

    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyyMMddHH");

    /** 序列号。 */
    public final long sn;

    /** 联系人 ID。 */
    public final long contactId;

    /** 时间戳。 */
    public final long timestamp;

    /** 权限。 */
    protected ReportPermission permission;

    /** 名称。 */
    protected String name;

    /** 属性。 */
    protected Attribute attribute;

    /** 摘要。 */
    protected String summary = "";

    /** 备注。 */
    protected String remark = "";

    /** 子类扩展数据，不参与基类序列化。 */
    protected JSONObject extension;

    public Report(long contactId, Attribute attribute) {
        this.sn = Utils.generateSerialNumber();
        this.contactId = contactId;
        this.permission = ReportPermission.createAllPermissions(this.contactId, this.sn);
        this.timestamp = System.currentTimeMillis();
        this.name = "AXL-" + sDateFormat.format(new Date(this.timestamp)) +
                String.format("%04d", Utils.randomInt(1, 9999));
        this.attribute = attribute;
    }

    public Report(long sn, long contactId, long timestamp, Attribute attribute) {
        this.sn = sn;
        this.contactId = contactId;
        this.permission = ReportPermission.createAllPermissions(this.contactId, this.sn);
        this.timestamp = timestamp;
        this.name = "AXL-" + sDateFormat.format(new Date(this.timestamp)) +
                String.format("%04d", Utils.randomInt(1, 9999));
        this.attribute = attribute;
    }

    public Report(long sn, long contactId, long timestamp, String name, Attribute attribute) {
        this.sn = sn;
        this.contactId = contactId;
        this.permission = ReportPermission.createAllPermissions(this.contactId, this.sn);
        this.timestamp = timestamp;
        this.name = name;
        this.attribute = attribute;
    }

    public Report(JSONObject json) {
        this.sn = json.getLong("sn");
        this.contactId = json.getLong("contactId");
        this.permission = json.has("permission") ?
                new ReportPermission(json.getJSONObject("permission")) :
                ReportPermission.createAllPermissions(this.contactId, this.sn);
        this.timestamp = json.getLong("timestamp");
        this.name = json.getString("name");
        this.attribute = new Attribute(json.getJSONObject("attribute"));
        if (json.has("summary")) {
            this.summary = json.getString("summary");
        }
        if (json.has("remark")) {
            this.remark = json.getString("remark");
        }
        if (json.has("extension")) {
            this.extension = json.getJSONObject("extension");
        }
    }

    /**
     * 获取名称。
     *
     * @return 名称。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取属性。
     *
     * @return 属性。
     */
    public Attribute getAttribute() {
        return this.attribute;
    }

    /**
     * 设置摘要。
     *
     * @param summary 摘要。
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * 获取摘要。
     *
     * @return 摘要。
     */
    public String getSummary() {
        return this.summary;
    }

    /**
     * 设置备注。
     *
     * @param remark 备注。
     */
    public void setRemark(String remark) {
        this.remark = remark;
    }

    /**
     * 获取备注。
     *
     * @return 备注。
     */
    public String getRemark() {
        return this.remark;
    }

    /**
     * 设置权限。
     *
     * @param permission 权限。
     */
    public void setPermission(ReportPermission permission) {
        this.permission = permission;
    }

    /**
     * 获取权限。
     *
     * @return 权限。
     */
    public ReportPermission getPermission() {
        return this.permission;
    }

    /**
     * 设置扩展数据。
     *
     * <p>用于承载各子类特有的附加字段，不参与基类序列化。</p>
     *
     * @param value 扩展 JSON 。
     */
    public void setExtension(JSONObject value) {
        this.extension = value;
    }

    /**
     * 获取扩展数据。
     *
     * @return 扩展 JSON ；未设置时为空对象。
     */
    public JSONObject getExtension() {
        return this.extension;
    }

    /**
     * 设置关键词列表。
     *
     * @param keywords 关键词列表。
     */
    public void setKeywords(List<JSONObject> keywords) {
        if (null == keywords) {
            return;
        }

        if (null == this.extension) {
            this.extension = new JSONObject();
        }
        JSONArray array = new JSONArray();
        for (JSONObject value : keywords) {
            array.put(value);
        }
        this.extension.put("keywords", array);
    }

    /**
     * 获取关键词列表。
     *
     * @return 关键词列表。
     */
    public List<JSONObject> getKeywords() {
        if (null == this.extension) {
            return null;
        }
        if (this.extension.has("keywords")) {
            JSONArray array = this.extension.getJSONArray("keywords");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < array.length(); ++i) {
                list.add(array.getJSONObject(i));
            }
            return list;
        }
        return null;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("contactId", this.contactId);
        json.put("timestamp", this.timestamp);
        json.put("name", this.name);
        json.put("permission", this.permission.toJSON());
        json.put("attribute", this.attribute.toJSON());
        if (null != this.summary) {
            json.put("summary", this.summary);
        }
        if (null != this.remark) {
            json.put("remark", this.remark);
        }
        if (null != this.extension) {
            json.put("extension", this.extension);
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
        JSONObject json = new JSONObject();
        json.put("sn", this.sn);
        json.put("contactId", this.contactId);
        json.put("timestamp", this.timestamp);
        json.put("name", this.name);
        json.put("permission", this.permission.toJSON());
        json.put("attribute", this.attribute.toJSON());
        if (null != this.summary) {
            json.put("summary", this.summary);
        }
        if (null != this.remark) {
            json.put("remark", this.remark);
        }
        if (null != this.extension) {
            json.put("extension", this.extension);
        }
        return json;
    }
}
