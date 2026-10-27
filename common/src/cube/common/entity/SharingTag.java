/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.core.net.Endpoint;
import cell.util.Utils;
import cube.util.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 分享标签。
 */
public class SharingTag extends Entity {

    /**
     * 一般状态。
     */
    public final static int STATE_NORMAL = 0;

    /**
     * 已取消状态。
     */
    public final static int STATE_CANCEL = 1;

    /**
     * 已删除状态。
     */
    public final static int STATE_DELETE = 2;

    private String code;

    private Trace sharer;

    private Trace parent;

    private SharingTagConfig config;

    private long expiryDate;

    private String httpHostInfo;
    private String httpsHostInfo;

    private String httpURL;

    private String httpsURL;

    private List<FileLabel> previewList;

    private int state = STATE_NORMAL;

    public SharingTag(SharingTagConfig config) {
        super(Utils.generateSerialNumber(), config.getDomain());
        this.config = config;

        this.sharer = new Trace(config.getContact());
        this.parent = new Trace(config.getContact());

        StringBuilder buf = new StringBuilder();
        buf.append(config.getFileLabel().getFileCode());
        buf.append(config.getDomain().getName());
        buf.append(config.getContact().getId().toString());
        buf.append(this.id.toString());
        this.code = FileUtils.fastHash(buf.toString());

        if (config.getDuration() > 0) {
            this.expiryDate = this.timestamp + config.getDuration();
        }
        else {
            this.expiryDate = 0;
        }
    }

    public SharingTag(Long id, String domain, long timestamp, String code, long expiryDate,
                      Contact contact, Device device, FileLabel fileLabel, long duration,
                      String password, boolean preview, boolean download, boolean traceDownload, int state) {
        super(id, domain, timestamp);
        this.code = code;
        this.expiryDate = expiryDate;
        this.config = new SharingTagConfig(contact, device, fileLabel, duration, password,
                preview, download, traceDownload);

        this.sharer = new Trace(this.config.getContact());
        this.parent = new Trace(this.config.getContact());

        this.state = state;
    }

    public SharingTag(JSONObject json) {
        super(json);
        this.code = json.getString("code");
        this.config = new SharingTagConfig(json.getJSONObject("config"));
        this.expiryDate = json.getLong("expiryDate");

        this.sharer = new Trace(this.config.getContact());
        this.parent = new Trace(this.config.getContact());

        if (json.has("httpURL")) {
            this.httpURL = json.getString("httpURL");
        }
        if (json.has("httpsURL")) {
            this.httpsURL = json.getString("httpsURL");
        }

        if (json.has("httpHostInfo")) {
            this.httpHostInfo = json.getString("httpHostInfo");
        }
        if (json.has("httpsHostInfo")) {
            this.httpsHostInfo = json.getString("httpsHostInfo");
        }

        if (json.has("previewList")) {
            this.previewList = new ArrayList<>();
            JSONArray array = json.getJSONArray("previewList");
            for (int i = 0; i < array.length(); ++i) {
                this.previewList.add(new FileLabel(array.getJSONObject(i)));
            }
        }

        if (json.has("sharer")) {
            this.sharer = new Trace(json.getJSONObject("sharer"));
        }

        if (json.has("parent")) {
            this.parent = new Trace(json.getJSONObject("parent"));
        }

        if (json.has("state")) {
            this.state = json.getInt("state");
        }
    }

    /**
     * 获取分享码。
     *
     * @return 返回分享码。
     */
    public String getCode() {
        return this.code;
    }

    /**
     * 获取到期日期。
     *
     * @return 返回绝对时间表示的到期日期。
     */
    public long getExpiryDate() {
        return this.expiryDate;
    }

    /**
     * 获取标签的配置数据。
     *
     * @return 返回标签的配置数据。
     */
    public SharingTagConfig getConfig() {
        return this.config;
    }

    /**
     * 重置联系人。
     *
     * @param contact 联系人。
     */
    public void resetContact(Contact contact) {
        this.config.setContact(contact);
    }

    /**
     * 设置双协议访问地址。
     *
     * @param http  HTTP 端点。
     * @param https HTTPS 端点。
     */
    public void setURLs(Endpoint http, Endpoint https) {
        this.httpHostInfo = http.getHost() + ":" + http.getPort();
        this.httpsHostInfo = https.getHost() + ":" + https.getPort();

        String parameter = "?s=" + this.sharer.toString() + "&p=" + this.parent.toString() + "&e=x";

        this.httpURL = "http://" + this.httpHostInfo +
                "/sharing/" + this.code + parameter;

        this.httpsURL = "https://" + this.httpsHostInfo +
                "/sharing/" + this.code + parameter;
    }

    /**
     * 获取HTTP 地址。
     *
     * @return HTTP 地址。
     */
    public String getHttpURL() {
        return this.httpURL;
    }

    /**
     * 获取HTTPS 地址。
     *
     * @return HTTPS 地址。
     */
    public String getHttpsURL() {
        return this.httpsURL;
    }

    /**
     * 设置预览图列表。
     *
     * @param list 指定预览图列表。
     */
    public void setPreviewList(List<FileLabel> list) {
        if (null == list || list.isEmpty()) {
            return;
        }

        if (null == this.previewList) {
            this.previewList = new ArrayList<>();
        }

        this.previewList.clear();
        this.previewList.addAll(list);
    }

    /**
     * 获取预览列表。
     *
     * @return 预览列表。
     */
    public List<FileLabel> getPreviewList() {
        return this.previewList;
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
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("code", this.code);
        json.put("expiryDate", this.expiryDate);
        json.put("config", this.config.toJSON());
        json.put("state", this.state);

        if (null != this.httpURL) {
            json.put("httpURL", this.httpURL);
        }
        if (null != this.httpsURL) {
            json.put("httpsURL", this.httpsURL);
        }

        if (null != this.httpHostInfo) {
            json.put("httpHostInfo", this.httpHostInfo);
        }
        if (null != this.httpsHostInfo) {
            json.put("httpsHostInfo", this.httpsHostInfo);
        }

        if (null != this.sharer) {
            json.put("sharer", this.sharer.toJSON());
        }
        if (null != this.parent) {
            json.put("parent", this.parent.toJSON());
        }

        if (null != this.previewList) {
            JSONArray array = new JSONArray();
            for (FileLabel fileLabel : this.previewList) {
                array.put(fileLabel.toJSON());
            }
            json.put("previewList", array);
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
        JSONObject json = super.toCompactJSON();
        json.put("code", this.code);
        json.put("expiryDate", this.expiryDate);
        json.put("config", this.config.toCompactJSON());
        json.put("state", this.state);

        if (null != this.httpURL) {
            json.put("httpURL", this.httpURL);
        }
        if (null != this.httpsURL) {
            json.put("httpsURL", this.httpsURL);
        }

        if (null != this.httpHostInfo) {
            json.put("httpHostInfo", this.httpHostInfo);
        }
        if (null != this.httpsHostInfo) {
            json.put("httpsHostInfo", this.httpsHostInfo);
        }

        if (null != this.sharer) {
            json.put("sharer", this.sharer.toJSON());
        }
        if (null != this.parent) {
            json.put("parent", this.parent.toJSON());
        }

        if (null != this.previewList) {
            JSONArray array = new JSONArray();
            for (FileLabel fileLabel : this.previewList) {
                array.put(fileLabel.toCompactJSON());
            }
            json.put("previewList", array);
        }

        return json;
    }

    /**
     * 转换为面向客户端的 JSON。
     *
     * <p>裁剪掉仅服务端使用的字段，减小下发体积。</p>
     *
     * @return 客户端 JSON 。
     */
    public JSONObject toJSONForClient() {
        JSONObject json = this.toCompactJSON();
        JSONObject config = json.getJSONObject("config");
        if (config.has("password")) {
            config.remove("password");
            config.put("hasPassword", true);
        }
        else {
            config.put("hasPassword", false);
        }
        return json;
    }

    /**
     * 生成访问地址数组。
     *
     * @param tag    分享标签。
     * @param sharer 分享者标识。
     * @return 访问地址数组。
     */
    public static String[] makeURLs(SharingTag tag, String sharer) {
        String parameter = "?s=" + sharer + "&p=" + tag.sharer.toString() + "&e=x";

        String httpURL = "http://" + tag.httpHostInfo + "/sharing/" + tag.code + parameter;
        String httpsURL = "https://" + tag.httpsHostInfo + "/sharing/" + tag.code + parameter;
        return new String[]{ httpURL, httpsURL };
    }
}
