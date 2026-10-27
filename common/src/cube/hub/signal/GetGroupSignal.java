/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import org.json.JSONObject;

/**
 * 获取群组数据信令。
 */
public class GetGroupSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "GetGroup";

    private String groupName;

    public GetGroupSignal(String channelCode, String groupName) {
        super(NAME);
        setCode(channelCode);
        this.groupName = groupName;
    }

    public GetGroupSignal(JSONObject json) {
        super(json);
        this.groupName = json.getString("groupName");
    }

    /**
     * 获取组名称。
     *
     * @return 组名称。
     */
    public String getGroupName() {
        return this.groupName;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("groupName", this.groupName);
        return json;
    }
}
