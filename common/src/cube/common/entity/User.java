/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cube.auth.AuthToken;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * 应用层用户。
 * 用户ID和Contact ID一致。
 */
public class User extends Entity {

    /**
     * 用户画像知识库的内部名称。
     */
    public final static String KnowledgeBaseName = "mind_echo";
    /**
     * 用户画像知识库的展示名称。
     */
    public final static String KnowledgeBaseDisplayName = "MindEcho";
    /**
     * 用户画像在知识库中的标题。
     */
    public final static String KnowledgeTitle = "UserProfile";

    private String name;

    private String appAgent;

    private String displayName;

    private String avatar;

    private String phoneNumber;

    private String email;

    private String password;

    private long registerTime;

    private AuthToken authToken;

    private String channel;

    public User(long id, String name, String appAgent, String channel) {
        super(id);
        this.name = name;
        this.appAgent = appAgent;
        this.channel = channel;
        this.displayName = "";
        this.avatar = "";
        this.phoneNumber = "";
        this.email = "";
        this.password = "";
    }

    public User(JSONObject json) {
        super(json);
        this.name = json.getString("name");
        this.appAgent = json.getString("appAgent");
        this.channel = json.has("channel") ? json.getString("channel") : "Unknown";
        this.displayName = json.getString("displayName");
        this.avatar = json.getString("avatar");
        this.phoneNumber = json.has("phoneNumber") ? json.getString("phoneNumber") : "";
        this.email = json.has("email") ? json.getString("email") : "";
        this.password = json.has("password") ? json.getString("password") : "";
        this.registerTime = json.has("registerTime") ? json.getLong("registerTime") : System.currentTimeMillis();
        if (json.has("authToken")) {
            this.authToken = new AuthToken(json.getJSONObject("authToken"));
        }
    }

    /**
     * 获取用户来源渠道。
     *
     * @return 渠道标识；旧数据缺省为 {@code Unknown} 。
     */
    public String getChannel() {
        return this.channel;
    }

    /**
     * 设置用户名。
     *
     * @param name 用户名。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取用户名。
     *
     * @return 用户名。
     */
    public String getName() {
        return this.name;
    }

    /**
     * 设置手机号码。
     *
     * @param phoneNumber 手机号码。
     */
    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    /**
     * 获取手机号码。
     *
     * @return 手机号码；未绑定为空字符串。
     */
    public String getPhoneNumber() {
        return this.phoneNumber;
    }

    /**
     * 设置邮箱地址。
     *
     * @param email 邮箱地址。
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * 获取邮箱地址。
     *
     * @return 邮箱地址；未绑定为空字符串。
     */
    public String getEmail() {
        return this.email;
    }

    /**
     * 设置登录密码。
     *
     * <p>存储的是密码摘要，不是明文。</p>
     *
     * @param password 密码摘要。
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 获取登录密码摘要。
     *
     * @return 密码摘要；未设置为空字符串。
     */
    public String getPassword() {
        return this.password;
    }

    /**
     * 设置昵称。
     *
     * @param displayName 昵称。
     */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取昵称。
     *
     * @return 昵称。
     */
    public String getDisplayName() {
        return this.displayName;
    }

    /**
     * 设置注册时间。
     *
     * @param time 注册时间戳，单位毫秒。
     */
    public void setRegisterTime(long time) {
        this.registerTime = time;
    }

    /**
     * 获取注册时间。
     *
     * @return 注册时间戳，单位毫秒；未注册时为 0 。
     */
    public long getRegisterTime() {
        return this.registerTime;
    }

    /**
     * 绑定鉴权令牌。
     *
     * @param authToken 鉴权令牌。
     */
    public void setAuthToken(AuthToken authToken) {
        this.authToken = authToken;
    }

    /**
     * 判断用户是否已完成注册。
     *
     * <p>注册时间非 0 且手机号或邮箱足够长时才算完成注册：
     * 这两个条件用于过滤仅创建了账号但未填写联系方式的情况。</p>
     *
     * @return 已完成注册返回 {@code true} 。
     */
    public boolean isRegistered() {
        if (0 == this.registerTime) {
            return false;
        }

        return this.phoneNumber.length() > 8 || this.email.length() > 6;
    }

    /**
     * 获取用户对应的联系人 ID 。
     *
     * <p>联系人 ID 记录在鉴权令牌中，未绑定令牌时返回 0 。</p>
     *
     * @return 联系人 ID ；未绑定时返回 0 。
     */
    public long getContactId() {
        if (null != this.authToken) {
            return this.authToken.getContactId();
        }
        return 0;
    }

    /**
     * 生成用户信息的 Markdown 片段。
     *
     * <p>片段直接拼进提示词，供模型了解当前用户；未注册时只输出未注册状态。</p>
     *
     * @return Markdown 格式的用户信息。
     */
    public String markdown() {
        StringBuilder buf = new StringBuilder();
        buf.append("# 用户“").append(this.name).append("”的信息\n\n");
        buf.append("* 用户名：").append(this.name).append("\n");
        buf.append("* 用户昵称：").append(this.displayName).append("\n");

        if (this.isRegistered()) {
            buf.append("* 用户注册状态：").append("已注册").append("\n");
            // 注册日期
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(this.registerTime);
            buf.append("* 用户注册日期：");
            buf.append(calendar.get(Calendar.YEAR)).append("年");
            buf.append(calendar.get(Calendar.MONTH) + 1).append("月");
            buf.append(calendar.get(Calendar.DATE)).append("日\n");
        }
        else {
            buf.append("* 用户注册状态：").append("未注册").append("\n");
        }

        if (this.phoneNumber.length() > 1) {
            buf.append("* 用户手机号码：").append(this.phoneNumber).append("\n");
        }

        if (this.email.length() > 1) {
            buf.append("* 用户邮箱：").append(this.email).append("\n");
        }
        buf.append("\n");
        return buf.toString();
    }

    /**
     * 序列化为完整 JSON 。
     *
     * @return 用户 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("name", this.name);
        json.put("appAgent", this.appAgent);
        json.put("channel", this.channel);
        json.put("displayName", this.displayName);
        json.put("avatar", this.avatar);
        json.put("phoneNumber", this.phoneNumber);
        json.put("email", this.email);
        json.put("password", this.password);
        json.put("registerTime", this.registerTime);
        if (null != this.authToken) {
            json.put("authToken", this.authToken.toCompactJSON());
        }
        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 用户 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        json.remove("appAgent");
        json.remove("password");
        return json;
    }
}
