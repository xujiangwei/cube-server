/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.ContactZone;
import cube.hub.data.DataHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 联系人 Zone 数据事件。
 */
public class ContactZoneEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "ContactZone";

    private ContactZone contactZone;

    private int beginIndex;

    private int endIndex;

    private int totalSize;

    public ContactZoneEvent(ContactZone contactZone, int beginIndex, int endIndex, int totalSize) {
        super(NAME);
        this.contactZone = contactZone;
        this.beginIndex = beginIndex;
        this.endIndex = endIndex;
        this.totalSize = totalSize;
    }

    public ContactZoneEvent(JSONObject json) {
        super(json);
        this.contactZone = new ContactZone(json.getJSONObject("zone"));
        this.beginIndex = json.getInt("begin");
        this.endIndex = json.getInt("end");
        this.totalSize = json.getInt("total");
    }

    /**
     * 获取联系人区域。
     *
     * @return 联系人区域。
     */
    public ContactZone getContactZone() {
        return this.contactZone;
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
     * 获取总数大小。
     *
     * @return 总数大小。
     */
    public int getTotalSize() {
        return this.totalSize;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("zone", this.contactZone.toJSON());
        json.put("begin", this.beginIndex);
        json.put("end", this.endIndex);
        json.put("total", this.totalSize);
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
        json.put("begin", this.beginIndex);
        json.put("end", this.endIndex);
        json.put("total", this.totalSize);

        // 过滤数据
        JSONObject zoneJson = new JSONObject(this.contactZone.toJSON().toMap());
        JSONArray participants = zoneJson.getJSONArray("participants");
        for (int i = 0; i < participants.length(); ++i) {
            JSONObject part = participants.getJSONObject(i);
            if (part.has("linkedContact")) {
                part.put("linkedContact",
                        DataHelper.filterContactAvatarFileLabel(part.getJSONObject("linkedContact")));
            }
        }

        json.put("zone", zoneJson);
        return json;
    }
}
