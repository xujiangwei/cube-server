/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cube.common.entity.ContactZoneParticipantType;
import org.json.JSONObject;

/**
 * 获取联系人分区信令。
 */
public class GetContactZoneSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "GetContactZone";

    private ContactZoneParticipantType participantType;

    private int beginIndex;

    private int endIndex;

    public GetContactZoneSignal(String channelCode, ContactZoneParticipantType participantType,
                                int beginIndex, int endIndex) {
        super(NAME);
        setCode(channelCode);
        this.participantType = participantType;
        this.beginIndex = beginIndex;
        this.endIndex = endIndex;
    }

    public GetContactZoneSignal(JSONObject json) {
        super(json);
        this.participantType = ContactZoneParticipantType.parse(json.getInt("participantType"));
        this.beginIndex = json.getInt("begin");
        this.endIndex = json.getInt("end");
    }

    /**
     * 获取参与者类型。
     *
     * @return 参与者类型。
     */
    public ContactZoneParticipantType getParticipantType() {
        return this.participantType;
    }

    /**
     * 获取起始索引。
     *
     * @return 起始索引，从 0 开始。
     */
    public int getBeginIndex() {
        return this.beginIndex;
    }

    /**
     * 获取结束索引。
     *
     * @return 结束索引。
     */
    public int getEndIndex() {
        return this.endIndex;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("participantType", this.participantType.code);
        json.put("begin", this.beginIndex);
        json.put("end", this.endIndex);
        return json;
    }
}
