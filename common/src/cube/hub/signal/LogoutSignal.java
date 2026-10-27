/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cube.common.entity.Contact;
import org.json.JSONObject;

/**
 * 账号退出登录。
 */
public class LogoutSignal extends Signal {

    /** 名称。 */
    public final static String NAME = "Logout";

    private Contact account;

    public LogoutSignal(String channelCode) {
        super(NAME);
        setCode(channelCode);
    }

    public LogoutSignal(Contact account, String channelCode) {
        super(NAME);
        this.account = account;
        setCode(channelCode);
    }

    public LogoutSignal(JSONObject json) {
        super(json);
        if (json.has("account")) {
            this.account = new Contact(json.getJSONObject("account"));
        }
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
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        if (null != this.account) {
            json.put("account", this.account.toCompactJSON());
        }
        return json;
    }
}
