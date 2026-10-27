/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cube.common.entity.Contact;
import org.json.JSONObject;

/**
 * 添加好友信令。
 */
public class AddFriendSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "AddFriend";

    private Contact account;

    private String searchKeyword;

    /**
     * 申请附言。
     */
    private String postscript;

    /**
     * 备注名。
     */
    private String remarkName;

    public AddFriendSignal(String channelCode, String searchKeyword) {
        super(NAME);
        setCode(channelCode);
        this.searchKeyword = searchKeyword;
    }

    public AddFriendSignal(JSONObject json) {
        super(json);
        this.searchKeyword = json.getString("searchKeyword");

        if (json.has("account")) {
            this.account = new Contact(json.getJSONObject("account"));
        }
        if (json.has("postscript")) {
            this.postscript = json.getString("postscript");
        }
        if (json.has("remarkName")) {
            this.remarkName = json.getString("remarkName");
        }
    }

    /**
     * 获取搜索关键词。
     *
     * @return 关键词。
     */
    public String getSearchKeyword() {
        return this.searchKeyword;
    }

    /**
     * 设置账号。
     *
     * @param account 联系人。
     */
    public void setAccount(Contact account) {
        this.account = account;
    }

    /**
     * 获取账号。
     *
     * @return 联系人。
     */
    public Contact getAccount() {
        return this.account;
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
        json.put("searchKeyword", this.searchKeyword);

        if (null != this.account) {
            json.put("account", this.account.toJSON());
        }
        if (null != this.postscript) {
            json.put("postscript", this.postscript);
        }
        if (null != this.remarkName) {
            json.put("remarkName", this.remarkName);
        }
        return json;
    }
}
