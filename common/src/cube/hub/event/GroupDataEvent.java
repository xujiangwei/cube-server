/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import cube.common.entity.Group;
import cube.hub.data.DataHelper;
import org.json.JSONObject;

/**
 * 群组数据事件。
 */
public class GroupDataEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "GroupData";

    private Group group;

    public GroupDataEvent(Contact account, Group group) {
        super(NAME, account);
        this.group = group;
    }

    public GroupDataEvent(JSONObject json) {
        super(json);
        this.group = new Group(json.getJSONObject("group"));
    }

    /**
     * 获取组。
     *
     * @return 组。
     */
    public Group getGroup() {
        return this.group;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("group", this.group.toJSON());
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
        json.put("group", DataHelper.filterContactAvatarFileLabel(this.group.toJSON()));
        return json;
    }
}
