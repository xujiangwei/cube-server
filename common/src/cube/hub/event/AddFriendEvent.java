/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.event;

import cube.common.entity.Contact;
import org.json.JSONObject;

/**
 * 添加朋友事件。
 */
public class AddFriendEvent extends WeChatEvent {

    /** 名称。 */
    public final static String NAME = "AddFriend";

    private Contact friend;

    private String postscript;

    private String remarkName;

    public AddFriendEvent(Contact friend) {
        super(NAME);
        this.friend = friend;
    }

    public AddFriendEvent(JSONObject json) {
        super(json);
        this.friend = new Contact(json.getJSONObject("friend"));
        if (json.has("postscript")) {
            this.postscript = json.getString("postscript");
        }
        if (json.has("remarkName")) {
            this.remarkName = json.getString("remarkName");
        }
    }

    /**
     * 获取待添加的联系人。
     *
     * @return 联系人。
     */
    public Contact getFriend() {
        return this.friend;
    }

    /**
     * 设置附言。
     *
     * @param postscript 附言。
     */
    public void setPostscript(String postscript) {
        this.postscript = postscript;
    }

    /**
     * 获取附言。
     *
     * @return 附言；未设置返回 {@code null} 。
     */
    public String getPostscript() {
        return this.postscript;
    }

    /**
     * 设置备注名称。
     *
     * @param remarkName 备注名称。
     */
    public void setRemarkName(String remarkName) {
        this.remarkName = remarkName;
    }

    /**
     * 获取备注名称。
     *
     * @return 备注名称。
     */
    public String getRemarkName() {
        return this.remarkName;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("friend", this.friend.toJSON());

        if (null != this.postscript) {
            json.put("postscript", this.postscript);
        }

        if (null != this.remarkName) {
            json.put("remarkName", this.remarkName);
        }

        return json;
    }
}
