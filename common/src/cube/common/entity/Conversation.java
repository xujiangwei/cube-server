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
 * 会话。
 */
public class Conversation extends Entity {

    /**
     * 所有人 ID 。
     */
    private Long ownerId;

    /**
     * 会话类型。
     */
    private ConversationType type;

    /**
     * 会话状态。
     */
    private ConversationState state;

    /**
     * 会话提醒类型。
     */
    private ConversationRemindType remindType;

    /**
     * 与会话相关的关键实体的 ID 。
     */
    private Long pivotalId;

    /**
     * 最近的消息列表。
     */
    private List<Message> recentMessages;

    /**
     * 未读消息记录数。
     */
    private int unreadCount;

    /**
     * 上下文数据。
     */
    private JSONObject context;

    /**
     * 头像名称。
     */
    private String avatarName;

    /**
     * 头像的 URL 。
     */
    private String avatarURL;

    /**
     * 关键实体。
     */
    private AbstractContact pivotalEntity;

    /**
     * 构造函数。
     * @param id id。
     * @param domain 域。
     * @param timestamp 时间戳，单位毫秒。
     * @param ownerId ownerId。
     * @param type 类型。
     * @param state 状态。
     * @param pivotalId pivotalId。
     * @param remindType remindType。
     */
    public Conversation(Long id, String domain, long timestamp, Long ownerId, ConversationType type,
                        ConversationState state, Long pivotalId, ConversationRemindType remindType) {
        super(id, domain, timestamp);
        this.ownerId = ownerId;
        this.type = type;
        this.state = state;
        this.pivotalId = pivotalId;
        this.remindType = remindType;
        this.unreadCount = 0;
    }

    /**
     * 构造函数。
     * @param contact contact。
     */
    public Conversation(Contact contact) {
        this(contact, new ArrayList<>());
    }

    /**
     * 构造函数。
     * @param group group。
     */
    public Conversation(Group group) {
        this(group, new ArrayList<>());
    }

    /**
     * 构造函数。
     * @param contact contact。
     * @param recentMessages recentMessages。
     */
    public Conversation(Contact contact, List<Message> recentMessages) {
        super(Utils.generateSerialNumber(), "", System.currentTimeMillis());
        this.pivotalEntity = contact;
        this.ownerId = 0L;
        this.type = ConversationType.Contact;
        this.state = ConversationState.Normal;
        this.pivotalId = 0L;
        this.remindType = ConversationRemindType.Normal;
        this.unreadCount = 0;
        this.recentMessages = recentMessages;
    }

    /**
     * 构造函数。
     * @param group group。
     * @param recentMessages recentMessages。
     */
    public Conversation(Group group, List<Message> recentMessages) {
        super(Utils.generateSerialNumber(), "", System.currentTimeMillis());
        this.pivotalEntity = group;
        this.ownerId = 0L;
        this.type = ConversationType.Group;
        this.state = ConversationState.Normal;
        this.pivotalId = 0L;
        this.remindType = ConversationRemindType.Normal;
        this.unreadCount = 0;
        this.recentMessages = recentMessages;
    }

    /**
     * 构造函数。
     * @param json JSON 数据。
     */
    public Conversation(JSONObject json) {
        this(json, null);
    }

    /**
     * 构造函数。
     * @param json JSON 数据。
     * @param owner owner。
     */
    public Conversation(JSONObject json, Contact owner) {
        super(json);
        this.ownerId = (null != owner) ? owner.getId() : 0L;
        this.pivotalId = json.getLong("pivotal");
        this.type = ConversationType.parse(json.getInt("type"));
        this.state = ConversationState.parse(json.getInt("state"));
        this.remindType = ConversationRemindType.parse(json.getInt("reminding"));

        this.unreadCount = json.has("unread") ? json.getInt("unread") : 0;

        if (json.has("context")) {
            this.context = json.getJSONObject("context");
        }

        if (json.has("pivotalEntity")) {
            if (ConversationType.Contact == this.type) {
                this.pivotalEntity = new Contact(json.getJSONObject("pivotalEntity"));
            }
            else if (ConversationType.Group == this.type) {
                this.pivotalEntity = new Group(json.getJSONObject("pivotalEntity"));
            }
        }

        if (json.has("recentMessage")) {
            this.recentMessages = new ArrayList<>();
            this.recentMessages.add(new Message(json.getJSONObject("recentMessage")));
        }
        else if (json.has("recentMessages")) {
            this.recentMessages = new ArrayList<>();
            JSONArray array = json.getJSONArray("recentMessages");
            for (int i = 0; i < array.length(); ++i) {
                Message message = new Message(array.getJSONObject(i));
                this.recentMessages.add(message);
            }
        }

        if (json.has("avatarName")) {
            this.avatarName = json.getString("avatarName");
        }

        if (json.has("avatarURL")) {
            this.avatarURL = json.getString("avatarURL");
        }
    }

    /**
     * 获取所有者 ID。
     *
     * @return 所有者 ID。
     */
    public Long getOwnerId() {
        return this.ownerId;
    }

    /**
     * 获取类型。
     *
     * @return 类型。
     */
    public ConversationType getType() {
        return this.type;
    }

    /**
     * 获取关键实体 ID。
     *
     * @return 关键实体 ID。
     */
    public Long getPivotalId() {
        return this.pivotalId;
    }

    /**
     * 获取状态。
     *
     * @return 状态。
     */
    public ConversationState getState() {
        return this.state;
    }

    /**
     * 获取提醒类型。
     *
     * @return 提醒类型。
     */
    public ConversationRemindType getRemindType() {
        return this.remindType;
    }

    /**
     * 获取上下文。
     *
     * @return 上下文。
     */
    public JSONObject getContext() {
        return this.context;
    }

    /**
     * 设置上下文。
     *
     * @param context 上下文。
     */
    public void setContext(JSONObject context) {
        this.context = context;
    }

    /**
     * 获取关键实体。
     *
     * @return 关键实体。
     */
    public AbstractContact getPivotalEntity() {
        return this.pivotalEntity;
    }

    /**
     * 设置关键实体。
     *
     * @param pivotalEntity 关键实体。
     */
    public void setPivotalEntity(AbstractContact pivotalEntity) {
        this.pivotalEntity = pivotalEntity;
    }

    /**
     * 获取最近消息列表。
     *
     * @return 最近消息列表。
     */
    public List<Message> getRecentMessages() {
        return this.recentMessages;
    }

    /**
     * 添加最近消息。
     *
     * @param message 最近消息。
     */
    public void addRecentMessage(Message message) {
        if (null == this.recentMessages) {
            this.recentMessages = new ArrayList<>();
        }

        this.recentMessages.add(message);
    }

    /**
     * 移除最近消息。
     *
     * @param message 最近消息。
     */
    public void removeRecentMessage(Message message) {
        if (null == this.recentMessages) {
            return;
        }

        this.recentMessages.remove(message);
    }

    /**
     * 获取最近消息。
     *
     * @return 最近消息。
     */
    public Message getRecentMessage() {
        if (null == this.recentMessages) {
            return null;
        }

        return this.recentMessages.get(this.recentMessages.size() - 1);
    }

    /**
     * 设置最近消息。
     *
     * @param message 最近消息。
     */
    public void setRecentMessage(Message message) {
        if (null != this.recentMessages) {
            this.recentMessages.clear();
        }
        else {
            this.recentMessages = new ArrayList<>();
        }

        this.recentMessages.add(message);
    }

    /**
     * 设置未读数。
     *
     * @param count 未读数。
     */
    public void setUnreadCount(int count) {
        this.unreadCount = count;
    }

    /**
     * 设置头像名称。
     *
     * @param avatarName 头像名称。
     */
    public void setAvatarName(String avatarName) {
        this.avatarName = avatarName;
    }

    /**
     * 设置头像地址。
     *
     * @param avatarURL 头像地址。
     */
    public void setAvatarURL(String avatarURL) {
        this.avatarURL = avatarURL;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("owner", this.ownerId.longValue());
        json.put("type", this.type.code);
        json.put("state", this.state.code);
        json.put("reminding", this.remindType.code);
        json.put("pivotal", this.pivotalId.longValue());
        json.put("unread", this.unreadCount);

        if (null != this.context) {
            json.put("context", this.context);
        }

        if (null != this.pivotalEntity) {
            json.put("pivotalEntity", this.pivotalEntity.toCompactJSON());
        }

        if (null != this.recentMessages) {
            if (this.recentMessages.size() == 1) {
                json.put("recentMessage", this.recentMessages.get(0).toJSON());
            }
            else {
                JSONArray array = new JSONArray();
                for (Message message : this.recentMessages) {
                    array.put(message.toJSON());
                }
                json.put("recentMessages", array);
            }
        }

        if (null != this.avatarName) {
            json.put("avatarName", this.avatarName);
        }
        if (null != this.avatarURL) {
            json.put("avatarURL", this.avatarURL);
        }

        return json;
    }
}
