/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.common.entity;

import cell.util.Utils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 会议实体。
 */
public class Conference extends Entity {

    /**
     * 会议码。
     */
    private String code;

    /**
     * 会议主题。
     */
    private String subject;

    /**
     * 会议密码。
     */
    private String password = "";

    /**
     * 会议摘要。
     */
    private String summary = "";

    /**
     * 创建人。
     */
    private Contact founder;

    /**
     * 创建人 ID 。
     */
    private Long founderId;

    /**
     * 主持人。
     */
    private Contact presenter;

    /**
     * 主持人 ID 。
     */
    private Long presenterId;

    /**
     * 创建时间。
     */
    private long creation;

    /**
     * 计划时间。
     */
    private long scheduleTime;

    /**
     * 过期时间。
     */
    private long expireTime;

    /**
     * 邀请列表。
     */
    private List<Invitation> invitees;

    /**
     * 会议房间。
     */
    private Room room;

    /**
     * 是否已取消。
     */
    private boolean cancelled = false;

    /**
     * 构造函数。
     * @param id id。
     * @param domainName 域名。
     */
    public Conference(Long id, String domainName, Contact founder) {
        super(id, domainName);
        this.code = Utils.randomNumberString(8);
        this.founder = founder;
        this.founderId = founder.getId();
        this.presenter = founder;
        this.presenterId = this.presenter.getId();
        this.creation = this.getTimestamp();
        this.invitees = new ArrayList<>();
        this.room = new Room(id, 0L);
    }

    /**
     * 构造函数。
     * @param id id。
     * @param domainName 域名。
     * @param code 访问令牌。
     * @param subject subject。
     * @param password password。
     * @param summary summary。
     * @param founderId founderId。
     * @param presenterId presenterId。
     * @param creation creation。
     * @param scheduleTime scheduleTime。
     * @param expireTime expireTime。
     * @param participantGroupId participantGroupId。
     * @param commFieldId commFieldId。
     */
    public Conference(Long id, String domainName, String code, String subject, String password, String summary,
        Long founderId, Long presenterId, long creation, long scheduleTime, long expireTime,
        Long participantGroupId, Long commFieldId) {
        super(id, domainName);
        this.code = code;
        this.subject = subject;
        this.password = password;
        this.summary = summary;
        this.founderId = founderId;
        this.presenterId = presenterId;
        this.creation = creation;
        this.scheduleTime = scheduleTime;
        this.expireTime = expireTime;
        this.invitees = new ArrayList<>();
        this.room = new Room(participantGroupId, commFieldId);
    }

    /**
     * 构造函数。
     * @param json JSON 数据。
     */
    public Conference(JSONObject json) {
        super(json.getLong("id"), json.getString("domain"));
        this.code = json.getString("code");
        this.subject = json.getString("subject");
        this.password = json.getString("password");
        this.summary = json.getString("summary");
        this.founder = new Contact(json.getJSONObject("founder"));
        this.founderId = this.founder.getId();
        this.presenter = new Contact(json.getJSONObject("presenter"));
        this.presenterId = this.presenter.getId();
        this.creation = json.getLong("creation");
        this.scheduleTime = json.getLong("scheduleTime");
        this.expireTime = json.getLong("expireTime");

        this.invitees = new ArrayList<>();
        JSONArray array = json.getJSONArray("invitees");
        for (int i = 0; i < array.length(); ++i) {
            JSONObject data = array.getJSONObject(i);
            Invitation invitation = new Invitation(data);
            this.invitees.add(invitation);
        }

        this.room = new Room(json.getJSONObject("room"));
    }

    /**
     * 获取会议码。
     *
     * <p>会议码是会议对外的短标识，用于加入会议。</p>
     *
     * @return 会议码。
     */
    public String getCode() {
        return this.code;
    }

    /**
     * 设置会议主题。
     *
     * @param subject 会议主题。
     */
    public void setSubject(String subject) {
        this.subject = subject;
    }

    /**
     * 获取会议主题。
     *
     * @return 会议主题。
     */
    public String getSubject() {
        return this.subject;
    }

    /**
     * 获取会议密码。
     *
     * @return 会议密码；未设置密码时为空字符串，不为 {@code null} 。
     */
    public String getPassword() {
        return this.password;
    }

    /**
     * 设置会议密码。
     *
     * <p>传入空字符串或 {@code null} 表示取消密码，见 {@link #hasPassword()} 。</p>
     *
     * @param password 会议密码。
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 判断会议是否设置了入会密码。
     *
     * @return 密码非空时返回 {@code true} 。
     */
    public boolean hasPassword() {
        return (null != this.password && this.password.length() > 0);
    }

    /**
     * 获取会议摘要。
     *
     * @return 会议摘要；未设置时为空字符串。
     */
    public String getSummary() {
        return this.summary;
    }

    /**
     * 设置会议摘要。
     *
     * @param summary 会议摘要。
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * 获取会议创建人。
     *
     * <p>仅在加载了完整联系人信息时可用，否则返回 {@code null} ；只持有 ID 时改用 {@link #getFounderId()} 。</p>
     *
     * @return 创建人，可能为 {@code null} 。
     */
    public Contact getFounder() {
        return this.founder;
    }

    /**
     * 设置会议创建人。
     *
     * @param founder 创建人。
     */
    public void setFounder(Contact founder) {
        this.founder = founder;
        this.founderId = founder.getId();
    }

    /**
     * 获取会议创建人 ID 。
     *
     * <p>与 {@link #getFounder()} 的区别是只依赖 ID，不要求已加载联系人实体。</p>
     *
     * @return 创建人 ID ，未设置时可能为 {@code null} 。
     */
    public Long getFounderId() {
        return this.founderId;
    }

    /**
     * 获取会议主持人。
     *
     * <p>仅在加载了完整联系人信息时可用，否则返回 {@code null} 。</p>
     *
     * @return 主持人，可能为 {@code null} 。
     */
    public Contact getPresenter() {
        return this.presenter;
    }

    /**
     * 设置会议主持人。
     *
     * @param contact 主持人。
     */
    public void setPresenter(Contact contact) {
        this.presenter = contact;
        this.presenterId = contact.getId();
    }

    /**
     * 获取会议主持人 ID 。
     *
     * @return 主持人 ID ，未设置时可能为 {@code null} 。
     */
    public Long getPresenterId() {
        return this.presenterId;
    }

    /**
     * 获取会议创建时间。
     *
     * @return 创建时间戳，单位毫秒。
     */
    public long getCreation() {
        return this.creation;
    }

    /**
     * 获取计划开始时间。
     *
     * @return 计划开始时间戳，单位毫秒。
     */
    public long getScheduleTime() {
        return this.scheduleTime;
    }

    /**
     * 设置计划开始时间。
     *
     * @param time 计划开始时间戳，单位毫秒。
     */
    public void setScheduleTime(long time) {
        this.scheduleTime = time;
    }

    /**
     * 获取会议过期时间。
     *
     * @return 过期时间戳，单位毫秒；到点后会议不再接受加入。
     */
    public long getExpireTime() {
        return this.expireTime;
    }

    /**
     * 设置会议过期时间。
     *
     * @param time 过期时间戳，单位毫秒。
     */
    public void setExpireTime(long time) {
        this.expireTime = time;
    }

    /**
     * 按联系人 ID 查找邀请记录。
     *
     * <p>线性遍历邀请列表，邀请数量通常很小，无需索引。</p>
     *
     * @param id 被邀请的联系人 ID 。
     * @return 匹配的邀请；未找到返回 {@code null} 。
     */
    public Invitation getInvitee(Long id) {
        for (Invitation invitation : this.invitees) {
            if (invitation.id.longValue() == id.longValue()) {
                return invitation;
            }
        }
        return null;
    }

    /**
     * 获取全部邀请记录。
     *
     * @return 邀请列表；无邀请时为空列表。
     */
    public List<Invitation> getInvitees() {
        return this.invitees;
    }

    /**
     * 整体替换邀请列表。
     *
     * @param list 新的邀请列表。
     */
    public void setInvitees(List<Invitation> list) {
        this.invitees = list;
    }

    /**
     * 追加一条邀请。
     *
     * @param invitation 邀请记录。
     */
    public void addInvitee(Invitation invitation) {
        this.invitees.add(invitation);
    }

    /**
     * 获取会议关联的房间。
     *
     * @return 会议房间，可能为 {@code null} 。
     */
    public Room getRoom() {
        return this.room;
    }

    /**
     * 判断会议是否已取消。
     *
     * @return 已取消返回 {@code true} 。
     */
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * 设置会议取消状态。
     *
     * @param value 为 {@code true} 表示取消会议。
     */
    public void setCancelled(boolean value) {
        this.cancelled = value;
    }

    /**
     * 序列化为完整 JSON 。
     *
     * <p>包含创建人与主持人的完整联系人信息，以及全部邀请记录，体积较大。</p>
     *
     * @return 会议 JSON 对象。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("id", this.id.longValue());
        json.put("domain", this.domain.getName());
        json.put("code", this.code);
        json.put("subject", this.subject);
        json.put("password", this.password);
        json.put("summary", this.summary);
        json.put("founder", this.founder.toCompactJSON());
        json.put("presenter", this.presenter.toCompactJSON());
        json.put("creation", this.creation);
        json.put("scheduleTime", this.scheduleTime);
        json.put("expireTime", this.expireTime);

        JSONArray array = new JSONArray();
        for (Invitation invitation : this.invitees) {
            array.put(invitation.toJSON());
        }
        json.put("invitees", array);

        json.put("room", this.room.toJSON());

        json.put("cancelled", this.cancelled);

        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * <p>当前实现与 {@link #toJSON()} 输出一致。</p>
     *
     * @return 会议 JSON 对象。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = this.toJSON();
        json.remove("password");
        json.put("existingPwd", this.hasPassword());
        return json;
    }
}
