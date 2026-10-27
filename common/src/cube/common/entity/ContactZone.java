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
 * 联系人分区。
 */
public class ContactZone extends Entity {

    /**
     * 所属的联系人的 ID 。
     */
    public final long owner;

    /**
     * 分区名称。
     */
    public final String name;

    /**
     * 显示名。
     */
    public String displayName = "";

    /**
     * 状态。
     */
    public ContactZoneState state;

    /**
     * 是否工作在对等模式下。
     * 在对等模式下，添加 Contact 时，会在对方的同名 Zone 下也添加自己的 Contact ，且状态为 Pending 。
     */
    public boolean peerMode = false;

    /**
     * 分区的联系人列表。
     */
    private final List<ContactZoneParticipant> participants;

    public ContactZone(Long id, String domain, long owner, String name, long timestamp,
                       String displayName, ContactZoneState state, boolean peerMode) {
        super(id, domain);
        this.setTimestamp(timestamp);
        this.owner = owner;
        this.name = name;
        this.state = state;
        this.displayName = displayName;
        this.peerMode = peerMode;
        this.participants = new ArrayList<>();
    }

    /**
     * 构造函数。
     * @param name 名称。
     */
    public ContactZone(String name) {
        this(Utils.generateSerialNumber(), "", 0, name, System.currentTimeMillis(),
                name, ContactZoneState.Normal, false);
    }

    public ContactZone(JSONObject json) {
        super(json);
        this.owner = json.getLong("owner");
        this.name = json.getString("name");
        this.displayName = json.getString("displayName");
        this.state = ContactZoneState.parse(json.getInt("state"));
        this.peerMode = json.getBoolean("peerMode");
        this.participants = new ArrayList<>();

        if (json.has("participants")) {
            JSONArray array = json.getJSONArray("participants");
            for (int i = 0; i < array.length(); ++i) {
                JSONObject data = array.getJSONObject(i);
                ContactZoneParticipant participant = new ContactZoneParticipant(data);
                this.addParticipant(participant);
            }
        }
    }

    /**
     * 添加参与者。
     *
     * @param participant 参与者。
     */
    public void addParticipant(ContactZoneParticipant participant) {
        if (participant.type == ContactZoneParticipantType.Contact
                && participant.id.longValue() == this.owner) {
            // 过滤分区所有人
            return;
        }

        if (this.participants.contains(participant)) {
            this.participants.remove(participant);
        }

        this.participants.add(participant);
    }

    /**
     * 移除参与者。
     *
     * @param id 参与者。
     */
    public void removeParticipant(Long id) {
        for (ContactZoneParticipant participant : this.participants) {
            if (participant.id.equals(id)) {
                this.participants.remove(participant);
                break;
            }
        }
    }

    /**
     * 获取参与者列表。
     *
     * @return 参与者列表。
     */
    public List<ContactZoneParticipant> getParticipants() {
        return this.participants;
    }

    /**
     * 获取参与者。
     *
     * @param id 参与者。
     * @return 参与者。
     */
    public ContactZoneParticipant getParticipant(Long id) {
        for (ContactZoneParticipant participant : this.participants) {
            if (participant.id.equals(id)) {
                return participant;
            }
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
        JSONObject json = ContactZone.this.toCompactJSON();

        JSONArray participants = new JSONArray();

        for (ContactZoneParticipant participant : this.participants) {
            participants.put(participant.toJSON());
        }

        json.put("participants", participants);

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
        json.put("owner", this.owner);
        json.put("name", this.name);
        json.put("displayName", this.displayName);
        json.put("state", this.state.code);
        json.put("peerMode", this.peerMode);
        return json;
    }
}
